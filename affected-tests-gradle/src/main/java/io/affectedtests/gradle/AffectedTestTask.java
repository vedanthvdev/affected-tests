package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine;
import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.gradle.api.DefaultTask;
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
 * <p>The task is Configuration Cache compatible: it does not access
 * {@code Project} at execution time and uses injected {@link ExecOperations}
 * to spawn the test run.
 */
public abstract class AffectedTestTask extends DefaultTask {

    /**
     * Git base ref to diff against.
     *
     * @return the base ref property
     */
    @Input
    public abstract Property<String> getBaseRef();

    /**
     * Whether to include uncommitted (unstaged) changes.
     *
     * @return the include uncommitted property
     */
    @Input
    public abstract Property<Boolean> getIncludeUncommitted();

    /**
     * Whether to include staged changes.
     *
     * @return the include staged property
     */
    @Input
    public abstract Property<Boolean> getIncludeStaged();

    /**
     * Whether to run the full suite if no affected tests are found.
     *
     * @return the run-all-if-no-matches property
     */
    @Input
    public abstract Property<Boolean> getRunAllIfNoMatches();

    /**
     * Discovery strategies to use.
     *
     * @return the strategies list property
     */
    @Input
    public abstract ListProperty<String> getStrategies();

    /**
     * Transitive dependency depth.
     *
     * @return the transitive depth property
     */
    @Input
    public abstract Property<Integer> getTransitiveDepth();

    /**
     * Test class suffixes for the naming strategy.
     *
     * @return the test suffixes list property
     */
    @Input
    public abstract ListProperty<String> getTestSuffixes();

    /**
     * Production source directories.
     *
     * @return the source dirs list property
     */
    @Input
    public abstract ListProperty<String> getSourceDirs();

    /**
     * Test source directories.
     *
     * @return the test dirs list property
     */
    @Input
    public abstract ListProperty<String> getTestDirs();

    /**
     * Glob patterns for files to exclude from analysis.
     *
     * @return the exclude paths list property
     */
    @Input
    public abstract ListProperty<String> getExcludePaths();

    /**
     * Whether to include tests for implementations of changed types.
     *
     * @return the include implementation tests property
     */
    @Input
    public abstract Property<Boolean> getIncludeImplementationTests();

    /**
     * Implementation naming suffixes.
     *
     * @return the implementation naming list property
     */
    @Input
    public abstract ListProperty<String> getImplementationNaming();

    /**
     * Mapping of source project to test project (for multi-module).
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
     * Configuration Cache safe.
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

    private void executeTests(Path projectDir, Set<String> testFqns, boolean runAll) {
        File gradlew = projectDir.resolve(isWindows() ? "gradlew.bat" : "gradlew").toFile();
        if (!gradlew.exists() || !gradlew.canExecute()) {
            getLogger().warn("Gradle wrapper not found at {}. Falling back to 'gradle' command.", gradlew);
            gradlew = new File("gradle");
        }

        List<String> args = new ArrayList<>();
        args.add(gradlew.getAbsolutePath());
        args.add("test");

        if (!runAll && !testFqns.isEmpty()) {
            java.util.regex.Pattern validFqn = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.\\$]*$");
            for (String fqn : testFqns) {
                if (!validFqn.matcher(fqn).matches()) {
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
            throw new RuntimeException("Test execution failed with exit code " + execResult.getExitValue());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
