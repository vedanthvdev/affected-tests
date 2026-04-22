package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine;
import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.AffectedTestsEngine.Buckets;
import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.ActionSource;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.config.Situation;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gradle task that detects affected tests and executes them.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Detects git changes against the configured base ref</li>
 *   <li>Maps changed files to production/test classes</li>
 *   <li>Discovers which test classes are affected</li>
 *   <li>Groups FQNs by owning subproject and runs {@code :<module>:test --tests <fqn>}
 *       once per module so that {@code --tests} filters don't cross module boundaries</li>
 * </ol>
 *
 * <p>Not compatible with Configuration Cache: reads live git state and scans
 * the file system at execution time. The task is also deliberately marked as
 * never up-to-date so git changes always trigger a re-run.
 */
public abstract class AffectedTestTask extends DefaultTask {

    public AffectedTestTask() {
        notCompatibleWithConfigurationCache("Reads live git state and scans the file system at execution time");
        // The task has no declared outputs and its result depends on live git state,
        // so up-to-date checks must always miss.
        getOutputs().upToDateWhen(t -> false);
    }

    /**
     * Git base ref to diff against.
     * Default: {@code "origin/master"}. Override via {@code -PaffectedTestsBaseRef=...}.
     *
     * @return the base ref property
     */
    @Input
    public abstract Property<String> getBaseRef();

    /**
     * Whether to include uncommitted (unstaged) changes in the diff.
     * Default: {@code true}.
     *
     * @return the include uncommitted property
     */
    @Input
    public abstract Property<Boolean> getIncludeUncommitted();

    /**
     * Whether to include staged (added to index) changes in the diff.
     * Default: {@code true}.
     *
     * @return the include staged property
     */
    @Input
    public abstract Property<Boolean> getIncludeStaged();

    /**
     * Whether to run the full test suite when no affected tests are found.
     * v2 back-compat — translated into {@link Situation#EMPTY_DIFF},
     * {@link Situation#ALL_FILES_IGNORED},
     * {@link Situation#ALL_FILES_OUT_OF_SCOPE}, and
     * {@link Situation#DISCOVERY_EMPTY} actions by the core config
     * builder when set. Unset means "let the v2 resolver pick defaults".
     * Default: unset (matching pre-v2 {@code false} once translated).
     *
     * <p>Marked {@link org.gradle.api.tasks.Optional @Optional} because
     * the extension no longer installs a convention — the Gradle task
     * must be free to leave the property unset when the user has not
     * overridden the legacy boolean.
     *
     * @return the run-all-if-no-matches property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<Boolean> getRunAllIfNoMatches();

    /**
     * Whether to force a full test run when the change set contains any
     * file that cannot be resolved to a Java class under the configured
     * source/test directories. v2 back-compat — translates into
     * {@link Situation#UNMAPPED_FILE}'s action.
     *
     * <p>Marked {@link org.gradle.api.tasks.Optional @Optional} because
     * the extension no longer installs a convention; leaving it unset
     * is what lets the v2 resolver reach its own defaults.
     *
     * @return the run-all-on-non-java-change property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<Boolean> getRunAllOnNonJavaChange();

    /**
     * Discovery strategies to use for finding affected tests.
     * Valid values: {@code "naming"}, {@code "usage"}, {@code "impl"}, {@code "transitive"}.
     * Default: all four.
     *
     * @return the strategies list property
     */
    @Input
    public abstract ListProperty<String> getStrategies();

    /**
     * How many levels of transitive dependencies to follow when the
     * {@code transitive} strategy is enabled.
     * Range: 0 (disabled) to 5. Default: {@code 2}.
     *
     * @return the transitive depth property
     */
    @Input
    public abstract Property<Integer> getTransitiveDepth();

    /**
     * Suffixes used by the naming strategy to find test classes.
     * Default: {@code ["Test", "IT", "ITTest", "IntegrationTest"]}.
     *
     * @return the test suffixes list property
     */
    @Input
    public abstract ListProperty<String> getTestSuffixes();

    /**
     * Production source directories relative to each module root.
     * Default: {@code ["src/main/java"]}.
     *
     * @return the source dirs list property
     */
    @Input
    public abstract ListProperty<String> getSourceDirs();

    /**
     * Test source directories relative to each module root.
     * Default: {@code ["src/test/java"]}.
     *
     * @return the test dirs list property
     */
    @Input
    public abstract ListProperty<String> getTestDirs();

    /**
     * v2 back-compat alias for {@link #getIgnorePaths()}. When neither
     * is set, the core config's default ignore-path list applies.
     *
     * @return the exclude paths list property
     * @deprecated prefer {@link #getIgnorePaths()}.
     */
    @Input
    @org.gradle.api.tasks.Optional
    @Deprecated
    public abstract ListProperty<String> getExcludePaths();

    /**
     * Glob patterns for files that must never influence test selection.
     * Optional — when unset, the core config's default list applies.
     *
     * @return the ignore paths list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getIgnorePaths();

    /**
     * Test source directories (e.g. {@code "api-test/src/test/java"})
     * whose contents the plugin must not dispatch via the
     * {@code affectedTest} task.
     *
     * @return the out-of-scope test dirs list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getOutOfScopeTestDirs();

    /**
     * Production source directories the plugin must treat as
     * out-of-scope.
     *
     * @return the out-of-scope source dirs list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getOutOfScopeSourceDirs();

    /**
     * Whether to include tests for implementations of changed interfaces/base classes.
     * Default: {@code true}.
     *
     * @return the include implementation tests property
     */
    @Input
    public abstract Property<Boolean> getIncludeImplementationTests();

    /**
     * Suffixes/prefixes for finding implementation classes.
     * Default: {@code ["Impl", "Default"]}.
     *
     * @return the implementation naming list property
     */
    @Input
    public abstract ListProperty<String> getImplementationNaming();

    /**
     * Execution profile name — one of {@code "auto"}, {@code "local"},
     * {@code "ci"}, {@code "strict"}. Unset leaves defaults in
     * pre-v2 mode.
     *
     * @return the mode property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getMode();

    /** @return the on-empty-diff situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnEmptyDiff();

    /** @return the on-all-files-ignored situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnAllFilesIgnored();

    /** @return the on-all-files-out-of-scope situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnAllFilesOutOfScope();

    /** @return the on-unmapped-file situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnUnmappedFile();

    /** @return the on-discovery-empty situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnDiscoveryEmpty();

    /**
     * When {@code true}, the task prints the full decision trace
     * (buckets, situation, action, action source) and exits without
     * running any tests. Used by operators to answer "why did this MR
     * land on that outcome?" without having to enable debug logs.
     *
     * <p>Marked {@link Internal @Internal} rather than {@link Input @Input}
     * because flipping the explain flag must not invalidate a cached
     * execution — it changes only the lifecycle logging, never the set
     * of tests Gradle would actually run.
     *
     * @return the explain flag property
     */
    @Internal
    @Option(option = "explain",
            description = "Print the decision trace (buckets, situation, action, source) "
                    + "and exit without running tests.")
    public abstract Property<Boolean> getExplain();

    /**
     * Map of subproject directory (relative to the root project, empty string
     * for the root project itself) to the Gradle path of that subproject
     * (e.g. {@code ":services:payment"}). Populated automatically by the plugin
     * and used to group affected test FQNs by their owning module.
     *
     * @return the subproject dirs map property
     */
    @Internal
    public abstract MapProperty<String, String> getSubprojectPaths();

    /**
     * The root project directory (resolved at configuration time).
     *
     * @return the root dir property
     */
    @Internal
    public abstract DirectoryProperty getRootDir();

    /**
     * Injected by Gradle for executing the test subprocess.
     *
     * @return the exec operations service
     */
    @Inject
    protected abstract ExecOperations getExecOperations();

    /**
     * Detects affected tests and executes them via a Gradle subprocess.
     */
    @TaskAction
    public void runAffectedTests() {
        Path projectDir = getRootDir().get().getAsFile().toPath();

        AffectedTestsConfig config = buildConfig();

        AffectedTestsEngine engine = new AffectedTestsEngine(config, projectDir);
        AffectedTestsResult result = engine.run();

        boolean explain = getExplain().getOrElse(false);
        if (explain) {
            // {@code --explain} is a diagnostic mode: we print the trace and
            // return without touching the executor so operators can re-run
            // as many times as they need without waiting for the suite.
            // Every line goes through {@code lifecycle()} so the trace is
            // visible by default (no {@code --info} gymnastics).
            for (String line : renderExplainTrace(config, result)) {
                getLogger().lifecycle(line);
            }
            return;
        }

        // The summary line is the single place the task names the
        // outcome (SELECTED / FULL_SUITE / SKIPPED), the situation that
        // produced it, and the reason phrase. Downstream lines must not
        // repeat any of those fields or CI logs drift into contradictory
        // duplicate phrasing. We hand the logger a format string + args
        // pair instead of a pre-formatted string so any future phrase
        // containing `{` or `}` characters (Liquibase file names,
        // JSONpath expressions) renders literally rather than silently
        // disappearing into the placeholder parser.
        LogLine summary = renderSummary(result);
        getLogger().lifecycle(summary.format(), summary.args());

        // Skipped results return here without touching the executor.
        // Intentionally no follow-up line — the summary already carried
        // the {@code SKIPPED (SITUATION)} prefix and the reason phrase,
        // so a separate "Skipping test execution (…)" log would only
        // duplicate what the operator already read.
        if (result.skipped()) {
            return;
        }

        if (result.testClassFqns().isEmpty() && !result.runAll()) {
            // Defensive fallthrough: the engine should have already
            // routed this into {@code SKIPPED} via
            // {@link AffectedTestsEngine.Situation#DISCOVERY_EMPTY},
            // but we keep the guard so a future regression surfaces as
            // a log line instead of a silent no-op.
            return;
        }

        executeTests(projectDir,
                result.testClassFqns(),
                result.testFqnToPath(),
                result.runAll());
    }

    /**
     * Assembles the immutable core config from the task's Gradle
     * properties. Legacy booleans and the new situation/mode knobs are
     * all optional at this layer; the core builder handles precedence
     * (explicit > legacy-boolean > mode > pre-v2 default).
     */
    private AffectedTestsConfig buildConfig() {
        AffectedTestsConfig.Builder builder = AffectedTestsConfig.builder()
                .baseRef(getBaseRef().get())
                .includeUncommitted(getIncludeUncommitted().get())
                .includeStaged(getIncludeStaged().get())
                .strategies(new LinkedHashSet<>(getStrategies().get()))
                .transitiveDepth(getTransitiveDepth().get())
                .testSuffixes(getTestSuffixes().get())
                .sourceDirs(getSourceDirs().get())
                .testDirs(getTestDirs().get())
                .includeImplementationTests(getIncludeImplementationTests().get())
                .implementationNaming(getImplementationNaming().get());

        if (getRunAllIfNoMatches().isPresent()) {
            builder.runAllIfNoMatches(getRunAllIfNoMatches().get());
        }
        if (getRunAllOnNonJavaChange().isPresent()) {
            builder.runAllOnNonJavaChange(getRunAllOnNonJavaChange().get());
        }
        if (getIgnorePaths().isPresent() && !getIgnorePaths().get().isEmpty()) {
            builder.ignorePaths(getIgnorePaths().get());
        } else if (getExcludePaths().isPresent() && !getExcludePaths().get().isEmpty()) {
            builder.excludePaths(getExcludePaths().get());
        }
        if (getOutOfScopeTestDirs().isPresent() && !getOutOfScopeTestDirs().get().isEmpty()) {
            builder.outOfScopeTestDirs(getOutOfScopeTestDirs().get());
        }
        if (getOutOfScopeSourceDirs().isPresent() && !getOutOfScopeSourceDirs().get().isEmpty()) {
            builder.outOfScopeSourceDirs(getOutOfScopeSourceDirs().get());
        }
        if (getMode().isPresent()) {
            builder.mode(parseMode(getMode().get()));
        }
        if (getOnEmptyDiff().isPresent()) {
            builder.onEmptyDiff(parseAction(getOnEmptyDiff().get(), "onEmptyDiff"));
        }
        if (getOnAllFilesIgnored().isPresent()) {
            builder.onAllFilesIgnored(parseAction(getOnAllFilesIgnored().get(), "onAllFilesIgnored"));
        }
        if (getOnAllFilesOutOfScope().isPresent()) {
            builder.onAllFilesOutOfScope(parseAction(getOnAllFilesOutOfScope().get(), "onAllFilesOutOfScope"));
        }
        if (getOnUnmappedFile().isPresent()) {
            builder.onUnmappedFile(parseAction(getOnUnmappedFile().get(), "onUnmappedFile"));
        }
        if (getOnDiscoveryEmpty().isPresent()) {
            builder.onDiscoveryEmpty(parseAction(getOnDiscoveryEmpty().get(), "onDiscoveryEmpty"));
        }
        return builder.build();
    }

    private static Mode parseMode(String raw) {
        try {
            return Mode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new GradleException("Unknown affectedTests.mode '" + raw
                    + "'. Expected one of: auto, local, ci, strict.", e);
        }
    }

    private static Action parseAction(String raw, String property) {
        try {
            return Action.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new GradleException("Unknown " + property + " action '" + raw
                    + "'. Expected one of: selected, full_suite, skipped.", e);
        }
    }

    private void executeTests(Path projectDir,
                              Set<String> testFqns,
                              Map<String, Path> fqnToPath,
                              boolean runAll) {
        String gradleCommand = resolveGradleCommand(projectDir);

        List<String> args = new ArrayList<>();
        args.add(gradleCommand);

        // The previous `runAll || testFqns.isEmpty()` disjunct was stale:
        // the guard in runAffectedTests above already returned when the set
        // was empty and runAll was false, so by the time we reach this branch
        // testFqns.isEmpty() can only be true under runAll.
        if (runAll) {
            // Reason already printed by renderSummary in the caller; keeping
            // it out of this line prevents the "log prints the same phrase
            // twice" grep collision on the "Running ALL tests" marker.
            getLogger().lifecycle("Running ALL tests.");
            args.add("test");
        } else {
            // Group FQNs by owning subproject and emit ":moduleA:test --tests x
            // :moduleB:test --tests y" so Gradle's --tests filters don't spill
            // across modules (where they'd fail any subproject that doesn't
            // happen to contain the FQN).
            Map<String, List<String>> grouped = groupFqnsByModule(projectDir, testFqns, fqnToPath);

            getLogger().lifecycle("Running {} affected test classes across {} module(s):",
                    testFqns.size(), grouped.size());

            for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                String modulePath = entry.getKey();
                List<String> fqnsForModule = entry.getValue();

                String taskPath = modulePath.isEmpty() ? "test" : modulePath + ":test";
                args.add(taskPath);
                for (String fqn : fqnsForModule) {
                    args.add("--tests");
                    args.add(fqn);
                    getLogger().lifecycle("  {} -> {}", taskPath, fqn);
                }
            }
        }

        // Skip recompiling production code — testClasses (declared as a task
        // dependency by the plugin) has already been built as part of this
        // outer Gradle invocation, so the class files the nested process needs
        // are on disk. We deliberately do NOT pass -x compileTestJava: on a
        // clean checkout the test classes wouldn't exist yet, and Gradle would
        // fail with "No tests found". Letting compileTestJava run is cheap and
        // correct on all checkouts.
        args.add("-x");
        args.add("compileJava");

        ExecResult execResult = getExecOperations().exec(spec -> {
            spec.commandLine(args);
            spec.workingDir(projectDir.toFile());
            spec.setIgnoreExitValue(true);
        });

        if (execResult.getExitValue() != 0) {
            throw new GradleException("Test execution failed with exit code " + execResult.getExitValue());
        }
    }

    /**
     * Groups discovered test FQNs by the Gradle path of the subproject that
     * owns each test file. Tests under the root project fall under the
     * empty-string key and get dispatched to the root {@code test} task.
     *
     * <p>If no matching subproject is found for an FQN (e.g. the project
     * structure changed since configuration time), the FQN is routed to the
     * root project as a best-effort fallback.
     */
    private Map<String, List<String>> groupFqnsByModule(Path projectDir,
                                                       Set<String> testFqns,
                                                       Map<String, Path> fqnToPath) {
        Map<String, String> subprojectPaths = getSubprojectPaths().getOrElse(Map.of());

        // Sort entries by descending dir-length so that deeper subprojects
        // (e.g. "services/payment") win over their parents ("services").
        List<Map.Entry<String, String>> orderedEntries = new ArrayList<>(subprojectPaths.entrySet());
        orderedEntries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String fqn : testFqns) {
            Path file = fqnToPath.get(fqn);
            String moduleGradlePath = resolveOwningModule(projectDir, file, orderedEntries);
            grouped.computeIfAbsent(moduleGradlePath, k -> new ArrayList<>()).add(fqn);
        }
        return grouped;
    }

    private String resolveOwningModule(Path projectDir, Path file,
                                       List<Map.Entry<String, String>> orderedEntries) {
        if (file == null) {
            return "";
        }
        Path relative;
        try {
            relative = projectDir.relativize(file.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            return "";
        }
        String normalized = relative.toString().replace(File.separatorChar, '/');
        for (Map.Entry<String, String> entry : orderedEntries) {
            String dir = entry.getKey();
            if (dir.isEmpty()) continue;
            String prefix = dir.endsWith("/") ? dir : dir + "/";
            if (normalized.startsWith(prefix)) {
                return entry.getValue();
            }
        }
        return "";
    }

    /**
     * Resolves the Gradle command to use. Prefers the wrapper in the project directory;
     * falls back to the bare {@code "gradle"} command name so the OS PATH is used.
     */
    private String resolveGradleCommand(Path projectDir) {
        String wrapperName = isWindows() ? "gradlew.bat" : "gradlew";
        File gradlew = projectDir.resolve(wrapperName).toFile();
        if (gradlew.exists() && gradlew.canExecute()) {
            return gradlew.getAbsolutePath();
        }
        // The wrapper is the contract we expect; falling back to a system-wide
        // gradle is a last resort. Warn loudly so broken checkouts don't pass
        // silently in CI.
        getLogger().warn("Gradle wrapper not found at {}/{}; falling back to '{}' from PATH. "
                        + "This usually indicates a broken or incomplete checkout.",
                projectDir, wrapperName, isWindows() ? "gradle.bat" : "gradle");
        return isWindows() ? "gradle.bat" : "gradle";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * A lifecycle log line expressed as an SLF4J-style format string plus
     * its positional arguments, so the caller can hand both to
     * {@link org.gradle.api.logging.Logger#lifecycle(String, Object...)}
     * and benefit from Gradle's placeholder parser — keeping this task
     * consistent with the other {@code lifecycle(...)} call-sites in the
     * file, and preventing any future phrase containing literal {@code {}}
     * from being swallowed by that parser.
     *
     * <p>Package-private so the log-shape test can assert on either the
     * format string or the rendered output without pulling in Gradle's
     * live logger.
     */
    record LogLine(String format, Object[] args) {
        LogLine {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(args, "args");
            // Defensive copy — otherwise a caller that mutates the array
            // after construction would change the rendered log line.
            // Cheap (always small), and stops the "Object[] component in
            // a record is secretly shared state" class of bugs dead.
            args = args.clone();
        }

        @Override
        public Object[] args() {
            return args.clone();
        }
    }

    /**
     * Renders an {@link EscalationReason} as a short human-readable phrase
     * suitable for a lifecycle log line. Package-private so the log-shape
     * test can pin the exact wording; kept in one place so the summary and
     * any downstream "Running ALL tests" lines cannot drift into
     * contradictory phrasing.
     *
     * <p>{@link EscalationReason#NONE} is rejected: this helper is called
     * only on {@code runAll} results, and a {@code runAll=true + NONE}
     * combination is an engine bug rather than a log-formatting concern.
     * Throwing here ensures such a drift is loud (build fails) instead of
     * silently surfacing a placeholder phrase to CI.
     */
    static String describeEscalation(EscalationReason reason) {
        Objects.requireNonNull(reason, "reason");
        // Phrases deliberately keep the legacy flag names ("runAllIfNoMatches=true",
        // "runAllOnNonJavaChange=true") alongside the new situation-based name so
        // existing CI greps stay matched. Removing either side would require a
        // coordinated pipeline migration we do not want to force in Phase 1.
        return switch (reason) {
            case RUN_ALL_ON_NON_JAVA_CHANGE ->
                    "runAllOnNonJavaChange=true / onUnmappedFile=FULL_SUITE — non-Java or unmapped file in diff";
            case RUN_ALL_ON_EMPTY_CHANGESET ->
                    "runAllIfNoMatches=true / onEmptyDiff=FULL_SUITE — no changed files detected";
            case RUN_ALL_IF_NO_MATCHES ->
                    "runAllIfNoMatches=true / onDiscoveryEmpty=FULL_SUITE — no affected tests discovered";
            case RUN_ALL_ON_ALL_FILES_IGNORED ->
                    "onAllFilesIgnored=FULL_SUITE — every changed file matched ignorePaths";
            case RUN_ALL_ON_ALL_FILES_OUT_OF_SCOPE ->
                    "onAllFilesOutOfScope=FULL_SUITE — every changed file sat under out-of-scope dirs";
            case NONE -> throw new IllegalStateException(
                    "describeEscalation must not be called for EscalationReason.NONE; "
                            + "the engine should only produce NONE on non-runAll results");
        };
    }

    /** Cap on files listed per bucket in the {@code --explain} trace. */
    static final int EXPLAIN_SAMPLE_LIMIT = 10;

    /**
     * Renders the human-readable decision trace produced by
     * {@code affectedTest --explain}. Returned as a list of lines so the
     * caller can hand each line to {@link org.gradle.api.logging.Logger#lifecycle(String)}
     * (no format placeholders — the content is pre-rendered) and so tests
     * can pin the exact shape without the live logger.
     *
     * <p>Every section names the source of the decision so an operator
     * can see at a glance whether the action came from an explicit
     * {@code onXxx} setting, a legacy boolean, the mode default table,
     * or the pre-v2 hardcoded baseline.
     *
     * <p>Package-private so {@code AffectedTestTaskExplainFormatTest}
     * can assert the format without spinning up Gradle.
     */
    static List<String> renderExplainTrace(AffectedTestsConfig config, AffectedTestsResult result) {
        List<String> lines = new ArrayList<>();
        lines.add("=== Affected Tests — decision trace (--explain) ===");
        lines.add("Base ref:        " + config.baseRef());
        String configuredMode = config.mode() == null ? "unset" : config.mode().name();
        String effectiveMode = config.effectiveMode() == null
                ? "n/a (pre-v2 defaults)"
                : config.effectiveMode().name();
        lines.add("Mode:            " + configuredMode + " (effective: " + effectiveMode + ")");
        lines.add("Changed files:   " + result.changedFiles().size());

        Buckets buckets = result.buckets();
        lines.add("Buckets:");
        lines.add("  ignored         " + buckets.ignoredFiles().size());
        lines.add("  out-of-scope    " + buckets.outOfScopeFiles().size());
        lines.add("  production .java " + buckets.productionFiles().size());
        lines.add("  test .java      " + buckets.testFiles().size());
        lines.add("  unmapped        " + buckets.unmappedFiles().size());

        appendSample(lines, "ignored",      buckets.ignoredFiles());
        appendSample(lines, "out-of-scope", buckets.outOfScopeFiles());
        appendSample(lines, "production",   buckets.productionFiles());
        appendSample(lines, "test",         buckets.testFiles());
        appendSample(lines, "unmapped",     buckets.unmappedFiles());

        ActionSource source = config.actionSourceFor(result.situation());
        lines.add("Situation:       " + result.situation().name());
        lines.add("Action:          " + result.action().name()
                + " (source: " + describeSource(source) + ")");

        String outcome;
        if (result.runAll()) {
            outcome = "FULL_SUITE — " + describeEscalation(result.escalationReason());
        } else if (result.skipped()) {
            outcome = "SKIPPED — no tests will run";
        } else if (result.action() == Action.SELECTED) {
            outcome = "SELECTED — " + result.testClassFqns().size()
                    + " test class(es) will run";
        } else {
            outcome = result.action().name();
        }
        lines.add("Outcome:         " + outcome);

        // The full action matrix is cheap to print (five rows) and
        // invaluable for debugging "why did my explicit setting not
        // win?" — so we always include it, not only on ambiguous
        // branches. Rows are rendered in a stable order matching the
        // Situation javadoc's evaluation order so greps/diffs stay
        // stable across runs.
        lines.add("Action matrix (situation → action [source]):");
        for (Situation s : situationOrder()) {
            ActionSource rowSource = config.actionSourceFor(s);
            lines.add(String.format(Locale.ROOT, "  %-24s %s [%s]",
                    s.name(), config.actionFor(s).name(), describeSource(rowSource)));
        }
        lines.add("=== end --explain ===");
        return lines;
    }

    private static void appendSample(List<String> lines, String label, Set<String> files) {
        if (files.isEmpty()) {
            return;
        }
        String preview = files.stream()
                .sorted()
                .limit(EXPLAIN_SAMPLE_LIMIT)
                .collect(Collectors.joining(", "));
        if (files.size() > EXPLAIN_SAMPLE_LIMIT) {
            preview = preview + ", … (+" + (files.size() - EXPLAIN_SAMPLE_LIMIT) + " more)";
        }
        lines.add("  " + label + " sample: " + preview);
    }

    /**
     * Situation order used by the explain trace and anywhere else we
     * render a full per-situation matrix. Matches the evaluation order
     * documented on {@link Situation} so operators can read the trace
     * top-to-bottom and mentally simulate the engine without cross-
     * referencing another doc.
     */
    private static List<Situation> situationOrder() {
        return List.of(
                Situation.EMPTY_DIFF,
                Situation.ALL_FILES_IGNORED,
                Situation.ALL_FILES_OUT_OF_SCOPE,
                Situation.UNMAPPED_FILE,
                Situation.DISCOVERY_EMPTY,
                Situation.DISCOVERY_SUCCESS
        );
    }

    private static String describeSource(ActionSource source) {
        return switch (source) {
            case EXPLICIT          -> "explicit onXxx setting";
            case LEGACY_BOOLEAN    -> "legacy boolean (runAllIfNoMatches / runAllOnNonJavaChange)";
            case MODE_DEFAULT      -> "mode default";
            case HARDCODED_DEFAULT -> "pre-v2 hardcoded default";
        };
    }

    /**
     * Builds the single summary line printed to Gradle's lifecycle log
     * for every {@code affectedTest} run. Every branch names the
     * outcome ({@link Action#SELECTED}, {@link Action#FULL_SUITE},
     * {@link Action#SKIPPED}) and the {@link Situation} that produced
     * it, followed by the file count and branch-specific details.
     *
     * <p>Shape:
     * <pre>
     * Affected Tests: SELECTED (DISCOVERY_SUCCESS) — N changed file(s), P production class(es), T test class(es) affected
     * Affected Tests: FULL_SUITE (UNMAPPED_FILE) — N changed file(s); running full suite (reason)
     * Affected Tests: SKIPPED (ALL_FILES_IGNORED) — N changed file(s); every changed file matched ignorePaths
     * </pre>
     *
     * <p>Pluralisation is deliberately fixed to {@code file(s)} and
     * {@code class(es)} across every branch so CI greps stay stable
     * across runs with different selection sizes. Every phrase that
     * pre-v2 or Phase-1 CI pipelines grep for ({@code "running full
     * suite"}, {@code "runAllIfNoMatches=true"}, {@code "no affected
     * tests discovered"}, etc.) survives verbatim — this change adds
     * the outcome/situation prefix without removing any existing
     * vocabulary.
     */
    static LogLine renderSummary(AffectedTestsResult result) {
        String prefix = "Affected Tests: " + result.action().name()
                + " (" + result.situation().name() + ") — ";
        if (result.runAll()) {
            // runAll branch keeps the pre-v2 "running full suite (reason)"
            // wording verbatim so existing CI greps for that substring
            // continue to match every FULL_SUITE run. Reason phrase is
            // sourced from describeEscalation to avoid duplicating the
            // legacy vocabulary across two files.
            return new LogLine(
                    prefix + "{} changed file(s); running full suite ({}).",
                    new Object[] {
                            result.changedFiles().size(),
                            describeEscalation(result.escalationReason())
                    });
        }
        if (result.skipped()) {
            return new LogLine(
                    prefix + "{} changed file(s); {}.",
                    new Object[] {
                            result.changedFiles().size(),
                            describeSkipReason(result.situation())
                    });
        }
        return new LogLine(
                prefix + "{} changed file(s), {} production class(es), {} test class(es) affected",
                new Object[] {
                        result.changedFiles().size(),
                        result.changedProductionClasses().size(),
                        result.testClassFqns().size()
                });
    }

    /**
     * Renders a {@link Situation} as a short human-readable phrase
     * suitable for the summary line's {@code SKIPPED} branch. Only the
     * five "ambiguous" situations can legitimately resolve to
     * {@link Action#SKIPPED}; {@link Situation#DISCOVERY_SUCCESS} is
     * rejected because the engine never skips when it found tests.
     *
     * <p>Package-private so {@code AffectedTestTaskLogFormatTest} can
     * pin the exact wording — mirrors {@link #describeEscalation} so
     * the two halves of the summary log share one vocabulary.
     */
    static String describeSkipReason(Situation situation) {
        Objects.requireNonNull(situation, "situation");
        return switch (situation) {
            case EMPTY_DIFF ->
                    "no changed files detected";
            case ALL_FILES_IGNORED ->
                    "every changed file matched ignorePaths";
            case ALL_FILES_OUT_OF_SCOPE ->
                    "every changed file sat under out-of-scope dirs";
            case UNMAPPED_FILE ->
                    "onUnmappedFile=SKIPPED — non-Java or unmapped file in diff";
            case DISCOVERY_EMPTY ->
                    "no affected tests discovered";
            case DISCOVERY_SUCCESS -> throw new IllegalStateException(
                    "describeSkipReason must not be called for DISCOVERY_SUCCESS; "
                            + "the engine only produces that situation on a non-empty "
                            + "selection, which is never skipped");
        };
    }
}
