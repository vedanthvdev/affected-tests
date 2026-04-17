package io.affectedtests.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gradle plugin that registers the {@code affectedTest} task and the
 * {@code affectedTests} DSL extension.
 *
 * <p>Plugin ID: {@code io.github.vedanthvdev.affectedtests}
 */
public class AffectedTestsPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        AffectedTestsExtension extension = project.getExtensions()
                .create("affectedTests", AffectedTestsExtension.class);

        extension.getBaseRef().convention(
                project.getProviders().gradleProperty("affectedTestsBaseRef")
                        .orElse("origin/master")
        );
        extension.getIncludeUncommitted().convention(true);
        extension.getIncludeStaged().convention(true);
        extension.getRunAllIfNoMatches().convention(false);
        extension.getStrategies().convention(List.of("naming", "usage", "impl", "transitive"));
        extension.getTransitiveDepth().convention(2);
        extension.getTestSuffixes().convention(List.of("Test", "IT", "ITTest", "IntegrationTest"));
        extension.getSourceDirs().convention(List.of("src/main/java"));
        extension.getTestDirs().convention(List.of("src/test/java"));
        extension.getExcludePaths().convention(List.of("**/generated/**"));
        extension.getIncludeImplementationTests().convention(true);
        extension.getImplementationNaming().convention(List.of("Impl"));

        Project rootProject = project.getRootProject();
        Directory rootDir = rootProject.getLayout().getProjectDirectory();

        project.getTasks().register("affectedTest", AffectedTestTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs only the tests affected by changes in the current branch.");

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

            task.getRootDir().set(rootDir);
            task.getSubprojectPaths().set(project.provider(() -> collectSubprojectPaths(rootProject)));

            // Ensure test classes are compiled before the nested gradle invocation
            // runs. Without this, a fresh CI checkout would have nothing to test
            // and Gradle would fail with "No tests found for given includes".
            rootProject.allprojects(p -> p.getPluginManager().withPlugin("java", unused ->
                    task.dependsOn(p.getTasks().named("testClasses"))));
        });
    }

    /**
     * Returns a map of relative-path-from-root-project-dir (empty string for the
     * root project itself) to the Gradle path of each project. The task uses
     * this map to route {@code --tests} filters to the correct module task.
     */
    private static Map<String, String> collectSubprojectPaths(Project rootProject) {
        File rootDir = rootProject.getProjectDir();
        Map<String, String> result = new LinkedHashMap<>();
        rootProject.getAllprojects().forEach(p -> {
            String relative = relativiseNormalised(rootDir, p.getProjectDir());
            result.putIfAbsent(relative, p.getPath());
        });
        return result;
    }

    private static String relativiseNormalised(File rootDir, File projectDir) {
        String relative = rootDir.toPath().relativize(projectDir.toPath()).toString();
        if (relative.isEmpty()) {
            return "";
        }
        return relative.replace(File.separatorChar, '/');
    }
}
