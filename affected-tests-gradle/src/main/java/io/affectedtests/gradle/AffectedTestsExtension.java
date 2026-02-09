package io.affectedtests.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * DSL extension for configuring the Affected Tests plugin.
 * <p>
 * Usage in {@code build.gradle}:
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
 *     excludePaths = ["** /generated/**"]
 *     includeImplementationTests = true
 *     implementationNaming = ["Impl"]
 * }
 * }</pre>
 */
public abstract class AffectedTestsExtension {

    /** Git base ref to diff against. Default: "origin/master". */
    public abstract Property<String> getBaseRef();

    /** Include uncommitted (unstaged) changes. Default: true. */
    public abstract Property<Boolean> getIncludeUncommitted();

    /** Include staged changes. Default: true. */
    public abstract Property<Boolean> getIncludeStaged();

    /** Run full test suite if no affected tests are found. Default: false. */
    public abstract Property<Boolean> getRunAllIfNoMatches();

    /** Strategies to use. Default: ["naming", "usage", "impl"]. */
    public abstract ListProperty<String> getStrategies();

    /** How many levels of transitive dependencies to follow. Default: 2. */
    public abstract Property<Integer> getTransitiveDepth();

    /** Test class suffixes. Default: ["Test", "IT", "ITTest", "IntegrationTest"]. */
    public abstract ListProperty<String> getTestSuffixes();

    /** Production source directories. Default: ["src/main/java"]. */
    public abstract ListProperty<String> getSourceDirs();

    /** Test source directories. Default: ["src/test/java"]. */
    public abstract ListProperty<String> getTestDirs();

    /** Glob patterns for files to exclude. Default: ["** /generated/**"]. */
    public abstract ListProperty<String> getExcludePaths();

    /** Include tests for implementations of changed types. Default: true. */
    public abstract Property<Boolean> getIncludeImplementationTests();

    /** Implementation naming suffixes. Default: ["Impl"]. */
    public abstract ListProperty<String> getImplementationNaming();

    /** Mapping of source project to test project (for multi-module). */
    public abstract MapProperty<String, String> getTestProjectMapping();
}
