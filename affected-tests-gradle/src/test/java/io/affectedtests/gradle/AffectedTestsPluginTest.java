package io.affectedtests.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AffectedTestsPluginTest {

    @Test
    void pluginRegistersTask() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        assertNotNull(project.getTasks().findByName("affectedTest"),
                "affectedTest task should be registered");
    }

    @Test
    void pluginRegistersExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        assertNotNull(project.getExtensions().findByName("affectedTests"),
                "affectedTests extension should be registered");
    }

    @Test
    void extensionHasDefaults() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);

        assertEquals("origin/master", ext.getBaseRef().get());
        assertTrue(ext.getIncludeUncommitted().get());
        assertTrue(ext.getIncludeStaged().get());
        assertFalse(ext.getRunAllIfNoMatches().get());
        // The "run more, never run less" default: any non-Java or unmapped file
        // in the diff must escalate to a full suite. Flipping this default
        // silently would be a material behaviour change, so pin it in a test.
        assertTrue(ext.getRunAllOnNonJavaChange().get(),
                "runAllOnNonJavaChange must default to true to preserve the safety escalation");
        // Defaults: naming, usage, impl, transitive — transitive is gated by the
        // strategies list (see I9 in the review), not just by transitiveDepth.
        assertEquals(4, ext.getStrategies().get().size());
        assertTrue(ext.getStrategies().get().contains("transitive"));
        assertEquals(2, ext.getTransitiveDepth().get());
        assertEquals(4, ext.getTestSuffixes().get().size());
        assertTrue(ext.getIncludeImplementationTests().get());
        assertEquals(1, ext.getImplementationNaming().get().size());
    }

    @Test
    void taskHasCorrectGroupAndDescription() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        var task = project.getTasks().findByName("affectedTest");
        assertNotNull(task);
        assertEquals("verification", task.getGroup());
        assertNotNull(task.getDescription());
        assertTrue(task.getDescription().contains("affected"));
    }

    @Test
    void taskInputsAreWiredFromExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        // Modify extension
        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        ext.getBaseRef().set("origin/main");
        ext.getTransitiveDepth().set(0);
        ext.getRunAllOnNonJavaChange().set(false);

        // Verify task picks up the change
        AffectedTestTask task = (AffectedTestTask) project.getTasks().findByName("affectedTest");
        assertNotNull(task);
        assertEquals("origin/main", task.getBaseRef().get());
        assertEquals(0, task.getTransitiveDepth().get());
        assertFalse(task.getRunAllOnNonJavaChange().get(),
                "Task input must reflect the extension's runAllOnNonJavaChange override");
    }
}
