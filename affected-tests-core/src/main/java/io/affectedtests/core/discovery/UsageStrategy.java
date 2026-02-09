package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Strategy: Usage / Reference scanning.
 *
 * <p>Scans test source files for references to changed production classes.
 * Uses a two-tier approach:
 * <ol>
 *   <li><strong>Import matching (primary):</strong> If a test file directly imports the
 *       changed class FQN, it is considered affected. This catches all usage patterns:
 *       fields, method parameters, local variables, constructor args, type casts, etc.</li>
 *   <li><strong>Type reference scanning (secondary):</strong> For same-package and wildcard
 *       import cases, scans for the simple name in field declarations, method parameters,
 *       constructor instantiations, and type references.</li>
 * </ol>
 *
 * <p>Examples it catches:
 * <ul>
 *   <li>{@code import com.example.PaymentDetails;} (any usage in the file)</li>
 *   <li>{@code private FooBar underTest;}</li>
 *   <li>{@code @Autowired private FooBar fooBar;}</li>
 *   <li>{@code @Mock private FooBar fooBar;}</li>
 *   <li>{@code public void test(FooBar param) {...}}</li>
 *   <li>{@code new FooBar(...)}</li>
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

        if (changedProductionClasses.isEmpty()) {
            return discoveredTests;
        }

        // Build lookup structures
        Map<String, String> simpleNameToFqn = new HashMap<>();
        Set<String> changedFqns = new HashSet<>(changedProductionClasses);
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

                // Skip if this test class IS one of the changed production classes
                if (changedFqns.contains(testFqn)) continue;

                if (testReferencesChangedClass(cu, changedFqns, simpleNames, simpleNameToFqn)) {
                    discoveredTests.add(testFqn);
                    log.debug("Usage match: {}", testFqn);
                }
            } catch (Exception e) {
                log.debug("Error parsing test file {}: {}", testFile, e.getMessage());
            }
        }

        log.info("[usage] Discovered {} tests for {} changed classes",
                discoveredTests.size(), changedProductionClasses.size());
        return discoveredTests;
    }

    /**
     * Checks whether a test compilation unit references any of the changed classes.
     * Uses a two-tier approach: direct import match first, then type reference scanning.
     */
    private boolean testReferencesChangedClass(CompilationUnit cu,
                                               Set<String> changedFqns,
                                               Set<String> simpleNames,
                                               Map<String, String> simpleNameToFqn) {
        // Collect imports
        Set<String> importedFqns = new HashSet<>();
        Set<String> wildcardPackages = new HashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk()) {
                wildcardPackages.add(imp.getNameAsString());
            } else {
                importedFqns.add(imp.getNameAsString());
            }
        }

        // --- Tier 1: Direct import match ---
        // If the test directly imports the changed class FQN, it's affected.
        // This catches ALL usage patterns (fields, params, locals, casts, generics, etc.)
        for (String changedFqn : changedFqns) {
            if (importedFqns.contains(changedFqn)) {
                log.debug("  Direct import match: {}", changedFqn);
                return true;
            }
        }

        // --- Tier 1b: Wildcard import match ---
        // If a wildcard import covers the changed class's package, check type references
        for (String changedFqn : changedFqns) {
            String pkg = packageOf(changedFqn);
            if (wildcardPackages.contains(pkg)) {
                String simpleName = simpleClassName(changedFqn);
                if (typeNameAppearsInAst(cu, simpleName)) {
                    log.debug("  Wildcard import + type ref match: {}", changedFqn);
                    return true;
                }
            }
        }

        // --- Tier 2: Same-package (no import needed) ---
        String testPackage = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        for (String changedFqn : changedFqns) {
            String changedPkg = packageOf(changedFqn);
            if (testPackage.equals(changedPkg)) {
                String simpleName = simpleClassName(changedFqn);
                if (typeNameAppearsInAst(cu, simpleName)) {
                    log.debug("  Same-package type ref match: {}", changedFqn);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks whether the given simple type name appears in the AST as a type reference.
     * Scans fields, method parameters, constructor instantiations, and all type references.
     */
    private boolean typeNameAppearsInAst(CompilationUnit cu, String simpleName) {
        // Check field declarations
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator var : field.getVariables()) {
                if (typeMatches(var.getTypeAsString(), simpleName)) return true;
            }
        }

        // Check method parameters
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            for (Parameter param : method.getParameters()) {
                if (typeMatches(param.getTypeAsString(), simpleName)) return true;
            }
            // Check return type
            if (typeMatches(method.getTypeAsString(), simpleName)) return true;
        }

        // Check constructor calls: new FooBar(...)
        for (ObjectCreationExpr expr : cu.findAll(ObjectCreationExpr.class)) {
            if (typeMatches(expr.getTypeAsString(), simpleName)) return true;
        }

        // Check all ClassOrInterfaceType nodes (catches generics, casts, etc.)
        for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
            if (type.getNameAsString().equals(simpleName)) return true;
        }

        return false;
    }

    /**
     * Matches a type string against a simple name, handling generics.
     */
    private boolean typeMatches(String typeString, String simpleName) {
        // Strip generics: "List<FooBar>" -> "List", but also check inside generics
        if (typeString.equals(simpleName)) return true;
        if (typeString.contains(simpleName)) {
            // Could be in generics like "List<FooBar>" or "Map<String, FooBar>"
            return true;
        }
        return false;
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

    static String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(0, dot) : "";
    }
}
