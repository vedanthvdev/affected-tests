package io.affectedtests.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * DSL extension for configuring the Affected Tests plugin.
 *
 * <p>Usage in {@code build.gradle}:
 * <pre>{@code
 * affectedTests {
 *     baseRef = "origin/master"
 *     includeUncommitted = true
 *     runAllIfNoMatches = false
 *     strategies = ["naming", "usage", "impl", "transitive"]
 *     transitiveDepth = 2
 *     testSuffixes = ["Test", "IT", "ITTest", "IntegrationTest"]
 *     sourceDirs = ["src/main/java"]
 *     testDirs = ["src/test/java"]
 *     excludePaths = ["&#42;&#42;/generated/&#42;&#42;"]
 *     includeImplementationTests = true
 *     implementationNaming = ["Impl"]
 * }
 * }</pre>
 */
public abstract class AffectedTestsExtension {

    /**
     * Git base ref to diff against.
     * Default: {@code "origin/master"}.
     *
     * @return the base ref property
     */
    public abstract Property<String> getBaseRef();

    /**
     * Include uncommitted (unstaged) changes.
     * Default: {@code true}.
     *
     * @return the include uncommitted property
     */
    public abstract Property<Boolean> getIncludeUncommitted();

    /**
     * Include staged changes.
     * Default: {@code true}.
     *
     * @return the include staged property
     */
    public abstract Property<Boolean> getIncludeStaged();

    /**
     * Run full test suite if no affected tests are found.
     * Default: {@code false}.
     *
     * @return the run-all-if-no-matches property
     */
    public abstract Property<Boolean> getRunAllIfNoMatches();

    /**
     * Force a full test run whenever the change set contains any file that
     * cannot be resolved to a Java class under {@link #getSourceDirs()} or
     * {@link #getTestDirs()} — for example {@code application.yml},
     * {@code build.gradle}, a Liquibase changelog, or a logback config.
     * Files matching {@link #getExcludePaths()} are treated as an explicit
     * opt-out and do not trigger the escalation.
     *
     * <p>Default: {@code true} — "run more, never run less".
     *
     * @return the run-all-on-non-java-change property
     */
    public abstract Property<Boolean> getRunAllOnNonJavaChange();

    /**
     * Strategies to use for test discovery. Valid values:
     * {@code "naming"}, {@code "usage"}, {@code "impl"}, {@code "transitive"}.
     * Default: all four.
     *
     * <p>The {@code transitive} strategy additionally respects
     * {@link #getTransitiveDepth() transitiveDepth}.
     *
     * @return the strategies list property
     */
    public abstract ListProperty<String> getStrategies();

    /**
     * How many levels of transitive dependencies to follow when the
     * {@code transitive} strategy is enabled.
     * Default: {@code 2}. Range: 0–5.
     *
     * @return the transitive depth property
     */
    public abstract Property<Integer> getTransitiveDepth();

    /**
     * Test class suffixes used by the naming strategy.
     * Default: {@code ["Test", "IT", "ITTest", "IntegrationTest"]}.
     *
     * @return the test suffixes list property
     */
    public abstract ListProperty<String> getTestSuffixes();

    /**
     * Production source directories (relative to each module root).
     * Default: {@code ["src/main/java"]}.
     *
     * @return the source dirs list property
     */
    public abstract ListProperty<String> getSourceDirs();

    /**
     * Test source directories (relative to each module root).
     * Default: {@code ["src/test/java"]}.
     *
     * @return the test dirs list property
     */
    public abstract ListProperty<String> getTestDirs();

    /**
     * Glob patterns for files to exclude from analysis.
     * v2 back-compat alias for {@link #getIgnorePaths()}. Default is an
     * empty Gradle property — when unset, the v2 {@code ignorePaths}
     * default applies (a broader list than the pre-v2 single-entry
     * {@code ["**}{@code /generated/**"]} list).
     *
     * @return the exclude paths list property
     * @deprecated prefer {@link #getIgnorePaths()} in new configs.
     */
    @Deprecated
    public abstract ListProperty<String> getExcludePaths();

    /**
     * Glob patterns for files that must never influence test selection —
     * for purely documentation, build metadata, or generated artifacts.
     * A diff consisting entirely of ignored paths routes through
     * {@code Situation.ALL_FILES_IGNORED}.
     *
     * <p>When unset, the core {@code AffectedTestsConfig} default list
     * applies (markdown, generated/, text/licence/changelog, images).
     *
     * @return the ignore paths list property
     */
    public abstract ListProperty<String> getIgnorePaths();

    /**
     * Test source directories (e.g. {@code "api-test/src/test/java"})
     * whose contents the plugin must not dispatch via the
     * {@code affectedTest} task. A diff entirely under these directories
     * routes through {@code Situation.ALL_FILES_OUT_OF_SCOPE}. Intended
     * for Cucumber/api-test, performance, or other non-unit-test source
     * sets.
     *
     * @return the out-of-scope test dirs list property
     */
    public abstract ListProperty<String> getOutOfScopeTestDirs();

    /**
     * Production source directories the plugin must treat as
     * out-of-scope. A diff entirely under these dirs routes through
     * {@code Situation.ALL_FILES_OUT_OF_SCOPE}.
     *
     * @return the out-of-scope source dirs list property
     */
    public abstract ListProperty<String> getOutOfScopeSourceDirs();

    /**
     * Include tests for implementations of changed interfaces/base classes.
     * Default: {@code true}.
     *
     * @return the include implementation tests property
     */
    public abstract Property<Boolean> getIncludeImplementationTests();

    /**
     * Implementation naming prefixes/suffixes (e.g. "Impl" matches
     * {@code FooBarImpl} for {@code FooBar}; "Default" matches
     * {@code DefaultFooBar} for {@code FooBar}).
     * Default: {@code ["Impl", "Default"]}.
     *
     * @return the implementation naming list property
     */
    public abstract ListProperty<String> getImplementationNaming();

    /**
     * Execution profile ({@code "auto"}, {@code "local"}, {@code "ci"},
     * or {@code "strict"}). Controls per-situation default actions
     * when neither the explicit {@code onXxx} setting nor the legacy
     * boolean translation has picked one.
     *
     * <p>Default: unset — which preserves the pre-v2 defaults for
     * zero-config users. Setting it to {@code "auto"} is the
     * recommended migration: it detects CI via common env vars and
     * picks the CI defaults there, and the LOCAL defaults otherwise.
     *
     * @return the mode property
     */
    public abstract Property<String> getMode();

    /**
     * Action to take on an empty git diff. One of {@code "selected"},
     * {@code "full_suite"}, {@code "skipped"} (case-insensitive).
     *
     * @return the on-empty-diff property
     */
    public abstract Property<String> getOnEmptyDiff();

    /**
     * Action to take when every file in the diff matched
     * {@link #getIgnorePaths()}. One of {@code "selected"},
     * {@code "full_suite"}, {@code "skipped"}.
     *
     * @return the on-all-files-ignored property
     */
    public abstract Property<String> getOnAllFilesIgnored();

    /**
     * Action to take when every file in the diff sat under
     * {@link #getOutOfScopeTestDirs()} or {@link #getOutOfScopeSourceDirs()}.
     * One of {@code "selected"}, {@code "full_suite"}, {@code "skipped"}.
     *
     * @return the on-all-files-out-of-scope property
     */
    public abstract Property<String> getOnAllFilesOutOfScope();

    /**
     * Action to take when the diff contains at least one unmapped file
     * (non-Java, outside configured source/test dirs). One of
     * {@code "selected"}, {@code "full_suite"}, {@code "skipped"}. When
     * set to {@code "selected"} the engine treats the unmapped file as
     * if it weren't there and continues to discovery — matching
     * pre-v2 {@code runAllOnNonJavaChange=false} behaviour.
     *
     * @return the on-unmapped-file property
     */
    public abstract Property<String> getOnUnmappedFile();

    /**
     * Action to take when discovery completes but returns no tests. One
     * of {@code "selected"}, {@code "full_suite"}, {@code "skipped"}.
     *
     * @return the on-discovery-empty property
     */
    public abstract Property<String> getOnDiscoveryEmpty();
}
