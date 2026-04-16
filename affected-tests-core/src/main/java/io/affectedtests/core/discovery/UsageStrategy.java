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

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Strategy: Usage / Reference scanning.
 *
 * <p>Scans test source files for references to changed production classes.
 * Uses a two-tier approach:
 * <ol>
 *   <li><strong>Import matching (primary):</strong> if a test file directly imports the
 *       changed class FQN, it is considered affected.</li>
 *   <li><strong>Type reference scanning (secondary):</strong> for same-package and wildcard
 *       import cases, scans for the simple name in field declarations, method parameters,
 *       constructor instantiations, and type references.</li>
 * </ol>
 */
public final class UsageStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(UsageStrategy.class);

    private final AffectedTestsConfig config;
    // Patterns are derived from simple class names; cache avoids recompiling on
    // every AST-walk iteration (hot path called per file × per changed class).
    private final Map<String, Pattern> patternCache = new HashMap<>();

    public UsageStrategy(AffectedTestsConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        List<Path> testFiles = SourceFileScanner.collectTestFiles(projectDir, config.testDirs());
        return scanTestFiles(changedProductionClasses, testFiles, null);
    }

    /**
     * Discovers tests using a pre-built project index (avoids redundant file walks
     * and AST parses).
     */
    public Set<String> discoverTests(Set<String> changedProductionClasses, ProjectIndex index) {
        return scanTestFiles(changedProductionClasses, index.testFiles(), index);
    }

    private Set<String> scanTestFiles(Set<String> changedProductionClasses,
                                      List<Path> testFiles, ProjectIndex index) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        if (changedProductionClasses.isEmpty()) {
            return discoveredTests;
        }

        Map<String, Set<String>> simpleNameToFqns = new HashMap<>();
        Set<String> changedFqns = new HashSet<>(changedProductionClasses);
        Set<String> simpleNames = new HashSet<>();
        for (String fqn : changedProductionClasses) {
            String simpleName = SourceFileScanner.simpleClassName(fqn);
            simpleNameToFqns.computeIfAbsent(simpleName, k -> new HashSet<>()).add(fqn);
            simpleNames.add(simpleName);
        }

        JavaParser fallbackParser = (index == null) ? new JavaParser() : null;

        for (Path testFile : testFiles) {
            CompilationUnit cu = parseOrGet(testFile, index, fallbackParser);
            if (cu == null) continue;

            String testFqn = extractFqn(cu, testFile);
            if (testFqn == null) continue;

            if (changedFqns.contains(testFqn)) continue;

            if (testReferencesChangedClass(cu, changedFqns, simpleNames, simpleNameToFqns)) {
                discoveredTests.add(testFqn);
                log.debug("Usage match: {}", testFqn);
            }
        }

        log.info("[usage] Discovered {} tests for {} changed classes",
                discoveredTests.size(), changedProductionClasses.size());
        return discoveredTests;
    }

    private CompilationUnit parseOrGet(Path file, ProjectIndex index, JavaParser fallbackParser) {
        if (index != null) {
            return index.compilationUnit(file);
        }
        try {
            ParseResult<CompilationUnit> result = fallbackParser.parse(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return result.getResult().get();
            }
        } catch (Exception e) {
            log.debug("Error parsing {}: {}", file, e.getMessage());
        }
        return null;
    }

    /**
     * Checks whether a test compilation unit references any of the changed classes.
     * Uses a two-tier approach: direct import match first, then type reference scanning.
     */
    private boolean testReferencesChangedClass(CompilationUnit cu,
                                               Set<String> changedFqns,
                                               Set<String> simpleNames,
                                               Map<String, Set<String>> simpleNameToFqns) {
        Set<String> importedFqns = new HashSet<>();
        Set<String> wildcardPackages = new HashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk()) {
                wildcardPackages.add(imp.getNameAsString());
            } else {
                importedFqns.add(imp.getNameAsString());
            }
        }

        // Tier 1: Direct import match
        for (String changedFqn : changedFqns) {
            if (importedFqns.contains(changedFqn)) {
                log.debug("  Direct import match: {}", changedFqn);
                return true;
            }
        }

        // Tier 1b: Wildcard import match
        for (String changedFqn : changedFqns) {
            String pkg = SourceFileScanner.packageOf(changedFqn);
            if (wildcardPackages.contains(pkg)) {
                String simpleName = SourceFileScanner.simpleClassName(changedFqn);
                if (typeNameAppearsInAst(cu, simpleName)) {
                    log.debug("  Wildcard import + type ref match: {}", changedFqn);
                    return true;
                }
            }
        }

        // Tier 2: Same-package (no import needed)
        String testPackage = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        for (String changedFqn : changedFqns) {
            String changedPkg = SourceFileScanner.packageOf(changedFqn);
            if (testPackage.equals(changedPkg)) {
                String simpleName = SourceFileScanner.simpleClassName(changedFqn);
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
     */
    private boolean typeNameAppearsInAst(CompilationUnit cu, String simpleName) {
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator var : field.getVariables()) {
                if (typeMatches(var.getTypeAsString(), simpleName)) return true;
            }
        }

        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            for (Parameter param : method.getParameters()) {
                if (typeMatches(param.getTypeAsString(), simpleName)) return true;
            }
            if (typeMatches(method.getTypeAsString(), simpleName)) return true;
        }

        for (ObjectCreationExpr expr : cu.findAll(ObjectCreationExpr.class)) {
            if (typeMatches(expr.getTypeAsString(), simpleName)) return true;
        }

        for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
            if (type.getNameAsString().equals(simpleName)) return true;
        }

        return false;
    }

    /**
     * Matches a type string against a simple name, handling generics.
     * Uses word-boundary matching to avoid false positives (e.g. "Id" matching "GridLayout").
     */
    private boolean typeMatches(String typeString, String simpleName) {
        if (typeString.equals(simpleName)) return true;
        Pattern pattern = patternCache.computeIfAbsent(simpleName,
                n -> Pattern.compile("(?<![a-zA-Z0-9_])" + Pattern.quote(n) + "(?![a-zA-Z0-9_])"));
        return pattern.matcher(typeString).find();
    }

    private String extractFqn(CompilationUnit cu, Path testFile) {
        for (String testDir : config.testDirs()) {
            Path testRoot = SourceFileScanner.findTestRoot(testFile, testDir);
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

        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        return cu.getPrimaryTypeName()
                .map(name -> pkg.isEmpty() ? name : pkg + "." + name)
                .orElse(null);
    }
}
