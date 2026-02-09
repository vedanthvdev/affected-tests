package io.affectedtests.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.List;
import java.util.Set;

/**
 * Gradle plugin that registers the {@code affectedTest} task and the
 * {@code affectedTests} DSL extension.
 * <p>
 * Plugin ID: {@code io.affectedtests}
 */
public class AffectedTestsPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // Register extension with defaults
        AffectedTestsExtension extension = project.getExtensions()
                .create("affectedTests", AffectedTestsExtension.class);

        // Set defaults
        extension.getBaseRef().convention(
                project.getProviders().gradleProperty("affectedTestsBaseRef")
                        .orElse("origin/master")
        );
        extension.getIncludeUncommitted().convention(true);
        extension.getIncludeStaged().convention(true);
        extension.getRunAllIfNoMatches().convention(false);
        extension.getStrategies().convention(List.of("naming", "usage", "impl"));
        extension.getTransitiveDepth().convention(2);
        extension.getTestSuffixes().convention(List.of("Test", "IT", "ITTest", "IntegrationTest"));
        extension.getSourceDirs().convention(List.of("src/main/java"));
        extension.getTestDirs().convention(List.of("src/test/java"));
        extension.getExcludePaths().convention(List.of("**/generated/**"));
        extension.getIncludeImplementationTests().convention(true);
        extension.getImplementationNaming().convention(List.of("Impl"));
        extension.getTestProjectMapping().convention(java.util.Map.of());

        // Register the affectedTest task
        project.getTasks().register("affectedTest", AffectedTestTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs only the tests affected by changes in the current branch.");

            // Wire extension properties to task properties
            task.getBaseRef().set(extension.getBaseRef());
            task.getIncludeUncommitted().set(extension.getIncludeUncommitted());
            task.getIncludeStaged().set(extension.getIncludeStaged());
            task.getRunAllIfNoMatches().set(extension.getRunAllIfNoMatches());
            task.getStrategies().set(extension.getStrategies());
            task.getTransitiveDepth().set(extension.getTransitiveDepth());
            task.getTestSuffixes().set(extension.getTestSuffixes());
            task.getSourceDirs().set(extension.getSourceDirs());
            task.getTestDirs().set(extension.getTestDirs());
            task.getExcludePaths().set(extension.getExcludePaths());
            task.getIncludeImplementationTests().set(extension.getIncludeImplementationTests());
            task.getImplementationNaming().set(extension.getImplementationNaming());
            task.getTestProjectMapping().set(extension.getTestProjectMapping());
        });
    }
}
