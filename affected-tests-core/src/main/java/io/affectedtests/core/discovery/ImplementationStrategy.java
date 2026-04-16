package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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

        // 1. Naming convention: look for *Impl classes
        for (String suffix : config.implementationNaming()) {
            for (String fqn : changedClasses) {
                String implSimpleName = SourceFileScanner.simpleClassName(fqn) + suffix;
                for (String sourceFqn : allSourceFqns) {
                    if (SourceFileScanner.simpleClassName(sourceFqn).equals(implSimpleName)) {
                        implementations.add(sourceFqn);
                    }
                }
            }
        }

        // 2. AST scanning: find classes that extend/implement changed types
        JavaParser fallbackParser = (index == null) ? new JavaParser() : null;

        for (Path sourceFile : sourceFiles) {
            CompilationUnit cu = parseOrGet(sourceFile, index, fallbackParser);
            if (cu == null) continue;

            List<ClassOrInterfaceDeclaration> declarations =
                    cu.findAll(ClassOrInterfaceDeclaration.class);

            for (ClassOrInterfaceDeclaration decl : declarations) {
                for (ClassOrInterfaceType extended : decl.getExtendedTypes()) {
                    if (simpleNameToFqns.containsKey(extended.getNameAsString())) {
                        String implFqn = extractFqn(cu, decl);
                        if (implFqn != null) {
                            implementations.add(implFqn);
                            log.debug("[impl] {} extends {}", implFqn, extended.getNameAsString());
                        }
                    }
                }
                for (ClassOrInterfaceType implemented : decl.getImplementedTypes()) {
                    if (simpleNameToFqns.containsKey(implemented.getNameAsString())) {
                        String implFqn = extractFqn(cu, decl);
                        if (implFqn != null) {
                            implementations.add(implFqn);
                            log.debug("[impl] {} implements {}", implFqn, implemented.getNameAsString());
                        }
                    }
                }
            }
        }

        return implementations;
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
            log.debug("Error parsing source file {}: {}", file, e.getMessage());
        }
        return null;
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

    private String extractFqn(CompilationUnit cu, ClassOrInterfaceDeclaration decl) {
        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        String name = decl.getNameAsString();
        return pkg.isEmpty() ? name : pkg + "." + name;
    }
}
