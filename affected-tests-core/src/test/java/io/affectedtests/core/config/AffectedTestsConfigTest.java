package io.affectedtests.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AffectedTestsConfigTest {

    @Test
    void defaultsAreApplied() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();

        assertEquals("origin/master", config.baseRef());
        // v1.10.x flip: the core builder now defaults to COMMITTED-ONLY
        // (both flags false). Rationale lives in the release notes and
        // AffectedTestsPlugin#apply — picking CI semantics as the
        // default, with WIP inclusion as an explicit opt-in, keeps a
        // programmatic run on the same HEAD deterministic.
        assertFalse(config.includeUncommitted(),
                "Core builder default must be COMMITTED-ONLY as of the v1.9.14 → next-release flip");
        assertFalse(config.includeStaged(),
                "Core builder default must be COMMITTED-ONLY as of the v1.9.14 → next-release flip");
        // Pre-v2 legacy defaults preserved 1:1 for zero-config callers —
        // the getters below read the raw configured value (or the
        // hardcoded pre-v2 default when unset), not the resolved
        // per-situation action, so the assertions stay deterministic
        // regardless of whether the test runs in CI or on a laptop.
        assertFalse(config.runAllIfNoMatches());
        assertTrue(config.runAllOnNonJavaChange());
        assertEquals(Set.of("naming", "usage", "impl", "transitive"), config.strategies());
        // v2 raises the default from 2 to 4: real ctrl→svc→repo→mapper
        // chains sit 3-4 deep so 2 dropped coverage on zero-config.
        assertEquals(4, config.transitiveDepth());
        assertEquals(List.of("Test", "IT", "ITTest", "IntegrationTest"), config.testSuffixes());
        assertEquals(List.of("src/main/java"), config.sourceDirs());
        assertEquals(List.of("src/test/java"), config.testDirs());
        // v2 widens the default ignore list from "generated/**" only to a
        // broader "things that can't possibly affect tests" baseline
        // (markdown, images, licence/changelog). Assertion checks a
        // representative subset; tests that care about exact contents
        // should inspect {@link AffectedTestsConfig.Builder#DEFAULT_IGNORE_PATHS}.
        assertTrue(config.ignorePaths().contains("**/generated/**"));
        assertTrue(config.ignorePaths().contains("**/*.md"));
        assertEquals(config.ignorePaths(), config.excludePaths(),
                "excludePaths must alias ignorePaths in v2 — callers can't read two diverging lists");
        assertTrue(config.includeImplementationTests());
        assertEquals(List.of("Impl", "Default"), config.implementationNaming());
        assertEquals(List.of(), config.outOfScopeTestDirs());
        assertEquals(List.of(), config.outOfScopeSourceDirs());
        assertEquals(Mode.AUTO, config.mode());
        // Zero-config callers get pre-v2 situation actions from the
        // hard-coded defaults — NOT from mode detection — so the result
        // is deterministic whether or not $CI is set.
        assertEquals(Action.SKIPPED, config.actionFor(Situation.EMPTY_DIFF));
        assertEquals(Action.SKIPPED, config.actionFor(Situation.DISCOVERY_EMPTY));
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.UNMAPPED_FILE));
        assertEquals(Action.SELECTED, config.actionFor(Situation.DISCOVERY_SUCCESS));
    }

    @Test
    void customValuesArePreserved() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef("origin/main")
                .includeUncommitted(false)
                .includeStaged(false)
                .runAllIfNoMatches(true)
                .strategies(Set.of("naming"))
                .transitiveDepth(0)
                .testSuffixes(List.of("Test"))
                .sourceDirs(List.of("src/main/java", "src/main/kotlin"))
                .testDirs(List.of("src/test/java", "src/test/kotlin"))
                .excludePaths(List.of("**/generated/**", "**/*Dto.java"))
                .includeImplementationTests(false)
                .implementationNaming(List.of("Impl", "Default"))
                .build();

        assertEquals("origin/main", config.baseRef());
        assertFalse(config.includeUncommitted());
        assertFalse(config.includeStaged());
        assertTrue(config.runAllIfNoMatches());
        assertEquals(Set.of("naming"), config.strategies());
        assertEquals(0, config.transitiveDepth());
        assertEquals(List.of("Test"), config.testSuffixes());
        assertEquals(2, config.sourceDirs().size());
        assertEquals(2, config.testDirs().size());
        assertEquals(2, config.excludePaths().size());
        assertFalse(config.includeImplementationTests());
        assertEquals(2, config.implementationNaming().size());
    }

    @Test
    void transitiveDepthIsClamped() {
        AffectedTestsConfig high = AffectedTestsConfig.builder().transitiveDepth(10).build();
        assertEquals(5, high.transitiveDepth());

        AffectedTestsConfig negative = AffectedTestsConfig.builder().transitiveDepth(-1).build();
        assertEquals(0, negative.transitiveDepth());
    }

    @Test
    void explicitSituationActionWinsOverLegacyBooleans() {
        // Explicit on* setter beats the legacy-boolean translation — a
        // user migrating to v2 must be able to override a single branch
        // without having to clear the legacy booleans first.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .runAllIfNoMatches(true)
                .onDiscoveryEmpty(Action.SKIPPED)
                .build();
        assertEquals(Action.SKIPPED, config.actionFor(Situation.DISCOVERY_EMPTY));
        // Sibling situations driven by the same legacy boolean still
        // follow the legacy translation.
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.EMPTY_DIFF));
    }

    @Test
    void legacyBooleanBeatsModeDefault() {
        // Explicit legacy boolean beats the per-mode defaults so a CI
        // user who set runAllIfNoMatches=false + mode=CI genuinely gets
        // "skip" on empty diff instead of the CI safety-net full run.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .runAllIfNoMatches(false)
                .mode(Mode.CI)
                .build();
        assertEquals(Action.SKIPPED, config.actionFor(Situation.DISCOVERY_EMPTY));
    }

    @Test
    void modeCiEscalatesDiscoveryEmptyByDefault() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.DISCOVERY_EMPTY));
        assertEquals(Action.SKIPPED, config.actionFor(Situation.EMPTY_DIFF));
        assertEquals(Action.SKIPPED, config.actionFor(Situation.ALL_FILES_OUT_OF_SCOPE));
        assertEquals(Mode.CI, config.effectiveMode());
    }

    @Test
    void modeStrictEscalatesEverythingAmbiguous() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.STRICT)
                .build();
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.EMPTY_DIFF));
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.ALL_FILES_IGNORED));
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.UNMAPPED_FILE));
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.DISCOVERY_EMPTY));
        // ...except out-of-scope, which is always SKIPPED even in STRICT:
        // escalating it would fight the whole point of the knob.
        assertEquals(Action.SKIPPED, config.actionFor(Situation.ALL_FILES_OUT_OF_SCOPE));
    }

    @Test
    void runAllOnNonJavaChangeFalseTranslatesToSelected() {
        // Pre-v2 callers that opted out of the safety net expected
        // discovery to still run against whatever Java was in the diff.
        // SKIPPED would regress that contract silently.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .runAllOnNonJavaChange(false)
                .build();
        assertEquals(Action.SELECTED, config.actionFor(Situation.UNMAPPED_FILE));
    }

    @Test
    void actionSourceReflectsResolutionTierOrdering() {
        // Zero-config → hardcoded pre-v2 default. This is the baseline
        // every other tier overrides; pinning it here prevents a future
        // refactor from silently bumping zero-config users to a mode
        // default when they never set one.
        AffectedTestsConfig baseline = AffectedTestsConfig.builder().build();
        assertEquals(ActionSource.HARDCODED_DEFAULT,
                baseline.actionSourceFor(Situation.EMPTY_DIFF));
        assertEquals(ActionSource.HARDCODED_DEFAULT,
                baseline.actionSourceFor(Situation.UNMAPPED_FILE));

        // Setting mode should flip all unpinned situations to MODE_DEFAULT —
        // the only escape hatch is an explicit onXxx or a legacy boolean.
        AffectedTestsConfig modeOnly = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();
        assertEquals(ActionSource.MODE_DEFAULT,
                modeOnly.actionSourceFor(Situation.DISCOVERY_EMPTY));

        // Legacy boolean must win over mode, or the shim would lie about
        // what the caller asked for.
        AffectedTestsConfig legacyOverMode = AffectedTestsConfig.builder()
                .mode(Mode.STRICT)
                .runAllIfNoMatches(false)
                .build();
        assertEquals(ActionSource.LEGACY_BOOLEAN,
                legacyOverMode.actionSourceFor(Situation.DISCOVERY_EMPTY));

        // Explicit onXxx must win over both legacy and mode — this is the
        // only tier that's guaranteed to survive future default changes
        // and the --explain flag has to be honest about that.
        AffectedTestsConfig explicitOverEverything = AffectedTestsConfig.builder()
                .mode(Mode.STRICT)
                .runAllIfNoMatches(false)
                .onDiscoveryEmpty(Action.FULL_SUITE)
                .build();
        assertEquals(ActionSource.EXPLICIT,
                explicitOverEverything.actionSourceFor(Situation.DISCOVERY_EMPTY));

        // DISCOVERY_SUCCESS is hardcoded SELECTED regardless of
        // configuration — that contract is reported as EXPLICIT because
        // no default can change it.
        assertEquals(ActionSource.EXPLICIT,
                baseline.actionSourceFor(Situation.DISCOVERY_SUCCESS));
    }

    @Test
    void ignorePathsAliasesExcludePaths() {
        AffectedTestsConfig byExclude = AffectedTestsConfig.builder()
                .excludePaths(List.of("**/*.gen.java"))
                .build();
        assertEquals(List.of("**/*.gen.java"), byExclude.ignorePaths());
        assertEquals(byExclude.ignorePaths(), byExclude.excludePaths());

        // When both are set the new name wins.
        AffectedTestsConfig both = AffectedTestsConfig.builder()
                .excludePaths(List.of("old"))
                .ignorePaths(List.of("new"))
                .build();
        assertEquals(List.of("new"), both.ignorePaths());
    }

    @Test
    void baseRefRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("   ").build());
    }

    @Test
    void baseRefRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("../../etc/passwd").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("refs/../../../secrets").build());
    }

    @Test
    void baseRefRejectsControlCharsInReflogSuffix() {
        // Regression for v1.9.21 #14: the REV_EXPR pattern's reflog
        // segment is {@code @\{[^}]+\}}, which happily matches
        // newline / ESC / CSI / DEL. Pre-fix these inputs passed
        // {@link AffectedTestsConfig.Builder#isAcceptableBaseRef} and
        // then flowed unsanitised into
        //   log.info("Base ref: {}", config.baseRef())
        // inside AffectedTestsEngine and into three IllegalStateException
        // messages in GitChangeDetector — a log-forgery surface when
        // baseRef came from an attacker-controlled CI env var.
        //
        // The fix is a blanket control-char rejection at the entry gate
        // of isAcceptableBaseRef, so every downstream matcher (short
        // SHA, refname, rev-expression) refuses tainted input.
        String fakePluginStatus = "master@{1\n"
                + "\u001b[2JAffected Tests: SELECTED (DISCOVERY_SUCCESS) — 0 tests\u001b[m}";
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef(fakePluginStatus).build(),
                "baseRef containing newline + ANSI inside @{…} must be rejected at "
                        + "validation time; otherwise an attacker could forge plugin-branded "
                        + "CI status lines by poisoning CI_BASE_REF");
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef("master@{1\n}").build(),
                "Bare newline in reflog segment must also be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef("master@{\u001b}").build(),
                "Bare ESC in reflog segment must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef("master@{\u007F}").build(),
                "Bare DEL in reflog segment must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef("master\r\noops").build(),
                "CRLF anywhere in the baseRef must be rejected pre-regex");
    }

    @Test
    void baseRefValidationErrorDoesNotEchoRawControlChars() {
        // Regression for v1.9.21 review finding T2-1: the reject branch of
        // isAcceptableBaseRef throws IllegalArgumentException whose message
        // embeds the offending baseRef. Gradle renders that message verbatim
        // into the build log (and into build-scan HTML), so echoing raw
        // newline/ESC/CSI/DEL would reintroduce the exact log-forgery
        // surface that #14 closed on the accept path — just via the
        // validation-failure path instead of log.info("Base ref: …").
        //
        // LogSanitizer must wrap the echoed value so the thrown message
        // contains no C0, C1, or DEL characters.
        String tainted = "master@{1\n\u001b[2JAffected Tests: SELECTED\u001b[m}";
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef(tainted).build());
        String message = thrown.getMessage();
        assertNotNull(message, "exception must carry a diagnostic message");
        // Each forbidden byte class gets its own assertion so a regression
        // that partially re-opens the surface (say, closes newline but leaks
        // ESC) still fails loudly with a pointed message.
        assertFalse(message.indexOf('\n') >= 0,
                "baseRef validation message must not echo raw newline: " + debugEscape(message));
        assertFalse(message.indexOf('\r') >= 0,
                "baseRef validation message must not echo raw CR: " + debugEscape(message));
        assertFalse(message.indexOf('\u001b') >= 0,
                "baseRef validation message must not echo raw ESC: " + debugEscape(message));
        assertFalse(message.indexOf('\u007F') >= 0,
                "baseRef validation message must not echo raw DEL: " + debugEscape(message));
    }

    private static String debugEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Test
    void baseRefAllowsValidRefs() {
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("origin/master").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("origin/main").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("abc123def456").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("refs/heads/feature-branch").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("HEAD~3").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("HEAD").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("HEAD^").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("master~2").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("a".repeat(40)).build());
    }

    @Test
    void baseRefRejectsRefnameSlurGarbage() {
        // Regression: the v1.x check accepted anything without a `/..` in
        // it. JGit's refname rules reject a far richer set of garbage
        // (control chars, @{} outside rev-expressions, trailing `.lock`,
        // leading `/`, etc.). Lock in that those shapes are refused at
        // the builder boundary so downstream JGit calls never see them.
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("/leading-slash").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("trailing.lock").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("contains\nnewline").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("..").build());
    }

    @Test
    void builderRejectsNullCollections() {
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().strategies(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().testSuffixes(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().sourceDirs(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().testDirs(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().excludePaths(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().implementationNaming(null).build());
    }

    @Test
    void configIsImmutable() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();

        assertThrows(UnsupportedOperationException.class, () ->
                config.testSuffixes().add("Spec"));
        assertThrows(UnsupportedOperationException.class, () ->
                config.excludePaths().add("foo"));
    }

    // -----------------------------------------------------------------
    // Phase 2 — deprecation warnings for legacy v1 knobs.
    //
    // The warnings exist to nudge callers onto the v2 config without
    // breaking their build. The core guarantee is: *only* explicit uses
    // of a legacy setter trigger a warning, and every warning names the
    // replacement knob so operators can fix their build.gradle without
    // reading the docs first.
    // -----------------------------------------------------------------

    @Test
    void deprecationWarningsAreEmptyForZeroConfigBuild() {
        // Zero-config install must not emit warnings even though the
        // effective config still resolves via the legacy-boolean shim:
        // the warning tracks *caller intent* (did they type the old
        // name), not the resolution path the engine walked.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        assertTrue(config.deprecationWarnings().isEmpty(),
                "Zero-config installs must not spam a deprecation warning — "
                        + "the legacy boolean shim is implementation detail, "
                        + "the caller never typed runAllIfNoMatches anywhere");
    }

    @Test
    void deprecationWarningFiresWhenLegacyRunAllIfNoMatchesIsSet() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .runAllIfNoMatches(false)
                .build();
        String warning = singleWarning(config,
                "runAllIfNoMatches must produce exactly one warning");
        assertTrue(warning.contains("runAllIfNoMatches"),
                "Warning must name the deprecated knob so a build.gradle 'grep' can "
                        + "find it: " + warning);
        assertTrue(warning.contains("onEmptyDiff")
                        && warning.contains("onDiscoveryEmpty"),
                "Warning must name the v2 replacements so the operator can migrate "
                        + "from the log alone, without opening the docs: " + warning);
    }

    @Test
    void deprecationWarningFiresWhenLegacyRunAllOnNonJavaChangeIsSet() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .runAllOnNonJavaChange(true)
                .build();
        String warning = singleWarning(config,
                "runAllOnNonJavaChange must produce exactly one warning");
        assertTrue(warning.contains("runAllOnNonJavaChange"),
                "Warning must name the deprecated knob: " + warning);
        assertTrue(warning.contains("onUnmappedFile"),
                "Warning must name the v2 replacement (onUnmappedFile): " + warning);
    }

    @Test
    void deprecationWarningFiresWhenLegacyExcludePathsIsSet() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .excludePaths(List.of("**/generated/**"))
                .build();
        String warning = singleWarning(config,
                "excludePaths must produce exactly one warning");
        assertTrue(warning.contains("excludePaths"));
        assertTrue(warning.contains("ignorePaths"),
                "Warning must name the rename target (ignorePaths): " + warning);
    }

    @Test
    void deprecationWarningsListThreeEntriesWhenAllLegacyKnobsAreSet() {
        // All three legacy knobs at once — the log must name each one
        // individually so a caller with a chain of legacy config lines
        // sees a full audit, not just the first deprecation.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .runAllIfNoMatches(true)
                .runAllOnNonJavaChange(true)
                .excludePaths(List.of("**/generated/**"))
                .build();
        assertEquals(3, config.deprecationWarnings().size(),
                "Three legacy knobs set => three distinct warnings");
        // Stable order keeps CI log greps deterministic across runs.
        assertTrue(config.deprecationWarnings().get(0).contains("runAllIfNoMatches"));
        assertTrue(config.deprecationWarnings().get(1).contains("runAllOnNonJavaChange"));
        assertTrue(config.deprecationWarnings().get(2).contains("excludePaths"));
    }

    @Test
    void deprecationWarningsAreUnmodifiable() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .runAllIfNoMatches(true)
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> config.deprecationWarnings().add("mutated"),
                "List must be unmodifiable so the Gradle task cannot accidentally "
                        + "leak mutations to a shared config instance");
    }

    @Test
    void deprecationWarningsDoNotFireForV2KnobsEvenWhenSet() {
        // v2-native config: mode, onXxx, ignorePaths, outOfScope*.
        // None of these should trigger a deprecation warning.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .onUnmappedFile(Action.FULL_SUITE)
                .onDiscoveryEmpty(Action.FULL_SUITE)
                .onAllFilesIgnored(Action.SKIPPED)
                .ignorePaths(List.of("**/generated/**", "*.md"))
                .outOfScopeTestDirs(List.of("api-test/src/test/java"))
                .build();
        assertTrue(config.deprecationWarnings().isEmpty(),
                "v2-native config must never emit a deprecation — "
                        + "otherwise migrators have nothing to aim for");
    }

    private static String singleWarning(AffectedTestsConfig config, String message) {
        assertEquals(1, config.deprecationWarnings().size(), message);
        return config.deprecationWarnings().get(0);
    }

    @Test
    void effectiveModeIsAlwaysConcreteForZeroConfigCallers() {
        // Regression: pre-fix, a zero-config caller got mode()=AUTO and
        // effectiveMode()=null, despite the javadoc's "Always one of
        // LOCAL, CI or STRICT" contract. Any downstream code following
        // the documented shape (`switch(config.effectiveMode())`) would
        // NPE. The fix resolves null to the AUTO-detected mode at the
        // getter.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef("origin/master")
                .build();
        assertNotNull(config.effectiveMode(),
                "effectiveMode() must never return null — it is the documented "
                        + "input to operator-facing switches on the resolved mode");
        assertTrue(config.effectiveMode() == Mode.LOCAL
                        || config.effectiveMode() == Mode.CI
                        || config.effectiveMode() == Mode.STRICT,
                "effectiveMode() must be one of the three concrete modes");
    }
}
