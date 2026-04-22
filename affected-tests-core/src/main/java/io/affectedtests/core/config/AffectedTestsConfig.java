package io.affectedtests.core.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration for affected test detection.
 * Immutable value object — use the {@link Builder} to construct.
 *
 * <h2>v2 situation-based config</h2>
 * The legacy booleans {@code runAllIfNoMatches} and {@code runAllOnNonJavaChange}
 * remain supported for back-compat and map onto the v2
 * {@link Situation} / {@link Action} model via a translation shim in
 * {@link Builder#build()}:
 *
 * <ul>
 *   <li>{@code runAllIfNoMatches=true} &rarr; every "no affected tests" branch
 *       ({@link Situation#EMPTY_DIFF}, {@link Situation#ALL_FILES_IGNORED},
 *       {@link Situation#ALL_FILES_OUT_OF_SCOPE},
 *       {@link Situation#DISCOVERY_EMPTY}) escalates to {@link Action#FULL_SUITE}.</li>
 *   <li>{@code runAllIfNoMatches=false} &rarr; the same four branches resolve
 *       to {@link Action#SKIPPED} (i.e. run no tests), matching the pre-v2
 *       "silent no-op" behaviour when discovery turned up nothing.</li>
 *   <li>{@code runAllOnNonJavaChange=true} &rarr; {@link Situation#UNMAPPED_FILE}
 *       resolves to {@link Action#FULL_SUITE}.</li>
 *   <li>{@code runAllOnNonJavaChange=false} &rarr; {@link Situation#UNMAPPED_FILE}
 *       resolves to {@link Action#SELECTED}, meaning "treat the unmapped file
 *       as if it hadn't been there and continue to discovery". That matches
 *       the pre-v2 "silent skip on YAML" behaviour exactly.</li>
 *   <li>An explicit {@code onXxx()} call always wins over the legacy boolean
 *       translation, and the legacy boolean translation always wins over the
 *       {@link Mode}-based defaults. Users upgrading can therefore adopt the
 *       new DSL branch-by-branch without having to delete their legacy
 *       config in a single step.</li>
 * </ul>
 */
public final class AffectedTestsConfig {

    /** The built-in discovery strategy names. */
    public static final String STRATEGY_NAMING = "naming";
    public static final String STRATEGY_USAGE = "usage";
    public static final String STRATEGY_IMPL = "impl";
    public static final String STRATEGY_TRANSITIVE = "transitive";

    private final String baseRef;
    private final boolean includeUncommitted;
    private final boolean includeStaged;
    private final boolean runAllIfNoMatches;
    private final boolean runAllOnNonJavaChange;
    private final Set<String> strategies;
    private final int transitiveDepth;
    private final List<String> testSuffixes;
    private final List<String> sourceDirs;
    private final List<String> testDirs;
    private final List<String> ignorePaths;
    private final List<String> outOfScopeTestDirs;
    private final List<String> outOfScopeSourceDirs;
    private final boolean includeImplementationTests;
    private final List<String> implementationNaming;
    private final Mode mode;
    private final Mode effectiveMode;
    private final Map<Situation, Action> situationActions;
    private final Map<Situation, ActionSource> situationActionSources;

    private AffectedTestsConfig(Builder builder) {
        this.baseRef = builder.baseRef;
        this.includeUncommitted = builder.includeUncommitted;
        this.includeStaged = builder.includeStaged;
        this.strategies = Set.copyOf(builder.strategies);
        this.transitiveDepth = builder.transitiveDepth;
        this.testSuffixes = List.copyOf(builder.testSuffixes);
        this.sourceDirs = List.copyOf(builder.sourceDirs);
        this.testDirs = List.copyOf(builder.testDirs);
        this.includeImplementationTests = builder.includeImplementationTests;
        this.implementationNaming = List.copyOf(builder.implementationNaming);

        // Resolve the paths-to-ignore list. Precedence matches the doc:
        // explicit ignorePaths() > explicit excludePaths() > builder default.
        List<String> resolvedIgnore;
        if (builder.ignorePaths != null) {
            resolvedIgnore = List.copyOf(builder.ignorePaths);
        } else if (builder.excludePaths != null) {
            resolvedIgnore = List.copyOf(builder.excludePaths);
        } else {
            resolvedIgnore = List.copyOf(Builder.DEFAULT_IGNORE_PATHS);
        }
        this.ignorePaths = resolvedIgnore;

        this.outOfScopeTestDirs = List.copyOf(
                builder.outOfScopeTestDirs != null ? builder.outOfScopeTestDirs : List.of());
        this.outOfScopeSourceDirs = List.copyOf(
                builder.outOfScopeSourceDirs != null ? builder.outOfScopeSourceDirs : List.of());

        this.mode = builder.mode != null ? builder.mode : Mode.AUTO;
        this.effectiveMode = resolveEffectiveMode(builder.mode);

        // Keep the legacy getters literal — they return what the caller set,
        // or the pre-v2 default if the caller never touched them. A
        // "resolved view" that incorporated the new situation actions or
        // mode detection would have been more accurate, but it would also
        // flip the return value of {@code runAllIfNoMatches()} based on
        // whether {@code CI} is set in the environment, which is exactly
        // the kind of test-only determinism the pre-v2 API implicitly
        // guaranteed. Engine code no longer consults these getters —
        // see {@link #actionFor(Situation)}.
        this.runAllIfNoMatches =
                builder.runAllIfNoMatches != null ? builder.runAllIfNoMatches : false;
        this.runAllOnNonJavaChange =
                builder.runAllOnNonJavaChange != null ? builder.runAllOnNonJavaChange : true;

        ResolvedActions resolved = resolveSituationActions(builder, this.effectiveMode);
        this.situationActions = resolved.actions;
        this.situationActionSources = resolved.sources;
    }

    /** Parallel pair returned from the situation-action resolver. */
    private record ResolvedActions(
            Map<Situation, Action> actions,
            Map<Situation, ActionSource> sources) {
    }

    /**
     * Resolves the effective mode for action defaults. When the caller did
     * not set a mode at all (null), we deliberately resolve to {@code null}
     * here — the resolver downstream treats that as "fall through to the
     * pre-v2 legacy-boolean defaults" instead of consulting a mode table.
     * That keeps zero-config callers on the exact pre-v2 behaviour even
     * when {@code $CI} happens to be set, which is what prevents the
     * existing engine tests from going flaky on GitHub Actions runners.
     */
    private static Mode resolveEffectiveMode(Mode configured) {
        if (configured == null) return null;
        if (configured == Mode.AUTO) return Builder.detectMode();
        return configured;
    }

    /**
     * Per-situation actions in strict priority order:
     * <ol>
     *   <li>the caller's explicit {@code on*} action,</li>
     *   <li>the translation of whichever legacy boolean that situation
     *       was historically driven by,</li>
     *   <li>the per-mode default (only when the caller set an explicit
     *       mode — {@code AUTO}/unset falls through to the final branch),</li>
     *   <li>the hard-coded pre-v2 default, kept identical to the legacy
     *       boolean defaults so zero-config callers continue to observe
     *       pre-v2 behaviour exactly.</li>
     * </ol>
     */
    private static ResolvedActions resolveSituationActions(Builder b, Mode effectiveMode) {
        Action legacyNoMatches = (b.runAllIfNoMatches == null)
                ? null
                : (b.runAllIfNoMatches ? Action.FULL_SUITE : Action.SKIPPED);
        // runAllOnNonJavaChange=false historically meant "ignore the unmapped
        // file and proceed with whatever Java the diff touched" — that is
        // {@code SELECTED} in the v2 model, not {@code SKIPPED}. The latter
        // would have regressed every pre-v2 caller that opted out of the
        // safety net expecting discovery to still run.
        Action legacyNonJava = (b.runAllOnNonJavaChange == null)
                ? null
                : (b.runAllOnNonJavaChange ? Action.FULL_SUITE : Action.SELECTED);

        EnumMap<Situation, Action> actions = new EnumMap<>(Situation.class);
        EnumMap<Situation, ActionSource> sources = new EnumMap<>(Situation.class);
        resolveInto(actions, sources, Situation.EMPTY_DIFF,
                b.onEmptyDiff, legacyNoMatches, effectiveMode, Action.SKIPPED);
        resolveInto(actions, sources, Situation.ALL_FILES_IGNORED,
                b.onAllFilesIgnored, legacyNoMatches, effectiveMode, Action.SKIPPED);
        // No legacy boolean maps to ALL_FILES_OUT_OF_SCOPE — the concept
        // did not exist pre-v2, so there is nothing to translate and the
        // hard-coded fallback is {@code SKIPPED}.
        resolveInto(actions, sources, Situation.ALL_FILES_OUT_OF_SCOPE,
                b.onAllFilesOutOfScope, null, effectiveMode, Action.SKIPPED);
        resolveInto(actions, sources, Situation.UNMAPPED_FILE,
                b.onUnmappedFile, legacyNonJava, effectiveMode, Action.FULL_SUITE);
        resolveInto(actions, sources, Situation.DISCOVERY_EMPTY,
                b.onDiscoveryEmpty, legacyNoMatches, effectiveMode, Action.SKIPPED);
        // DISCOVERY_SUCCESS is definitionally SELECTED — there is no
        // other sensible outcome when discovery returns tests. Making
        // it configurable would let users set "discovery ran, found
        // tests, now run nothing", which is never what anyone wants.
        // It is reported as EXPLICIT in the source map because that's
        // the honest answer — the code fixes it rather than choosing a
        // default that someone could override.
        actions.put(Situation.DISCOVERY_SUCCESS, Action.SELECTED);
        sources.put(Situation.DISCOVERY_SUCCESS, ActionSource.EXPLICIT);
        return new ResolvedActions(Map.copyOf(actions), Map.copyOf(sources));
    }

    private static void resolveInto(EnumMap<Situation, Action> actions,
                                    EnumMap<Situation, ActionSource> sources,
                                    Situation s,
                                    Action explicit,
                                    Action legacy,
                                    Mode effectiveMode,
                                    Action preV2Default) {
        if (explicit != null) {
            actions.put(s, explicit);
            sources.put(s, ActionSource.EXPLICIT);
            return;
        }
        if (legacy != null) {
            actions.put(s, legacy);
            sources.put(s, ActionSource.LEGACY_BOOLEAN);
            return;
        }
        if (effectiveMode != null) {
            actions.put(s, defaultFor(s, effectiveMode));
            sources.put(s, ActionSource.MODE_DEFAULT);
            return;
        }
        actions.put(s, preV2Default);
        sources.put(s, ActionSource.HARDCODED_DEFAULT);
    }

    /**
     * Per-mode defaults (see {@link Mode} javadoc for the full table).
     * Only invoked when the caller set an explicit mode — {@code AUTO}
     * without any legacy override falls through to the pre-v2 defaults
     * instead.
     */
    private static Action defaultFor(Situation s, Mode effectiveMode) {
        return switch (effectiveMode) {
            case LOCAL -> switch (s) {
                case EMPTY_DIFF, ALL_FILES_IGNORED, ALL_FILES_OUT_OF_SCOPE, DISCOVERY_EMPTY -> Action.SKIPPED;
                case UNMAPPED_FILE -> Action.FULL_SUITE;
                case DISCOVERY_SUCCESS -> Action.SELECTED;
            };
            case CI -> switch (s) {
                case EMPTY_DIFF, ALL_FILES_IGNORED, ALL_FILES_OUT_OF_SCOPE -> Action.SKIPPED;
                case UNMAPPED_FILE, DISCOVERY_EMPTY -> Action.FULL_SUITE;
                case DISCOVERY_SUCCESS -> Action.SELECTED;
            };
            case STRICT -> switch (s) {
                case ALL_FILES_OUT_OF_SCOPE -> Action.SKIPPED;
                case EMPTY_DIFF, ALL_FILES_IGNORED, UNMAPPED_FILE, DISCOVERY_EMPTY -> Action.FULL_SUITE;
                case DISCOVERY_SUCCESS -> Action.SELECTED;
            };
            case AUTO -> throw new IllegalStateException(
                    "AUTO must be resolved to LOCAL or CI before calling defaultFor");
        };
    }

    public String baseRef() { return baseRef; }
    public boolean includeUncommitted() { return includeUncommitted; }
    public boolean includeStaged() { return includeStaged; }
    public boolean runAllIfNoMatches() { return runAllIfNoMatches; }

    /**
     * Whether to force a full test run whenever the change set contains any
     * file that cannot be resolved to a Java class under the configured
     * {@link #sourceDirs()} or {@link #testDirs()} — for example
     * {@code application.yml}, {@code build.gradle}, a Liquibase changelog,
     * or a logback config. Files matching {@link #ignorePaths()} are
     * treated as an explicit opt-out and do not trigger the escalation.
     *
     * <p>Default: {@code true} — "run more, never run less".
     *
     * @return the run-all-on-non-java-change property
     */
    public boolean runAllOnNonJavaChange() { return runAllOnNonJavaChange; }
    public Set<String> strategies() { return strategies; }
    public int transitiveDepth() { return transitiveDepth; }
    public List<String> testSuffixes() { return testSuffixes; }
    public List<String> sourceDirs() { return sourceDirs; }
    public List<String> testDirs() { return testDirs; }

    /**
     * Glob patterns for files that must not influence test selection at all.
     * A diff consisting entirely of ignored paths routes through
     * {@link Situation#ALL_FILES_IGNORED}.
     *
     * @return the ignore paths list
     */
    public List<String> ignorePaths() { return ignorePaths; }

    /**
     * Back-compat alias for {@link #ignorePaths()}. Returns the same list —
     * v2 collapsed the two legacy names into a single effective list so
     * downstream code never has to consult both.
     *
     * @return the effective ignore paths list (identical to {@link #ignorePaths()})
     * @deprecated use {@link #ignorePaths()} in new code; both return the same value.
     */
    @Deprecated
    public List<String> excludePaths() { return ignorePaths; }

    /**
     * Test source directories (e.g. {@code api-test/src/test/java}) that the
     * plugin must not resolve as in-scope tests. A diff consisting entirely
     * of files under these directories routes through
     * {@link Situation#ALL_FILES_OUT_OF_SCOPE}. Intended for test source sets
     * the user does not want the {@code affectedTest} task to dispatch
     * (Cucumber/api-test, performance tests, etc.).
     *
     * @return the out-of-scope test directories
     */
    public List<String> outOfScopeTestDirs() { return outOfScopeTestDirs; }

    /**
     * Production source directories the plugin must not consider as in-scope
     * sources during mapping and discovery. A diff entirely under these
     * directories also routes through {@link Situation#ALL_FILES_OUT_OF_SCOPE}.
     *
     * @return the out-of-scope source directories
     */
    public List<String> outOfScopeSourceDirs() { return outOfScopeSourceDirs; }
    public boolean includeImplementationTests() { return includeImplementationTests; }
    public List<String> implementationNaming() { return implementationNaming; }

    /**
     * The configured {@link Mode} — the raw value as set by the caller.
     * May be {@link Mode#AUTO}. Use {@link #effectiveMode()} to read the
     * already-resolved mode.
     *
     * @return the configured mode (may be AUTO)
     */
    public Mode mode() { return mode; }

    /**
     * The mode after {@link Mode#AUTO} resolution. Always one of
     * {@link Mode#LOCAL}, {@link Mode#CI} or {@link Mode#STRICT}.
     *
     * @return the resolved mode
     */
    public Mode effectiveMode() { return effectiveMode; }

    /**
     * The {@link Action} the engine will take for a given {@link Situation}.
     * Produced by layering the explicit caller-set action (highest priority),
     * then the legacy-boolean translation, then the mode default.
     *
     * @param situation the situation to resolve
     * @return the configured action for {@code situation}
     */
    public Action actionFor(Situation situation) {
        return Objects.requireNonNull(situationActions.get(situation), "no action for " + situation);
    }

    /**
     * View of the full per-situation action map. Useful for diagnostic
     * output like {@code --explain}; engine code should prefer
     * {@link #actionFor(Situation)}.
     *
     * @return an immutable situation-to-action map
     */
    public Map<Situation, Action> situationActions() { return situationActions; }

    /**
     * The {@link ActionSource} that picked the {@link Action} for a given
     * {@link Situation}. Used by {@code --explain} so operators can tell
     * whether an outcome came from an explicit setting, a legacy boolean,
     * a mode default, or the pre-v2 hardcoded baseline.
     *
     * @param situation the situation to resolve
     * @return the source tier that produced {@link #actionFor(Situation)}
     */
    public ActionSource actionSourceFor(Situation situation) {
        return Objects.requireNonNull(situationActionSources.get(situation),
                "no action source for " + situation);
    }

    /**
     * View of the per-situation {@link ActionSource} map. Kept immutable
     * and aligned with {@link #situationActions()} so consumers can zip
     * the two for diagnostic output.
     *
     * @return an immutable situation-to-source map
     */
    public Map<Situation, ActionSource> situationActionSources() { return situationActionSources; }

    /** Creates a builder with sensible defaults. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        /**
         * Default list of paths that must never influence test selection —
         * wider than the pre-v2 default ({@code ["**}{@code /generated/**"]})
         * so markdown-only PRs don't sneak past ignore rules on zero-config
         * installs.
         */
        static final List<String> DEFAULT_IGNORE_PATHS = List.of(
                // Each "extension" category is listed twice: once for the
                // root-level form ({@code *.md}) and once for the nested
                // form ({@code **}{@code /*.md}). Java's glob PathMatcher
                // does NOT treat {@code **}{@code /} as optional — the
                // root-level forms are genuinely required or a pure
                // "README.md" diff silently falls through to the unmapped
                // bucket and triggers the full-suite safety net on
                // zero-config installs.
                "**/generated/**",
                "*.md", "**/*.md",
                "*.txt", "**/*.txt",
                "LICENSE", "**/LICENSE",
                "LICENSE.*", "**/LICENSE.*",
                "CHANGELOG*", "**/CHANGELOG*",
                "*.png", "**/*.png",
                "*.jpg", "**/*.jpg",
                "*.jpeg", "**/*.jpeg",
                "*.svg", "**/*.svg",
                "*.gif", "**/*.gif"
        );

        private String baseRef = "origin/master";
        private boolean includeUncommitted = true;
        private boolean includeStaged = true;
        private Boolean runAllIfNoMatches;
        private Boolean runAllOnNonJavaChange;
        private Set<String> strategies = Set.of(STRATEGY_NAMING, STRATEGY_USAGE, STRATEGY_IMPL, STRATEGY_TRANSITIVE);
        // 4 matches the v2 design: most real-world ctrl -> svc -> repo ->
        // mapper chains are 2-3 deep, so 4 leaves headroom without
        // exploding discovery cost. Callers can still clamp back to 2
        // with the {@link #transitiveDepth(int)} setter.
        private int transitiveDepth = 4;
        private List<String> testSuffixes = List.of("Test", "IT", "ITTest", "IntegrationTest");
        private List<String> sourceDirs = List.of("src/main/java");
        private List<String> testDirs = List.of("src/test/java");
        private List<String> ignorePaths;
        private List<String> excludePaths;
        private List<String> outOfScopeTestDirs;
        private List<String> outOfScopeSourceDirs;
        private boolean includeImplementationTests = true;
        // "Default" covers the Java-idiom pattern of {@code FooService} with
        // a {@code DefaultFooService} implementation; "Impl" covers the
        // {@code FooServiceImpl} pattern. v1 only knew about "Impl", which
        // silently dropped tests for every "Default"-prefixed impl in the
        // wild on zero-config installs.
        private List<String> implementationNaming = List.of("Impl", "Default");

        private Mode mode;
        private Action onEmptyDiff;
        private Action onAllFilesIgnored;
        private Action onAllFilesOutOfScope;
        private Action onUnmappedFile;
        private Action onDiscoveryEmpty;

        public Builder baseRef(String baseRef) {
            if (baseRef == null || baseRef.isBlank()) {
                throw new IllegalArgumentException("baseRef must not be null or blank");
            }
            if (baseRef.contains("..") && baseRef.contains("/")) {
                // Prevent path traversal in ref names like "../../etc/passwd"
                // (normal refs like "origin/master" or SHAs are fine)
                String normalized = baseRef.replace("\\", "/");
                if (normalized.contains("../")) {
                    throw new IllegalArgumentException("baseRef contains suspicious path traversal: " + baseRef);
                }
            }
            this.baseRef = baseRef;
            return this;
        }
        public Builder includeUncommitted(boolean v) { this.includeUncommitted = v; return this; }
        public Builder includeStaged(boolean v) { this.includeStaged = v; return this; }
        public Builder runAllIfNoMatches(boolean v) { this.runAllIfNoMatches = v; return this; }
        public Builder runAllOnNonJavaChange(boolean v) { this.runAllOnNonJavaChange = v; return this; }
        public Builder strategies(Set<String> v) {
            this.strategies = Objects.requireNonNull(v, "strategies must not be null");
            return this;
        }
        public Builder transitiveDepth(int v) { this.transitiveDepth = Math.max(0, Math.min(v, 5)); return this; }
        public Builder testSuffixes(List<String> v) {
            this.testSuffixes = Objects.requireNonNull(v, "testSuffixes must not be null");
            return this;
        }
        public Builder sourceDirs(List<String> v) {
            this.sourceDirs = Objects.requireNonNull(v, "sourceDirs must not be null");
            return this;
        }
        public Builder testDirs(List<String> v) {
            this.testDirs = Objects.requireNonNull(v, "testDirs must not be null");
            return this;
        }

        /**
         * Back-compat alias for {@link #ignorePaths(List)}. If both are set,
         * {@link #ignorePaths(List)} wins.
         *
         * @param v the exclude paths
         * @return this builder
         * @deprecated prefer {@link #ignorePaths(List)} for new code.
         */
        @Deprecated
        public Builder excludePaths(List<String> v) {
            this.excludePaths = Objects.requireNonNull(v, "excludePaths must not be null");
            return this;
        }

        /** Glob patterns for files the plugin must ignore entirely. */
        public Builder ignorePaths(List<String> v) {
            this.ignorePaths = Objects.requireNonNull(v, "ignorePaths must not be null");
            return this;
        }

        /** Test source directories the plugin must not dispatch (e.g. {@code api-test/src/test/java}). */
        public Builder outOfScopeTestDirs(List<String> v) {
            this.outOfScopeTestDirs = Objects.requireNonNull(v, "outOfScopeTestDirs must not be null");
            return this;
        }

        /** Production source directories the plugin must treat as out-of-scope. */
        public Builder outOfScopeSourceDirs(List<String> v) {
            this.outOfScopeSourceDirs = Objects.requireNonNull(v, "outOfScopeSourceDirs must not be null");
            return this;
        }

        public Builder includeImplementationTests(boolean v) { this.includeImplementationTests = v; return this; }
        public Builder implementationNaming(List<String> v) {
            this.implementationNaming = Objects.requireNonNull(v, "implementationNaming must not be null");
            return this;
        }

        public Builder mode(Mode v) {
            this.mode = Objects.requireNonNull(v, "mode must not be null");
            return this;
        }
        public Builder onEmptyDiff(Action v) {
            this.onEmptyDiff = Objects.requireNonNull(v, "onEmptyDiff must not be null");
            return this;
        }
        public Builder onAllFilesIgnored(Action v) {
            this.onAllFilesIgnored = Objects.requireNonNull(v, "onAllFilesIgnored must not be null");
            return this;
        }
        public Builder onAllFilesOutOfScope(Action v) {
            this.onAllFilesOutOfScope = Objects.requireNonNull(v, "onAllFilesOutOfScope must not be null");
            return this;
        }
        public Builder onUnmappedFile(Action v) {
            this.onUnmappedFile = Objects.requireNonNull(v, "onUnmappedFile must not be null");
            return this;
        }
        public Builder onDiscoveryEmpty(Action v) {
            this.onDiscoveryEmpty = Objects.requireNonNull(v, "onDiscoveryEmpty must not be null");
            return this;
        }

        public AffectedTestsConfig build() {
            return new AffectedTestsConfig(this);
        }

        /**
         * Detects whether the current process is running in CI via common
         * env vars. Kept package-private so tests can verify the detection
         * rules without going through {@link #build()}.
         */
        static Mode detectMode() {
            if (envSet("CI")
                    || envSet("GITLAB_CI")
                    || envSet("GITHUB_ACTIONS")
                    || envSet("JENKINS_HOME")
                    || envSet("CIRCLECI")
                    || envSet("TRAVIS")
                    || envSet("BUILDKITE")
                    || envSet("TF_BUILD")) {
                return Mode.CI;
            }
            return Mode.LOCAL;
        }

        private static boolean envSet(String name) {
            String v = System.getenv(name);
            return v != null && !v.isBlank();
        }
    }
}
