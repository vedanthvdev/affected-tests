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
        // COMMITTED-ONLY by default: the plugin's whole job is "what
        // tests does this MR touch?", and the MR is the committed diff
        // — not the dev's WIP. Matching this default to the CI reality
        // means a local `./gradlew affectedTest` run picks the same
        // tests CI will run on the same HEAD, and two runs on the same
        // commit are deterministic. Adopters who iterate on WIP tests
        // flip these back to `true` in their build.gradle; the plugin
        // never silently expands the diff boundary.
        extension.getIncludeUncommitted().convention(false);
        extension.getIncludeStaged().convention(false);
        // No conventions for the two legacy booleans — leaving them
        // unset is the v2 signal that the caller has not overridden the
        // defaults, which lets the core config resolver apply
        // mode-based defaults instead of always shimming the pre-v2
        // boolean translation. Zero-config users still get pre-v2
        // behaviour because the core builder's hardcoded fallbacks
        // match the old convention values 1:1.
        extension.getStrategies().convention(List.of("naming", "usage", "impl", "transitive"));
        extension.getTransitiveDepth().convention(4);
        extension.getTestSuffixes().convention(List.of("Test", "IT", "ITTest", "IntegrationTest"));
        extension.getSourceDirs().convention(List.of("src/main/java"));
        extension.getTestDirs().convention(List.of("src/test/java"));
        // No convention for excludePaths / ignorePaths: an empty Gradle
        // provider is how we signal "let the core config builder pick
        // the v2 default list" (which is broader than the pre-v2
        // single-entry default). Setting a convention here would pin
        // the pre-v2 narrow default even on zero-config installs.
        extension.getIncludeImplementationTests().convention(true);
        extension.getImplementationNaming().convention(List.of("Impl", "Default"));

        Project rootProject = project.getRootProject();
        Directory rootDir = rootProject.getLayout().getProjectDirectory();

        project.getTasks().register("affectedTest", AffectedTestTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs only the tests affected by changes in the current branch.");

            task.getBaseRef().set(extension.getBaseRef());
            task.getIncludeUncommitted().set(extension.getIncludeUncommitted());
            task.getIncludeStaged().set(extension.getIncludeStaged());
            task.getRunAllIfNoMatches().set(extension.getRunAllIfNoMatches());
            task.getRunAllOnNonJavaChange().set(extension.getRunAllOnNonJavaChange());
            task.getStrategies().set(extension.getStrategies());
            task.getTransitiveDepth().set(extension.getTransitiveDepth());
            task.getTestSuffixes().set(extension.getTestSuffixes());
            task.getSourceDirs().set(extension.getSourceDirs());
            task.getTestDirs().set(extension.getTestDirs());
            task.getExcludePaths().set(extension.getExcludePaths());
            task.getIgnorePaths().set(extension.getIgnorePaths());
            task.getOutOfScopeTestDirs().set(extension.getOutOfScopeTestDirs());
            task.getOutOfScopeSourceDirs().set(extension.getOutOfScopeSourceDirs());
            task.getIncludeImplementationTests().set(extension.getIncludeImplementationTests());
            task.getImplementationNaming().set(extension.getImplementationNaming());
            task.getMode().set(extension.getMode());
            task.getOnEmptyDiff().set(extension.getOnEmptyDiff());
            task.getOnAllFilesIgnored().set(extension.getOnAllFilesIgnored());
            task.getOnAllFilesOutOfScope().set(extension.getOnAllFilesOutOfScope());
            task.getOnUnmappedFile().set(extension.getOnUnmappedFile());
            task.getOnDiscoveryEmpty().set(extension.getOnDiscoveryEmpty());
            task.getOnDiscoveryIncomplete().set(extension.getOnDiscoveryIncomplete());
            task.getGradlewTimeoutSeconds().set(extension.getGradlewTimeoutSeconds());

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
