package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Strategy: Reverse Transitive / "Used-by".
 * <p>
 * Builds a reverse dependency map of all production classes: for each class,
 * which other classes depend on it (have it as a field type). When a class
 * changes, walks this "used-by" graph N levels deep to find consumers, then
 * discovers tests for those consumers via the naming and usage strategies.
 * <p>
 * Depth is configurable via {@code transitiveDepth} (default 2, max 5).
 * <p>
 * Example: if {@code PaymentGateway} changes and {@code PaymentService} has a
 * {@code PaymentGateway} field, then at depth 1 we discover
 * {@code PaymentServiceTest} via naming.
 */
public final class TransitiveStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(TransitiveStrategy.class);

    private final AffectedTestsConfig config;
    private final NamingConventionStrategy namingStrategy;
    private final UsageStrategy usageStrategy;

    public TransitiveStrategy(AffectedTestsConfig config,
                              NamingConventionStrategy namingStrategy,
                              UsageStrategy usageStrategy) {
        this.config = config;
        this.namingStrategy = namingStrategy;
        this.usageStrategy = usageStrategy;
    }

    @Override
    public String name() {
        return "transitive";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        if (config.transitiveDepth() <= 0) {
            log.debug("[transitive] Transitive depth is 0, skipping");
            return discoveredTests;
        }

        // Build the dependency graph level by level
        Set<String> currentLevel = new LinkedHashSet<>(changedProductionClasses);
        Set<String> allVisited = new LinkedHashSet<>(changedProductionClasses);

        // Pre-index: reverse map — FQN → set of FQNs that depend on it
        Map<String, Set<String>> dependencyMap = buildReverseDependencyMap(projectDir);

        for (int depth = 1; depth <= config.transitiveDepth(); depth++) {
            Set<String> nextLevel = new LinkedHashSet<>();

            for (String classFqn : currentLevel) {
                Set<String> deps = dependencyMap.getOrDefault(classFqn, Set.of());
                for (String dep : deps) {
                    if (!allVisited.contains(dep)) {
                        nextLevel.add(dep);
                        allVisited.add(dep);
                    }
                }
            }

            if (nextLevel.isEmpty()) {
                log.debug("[transitive] No more downstream types at depth {}", depth);
                break;
            }

            log.debug("[transitive] Depth {}: found {} downstream types", depth, nextLevel.size());

            // Discover tests for these downstream types
            discoveredTests.addAll(namingStrategy.discoverTests(nextLevel, projectDir));
            discoveredTests.addAll(usageStrategy.discoverTests(nextLevel, projectDir));

            currentLevel = nextLevel;
        }

        log.info("[transitive] Discovered {} tests via transitive dependencies (depth={})",
                discoveredTests.size(), config.transitiveDepth());
        return discoveredTests;
    }

    /**
     * Builds a <em>reverse</em> dependency map: for each production class FQN,
     * lists the FQNs of other production classes that <strong>depend on</strong> it
     * (i.e. have it as a field type). This is the "usedBy" direction, so when a
     * class changes we can find all classes that consume it.
     */
    private Map<String, Set<String>> buildReverseDependencyMap(Path projectDir) {
        Map<String, Set<String>> reverseMap = new HashMap<>();
        List<Path> sourceFiles = SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs());
        JavaParser parser = new JavaParser();

        // First pass: collect all known FQNs so we can resolve simple names
        Set<String> allKnownFqns = new HashSet<>();
        for (Path file : sourceFiles) {
            String fqn = pathToFqn(file);
            if (fqn != null) {
                allKnownFqns.add(fqn);
            }
        }

        // Second pass: for each source file, find field types and build reverse edges
        for (Path file : sourceFiles) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (!result.isSuccessful() || result.getResult().isEmpty()) continue;

                CompilationUnit cu = result.getResult().get();
                String classFqn = pathToFqn(file);
                if (classFqn == null) continue;

                // Build import map
                Map<String, String> importMap = new HashMap<>();
                Set<String> wildcardPackages = new HashSet<>();
                for (ImportDeclaration imp : cu.getImports()) {
                    if (imp.isAsterisk()) {
                        wildcardPackages.add(imp.getNameAsString());
                    } else {
                        String impFqn = imp.getNameAsString();
                        importMap.put(SourceFileScanner.simpleClassName(impFqn), impFqn);
                    }
                }

                String currentPackage = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString())
                        .orElse("");

                // Extract field types
                List<FieldDeclaration> fields = cu.findAll(FieldDeclaration.class);
                for (FieldDeclaration field : fields) {
                    for (VariableDeclarator var : field.getVariables()) {
                        String typeName = var.getTypeAsString();
                        if (typeName.contains("<")) {
                            typeName = typeName.substring(0, typeName.indexOf('<'));
                        }

                        // Resolve to FQN
                        String resolvedFqn = importMap.get(typeName);
                        if (resolvedFqn == null) {
                            String candidate = currentPackage.isEmpty()
                                    ? typeName : currentPackage + "." + typeName;
                            if (allKnownFqns.contains(candidate)) {
                                resolvedFqn = candidate;
                            }
                        }
                        if (resolvedFqn == null) {
                            for (String pkg : wildcardPackages) {
                                String candidate = pkg + "." + typeName;
                                if (allKnownFqns.contains(candidate)) {
                                    resolvedFqn = candidate;
                                    break;
                                }
                            }
                        }

                        // Reverse edge: resolvedFqn is used by classFqn
                        if (resolvedFqn != null && allKnownFqns.contains(resolvedFqn)
                                && !resolvedFqn.equals(classFqn)) {
                            reverseMap.computeIfAbsent(resolvedFqn, k -> new LinkedHashSet<>())
                                    .add(classFqn);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing {} for dependency map: {}", file, e.getMessage());
            }
        }

        log.debug("[transitive] Built reverse dependency map with {} entries", reverseMap.size());
        return reverseMap;
    }

    private String pathToFqn(Path file) {
        String filePath = file.toString().replace(java.io.File.separatorChar, '/');
        for (String sourceDir : config.sourceDirs()) {
            String normalizedDir = sourceDir.replace('\\', '/');
            if (!normalizedDir.endsWith("/")) normalizedDir += "/";

            int idx = filePath.indexOf(normalizedDir);
            if (idx >= 0) {
                String relative = filePath.substring(idx + normalizedDir.length());
                if (relative.endsWith(".java")) {
                    relative = relative.substring(0, relative.length() - 5);
                }
                return relative.replace('/', '.');
            }
        }
        return null;
    }
}
