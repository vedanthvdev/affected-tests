package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
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
 * Example: if {@code BazGateway} changes and {@code FooService} has a
 * {@code BazGateway} field, then at depth 1 we discover
 * {@code FooServiceTest} via naming.
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
        List<Path> sourceFiles = SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs());
        return walkTransitives(changedProductionClasses, sourceFiles, projectDir, null);
    }

    /**
     * Discovers tests using a pre-built project index (avoids redundant file walks).
     */
    public Set<String> discoverTests(Set<String> changedProductionClasses, ProjectIndex index) {
        return walkTransitives(changedProductionClasses, index.sourceFiles(), null, index);
    }

    private Set<String> walkTransitives(Set<String> changedProductionClasses,
                                        List<Path> sourceFiles,
                                        Path projectDir, ProjectIndex index) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        if (config.transitiveDepth() <= 0) {
            log.debug("[transitive] Transitive depth is 0, skipping");
            return discoveredTests;
        }

        Set<String> currentLevel = new LinkedHashSet<>(changedProductionClasses);
        Set<String> allVisited = new LinkedHashSet<>(changedProductionClasses);

        Map<String, Set<String>> dependencyMap = buildReverseDependencyMap(sourceFiles);

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

            if (index != null) {
                discoveredTests.addAll(namingStrategy.discoverTests(nextLevel, index));
                discoveredTests.addAll(usageStrategy.discoverTests(nextLevel, index));
            } else {
                discoveredTests.addAll(namingStrategy.discoverTests(nextLevel, projectDir));
                discoveredTests.addAll(usageStrategy.discoverTests(nextLevel, projectDir));
            }

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
    private Map<String, Set<String>> buildReverseDependencyMap(List<Path> sourceFiles) {
        Map<String, Set<String>> reverseMap = new HashMap<>();
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

                Set<String> referencedTypes = new LinkedHashSet<>();

                // Extract field types
                for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
                    for (VariableDeclarator var : field.getVariables()) {
                        referencedTypes.add(stripGenerics(var.getTypeAsString()));
                    }
                }

                // Extract method parameter types and return types
                for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                    referencedTypes.add(stripGenerics(method.getTypeAsString()));
                    for (Parameter param : method.getParameters()) {
                        referencedTypes.add(stripGenerics(param.getTypeAsString()));
                    }
                }

                // Resolve each referenced type and build reverse edges
                for (String typeName : referencedTypes) {
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

                    if (resolvedFqn != null && allKnownFqns.contains(resolvedFqn)
                            && !resolvedFqn.equals(classFqn)) {
                        reverseMap.computeIfAbsent(resolvedFqn, k -> new LinkedHashSet<>())
                                .add(classFqn);
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing {} for dependency map: {}", file, e.getMessage());
            }
        }

        log.debug("[transitive] Built reverse dependency map with {} entries", reverseMap.size());
        return reverseMap;
    }

    private static String stripGenerics(String typeName) {
        int idx = typeName.indexOf('<');
        return idx >= 0 ? typeName.substring(0, idx) : typeName;
    }

    private String pathToFqn(Path file) {
        return SourceFileScanner.pathToFqn(file, config.sourceDirs());
    }
}
