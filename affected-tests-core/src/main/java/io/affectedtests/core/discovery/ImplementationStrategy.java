package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
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
            simpleNameToFqn.put(SourceFileScanner.simpleClassName(fqn), fqn);
        }

        // 1. Naming convention: look for *Impl classes
        Set<String> allSourceFqns = collectSourceFqns(projectDir);
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
        List<Path> sourceFiles = SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs());
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

    /**
     * Collects FQNs for all source files under project dir (used for naming-convention matching).
     */
    private Set<String> collectSourceFqns(Path projectDir) {
        Set<String> fqns = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            Path sourcePath = projectDir.resolve(sourceDir);
            if (Files.isDirectory(sourcePath)) {
                fqns.addAll(SourceFileScanner.fqnsUnder(sourcePath));
            }
            // sub-modules
            SourceFileScanner.collectSourceFiles(projectDir, List.of(sourceDir));
        }
        // Actually let's just use fqnsUnder for all source paths
        for (String sourceDir : config.sourceDirs()) {
            Path sourcePath = projectDir.resolve(sourceDir);
            if (Files.isDirectory(sourcePath)) {
                fqns.addAll(SourceFileScanner.fqnsUnder(sourcePath));
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
