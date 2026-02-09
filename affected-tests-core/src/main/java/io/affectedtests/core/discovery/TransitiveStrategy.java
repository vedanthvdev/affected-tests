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

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Strategy: Transitive / Downstream.
 * <p>
 * When a production class changes, follows its dependency graph to find additional
 * production types it uses (field types, parameter types). Then discovers tests for
 * those downstream types. Depth is configurable via {@code transitiveDepth}.
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

        // Pre-index: map FQN → set of FQNs it depends on (field types)
        Map<String, Set<String>> dependencyMap = buildDependencyMap(projectDir);

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
     * Builds a map of class FQN → set of FQNs it depends on (based on field types
     * resolved via imports). This scans all production source files.
     */
    private Map<String, Set<String>> buildDependencyMap(Path projectDir) {
        Map<String, Set<String>> map = new HashMap<>();
        List<Path> sourceFiles = collectSourceFiles(projectDir);
        JavaParser parser = new JavaParser();

        // First pass: collect all known FQNs so we can resolve simple names
        Set<String> allKnownFqns = new HashSet<>();
        Map<String, Set<String>> simpleNameToFqns = new HashMap<>();

        for (Path file : sourceFiles) {
            String fqn = pathToFqn(file, projectDir);
            if (fqn != null) {
                allKnownFqns.add(fqn);
                String simple = simpleClassName(fqn);
                simpleNameToFqns.computeIfAbsent(simple, k -> new HashSet<>()).add(fqn);
            }
        }

        // Second pass: for each source file, find field types and resolve to FQNs
        for (Path file : sourceFiles) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (!result.isSuccessful() || result.getResult().isEmpty()) continue;

                CompilationUnit cu = result.getResult().get();
                String classFqn = pathToFqn(file, projectDir);
                if (classFqn == null) continue;

                Set<String> deps = new LinkedHashSet<>();

                // Build import map
                Map<String, String> importMap = new HashMap<>();
                Set<String> wildcardPackages = new HashSet<>();
                for (ImportDeclaration imp : cu.getImports()) {
                    if (imp.isAsterisk()) {
                        wildcardPackages.add(imp.getNameAsString());
                    } else {
                        String impFqn = imp.getNameAsString();
                        importMap.put(simpleClassName(impFqn), impFqn);
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
                            // Check same package
                            String candidate = currentPackage.isEmpty()
                                    ? typeName : currentPackage + "." + typeName;
                            if (allKnownFqns.contains(candidate)) {
                                resolvedFqn = candidate;
                            }
                        }
                        if (resolvedFqn == null) {
                            // Check wildcard imports
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
                            deps.add(resolvedFqn);
                        }
                    }
                }

                if (!deps.isEmpty()) {
                    map.put(classFqn, deps);
                }
            } catch (Exception e) {
                log.debug("Error parsing {} for dependency map: {}", file, e.getMessage());
            }
        }

        log.debug("[transitive] Built dependency map with {} entries", map.size());
        return map;
    }

    private String pathToFqn(Path file, Path projectDir) {
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

    private List<Path> collectSourceFiles(Path projectDir) {
        List<Path> files = new ArrayList<>();
        for (String sourceDir : config.sourceDirs()) {
            Path sourcePath = projectDir.resolve(sourceDir);
            if (Files.isDirectory(sourcePath)) {
                collectJavaFiles(sourcePath, files);
            }
            try (var dirs = Files.walk(projectDir, 1)) {
                dirs.filter(Files::isDirectory)
                    .filter(d -> !d.equals(projectDir))
                    .forEach(moduleDir -> {
                        Path modSourcePath = moduleDir.resolve(sourceDir);
                        if (Files.isDirectory(modSourcePath)) {
                            collectJavaFiles(modSourcePath, files);
                        }
                    });
            } catch (IOException e) {
                log.warn("Error collecting source files", e);
            }
        }
        return files;
    }

    private void collectJavaFiles(Path dir, List<Path> result) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error collecting Java files from {}", dir, e);
        }
    }

    private static String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
