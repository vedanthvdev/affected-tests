package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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
        if (!config.includeImplementationTests()) {
            log.debug("[impl] Implementation test discovery disabled");
            return new LinkedHashSet<>();
        }

        Set<String> implClasses = findImplementations(changedProductionClasses,
                collectSourceFqns(projectDir),
                SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs()),
                null);
        return discoverTestsForImpls(implClasses, projectDir, null);
    }

    /**
     * Discovers tests using a pre-built project index (avoids redundant file walks
     * and AST parses).
     */
    public Set<String> discoverTests(Set<String> changedProductionClasses, ProjectIndex index) {
        if (!config.includeImplementationTests()) {
            log.debug("[impl] Implementation test discovery disabled");
            return new LinkedHashSet<>();
        }

        Set<String> implClasses = findImplementations(changedProductionClasses,
                index.sourceFqns(), index.sourceFiles(), index);
        return discoverTestsForImpls(implClasses, null, index);
    }

    private Set<String> discoverTestsForImpls(Set<String> implClasses, Path projectDir, ProjectIndex index) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        if (!implClasses.isEmpty()) {
            log.info("[impl] Found {} implementation classes", implClasses.size());

            if (config.strategies().contains(AffectedTestsConfig.STRATEGY_NAMING)) {
                discoveredTests.addAll(index != null
                        ? namingStrategy.discoverTests(implClasses, index)
                        : namingStrategy.discoverTests(implClasses, projectDir));
            }
            if (config.strategies().contains(AffectedTestsConfig.STRATEGY_USAGE)) {
                discoveredTests.addAll(index != null
                        ? usageStrategy.discoverTests(implClasses, index)
                        : usageStrategy.discoverTests(implClasses, projectDir));
            }
        }

        log.info("[impl] Discovered {} tests via implementation strategy", discoveredTests.size());
        return discoveredTests;
    }

    /**
     * Finds classes that extend or implement any of the changed production classes.
     * Uses both JavaParser AST scanning and naming convention ({@code *Impl}).
     */
    private Set<String> findImplementations(Set<String> changedClasses,
                                            Set<String> allSourceFqns,
                                            List<Path> sourceFiles,
                                            ProjectIndex index) {
        Set<String> implementations = new LinkedHashSet<>();

        Map<String, Set<String>> simpleNameToFqns = new HashMap<>();
        for (String fqn : changedClasses) {
            simpleNameToFqns.computeIfAbsent(SourceFileScanner.simpleClassName(fqn), k -> new HashSet<>()).add(fqn);
        }

        // 1. Naming convention: look for both suffix (*Impl) and prefix
        // (Default*) derivatives of the changed interface name. The
        // config list ships with {"Impl", "Default"} and the Builder
        // javadoc promises both shapes; before this loop checked both
        // sides, "Default" was appended as a suffix (FooServiceDefault),
        // which matches nothing real — Spring/Guice code writes
        // DefaultFooService. The AST branch below rescues the clean
        // "implements FooService" case, but impls that declare the
        // super-type via generics only, or files JavaParser could not
        // parse, were silently missed.
        Set<String> changedSimpleNames = new HashSet<>();
        for (String fqn : changedClasses) {
            changedSimpleNames.add(SourceFileScanner.simpleClassName(fqn));
        }
        for (String token : config.implementationNaming()) {
            for (String changedSimple : changedSimpleNames) {
                String suffixShape = changedSimple + token;
                String prefixShape = token + changedSimple;
                for (String sourceFqn : allSourceFqns) {
                    String sourceSimple = SourceFileScanner.simpleClassName(sourceFqn);
                    if (sourceSimple.equals(suffixShape) || sourceSimple.equals(prefixShape)) {
                        implementations.add(sourceFqn);
                    }
                }
            }
        }

        // 2. AST scanning: find classes that extend/implement changed types,
        // iterated to a fixpoint so multi-level hierarchies are covered.
        //
        // Motivating case: `interface A` ← `abstract class B implements A`
        // ← `class C extends B`, with `CTest` the only real test
        // coverage of A's behaviour through the C implementation. When A
        // changes, a single pass finds only B (because nothing declares
        // `extends A` directly except B). Pre-fix, CTest was silently
        // dropped. The fixpoint loop treats each newly-found impl as a
        // fresh target for the next pass; the loop terminates when a
        // pass adds nothing new, or when depth hits the configured
        // transitiveDepth (reused as a sanity bound — in practice Java
        // hierarchies are 2-3 deep).
        JavaParser fallbackParser = (index == null) ? JavaParsers.newParser() : null;

        // Deep-copy the inner sets so the fixpoint loop's subsequent
        // `.add(implFqn)` calls don't leak mutations into
        // simpleNameToFqns. The latter is unused after this point
        // today, but a shared mutable reference is a footgun waiting
        // for the next refactor.
        Map<String, Set<String>> targetsBySimpleName = new HashMap<>();
        simpleNameToFqns.forEach((k, v) -> targetsBySimpleName.put(k, new HashSet<>(v)));
        int depthCap = Math.max(1, config.transitiveDepth());
        for (int depth = 0; depth < depthCap; depth++) {
            Set<String> newImpls = new LinkedHashSet<>();
            for (Path sourceFile : sourceFiles) {
                CompilationUnit cu = parseOrGet(sourceFile, index, fallbackParser);
                if (cu == null) continue;

                // Iterate every TypeDeclaration — not just
                // ClassOrInterfaceDeclaration. Records (Java 16+) and
                // enums (Java 5+) can both implement interfaces:
                //
                //   record UsdMoney(long cents) implements Money { ... }
                //   enum Currency implements HasCode { USD, EUR, ... }
                //
                // The old loop only scanned classes/interfaces, so a
                // service interface with a record-valued consumer
                // (increasingly common in value-object-heavy codebases)
                // saw its consumer silently dropped on interface
                // changes — the one failure mode this strategy exists
                // to prevent.
                // JavaParser's findAll(Class<T>) typing forces a raw
                // TypeDeclaration here because TypeDeclaration is
                // generic in its self-type. The supertypesOf helper
                // below re-narrows each declaration via pattern-
                // matching instanceof, so the raw iteration is safe.
                @SuppressWarnings({"rawtypes", "unchecked"})
                List<TypeDeclaration<?>> declarations =
                        (List<TypeDeclaration<?>>) (List) cu.findAll(TypeDeclaration.class);

                for (TypeDeclaration<?> decl : declarations) {
                    String implFqn = extractFqn(cu, decl);
                    if (implFqn == null || implementations.contains(implFqn)) {
                        // Already known — skip. `implementations.contains`
                        // is the loop's termination gate: once a class is
                        // in the set it stops seeding further passes.
                        continue;
                    }
                    for (ClassOrInterfaceType supertype : supertypesOf(decl)) {
                        if (targetsBySimpleName.containsKey(supertype.getNameAsString())) {
                            newImpls.add(implFqn);
                            log.debug("[impl] depth {}: {} ({}) extends/implements {}",
                                    depth + 1, implFqn, decl.getClass().getSimpleName(),
                                    supertype.getNameAsString());
                            break;
                        }
                    }
                }
            }

            if (newImpls.isEmpty()) {
                break;
            }
            implementations.addAll(newImpls);
            // Seed the next pass with the newly-found impls keyed by
            // simple name — subclasses of these impls will match on the
            // next iteration. Previously-targeted names stay in the map
            // so a single type can be discovered via two independent
            // paths without losing an edge.
            for (String implFqn : newImpls) {
                targetsBySimpleName
                        .computeIfAbsent(SourceFileScanner.simpleClassName(implFqn),
                                k -> new HashSet<>())
                        .add(implFqn);
            }
        }

        return implementations;
    }

    private CompilationUnit parseOrGet(Path file, ProjectIndex index, JavaParser fallbackParser) {
        if (index != null) {
            return index.compilationUnit(file);
        }
        return JavaParsers.parseOrWarn(fallbackParser, file, "impl");
    }

    /**
     * Collects FQNs for all source files under the project dir at any depth.
     */
    private Set<String> collectSourceFqns(Path projectDir) {
        Set<String> fqns = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            for (Path resolved : SourceFileScanner.findAllMatchingDirs(projectDir, sourceDir)) {
                fqns.addAll(SourceFileScanner.fqnsUnder(resolved));
            }
        }
        return fqns;
    }

    private String extractFqn(CompilationUnit cu, TypeDeclaration<?> decl) {
        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        String name = decl.getNameAsString();
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /**
     * Returns the combined extends + implements list for any
     * {@link TypeDeclaration} shape we care about.
     *
     * <p>Records and annotations cannot {@code extends} another named
     * type (records implicitly extend {@code java.lang.Record},
     * annotations implicitly extend {@code java.lang.annotation.Annotation}),
     * and {@link EnumDeclaration} enumerates its constants rather than a
     * superclass. For those shapes only {@code implements} contributes
     * supertype edges.
     */
    private static List<ClassOrInterfaceType> supertypesOf(TypeDeclaration<?> decl) {
        List<ClassOrInterfaceType> result = new ArrayList<>();
        if (decl instanceof ClassOrInterfaceDeclaration c) {
            result.addAll(c.getExtendedTypes());
            result.addAll(c.getImplementedTypes());
        } else if (decl instanceof RecordDeclaration r) {
            result.addAll(r.getImplementedTypes());
        } else if (decl instanceof EnumDeclaration e) {
            result.addAll(e.getImplementedTypes());
        } else if (decl instanceof com.github.javaparser.ast.body.AnnotationDeclaration) {
            // Annotations can't widen their implicit supertype
            // (java.lang.annotation.Annotation). Empty list is the
            // correct answer — not a drop.
            return result;
        } else {
            // Defensive: when a future JavaParser release introduces a
            // new TypeDeclaration subtype we want the new construct to
            // be *loud* rather than silently dropping every consumer
            // test that depended on it (the exact failure mode records
            // caused before batch 4 fixed them). WARN surfaces at the
            // default log level so operators can open a ticket; the
            // strategy keeps running and treats the type as having no
            // known supertypes, which is the correct conservative
            // fallback (cannot become a false impl match; at worst we
            // underselect for a single declaration).
            log.warn("Affected Tests: [impl] unknown TypeDeclaration subtype {} for {} — "
                            + "supertype edges cannot be derived, skipping",
                    decl.getClass().getSimpleName(), decl.getNameAsString());
        }
        return result;
    }
}
