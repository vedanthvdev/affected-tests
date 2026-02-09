package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Strategy: Implementation / "Impl" discovery.
 * <p>
 * When a base class or interface changes, discovers implementations of that type
 * and then delegates to naming and usage strategies to find tests for those implementations.
 */
public final class ImplementationStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(ImplementationStrategy.class);

    private final AffectedTestsConfig config;
    private final NamingConventionStrategy namingStrategy;
    private final UsageStrategy usageStrategy;

    public ImplementationStrategy(AffectedTestsConfig config,
                                  NamingConventionStrategy namingStrategy,
                                  UsageStrategy usageStrategy) {
        this.config = config;
        this.namingStrategy = namingStrategy;
        this.usageStrategy = usageStrategy;
    }

    @Override
    public String name() {
        return "impl";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        if (!config.includeImplementationTests()) {
            log.debug("[impl] Implementation test discovery disabled");
            return discoveredTests;
        }

        // For each changed production class, find implementations
        Set<String> implClasses = findImplementations(changedProductionClasses, projectDir);

        if (!implClasses.isEmpty()) {
            log.info("[impl] Found {} implementation classes for {} changed classes",
                    implClasses.size(), changedProductionClasses.size());

            // Run naming and usage discovery on implementation classes
            if (config.strategies().contains("naming")) {
                discoveredTests.addAll(namingStrategy.discoverTests(implClasses, projectDir));
            }
            if (config.strategies().contains("usage")) {
                discoveredTests.addAll(usageStrategy.discoverTests(implClasses, projectDir));
            }
        }

        log.info("[impl] Discovered {} tests via implementation strategy",
                discoveredTests.size());
        return discoveredTests;
    }

    /**
     * Finds classes that extend or implement any of the changed production classes.
     * Uses both JavaParser AST scanning and naming convention ({@code *Impl}).
     */
    private Set<String> findImplementations(Set<String> changedClasses, Path projectDir) {
        Set<String> implementations = new LinkedHashSet<>();

        // Build simple name lookup
        Map<String, String> simpleNameToFqn = new HashMap<>();
        for (String fqn : changedClasses) {
            simpleNameToFqn.put(simpleClassName(fqn), fqn);
        }

        // 1. Naming convention: look for *Impl classes
        for (String suffix : config.implementationNaming()) {
            for (String fqn : changedClasses) {
                String simpleName = simpleClassName(fqn);
                // Search for classes with this suffix appended
                String implName = simpleName + suffix;
                Set<String> found = findClassesBySimpleName(implName, projectDir);
                implementations.addAll(found);
            }
        }

        // 2. AST scanning: find classes that extend/implement changed types
        List<Path> sourceFiles = collectSourceFiles(projectDir);
        JavaParser parser = new JavaParser();

        for (Path sourceFile : sourceFiles) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(sourceFile);
                if (!result.isSuccessful() || result.getResult().isEmpty()) continue;

                CompilationUnit cu = result.getResult().get();
                List<ClassOrInterfaceDeclaration> declarations =
                        cu.findAll(ClassOrInterfaceDeclaration.class);

                for (ClassOrInterfaceDeclaration decl : declarations) {
                    // Check extends
                    for (ClassOrInterfaceType extended : decl.getExtendedTypes()) {
                        if (simpleNameToFqn.containsKey(extended.getNameAsString())) {
                            String implFqn = extractFqn(cu, decl);
                            if (implFqn != null) {
                                implementations.add(implFqn);
                                log.debug("[impl] {} extends {}", implFqn, extended.getNameAsString());
                            }
                        }
                    }
                    // Check implements
                    for (ClassOrInterfaceType implemented : decl.getImplementedTypes()) {
                        if (simpleNameToFqn.containsKey(implemented.getNameAsString())) {
                            String implFqn = extractFqn(cu, decl);
                            if (implFqn != null) {
                                implementations.add(implFqn);
                                log.debug("[impl] {} implements {}", implFqn, implemented.getNameAsString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing source file {}: {}", sourceFile, e.getMessage());
            }
        }

        return implementations;
    }

    private Set<String> findClassesBySimpleName(String simpleName, Path projectDir) {
        Set<String> found = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            Path sourcePath = projectDir.resolve(sourceDir);
            if (Files.isDirectory(sourcePath)) {
                findJavaFileByName(sourcePath, simpleName + ".java", found, sourcePath);
            }
            // Also check sub-modules
            try (var dirs = Files.walk(projectDir, 1)) {
                dirs.filter(Files::isDirectory)
                    .filter(d -> !d.equals(projectDir))
                    .forEach(moduleDir -> {
                        Path modSourcePath = moduleDir.resolve(sourceDir);
                        if (Files.isDirectory(modSourcePath)) {
                            findJavaFileByName(modSourcePath, simpleName + ".java", found, modSourcePath);
                        }
                    });
            } catch (IOException e) {
                log.warn("Error scanning modules for impl classes", e);
            }
        }
        return found;
    }

    private void findJavaFileByName(Path root, String fileName, Set<String> results, Path sourceRoot) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(fileName)) {
                        Path relative = sourceRoot.relativize(file);
                        String fqn = relative.toString()
                                .replace(java.io.File.separatorChar, '.')
                                .replace('/', '.');
                        if (fqn.endsWith(".java")) {
                            fqn = fqn.substring(0, fqn.length() - 5);
                        }
                        results.add(fqn);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error searching for {} in {}", fileName, root, e);
        }
    }

    private List<Path> collectSourceFiles(Path projectDir) {
        List<Path> files = new ArrayList<>();
        for (String sourceDir : config.sourceDirs()) {
            Path sourcePath = projectDir.resolve(sourceDir);
            if (Files.isDirectory(sourcePath)) {
                collectJavaFiles(sourcePath, files);
            }
            // sub-modules
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

    private String extractFqn(CompilationUnit cu, ClassOrInterfaceDeclaration decl) {
        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        String name = decl.getNameAsString();
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
