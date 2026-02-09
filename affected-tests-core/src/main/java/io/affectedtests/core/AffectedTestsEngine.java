package io.affectedtests.core;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.discovery.*;
import io.affectedtests.core.git.GitChangeDetector;
import io.affectedtests.core.mapping.PathToClassMapper;
import io.affectedtests.core.mapping.PathToClassMapper.MappingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Main orchestrator: detects changes, maps them to classes, discovers affected tests.
 * <p>
 * Usage:
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
     * Runs the full pipeline: detect changes → map to classes → discover tests.
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

        // Apply naming strategy
        if (config.strategies().contains("naming")) {
            allTestsToRun.addAll(namingStrategy.discoverTests(productionClasses, projectDir));
        }

        // Apply usage strategy
        if (config.strategies().contains("usage")) {
            allTestsToRun.addAll(usageStrategy.discoverTests(productionClasses, projectDir));
        }

        // Apply implementation strategy
        if (config.strategies().contains("impl")) {
            allTestsToRun.addAll(implStrategy.discoverTests(productionClasses, projectDir));
        }

        // Apply transitive strategy (uses naming + usage internally)
        if (config.transitiveDepth() > 0) {
            allTestsToRun.addAll(transitiveStrategy.discoverTests(productionClasses, projectDir));
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
        allTestsToRun.forEach(t -> log.info("  → {}", t));

        return new AffectedTestsResult(
                allTestsToRun,
                changedFiles,
                mapping.productionClasses(),
                mapping.testClasses(),
                runAll
        );
    }
}
