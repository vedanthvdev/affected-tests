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
 * Strategy: Usage / Reference scanning.
 * <p>
 * Scans test source files for field declarations whose type matches a changed
 * production class. Uses JavaParser to resolve imports and match types accurately.
 * <p>
 * Examples it catches:
 * <ul>
 *   <li>{@code private FooBar underTest;}</li>
 *   <li>{@code @Autowired private FooBar fooBar;}</li>
 *   <li>{@code @Mock private FooBar fooBar;}</li>
 * </ul>
 */
public final class UsageStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(UsageStrategy.class);

    private final AffectedTestsConfig config;

    public UsageStrategy(AffectedTestsConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        // Build lookup structures
        Map<String, String> simpleNameToFqn = new HashMap<>();
        Set<String> simpleNames = new HashSet<>();
        for (String fqn : changedProductionClasses) {
            String simpleName = simpleClassName(fqn);
            simpleNameToFqn.put(simpleName, fqn);
            simpleNames.add(simpleName);
        }

        // Scan all test files
        List<Path> testFiles = collectTestFiles(projectDir);
        JavaParser parser = new JavaParser();

        for (Path testFile : testFiles) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(testFile);
                if (!result.isSuccessful() || result.getResult().isEmpty()) {
                    log.debug("Failed to parse {}", testFile);
                    continue;
                }

                CompilationUnit cu = result.getResult().get();
                String testFqn = extractFqn(cu, testFile, projectDir);
                if (testFqn == null) continue;

                // Get imports to resolve types
                Set<String> importedFqns = new HashSet<>();
                Set<String> wildcardPackages = new HashSet<>();
                for (ImportDeclaration imp : cu.getImports()) {
                    if (imp.isAsterisk()) {
                        wildcardPackages.add(imp.getNameAsString());
                    } else {
                        importedFqns.add(imp.getNameAsString());
                    }
                }

                // Check field declarations for production class references
                List<FieldDeclaration> fields = cu.findAll(FieldDeclaration.class);
                for (FieldDeclaration field : fields) {
                    for (VariableDeclarator var : field.getVariables()) {
                        String typeName = var.getTypeAsString();
                        // Remove generics (e.g. List<FooBar> → List)
                        if (typeName.contains("<")) {
                            typeName = typeName.substring(0, typeName.indexOf('<'));
                        }

                        if (simpleNames.contains(typeName)) {
                            // Verify via imports that this refers to the changed class
                            String expectedFqn = simpleNameToFqn.get(typeName);
                            if (isImported(expectedFqn, typeName, importedFqns, wildcardPackages, cu)) {
                                discoveredTests.add(testFqn);
                                log.debug("Usage match: {} uses {} (field: {})",
                                        testFqn, expectedFqn, var.getNameAsString());
                                break; // found a match, no need to check more fields
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing test file {}: {}", testFile, e.getMessage());
            }
        }

        log.info("[usage] Discovered {} tests for {} changed classes",
                discoveredTests.size(), changedProductionClasses.size());
        return discoveredTests;
    }

    private boolean isImported(String expectedFqn, String simpleName,
                               Set<String> importedFqns, Set<String> wildcardPackages,
                               CompilationUnit cu) {
        // Direct import match
        if (importedFqns.contains(expectedFqn)) {
            return true;
        }

        // Wildcard import from same package
        String expectedPackage = packageOf(expectedFqn);
        if (wildcardPackages.contains(expectedPackage)) {
            return true;
        }

        // Same package (no import needed)
        String testPackage = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        if (testPackage.equals(expectedPackage)) {
            return true;
        }

        // Fallback: if no other class with the same simple name is imported,
        // assume it's our class (handles cases where IDE auto-imports)
        boolean anotherClassWithSameName = importedFqns.stream()
                .anyMatch(imp -> imp.endsWith("." + simpleName) && !imp.equals(expectedFqn));
        return !anotherClassWithSameName;
    }

    private String extractFqn(CompilationUnit cu, Path testFile, Path projectDir) {
        // Try to derive FQN from the test directory structure
        for (String testDir : config.testDirs()) {
            Path testRoot = findTestRoot(testFile, testDir);
            if (testRoot != null) {
                Path relative = testRoot.relativize(testFile);
                String fqn = relative.toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replace('/', '.');
                if (fqn.endsWith(".java")) {
                    fqn = fqn.substring(0, fqn.length() - 5);
                }
                return fqn;
            }
        }

        // Fallback: use package declaration + type name
        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        return cu.getPrimaryTypeName()
                .map(name -> pkg.isEmpty() ? name : pkg + "." + name)
                .orElse(null);
    }

    private Path findTestRoot(Path file, String testDir) {
        Path current = file.getParent();
        String normalizedTestDir = testDir.replace('/', java.io.File.separatorChar);
        while (current != null) {
            if (current.toString().endsWith(normalizedTestDir)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private List<Path> collectTestFiles(Path projectDir) {
        List<Path> testFiles = new ArrayList<>();
        for (String testDir : config.testDirs()) {
            // Direct test path
            Path testPath = projectDir.resolve(testDir);
            if (Files.isDirectory(testPath)) {
                collectJavaFiles(testPath, testFiles);
            }

            // Also check sub-modules
            try (var dirs = Files.walk(projectDir, 1)) {
                dirs.filter(Files::isDirectory)
                    .filter(d -> !d.equals(projectDir))
                    .forEach(moduleDir -> {
                        Path modulTestPath = moduleDir.resolve(testDir);
                        if (Files.isDirectory(modulTestPath)) {
                            collectJavaFiles(modulTestPath, testFiles);
                        }
                    });
            } catch (IOException e) {
                log.warn("Error scanning for test files under {}", projectDir, e);
            }
        }
        return testFiles;
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

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(0, dot) : "";
    }
}
