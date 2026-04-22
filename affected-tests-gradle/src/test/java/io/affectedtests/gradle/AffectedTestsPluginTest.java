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
        // Default is now COMMITTED-ONLY. Matches CI semantics exactly
        // (where the tree is already clean after checkout anyway) and
        // gives local `./gradlew affectedTest` runs the same test
        // selection the operator's MR will get in CI. Devs who want WIP
        // to seed the diff opt in explicitly — see CHANGELOG for the
        // v1.9.14 → next-release behaviour flip.
        assertFalse(ext.getIncludeUncommitted().get(),
                "Default must be COMMITTED-ONLY so local runs match CI — WIP inclusion is an explicit opt-in");
        assertFalse(ext.getIncludeStaged().get(),
                "Default must be COMMITTED-ONLY so local runs match CI — staged-index inclusion is an explicit opt-in");
        // v2: no convention for the legacy booleans — leaving them unset
        // is the signal the core builder uses to fall through to
        // mode-based defaults. Zero-config users still observe pre-v2
        // behaviour because the builder's hard-coded fallbacks match the
        // old convention values 1:1 (see AffectedTestsConfigTest).
        assertFalse(ext.getRunAllIfNoMatches().isPresent(),
                "v2 must not install a convention for runAllIfNoMatches; the core config resolver does the translation");
        assertFalse(ext.getRunAllOnNonJavaChange().isPresent(),
                "v2 must not install a convention for runAllOnNonJavaChange; the core config resolver does the translation");
        assertEquals(4, ext.getStrategies().get().size());
        assertTrue(ext.getStrategies().get().contains("transitive"));
        // v2 raises the default transitive depth from 2 to 4 — real-world
        // ctrl -> svc -> repo -> mapper chains sit at 3-4 levels, so 2
        // silently dropped coverage on zero-config installs.
        assertEquals(4, ext.getTransitiveDepth().get());
        assertEquals(4, ext.getTestSuffixes().get().size());
        assertTrue(ext.getIncludeImplementationTests().get());
        // v2 adds the "Default" prefix pattern alongside "Impl" so
        // DefaultFooService → FooService is also picked up by the
        // implementation strategy on zero-config installs.
        assertEquals(2, ext.getImplementationNaming().get().size());
        assertTrue(ext.getImplementationNaming().get().contains("Impl"));
        assertTrue(ext.getImplementationNaming().get().contains("Default"));
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
