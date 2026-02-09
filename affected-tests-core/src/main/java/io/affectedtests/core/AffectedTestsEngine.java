package io.affectedtests.core;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.discovery.*;
import io.affectedtests.core.git.GitChangeDetector;
import io.affectedtests.core.mapping.PathToClassMapper;
import io.affectedtests.core.mapping.PathToClassMapper.MappingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Main orchestrator: detects changes, maps them to classes, discovers affected tests.
 *
 * <p>Usage:
 * <pre>{@code
 * AffectedTestsConfig config = AffectedTestsConfig.builder().build();
 * AffectedTestsEngine engine = new AffectedTestsEngine(config, projectDir);
 * AffectedTestsResult result = engine.run();
 * // result.testClassFqns() contains the FQNs of tests to run
 * }</pre>
 */
public final class AffectedTestsEngine {

    private static final Logger log = LoggerFactory.getLogger(AffectedTestsEngine.class);

    private final AffectedTestsConfig config;
    private final Path projectDir;

    public AffectedTestsEngine(AffectedTestsConfig config, Path projectDir) {
        this.config = config;
        this.projectDir = projectDir;
    }

    /**
     * Result of the affected tests analysis.
     */
    public record AffectedTestsResult(
            Set<String> testClassFqns,
            Set<String> changedFiles,
            Set<String> changedProductionClasses,
            Set<String> changedTestClasses,
            boolean runAll
    ) {}

    /**
     * Runs the full pipeline: detect changes, map to classes, discover tests.
     */
    public AffectedTestsResult run() {
        log.info("=== Affected Tests Analysis ===");
        log.info("Project dir: {}", projectDir);
        log.info("Base ref: {}", config.baseRef());
        log.info("Strategies: {}", config.strategies());
        log.info("Transitive depth: {}", config.transitiveDepth());

        // Step 1: Detect changed files
        GitChangeDetector changeDetector = new GitChangeDetector(projectDir, config);
        Set<String> changedFiles = changeDetector.detectChangedFiles();

        if (changedFiles.isEmpty()) {
            log.info("No changed files detected.");
            return new AffectedTestsResult(Set.of(), changedFiles, Set.of(), Set.of(),
                    config.runAllIfNoMatches());
        }

        // Step 2: Map to production and test classes
        PathToClassMapper mapper = new PathToClassMapper(config);
        MappingResult mapping = mapper.mapChangedFiles(changedFiles);

        // Step 3: Discover affected tests
        Set<String> allTestsToRun = new LinkedHashSet<>();

        // Always include directly changed test files
        allTestsToRun.addAll(mapping.testClasses());
        log.info("Directly changed test classes: {}", mapping.testClasses().size());

        // Initialize strategies
        NamingConventionStrategy namingStrategy = new NamingConventionStrategy(config);
        UsageStrategy usageStrategy = new UsageStrategy(config);
        ImplementationStrategy implStrategy = new ImplementationStrategy(config, namingStrategy, usageStrategy);
        TransitiveStrategy transitiveStrategy = new TransitiveStrategy(config, namingStrategy, usageStrategy);

        Set<String> productionClasses = mapping.productionClasses();

        // Determine which directories to search for tests.
        // Start with the root project dir, then add cross-module mapped dirs.
        Set<Path> searchDirs = resolveTestSearchDirs(changedFiles, mapper);
        log.info("Test search directories: {}", searchDirs);

        // Run strategies against each search directory
        for (Path searchDir : searchDirs) {
            if (config.strategies().contains("naming")) {
                allTestsToRun.addAll(namingStrategy.discoverTests(productionClasses, searchDir));
            }
            if (config.strategies().contains("usage")) {
                allTestsToRun.addAll(usageStrategy.discoverTests(productionClasses, searchDir));
            }
            if (config.strategies().contains("impl")) {
                allTestsToRun.addAll(implStrategy.discoverTests(productionClasses, searchDir));
            }
            if (config.transitiveDepth() > 0) {
                allTestsToRun.addAll(transitiveStrategy.discoverTests(productionClasses, searchDir));
            }
        }

        // Handle no-match scenario
        boolean runAll = false;
        if (allTestsToRun.isEmpty() && config.runAllIfNoMatches()) {
            log.warn("No affected tests found but runAllIfNoMatches=true. Running full suite.");
            runAll = true;
        } else if (allTestsToRun.isEmpty()) {
            log.info("No affected tests found. Nothing to run.");
        }

        log.info("=== Result: {} affected test classes ===", allTestsToRun.size());
        allTestsToRun.forEach(t -> log.info("  -> {}", t));

        return new AffectedTestsResult(
                allTestsToRun,
                changedFiles,
                mapping.productionClasses(),
                mapping.testClasses(),
                runAll
        );
    }

    /**
     * Resolves which directories to search for tests. Always includes the root project
     * directory. When {@code testProjectMapping} is configured, also includes the
     * mapped target module directories for any changed source modules.
     *
     * <p>For example, if a file in {@code api/src/main/java/...} changed and
     * {@code testProjectMapping = {":api": ":application"}}, then the search dirs
     * will include both the root project dir and {@code <root>/application/}.
     */
    private Set<Path> resolveTestSearchDirs(Set<String> changedFiles, PathToClassMapper mapper) {
        Set<Path> dirs = new LinkedHashSet<>();

        // Always search from project root (covers single-project and sub-module walks)
        dirs.add(projectDir);

        Map<String, String> mapping = config.testProjectMapping();
        if (mapping.isEmpty()) {
            return dirs;
        }

        // Determine which source modules had changes
        Set<String> changedModules = new LinkedHashSet<>();
        for (String file : changedFiles) {
            String module = mapper.extractModule(file);
            if (!module.isEmpty()) {
                changedModules.add(module);
            }
        }

        // Map source modules to test modules and add those directories
        for (String changedModule : changedModules) {
            // Try with and without the ":" prefix for flexibility
            String targetModule = mapping.get(":" + changedModule);
            if (targetModule == null) {
                targetModule = mapping.get(changedModule);
            }
            if (targetModule != null) {
                // Strip leading ":" from target module name
                String targetDir = targetModule.startsWith(":") ? targetModule.substring(1) : targetModule;

                // Validate that the resolved path stays within the project directory
                Path targetPath = projectDir.resolve(targetDir).normalize();
                if (!targetPath.startsWith(projectDir)) {
                    log.error("Rejecting testProjectMapping target '{}' — resolves outside the project directory.", targetDir);
                    continue;
                }

                if (Files.isDirectory(targetPath)) {
                    dirs.add(targetPath);
                    log.info("Cross-module mapping: {} -> {} ({})", changedModule, targetModule, targetPath);
                } else {
                    log.warn("Mapped test project directory does not exist: {}", targetPath);
                }
            }
        }

        return dirs;
    }
}
