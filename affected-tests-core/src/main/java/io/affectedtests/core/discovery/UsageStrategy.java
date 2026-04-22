package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
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

        JavaParser fallbackParser = (index == null) ? JavaParsers.newParser() : null;

        for (Path testFile : testFiles) {
            CompilationUnit cu = parseOrGet(testFile, index, fallbackParser);
            if (cu == null) continue;

            String testFqn = extractFqn(cu, testFile);
            if (testFqn == null) continue;

            if (changedFqns.contains(testFqn)) continue;

            if (testReferencesChangedClass(cu, changedFqns, simpleNames, simpleNameToFqns)) {
                discoveredTests.add(testFqn);
                log.debug("Usage match: {}", LogSanitizer.sanitize(testFqn));
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
        return JavaParsers.parseOrWarn(fallbackParser, file, "usage");
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
            String name = imp.getNameAsString();
            if (imp.isStatic()) {
                // Static imports are member-scoped, not type-scoped. The name
                // reported by JavaParser for `import static a.b.C.MAX;` is
                // `a.b.C.MAX` and for `import static a.b.C.*;` is `a.b.C`
                // (with isAsterisk=true). The thing a test actually depends
                // on in both cases is the class `a.b.C`, so we normalise
                // back to the class FQN for direct-import matching. Before
                // this, a test that only referenced a changed class through
                // a `import static … .CONSTANT;` was silently missed
                // because the name in importedFqns had a member suffix that
                // never equalled any changedFqn.
                String classFqn = imp.isAsterisk()
                        ? name
                        : stripLastSegment(name);
                if (classFqn != null) {
                    importedFqns.add(classFqn);
                }
            } else if (imp.isAsterisk()) {
                wildcardPackages.add(name);
            } else {
                importedFqns.add(name);
            }
        }

        // Tier 1: Direct import match. `innerClassMatch` also fires when
        // an import targets a nested class of the changed outer — e.g.
        // the test writes `import c.d.Outer.Inner;` and the diff touches
        // `c.d.Outer` (PathToClassMapper is file-based, so it only
        // surfaces the outer FQN for the nested class's change). Without
        // this, a test that only uses the inner class is silently missed.
        // All {@code changedFqn} and {@code imported} values in the
        // Tier 1 / 1b / 2 / 3 blocks below are diff-derived and may
        // legitimately carry odd but-valid characters that still need
        // control-char sanitisation before they hit the logger — a
        // malicious MR can craft an import line like
        // {@code import com.evil.\u001b[m;}. Sanitisation is applied
        // even at DEBUG because operators bumping level to chase a
        // false-positive selection is exactly when forgery-resistance
        // matters most.
        for (String changedFqn : changedFqns) {
            if (importedFqns.contains(changedFqn)) {
                log.debug("  Direct import match: {}", LogSanitizer.sanitize(changedFqn));
                return true;
            }
            String innerPrefix = changedFqn + ".";
            for (String imported : importedFqns) {
                if (imported.startsWith(innerPrefix)) {
                    log.debug("  Inner-class import match: {} <- {}",
                            LogSanitizer.sanitize(changedFqn),
                            LogSanitizer.sanitize(imported));
                    return true;
                }
            }
        }

        // Tier 1b: Wildcard import match. Two shapes have to be handled:
        //
        //   * `import com.example.service.*;`      — a package wildcard;
        //     the test may reference any simple type inside that
        //     package. We gate on the simple name actually appearing in
        //     the AST so we don't over-select.
        //
        //   * `import com.example.Outer.*;`        — a class-member
        //     wildcard. Every member of `Outer` (including its nested
        //     types and public static members) is visible in the test
        //     without further qualification, so a change to
        //     `Outer.java` — which PathToClassMapper reports as a
        //     change to `com.example.Outer` — must pull the test in
        //     unconditionally; the test doesn't have to mention
        //     `Outer` by name at all. Pre-fix this case was bucketed
        //     as a package wildcard (`wildcardPackages` held
        //     "com.example.Outer") and the subsequent
        //     `typeNameAppearsInAst("Outer")` check almost always
        //     failed, silently dropping the consumer's coverage.
        for (String changedFqn : changedFqns) {
            if (wildcardPackages.contains(changedFqn)) {
                log.debug("  Wildcard class-member import match: {}",
                        LogSanitizer.sanitize(changedFqn));
                return true;
            }
            String pkg = SourceFileScanner.packageOf(changedFqn);
            if (wildcardPackages.contains(pkg)) {
                String simpleName = SourceFileScanner.simpleClassName(changedFqn);
                if (typeNameAppearsInAst(cu, simpleName)) {
                    log.debug("  Wildcard package import + type ref match: {}",
                            LogSanitizer.sanitize(changedFqn));
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
                    log.debug("  Same-package type ref match: {}",
                            LogSanitizer.sanitize(changedFqn));
                    return true;
                }
            }
        }

        // Tier 3: Fully-qualified inline references that never went
        // through an import. Catches
        //   `com.example.other.Thing t = new com.example.other.Thing();`
        //   `(com.example.other.Thing) x`
        //   `com.example.other.Thing.Inner nested = ...;`
        // i.e. anything where the test author typed the full dotted
        // name of the changed class at a use site. Walks every
        // {@link ClassOrInterfaceType} node and reads its
        // {@code getNameWithScope()} (which reconstitutes the dotted
        // chain from the parent scope references), so we don't depend
        // on raw source text or comments. The bare-name case is
        // already handled by Tier 1 / 1b / 2; skip dotless hits here
        // to avoid double-counting.
        for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
            String scoped = nameWithScopeOrNull(type);
            if (scoped == null || !scoped.contains(".")) continue;
            for (String changedFqn : changedFqns) {
                if (scoped.equals(changedFqn) || scoped.startsWith(changedFqn + ".")) {
                    log.debug("  Inline fully-qualified reference: {} -> {}",
                            LogSanitizer.sanitize(scoped),
                            LogSanitizer.sanitize(changedFqn));
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns the dotted name of {@code type} including its enclosing
     * scope chain, or {@code null} when JavaParser throws while
     * reconstructing it (e.g. a partially-resolved type node in an
     * invalid source file). Isolating the guard here means the caller
     * never has to defensively wrap the AST walk — a best-effort null
     * is enough to skip a single type node while the rest of the file
     * still contributes to discovery.
     */
    private static String nameWithScopeOrNull(ClassOrInterfaceType type) {
        try {
            return type.getNameWithScope();
        } catch (RuntimeException e) {
            return null;
        }
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
     * Returns {@code name} with the final {@code .segment} removed. For
     * {@code "a.b.C.MAX"} returns {@code "a.b.C"}; for a single-segment
     * input returns {@code null} (the input is already as stripped as it
     * can be and clearly wasn't a qualified member reference).
     */
    static String stripLastSegment(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0) {
            return null;
        }
        return name.substring(0, idx);
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
