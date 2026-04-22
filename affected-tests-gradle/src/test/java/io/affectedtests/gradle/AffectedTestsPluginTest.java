package io.affectedtests.gradle;

import io.affectedtests.core.config.AffectedTestsConfig;
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
        ext.getOnUnmappedFile().set("SELECTED");

        // Verify task picks up the change
        AffectedTestTask task = (AffectedTestTask) project.getTasks().findByName("affectedTest");
        assertNotNull(task);
        assertEquals("origin/main", task.getBaseRef().get());
        assertEquals(0, task.getTransitiveDepth().get());
        assertEquals("SELECTED", task.getOnUnmappedFile().get(),
                "Task input must reflect the extension's onUnmappedFile override");
    }

    @Test
    void extensionExposesOnDiscoveryIncompleteAndGradlewTimeout() {
        // Regression for B6-#9 + B6-#11 DSL wiring: missing setters
        // in AffectedTestsExtension would compile-break the build but
        // a typo between the extension and the task ( `getOnDiscoveryIncomplete`
        // vs `getOnDiscoveryIncompleteAction`) would only show up at
        // runtime when a user configured the DSL. Pin the names and
        // round-trip the values so that refactor doesn't silently
        // drop the wiring.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        ext.getOnDiscoveryIncomplete().set("SKIPPED");
        ext.getGradlewTimeoutSeconds().set(1800L);

        AffectedTestTask task = (AffectedTestTask) project.getTasks().findByName("affectedTest");
        assertNotNull(task);
        assertEquals("SKIPPED", task.getOnDiscoveryIncomplete().get(),
                "Task must expose the extension's onDiscoveryIncomplete setting byte-for-byte");
        assertEquals(1800L, task.getGradlewTimeoutSeconds().get(),
                "Task must expose the extension's gradlewTimeoutSeconds setting byte-for-byte");
    }

    @Test
    void gradlewTimeoutAndDiscoveryIncompleteAreUnconfiguredByDefault() {
        // Defaults must stay absent — presence-based defaulting is how
        // the core config builder distinguishes "user didn't say"
        // (fall through to mode defaults) from "user set 0" (explicit
        // no-timeout). Installing a convention would collapse those
        // two cases and silently change mode-default behaviour.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        assertFalse(ext.getOnDiscoveryIncomplete().isPresent(),
                "onDiscoveryIncomplete must have no convention so the core resolver picks the mode default");
        assertFalse(ext.getGradlewTimeoutSeconds().isPresent(),
                "gradlewTimeoutSeconds must have no convention so the task branch between watchdog/exec paths stays correct");
    }

    @Test
    void legacyDslKnobsNoLongerExistInV2() {
        // v2.0 breaking change: the three v1 knobs (`runAllIfNoMatches`,
        // `runAllOnNonJavaChange`, `excludePaths`) were removed entirely
        // after being deprecated across the v1.9.x line. This test locks
        // in their absence so a well-intentioned "restore the getter for
        // back-compat" revert gets caught at build time instead of
        // silently re-opening the v1 API surface and the deprecation
        // warnings it came with.
        //
        // We check reflectively rather than calling the accessors
        // directly (which would turn into a compile error the moment
        // someone re-adds them, masking the intent of the test). The
        // contract is: none of the pre-v2 accessors exist anywhere on
        // the public surface in v2+ — not on the extension, not on the
        // task, not on the config, not on the builder.
        java.util.List<String> forbiddenAccessors = java.util.List.of(
                "getRunAllIfNoMatches",
                "getRunAllOnNonJavaChange",
                "getExcludePaths"
        );
        assertAllAbsent(AffectedTestsExtension.class, forbiddenAccessors,
                "extension");
        assertAllAbsent(AffectedTestTask.class, forbiddenAccessors,
                "task");

        // On the core config type the legacy accessors were unprefixed
        // (`runAllIfNoMatches()`, not `getRunAllIfNoMatches()`), and
        // `deprecationWarnings()` was its own list we also deleted — if
        // any of them come back the two-tier resolver docs are
        // immediately out of sync with reality.
        java.util.List<String> forbiddenConfigAccessors = java.util.List.of(
                "runAllIfNoMatches",
                "runAllOnNonJavaChange",
                "excludePaths",
                "deprecationWarnings"
        );
        assertAllAbsent(AffectedTestsConfig.class, forbiddenConfigAccessors,
                "config");

        // And the three Builder setters that fed those accessors. We
        // pin them separately because a partial revert that restores
        // the builder-side without the getter-side would be just as
        // bad — it would silently accept the v1 DSL in Groovy while
        // doing nothing with it.
        java.util.List<String> forbiddenBuilderSetters = java.util.List.of(
                "runAllIfNoMatches",
                "runAllOnNonJavaChange",
                "excludePaths"
        );
        assertAllAbsent(AffectedTestsConfig.Builder.class, forbiddenBuilderSetters,
                "config builder");
    }

    private static void assertAllAbsent(Class<?> type,
                                        java.util.List<String> forbiddenNames,
                                        String surfaceLabel) {
        for (String name : forbiddenNames) {
            boolean stillDeclared = java.util.Arrays.stream(type.getMethods())
                    .anyMatch(m -> m.getName().equals(name));
            assertFalse(stillDeclared,
                    type.getSimpleName() + "." + name + "() must stay removed in v2 — "
                            + "bringing it back on the " + surfaceLabel + " surface reopens "
                            + "the v1 back-compat path and breaks the migration documented "
                            + "in the CHANGELOG");
        }
    }
}
