package io.affectedtests.core;

import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Situation;
import io.affectedtests.core.discovery.*;
import io.affectedtests.core.git.GitChangeDetector;
import io.affectedtests.core.mapping.PathToClassMapper;
import io.affectedtests.core.mapping.PathToClassMapper.MappingResult;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Main orchestrator: detects changes, maps them to classes, discovers affected tests.
 *
 * <p>Pipeline (v2 situation-based):
 * <ol>
 *   <li>Detect changed files via JGit ({@code baseRef..HEAD} + uncommitted/staged)</li>
 *   <li>Map file paths into five mutually-exclusive buckets: ignored,
 *       out-of-scope, production, test, unmapped</li>
 *   <li>Pick the {@link Situation} for the diff (see evaluation order in the
 *       {@link Situation} javadoc). {@link Situation#EMPTY_DIFF} short-circuits
 *       before any discovery runs.</li>
 *   <li>For "ambiguous" situations (empty diff, all-ignored, all-out-of-scope,
 *       unmapped) the {@link Action} from {@link AffectedTestsConfig#actionFor}
 *       is applied directly — no discovery.</li>
 *   <li>Otherwise run all enabled discovery strategies (naming, usage, impl,
 *       transitive) and merge their results, then route through
 *       {@link Situation#DISCOVERY_EMPTY} or {@link Situation#DISCOVERY_SUCCESS}.</li>
 *   <li>Filter the union against test classes that actually exist on disk so
 *       deleted/renamed tests don't reach the downstream {@code test} task</li>
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
     * escalation occurred. Preserved as a v1 back-compat surface so that
     * existing Gradle-task callers keep receiving the same reason codes —
     * the v2 engine now records a richer {@link Situation}/{@link Action}
     * pair internally and derives the legacy code from them.
     */
    public enum EscalationReason {
        /** No escalation — either a filtered selection or a plain "nothing to do" result. */
        NONE,
        /**
         * Git produced an empty change set (no files differ between
         * {@code baseRef} and the working tree) and {@code runAllIfNoMatches}
         * was true. Derived from {@link Situation#EMPTY_DIFF} +
         * {@link Action#FULL_SUITE}.
         */
        RUN_ALL_ON_EMPTY_CHANGESET,
        /**
         * Discovery completed, returned an empty test set, and the action
         * for {@link Situation#DISCOVERY_EMPTY} resolved to
         * {@link Action#FULL_SUITE}.
         */
        RUN_ALL_IF_NO_MATCHES,
        /**
         * The change set contained at least one file the mapper could not
         * resolve to a Java class under the configured source/test
         * directories, and the action for {@link Situation#UNMAPPED_FILE}
         * resolved to {@link Action#FULL_SUITE}.
         */
        RUN_ALL_ON_NON_JAVA_CHANGE,
        /**
         * Every file in the diff matched {@link AffectedTestsConfig#ignorePaths()}
         * and the action for {@link Situation#ALL_FILES_IGNORED} resolved
         * to {@link Action#FULL_SUITE}. v2-only — no legacy boolean
         * produces this code.
         */
        RUN_ALL_ON_ALL_FILES_IGNORED,
        /**
         * Every file in the diff sat under
         * {@link AffectedTestsConfig#outOfScopeTestDirs()} or
         * {@link AffectedTestsConfig#outOfScopeSourceDirs()} and the
         * action for {@link Situation#ALL_FILES_OUT_OF_SCOPE} resolved to
         * {@link Action#FULL_SUITE}. v2-only.
         */
        RUN_ALL_ON_ALL_FILES_OUT_OF_SCOPE
    }

    /**
     * Per-bucket breakdown of the diff as classified by
     * {@link PathToClassMapper}. Populated on every
     * {@link AffectedTestsResult} (empty buckets when the engine
     * short-circuited before mapping, e.g. on {@link Situation#EMPTY_DIFF}).
     * Carried on the result so {@code --explain} and downstream log
     * lines can describe "why" without re-running the mapper.
     *
     * @param ignoredFiles     files matching {@link AffectedTestsConfig#ignorePaths()}
     * @param outOfScopeFiles  files under {@link AffectedTestsConfig#outOfScopeTestDirs()}
     *                         or {@link AffectedTestsConfig#outOfScopeSourceDirs()}
     * @param productionFiles  {@code .java} files under a configured source dir
     * @param testFiles        {@code .java} files under a configured test dir
     * @param unmappedFiles    everything else (yaml, gradle, migrations, stray .java)
     */
    public record Buckets(
            Set<String> ignoredFiles,
            Set<String> outOfScopeFiles,
            Set<String> productionFiles,
            Set<String> testFiles,
            Set<String> unmappedFiles
    ) {
        public static Buckets empty() {
            return new Buckets(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        /** Total file count across every bucket — always equal to the size of the diff. */
        public int total() {
            return ignoredFiles.size() + outOfScopeFiles.size()
                    + productionFiles.size() + testFiles.size()
                    + unmappedFiles.size();
        }
    }

    /**
     * Result of the affected tests analysis.
     *
     * @param testClassFqns            FQNs of tests that should be executed
     *                                 (empty when {@link #runAll} or
     *                                 {@link #skipped} is true).
     * @param testFqnToPath            map of test FQN to its absolute file path on
     *                                 disk (used by callers to route invocations
     *                                 to the correct subproject). Empty when
     *                                 {@link #runAll} or {@link #skipped} is
     *                                 {@code true}.
     * @param changedFiles             raw changed file paths from git
     * @param changedProductionClasses production FQNs detected in the diff
     * @param changedTestClasses       test FQNs detected directly in the diff
     *                                 (may include FQNs whose files were deleted)
     * @param buckets                  per-bucket diff breakdown from the mapper;
     *                                 always present (empty buckets on an
     *                                 empty-diff short-circuit)
     * @param runAll                   whether the caller should run the full suite
     * @param skipped                  whether the caller should run no tests at all
     *                                 (v2 — previously impossible to express)
     * @param situation                which decision branch the engine landed on
     * @param action                   the resolved {@link Action} for
     *                                 {@link #situation}; one of SELECTED,
     *                                 FULL_SUITE, SKIPPED
     * @param escalationReason         legacy reason code kept in sync with
     *                                 {@link #situation}/{@link #action} for
     *                                 v1 callers; see {@link EscalationReason}
     */
    public record AffectedTestsResult(
            Set<String> testClassFqns,
            Map<String, Path> testFqnToPath,
            Set<String> changedFiles,
            Set<String> changedProductionClasses,
            Set<String> changedTestClasses,
            Buckets buckets,
            boolean runAll,
            boolean skipped,
            Situation situation,
            Action action,
            EscalationReason escalationReason
    ) {}

    /**
     * Runs the full pipeline: detect changes, map to classes, pick a
     * situation, resolve it to an action, and (where relevant) discover
     * the affected test set.
     */
    public AffectedTestsResult run() {
        log.info("=== Affected Tests Analysis ===");
        log.info("Project dir: {}", projectDir);
        log.info("Base ref: {}", config.baseRef());
        log.info("Strategies: {}", config.strategies());
        log.info("Transitive depth: {}", config.transitiveDepth());
        log.info("Effective mode: {}", config.effectiveMode());

        GitChangeDetector changeDetector = new GitChangeDetector(projectDir, config);
        Set<String> changedFiles = changeDetector.detectChangedFiles();

        if (changedFiles.isEmpty()) {
            log.info("No changed files detected.");
            return resolveAmbiguous(Situation.EMPTY_DIFF, changedFiles,
                    Set.of(), Set.of(), Buckets.empty());
        }

        PathToClassMapper mapper = new PathToClassMapper(config);
        MappingResult mapping = mapper.mapChangedFiles(changedFiles);

        Buckets buckets = new Buckets(
                Set.copyOf(mapping.ignoredFiles()),
                Set.copyOf(mapping.outOfScopeFiles()),
                Set.copyOf(mapping.changedProductionFiles()),
                Set.copyOf(mapping.changedTestFiles()),
                Set.copyOf(mapping.unmappedChangedFiles())
        );

        int diffSize = changedFiles.size();
        int ignored = mapping.ignoredFiles().size();
        int outOfScope = mapping.outOfScopeFiles().size();

        // Priority matches the Situation javadoc. Remember that the mapper
        // already routes each file into at most one bucket, so the "all X"
        // branches and the "any unmapped" branch are mutually exclusive by
        // construction. The order here just picks the situation name that
        // matches the diff's shape.
        if (ignored == diffSize) {
            log.info("All {} changed file(s) matched ignorePaths.", diffSize);
            return resolveAmbiguous(Situation.ALL_FILES_IGNORED, changedFiles,
                    mapping.productionClasses(), mapping.testClasses(), buckets);
        }
        if (outOfScope == diffSize) {
            log.info("All {} changed file(s) fell under out-of-scope dirs.", diffSize);
            return resolveAmbiguous(Situation.ALL_FILES_OUT_OF_SCOPE, changedFiles,
                    mapping.productionClasses(), mapping.testClasses(), buckets);
        }
        if (!mapping.unmappedChangedFiles().isEmpty()) {
            Action action = config.actionFor(Situation.UNMAPPED_FILE);
            // Filenames flow from the untrusted MR tree straight into the
            // logger here — sanitise or an attacker committing a file
            // with `\n` + fake plugin status line can forge CI audit
            // output. See LogSanitizer for the full rationale.
            List<String> examples = mapping.unmappedChangedFiles().stream()
                    .limit(5)
                    .map(LogSanitizer::sanitize)
                    .toList();
            log.warn("Non-Java / unmapped change detected ({} file(s)). Action: {}. Examples: {}",
                    mapping.unmappedChangedFiles().size(), action, examples);
            // SELECTED here means "ignore the unmapped file, proceed with
            // discovery on whatever production/test files were in the
            // diff" — this is the behaviour legacy
            // {@code runAllOnNonJavaChange=false} callers expect, and the
            // only way to express it in the v2 model without inventing a
            // second fallthrough enum value.
            if (action != Action.SELECTED) {
                return emptyResult(Situation.UNMAPPED_FILE, action, changedFiles,
                        mapping.productionClasses(), mapping.testClasses(), buckets);
            }
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

        if (allTestsToRun.isEmpty()) {
            Action action = config.actionFor(Situation.DISCOVERY_EMPTY);
            log.info("Discovery produced no affected tests. Action: {}.", action);
            return emptyResult(Situation.DISCOVERY_EMPTY, action, changedFiles,
                    mapping.productionClasses(), mapping.testClasses(), buckets);
        }

        log.info("=== Result: {} affected test classes ===", allTestsToRun.size());
        // FQNs are derived from diff filenames by the discovery strategies
        // and have not yet been through the AffectedTestTask#isValidFqn
        // gate; sanitise here so an attacker-planted filename like
        // `Test\nAffected Tests: SELECTED.java` can't forge a fake
        // log line at INFO/--info level.
        allTestsToRun.forEach(t -> log.info("  -> {}", LogSanitizer.sanitize(t)));

        return new AffectedTestsResult(
                allTestsToRun,
                Collections.unmodifiableMap(fqnToPath),
                changedFiles,
                mapping.productionClasses(),
                mapping.testClasses(),
                buckets,
                false,
                false,
                Situation.DISCOVERY_SUCCESS,
                Action.SELECTED,
                EscalationReason.NONE
        );
    }

    /**
     * Resolves a situation that short-circuits discovery into an empty
     * result with the appropriate {@code runAll}/{@code skipped} flags.
     * Used by every branch except {@link Situation#DISCOVERY_SUCCESS} and
     * {@link Situation#UNMAPPED_FILE}-with-{@link Action#SELECTED}.
     *
     * <p>When {@link Situation#UNMAPPED_FILE} resolves to
     * {@link Action#SELECTED} the engine deliberately does <em>not</em>
     * route through here — it continues into discovery so the diff's
     * Java files still get analysed, matching the pre-v2 behaviour of
     * {@code runAllOnNonJavaChange=false}.
     */
    private AffectedTestsResult resolveAmbiguous(Situation situation,
                                                 Set<String> changedFiles,
                                                 Set<String> changedProduction,
                                                 Set<String> changedTests,
                                                 Buckets buckets) {
        Action action = config.actionFor(situation);
        if (action == Action.SELECTED) {
            // The only meaningful way for SELECTED to reach here is
            // someone explicitly configured {@code onEmptyDiff(SELECTED)}
            // or similar. In every "ambiguous" branch there is no
            // selection to run by definition, so SELECTED collapses to
            // "do nothing, don't claim a full run".
            log.info("Situation {} → SELECTED with empty selection; running no tests.", situation);
        }
        return emptyResult(situation, action, changedFiles, changedProduction, changedTests, buckets);
    }

    private AffectedTestsResult emptyResult(Situation situation, Action action,
                                            Set<String> changedFiles,
                                            Set<String> changedProduction,
                                            Set<String> changedTests,
                                            Buckets buckets) {
        boolean runAll = action == Action.FULL_SUITE;
        // SELECTED on an ambiguous branch is treated as "skipped" for the
        // Gradle task's wiring — there is nothing to dispatch either way —
        // but the {@link Action} field on the result still reads SELECTED
        // so {@code --explain} can report honestly.
        boolean skipped = action == Action.SKIPPED
                || (action == Action.SELECTED && situation != Situation.DISCOVERY_SUCCESS);
        return new AffectedTestsResult(
                Set.of(),
                Map.of(),
                changedFiles,
                changedProduction,
                changedTests,
                buckets,
                runAll,
                skipped,
                situation,
                action,
                legacyReason(situation, action)
        );
    }

    private static EscalationReason legacyReason(Situation situation, Action action) {
        if (action != Action.FULL_SUITE) return EscalationReason.NONE;
        return switch (situation) {
            case EMPTY_DIFF -> EscalationReason.RUN_ALL_ON_EMPTY_CHANGESET;
            case DISCOVERY_EMPTY -> EscalationReason.RUN_ALL_IF_NO_MATCHES;
            case UNMAPPED_FILE -> EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE;
            case ALL_FILES_IGNORED -> EscalationReason.RUN_ALL_ON_ALL_FILES_IGNORED;
            case ALL_FILES_OUT_OF_SCOPE -> EscalationReason.RUN_ALL_ON_ALL_FILES_OUT_OF_SCOPE;
            case DISCOVERY_SUCCESS -> EscalationReason.NONE;
        };
    }
}
