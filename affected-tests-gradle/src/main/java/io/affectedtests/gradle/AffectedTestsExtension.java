package io.affectedtests.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
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
 *     strategies = ["naming", "usage", "impl"]
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
     * Strategies to use for test discovery.
     * Default: {@code ["naming", "usage", "impl"]}.
     *
     * @return the strategies list property
     */
    public abstract ListProperty<String> getStrategies();

    /**
     * How many levels of transitive dependencies to follow.
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
     * Default: {@code ["&#42;&#42;/generated/&#42;&#42;"]}.
     *
     * @return the exclude paths list property
     */
    public abstract ListProperty<String> getExcludePaths();

    /**
     * Include tests for implementations of changed interfaces/base classes.
     * Default: {@code true}.
     *
     * @return the include implementation tests property
     */
    public abstract Property<Boolean> getIncludeImplementationTests();

    /**
     * Implementation naming suffixes (e.g. "Impl" matches FooBarImpl for FooBar).
     * Default: {@code ["Impl"]}.
     *
     * @return the implementation naming list property
     */
    public abstract ListProperty<String> getImplementationNaming();

    /**
     * Mapping of source project to test project for multi-module builds
     * (e.g. {@code [":api": ":application"]}).
     *
     * @return the test project mapping property
     */
    public abstract MapProperty<String, String> getTestProjectMapping();
}
