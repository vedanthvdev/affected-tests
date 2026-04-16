package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine;
import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gradle task that detects affected tests and executes them.
 *
 * <p>This task:
 * <ol>
 *   <li>Detects git changes against the configured base ref</li>
 *   <li>Maps changed files to production/test classes</li>
 *   <li>Discovers which test classes are affected</li>
 *   <li>Executes a Gradle {@code test} invocation with {@code --tests} filters</li>
 * </ol>
 *
 * <p>Not compatible with Configuration Cache (reads live git state and
 * scans the file system at execution time).
 */
public abstract class AffectedTestTask extends DefaultTask {

    public AffectedTestTask() {
        notCompatibleWithConfigurationCache("Reads live git state and scans the file system at execution time");
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
     * Default: {@code false} (skip tests when nothing is affected).
     *
     * @return the run-all-if-no-matches property
     */
    @Input
    public abstract Property<Boolean> getRunAllIfNoMatches();

    /**
     * Discovery strategies to use for finding affected tests.
     * Valid values: {@code "naming"}, {@code "usage"}, {@code "impl"}.
     * Default: all three.
     *
     * @return the strategies list property
     */
    @Input
    public abstract ListProperty<String> getStrategies();

    /**
     * How many levels of transitive dependencies to follow.
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
     * Glob patterns for files to exclude from analysis.
     * Default: {@code ["&#42;&#42;/generated/&#42;&#42;"]}.
     *
     * @return the exclude paths list property
     */
    @Input
    public abstract ListProperty<String> getExcludePaths();

    /**
     * Whether to include tests for implementations of changed interfaces/base classes.
     * Default: {@code true}.
     *
     * @return the include implementation tests property
     */
    @Input
    public abstract Property<Boolean> getIncludeImplementationTests();

    /**
     * Suffixes for finding implementation classes (e.g. {@code "Impl"} matches
     * {@code FooServiceImpl} for a changed {@code FooService}).
     * Default: {@code ["Impl"]}.
     *
     * @return the implementation naming list property
     */
    @Input
    public abstract ListProperty<String> getImplementationNaming();

    /**
     * Mapping of source project to test project for multi-module builds
     * where tests for one module live in a different module
     * (e.g. {@code [":api": ":application"]}).
     * Default: empty map.
     *
     * @return the test project mapping property
     */
    @Input
    public abstract MapProperty<String, String> getTestProjectMapping();

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

        // Build config from task properties
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef(getBaseRef().get())
                .includeUncommitted(getIncludeUncommitted().get())
                .includeStaged(getIncludeStaged().get())
                .runAllIfNoMatches(getRunAllIfNoMatches().get())
                .strategies(new LinkedHashSet<>(getStrategies().get()))
                .transitiveDepth(getTransitiveDepth().get())
                .testSuffixes(getTestSuffixes().get())
                .sourceDirs(getSourceDirs().get())
                .testDirs(getTestDirs().get())
                .excludePaths(getExcludePaths().get())
                .includeImplementationTests(getIncludeImplementationTests().get())
                .implementationNaming(getImplementationNaming().get())
                .testProjectMapping(getTestProjectMapping().get())
                .build();

        // Run the engine
        AffectedTestsEngine engine = new AffectedTestsEngine(config, projectDir);
        AffectedTestsResult result = engine.run();

        // Report
        getLogger().lifecycle("Affected Tests: {} changed files, {} production classes, {} test classes affected",
                result.changedFiles().size(),
                result.changedProductionClasses().size(),
                result.testClassFqns().size());

        if (result.testClassFqns().isEmpty() && !result.runAll()) {
            getLogger().lifecycle("No affected tests to run. Skipping test execution.");
            return;
        }

        // Build the Gradle command to execute tests
        executeTests(projectDir, result.testClassFqns(), result.runAll());
    }

    private static final Pattern VALID_FQN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.$]*$");

    private void executeTests(Path projectDir, Set<String> testFqns, boolean runAll) {
        String gradleCommand = resolveGradleCommand(projectDir);

        List<String> args = new ArrayList<>();
        args.add(gradleCommand);
        args.add("test");

        if (!runAll && !testFqns.isEmpty()) {
            for (String fqn : testFqns) {
                if (!VALID_FQN.matcher(fqn).matches()) {
                    getLogger().warn("Skipping invalid test FQN: {}", fqn);
                    continue;
                }
                args.add("--tests");
                args.add(fqn);
            }
            getLogger().lifecycle("Running {} affected test classes:", testFqns.size());
            testFqns.forEach(t -> getLogger().lifecycle("  -> {}", t));
        } else {
            getLogger().lifecycle("Running ALL tests (runAllIfNoMatches=true).");
        }

        // Don't rebuild — tests are already compiled
        args.add("-x");
        args.add("compileJava");
        args.add("-x");
        args.add("compileTestJava");

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
     * Resolves the Gradle command to use. Prefers the wrapper in the project directory;
     * falls back to the bare {@code "gradle"} command name so the OS PATH is used.
     */
    private static String resolveGradleCommand(Path projectDir) {
        String wrapperName = isWindows() ? "gradlew.bat" : "gradlew";
        File gradlew = projectDir.resolve(wrapperName).toFile();
        if (gradlew.exists() && gradlew.canExecute()) {
            return gradlew.getAbsolutePath();
        }
        return isWindows() ? "gradle.bat" : "gradle";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
