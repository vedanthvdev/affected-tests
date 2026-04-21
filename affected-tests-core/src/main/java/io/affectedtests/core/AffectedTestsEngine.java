package io.affectedtests.core;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.discovery.*;
import io.affectedtests.core.git.GitChangeDetector;
import io.affectedtests.core.mapping.PathToClassMapper;
import io.affectedtests.core.mapping.PathToClassMapper.MappingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Main orchestrator: detects changes, maps them to classes, discovers affected tests.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Detect changed files via JGit ({@code baseRef..HEAD} + uncommitted/staged)</li>
 *   <li>Map file paths to production and test class FQNs</li>
 *   <li>Run all enabled discovery strategies (naming, usage, impl, transitive)
 *       and merge their results. Scanning is recursive — modules at any nesting
 *       depth are discovered automatically.</li>
 *   <li>Filter the union against test classes that actually exist on disk so
 *       deleted/renamed tests don't reach the downstream {@code test} task</li>
 *   <li>Return the filtered FQN set together with the file path of each test,
 *       so callers can route per-module test invocations correctly</li>
 * </ol>
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
     * Why a result flipped to {@code runAll = true}, or {@link #NONE} when no
     * escalation occurred. Callers (notably the Gradle task) use this to log
     * an accurate trigger name — without it, any escalation would have to be
     * attributed to a single hard-coded flag, which misleads users once
     * multiple escalation paths exist.
     */
    public enum EscalationReason {
        /** No escalation — either a filtered selection or a plain "nothing to do" result. */
        NONE,
        /**
         * Git produced an empty change set (no files differ between
         * {@code baseRef} and the working tree) and {@code runAllIfNoMatches}
         * was true. Distinct from {@link #RUN_ALL_IF_NO_MATCHES} because
         * discovery never actually ran, and the lifecycle log must say so
         * rather than claim "no affected tests discovered".
         */
        RUN_ALL_ON_EMPTY_CHANGESET,
        /** Discovery completed, returned an empty test set, and {@code runAllIfNoMatches} was true. */
        RUN_ALL_IF_NO_MATCHES,
        /**
         * The change set contained at least one file the mapper could not
         * resolve to a Java class under the configured source/test
         * directories (e.g. {@code application.yml}, {@code build.gradle}),
         * and {@code runAllOnNonJavaChange} was true.
         */
        RUN_ALL_ON_NON_JAVA_CHANGE
    }

    /**
     * Result of the affected tests analysis.
     *
     * @param testClassFqns            FQNs of tests that should be executed
     * @param testFqnToPath            map of test FQN to its absolute file path on
     *                                 disk (used by callers to route invocations
     *                                 to the correct subproject). Empty when
     *                                 {@link #runAll} is {@code true}.
     * @param changedFiles             raw changed file paths from git
     * @param changedProductionClasses production FQNs detected in the diff
     * @param changedTestClasses       test FQNs detected directly in the diff
     *                                 (may include FQNs whose files were deleted)
     * @param runAll                   whether the caller should run the full suite
     * @param escalationReason         tag describing why {@code runAll} flipped,
     *                                 or {@link EscalationReason#NONE} when it did not
     */
    public record AffectedTestsResult(
            Set<String> testClassFqns,
            Map<String, Path> testFqnToPath,
            Set<String> changedFiles,
            Set<String> changedProductionClasses,
            Set<String> changedTestClasses,
            boolean runAll,
            EscalationReason escalationReason
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

        GitChangeDetector changeDetector = new GitChangeDetector(projectDir, config);
        Set<String> changedFiles = changeDetector.detectChangedFiles();

        if (changedFiles.isEmpty()) {
            log.info("No changed files detected.");
            boolean runAll = config.runAllIfNoMatches();
            // Distinct reason from the post-discovery empty path: discovery
            // never ran here, so the task log must not claim "no affected
            // tests discovered" when in fact nothing was ever looked at.
            return new AffectedTestsResult(Set.of(), Map.of(), changedFiles, Set.of(), Set.of(),
                    runAll,
                    runAll ? EscalationReason.RUN_ALL_ON_EMPTY_CHANGESET : EscalationReason.NONE);
        }

        PathToClassMapper mapper = new PathToClassMapper(config);
        MappingResult mapping = mapper.mapChangedFiles(changedFiles);

        // Safety escalation: if the caller opted in (default) and any changed
        // file cannot be mapped to a Java class under the configured source
        // dirs — typically application.yml, build.gradle, a Liquibase
        // changelog, a logback config — we refuse to pick a subset. The
        // motto is "run more, never run less": the filtered set is empty and
        // runAll is true so the downstream task executes the whole suite.
        if (config.runAllOnNonJavaChange() && !mapping.unmappedChangedFiles().isEmpty()) {
            log.warn("Non-Java / unmapped change detected ({} file(s)). Forcing full test suite. Examples: {}",
                    mapping.unmappedChangedFiles().size(),
                    mapping.unmappedChangedFiles().stream().limit(5).toList());
            return new AffectedTestsResult(
                    Set.of(),
                    Map.of(),
                    changedFiles,
                    mapping.productionClasses(),
                    mapping.testClasses(),
                    true,
                    EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);
        }

        Set<String> candidateTests = new LinkedHashSet<>();
        candidateTests.addAll(mapping.testClasses());
        log.info("Directly changed test classes: {}", mapping.testClasses().size());

        NamingConventionStrategy namingStrategy = new NamingConventionStrategy(config);
        UsageStrategy usageStrategy = new UsageStrategy(config);
        ImplementationStrategy implStrategy = new ImplementationStrategy(config, namingStrategy, usageStrategy);
        TransitiveStrategy transitiveStrategy = new TransitiveStrategy(config, namingStrategy, usageStrategy);

        Set<String> productionClasses = mapping.productionClasses();

        ProjectIndex index = ProjectIndex.build(projectDir, config);

        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_NAMING)) {
            candidateTests.addAll(namingStrategy.discoverTests(productionClasses, index));
        }
        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_USAGE)) {
            candidateTests.addAll(usageStrategy.discoverTests(productionClasses, index));
        }
        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_IMPL)) {
            candidateTests.addAll(implStrategy.discoverTests(productionClasses, index));
        }
        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_TRANSITIVE)
                && config.transitiveDepth() > 0) {
            candidateTests.addAll(transitiveStrategy.discoverTests(productionClasses, index));
        }

        // C2 guard: keep only FQNs whose source file still exists on disk.
        // Deleted/renamed tests (their old FQN) must not be passed to Gradle's
        // --tests flag or it will fail the whole build with "No tests found".
        Map<String, Path> knownTests = index.testFqnToPath();
        Set<String> allTestsToRun = new LinkedHashSet<>();
        Map<String, Path> fqnToPath = new LinkedHashMap<>();
        for (String fqn : candidateTests) {
            Path file = knownTests.get(fqn);
            if (file != null) {
                allTestsToRun.add(fqn);
                fqnToPath.put(fqn, file);
            } else {
                log.debug("Skipping FQN with no matching test file on disk: {}", fqn);
            }
        }

        boolean runAll = false;
        EscalationReason reason = EscalationReason.NONE;
        if (allTestsToRun.isEmpty() && config.runAllIfNoMatches()) {
            log.warn("No affected tests found but runAllIfNoMatches=true. Running full suite.");
            runAll = true;
            reason = EscalationReason.RUN_ALL_IF_NO_MATCHES;
        } else if (allTestsToRun.isEmpty()) {
            log.info("No affected tests found. Nothing to run.");
        }

        log.info("=== Result: {} affected test classes ===", allTestsToRun.size());
        allTestsToRun.forEach(t -> log.info("  -> {}", t));

        return new AffectedTestsResult(
                allTestsToRun,
                Collections.unmodifiableMap(fqnToPath),
                changedFiles,
                mapping.productionClasses(),
                mapping.testClasses(),
                runAll,
                reason
        );
    }
}
