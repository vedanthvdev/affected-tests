package io.affectedtests.gradle;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void legacyKnobAssignmentThrowsWithV2MigrationHint_runAllIfNoMatches() {
        // S10 polish (v2.1): a v1 user dropping `runAllIfNoMatches = false`
        // into their build.gradle used to hit Gradle's generic
        // "unknown property" error, which names the knob but offers no
        // v2 replacement. The shim setter should replace that with a
        // targeted message pointing at the exact onXxx knob that took
        // over each responsibility, so the v1 build.gradle becomes
        // fixable without grepping the CHANGELOG.
        AffectedTestsExtension ext = freshExtension();

        GradleException ex = assertThrows(GradleException.class,
                () -> ext.setRunAllIfNoMatches(false));
        assertTrue(ex.getMessage().contains("runAllIfNoMatches"),
                "Error must name the removed v1 knob so grep-based alerting still locates it");
        assertTrue(ex.getMessage().contains("onEmptyDiff")
                        && ex.getMessage().contains("onDiscoveryEmpty"),
                "Error must name BOTH v2 replacement knobs — v1's single boolean split into two per-situation actions in v2");
        assertTrue(ex.getMessage().contains("v2.0") || ex.getMessage().contains("CHANGELOG"),
                "Error must point at the CHANGELOG/v2 migration doc");
    }

    @Test
    void legacyKnobAssignmentThrowsWithV2MigrationHint_runAllOnNonJavaChange() {
        AffectedTestsExtension ext = freshExtension();

        GradleException ex = assertThrows(GradleException.class,
                () -> ext.setRunAllOnNonJavaChange(true));
        assertTrue(ex.getMessage().contains("runAllOnNonJavaChange"));
        assertTrue(ex.getMessage().contains("onUnmappedFile"),
                "Error must name the direct v2 replacement so the fix is mechanical");
    }

    @Test
    void legacyKnobAssignmentThrowsWithV2MigrationHint_excludePaths() {
        AffectedTestsExtension ext = freshExtension();

        GradleException ex = assertThrows(GradleException.class,
                () -> ext.setExcludePaths(List.of("**/build/**")));
        assertTrue(ex.getMessage().contains("excludePaths"));
        // excludePaths is the only knob whose v1 semantics straddled two
        // v2 knobs — ignorePaths (never influences selection) vs
        // outOfScopeTestDirs (test dirs the affectedTest task won't
        // dispatch). The migration hint must name both so the operator
        // can pick the right one rather than guessing.
        assertTrue(ex.getMessage().contains("ignorePaths"));
        assertTrue(ex.getMessage().contains("outOfScopeTestDirs"));
    }

    @Test
    void negativeGradlewTimeoutFailsAtConfigurationTime() {
        // S11 polish (v2.1): the range check used to live in the task
        // builder so an invalid value only blew up when someone actually
        // ran `./gradlew affectedTest`. Move it into the plugin's
        // afterEvaluate so IDE sync and `./gradlew help` surface the
        // misconfiguration immediately. Builder-side check stays as
        // belt-and-braces and is covered by
        // AffectedTestsConfigTest#builderRejectsNegativeGradlewTimeout.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        ext.getGradlewTimeoutSeconds().set(-5L);

        GradleException ex = assertThrows(GradleException.class,
                () -> ((ProjectInternal) project).evaluate());
        // ProjectInternal#evaluate wraps afterEvaluate failures in a
        // ProjectConfigurationException with a generic "A problem
        // occurred evaluating..." header. The real validation message
        // lives on the cause — walk the chain.
        String message = collectMessages(ex);
        assertTrue(message.contains("gradlewTimeoutSeconds"),
                "Error must name the misconfigured knob (saw: " + message + ")");
        assertTrue(message.contains("-5"),
                "Error must echo the rejected value so the operator can locate it in build.gradle (saw: " + message + ")");
        assertTrue(message.contains(">= 0"),
                "Error must state the valid range — bare rejection without a bound is a support ticket (saw: " + message + ")");
    }

    private static String collectMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        while (current != null) {
            if (sb.length() > 0) sb.append(" || ");
            sb.append(current.getMessage());
            current = current.getCause();
        }
        return sb.toString();
    }

    private static AffectedTestsExtension freshExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");
        return project.getExtensions().getByType(AffectedTestsExtension.class);
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
