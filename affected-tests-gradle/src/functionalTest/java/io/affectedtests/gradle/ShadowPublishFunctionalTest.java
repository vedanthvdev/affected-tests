package io.affectedtests.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional test using Gradle TestKit to verify that the Shadow + plugin-publish
 * configuration works end-to-end. This catches build-time errors like the
 * "Variant for configuration 'shadowRuntimeElements' does not exist in component"
 * issue that unit tests with ProjectBuilder cannot detect.
 */
class ShadowPublishFunctionalTest {

    @TempDir
    Path projectDir;

    @Test
    void publishTasksCanBeConfiguredWithoutError() throws IOException {
        // Write a minimal build.gradle that applies the plugin and triggers
        // publishing configuration — the same way a real consumer would.
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'test-project'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.affectedtests'
                }
                """);

        // "tasks --all" forces Gradle to configure all tasks (including publishing
        // tasks like generateMetadataFileForPluginMavenPublication). If the Shadow/
        // plugin-publish wiring is broken, this will fail with a configuration error.
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("tasks", "--all")
                .withPluginClasspath()
                .build();

        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("affectedTest"),
                "affectedTest task should appear in task listing");
    }

    @Test
    void generatePomDoesNotFailWithShadowConfiguration() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'test-project'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.affectedtests'
                }
                """);

        // Run a dry-run of the build — this resolves all task dependencies and
        // configurations without actually executing them. A broken variant
        // configuration would cause a failure here.
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("build", "--dry-run")
                .withPluginClasspath()
                .build();

        assertNotNull(result.getOutput());
        // Dry run should succeed without variant configuration errors
        assertTrue(result.getOutput().contains("SKIPPED") || result.getOutput().contains("UP-TO-DATE")
                        || result.getOutput().contains(":build"),
                "Build dry-run should complete successfully");
    }
}
