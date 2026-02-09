package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine;
import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.testing.Test;

import java.nio.file.Path;
import java.util.*;

/**
 * Gradle task that detects affected tests and runs them.
 * <p>
 * This task:
 * <ol>
 *   <li>Detects git changes against the configured base ref</li>
 *   <li>Maps changed files to production/test classes</li>
 *   <li>Discovers which test classes are affected</li>
 *   <li>Configures and executes the {@code test} task with the appropriate filters</li>
 * </ol>
 */
public abstract class AffectedTestTask extends DefaultTask {

    @Input
    public abstract Property<String> getBaseRef();

    @Input
    public abstract Property<Boolean> getIncludeUncommitted();

    @Input
    public abstract Property<Boolean> getIncludeStaged();

    @Input
    public abstract Property<Boolean> getRunAllIfNoMatches();

    @Input
    public abstract ListProperty<String> getStrategies();

    @Input
    public abstract Property<Integer> getTransitiveDepth();

    @Input
    public abstract ListProperty<String> getTestSuffixes();

    @Input
    public abstract ListProperty<String> getSourceDirs();

    @Input
    public abstract ListProperty<String> getTestDirs();

    @Input
    public abstract ListProperty<String> getExcludePaths();

    @Input
    public abstract Property<Boolean> getIncludeImplementationTests();

    @Input
    public abstract ListProperty<String> getImplementationNaming();

    @Input
    public abstract MapProperty<String, String> getTestProjectMapping();

    @TaskAction
    public void runAffectedTests() {
        Project project = getProject();
        Path projectDir = project.getRootDir().toPath();

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

        // Execute tests
        if (result.runAll()) {
            getLogger().lifecycle("Running ALL tests (runAllIfNoMatches=true).");
            executeTestsForProject(project, Set.of()); // empty = run all
        } else {
            getLogger().lifecycle("Running {} affected test classes:", result.testClassFqns().size());
            result.testClassFqns().forEach(t -> getLogger().lifecycle("  → {}", t));
            executeTestsForProject(project, result.testClassFqns());
        }
    }

    private void executeTestsForProject(Project project, Set<String> testFqns) {
        // Find all projects that have a 'test' task
        Set<Project> testProjects = new LinkedHashSet<>();
        Map<String, String> mapping = getTestProjectMapping().get();

        if (project.getRootProject().getSubprojects().isEmpty()) {
            // Single-project build
            testProjects.add(project);
        } else {
            // Multi-project: include all subprojects that have tests
            for (Project sub : project.getRootProject().getAllprojects()) {
                try {
                    sub.getTasks().named("test");
                    testProjects.add(sub);
                } catch (Exception e) {
                    // no test task in this project
                }
            }
        }

        for (Project testProject : testProjects) {
            configureTestTask(testProject, testFqns);
        }
    }

    private void configureTestTask(Project project, Set<String> testFqns) {
        project.getTasks().withType(Test.class).configureEach(testTask -> {
            if (!testFqns.isEmpty()) {
                testTask.setEnabled(true);
                // Apply test filters
                for (String fqn : testFqns) {
                    testTask.filter(filter -> {
                        filter.includeTestsMatching(fqn);
                    });
                }
                getLogger().lifecycle("Configured {} with {} test filters",
                        testTask.getPath(), testFqns.size());
            }
            // If testFqns is empty and we're here, it means runAll = true, don't filter
        });

        // Ensure the test task runs
        try {
            project.getTasks().named("test").get().setEnabled(true);
        } catch (Exception e) {
            getLogger().warn("Could not enable test task for project {}", project.getPath());
        }
    }
}
