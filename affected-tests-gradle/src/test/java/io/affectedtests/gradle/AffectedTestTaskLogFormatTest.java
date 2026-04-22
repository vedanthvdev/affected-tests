package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.AffectedTestsEngine.Buckets;
import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.Situation;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the exact lifecycle-log wording the task produces for every
 * combination of {@code runAll} + {@link EscalationReason}. Without these
 * assertions the "the log lies about why we're running a full suite" class
 * of regressions would only be catchable by reading CI output.
 *
 * <p>Tests render via {@link MessageFormatter} — the same SLF4J machinery
 * Gradle's logger uses — so the assertions pin what the operator sees in
 * CI, not just what our format string happens to look like.
 */
class AffectedTestTaskLogFormatTest {

    private static String render(AffectedTestTask.LogLine line) {
        return MessageFormatter.arrayFormat(line.format(), line.args()).getMessage();
    }

    @Test
    void nonEscalatedSelectionRendersProductionAndTestCounts() {
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest", "com.example.BarTest"),
                Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of(),
                Buckets.empty(),
                false,
                false,
                Situation.DISCOVERY_SUCCESS,
                Action.SELECTED,
                EscalationReason.NONE);

        String summary = render(AffectedTestTask.renderSummary(result));

        assertEquals(
                "Affected Tests: SELECTED (DISCOVERY_SUCCESS) — 1 changed file(s), "
                        + "1 production class(es), 2 test class(es) affected",
                summary,
                "SELECTED summary must name the outcome and the situation up front so the "
                        + "operator can tell at a glance what the engine decided, then spell out "
                        + "the exact selection the downstream test task will receive");
    }

    @Test
    void summaryPrefixNamesOutcomeAndSituationOnEveryBranch() {
        // The Phase 1 close-out contract: every summary line must start
        // with "OUTCOME (SITUATION) —" so CI greps and human readers can
        // bucket runs by outcome without having to parse the tail.
        AffectedTestsResult selected = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                Buckets.empty(),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);
        AffectedTestsResult runAll = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/resources/application.yml"),
                Set.of(), Set.of(),
                Buckets.empty(),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);
        AffectedTestsResult skipped = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("README.md"), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.ALL_FILES_IGNORED, Action.SKIPPED,
                EscalationReason.NONE);

        assertTrue(render(AffectedTestTask.renderSummary(selected))
                        .startsWith("Affected Tests: SELECTED (DISCOVERY_SUCCESS) —"),
                "SELECTED prefix must name both action and situation — previously the summary "
                        + "carried neither, so any operator seeing 'Affected Tests: 1 changed file(s)…' "
                        + "had no idea whether discovery had actually landed or the engine was about "
                        + "to full-suite");
        assertTrue(render(AffectedTestTask.renderSummary(runAll))
                        .startsWith("Affected Tests: FULL_SUITE (UNMAPPED_FILE) —"),
                "FULL_SUITE prefix must name the situation so 'UNMAPPED_FILE vs DISCOVERY_EMPTY vs "
                        + "EMPTY_DIFF' is visible without cross-referencing the escalation reason");
        assertTrue(render(AffectedTestTask.renderSummary(skipped))
                        .startsWith("Affected Tests: SKIPPED (ALL_FILES_IGNORED) —"),
                "SKIPPED branch must also carry the prefix — previously skipped runs printed a "
                        + "separate 'Skipping test execution (...)' line without the outcome vocabulary");
    }

    @Test
    void skippedBranchNamesReasonPhraseForEachSituation() {
        // The SKIPPED branch is new — previously the task emitted a
        // separate side-line. Every non-DISCOVERY_SUCCESS situation
        // must produce a distinct reason phrase so operators can tell
        // "README-only MR" from "api-test-only MR" from "discovery
        // found nothing" at a glance.
        java.util.Map<Situation, String> expected = java.util.Map.of(
                Situation.EMPTY_DIFF,             "no changed files detected",
                Situation.ALL_FILES_IGNORED,      "every changed file matched ignorePaths",
                Situation.ALL_FILES_OUT_OF_SCOPE, "every changed file sat under out-of-scope dirs",
                Situation.UNMAPPED_FILE,          "non-Java or unmapped file in diff",
                Situation.DISCOVERY_EMPTY,        "no affected tests discovered",
                // DISCOVERY_INCOMPLETE + SKIPPED phrase — the action-
                // SELECTED variant has its own distinct rendering and
                // its own regression test below, so this map locks
                // only the SKIPPED branch.
                Situation.DISCOVERY_INCOMPLETE,   "discovery observed unparseable files");

        expected.forEach((situation, phrase) -> {
            AffectedTestsResult skipped = new AffectedTestsResult(
                    Set.of(), Map.of(),
                    Set.of(), Set.of(), Set.of(),
                    Buckets.empty(),
                    false, true,
                    situation, Action.SKIPPED,
                    EscalationReason.NONE);
            String summary = render(AffectedTestTask.renderSummary(skipped));
            assertTrue(summary.contains(phrase),
                    "SKIPPED summary for " + situation + " must contain phrase '" + phrase
                            + "' — operators rely on that substring to distinguish this skip "
                            + "from every other skip reason");
            assertTrue(summary.contains("SKIPPED (" + situation + ")"),
                    "SKIPPED summary must name the situation in the prefix");
        });
    }

    @Test
    void describeSkipReasonRejectsDiscoverySuccess() {
        // DISCOVERY_SUCCESS + SKIPPED is an engine bug — if discovery
        // returned tests we never skip. The helper must fail loudly so
        // such a drift cannot surface a placeholder phrase in CI.
        assertThrows(IllegalStateException.class,
                () -> AffectedTestTask.describeSkipReason(Situation.DISCOVERY_SUCCESS, Action.SKIPPED),
                "describeSkipReason(DISCOVERY_SUCCESS, *) must throw — that combination is an "
                        + "engine contract violation, not a log-formatting concern");
    }

    @Test
    void describeSkipReasonRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> AffectedTestTask.describeSkipReason(null, Action.SKIPPED));
        assertThrows(NullPointerException.class,
                () -> AffectedTestTask.describeSkipReason(Situation.EMPTY_DIFF, null));
    }

    @Test
    void discoveryIncompleteSelectedBranchDoesNotClaimSkipped() {
        // Regression for the B6-#9 summary-line bug: DISCOVERY_INCOMPLETE
        // with action=SELECTED and an empty selection (the LOCAL-mode
        // default) winds up on the skipped-summary branch via
        // emptyResult's skipped-flag rule, but the summary prefix
        // still correctly reads `SELECTED (DISCOVERY_INCOMPLETE)`.
        // Before the action-aware rendering, the reason phrase said
        // `onDiscoveryIncomplete=SKIPPED — ...`, producing a line that
        // contained both `SELECTED` and `SKIPPED` and contradicted
        // itself. Pinning the truthful variant keeps renderer and
        // resolver honest.
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.DISCOVERY_INCOMPLETE, Action.SELECTED,
                EscalationReason.NONE);
        String summary = render(AffectedTestTask.renderSummary(result));

        assertTrue(summary.contains("SELECTED (DISCOVERY_INCOMPLETE)"),
                "Prefix must name the resolved action, not the skipped flag");
        assertFalse(summary.contains("SKIPPED"),
                "Summary must not contain the SKIPPED literal when action=SELECTED; got: " + summary);
        assertTrue(summary.contains("no affected tests matched the parsed files"),
                "Reason phrase must describe the SELECTED+empty outcome honestly; got: " + summary);
    }

    @Test
    void describeSkipReasonBranchesOnActionForDiscoveryIncomplete() {
        // Unit-level pin for the helper directly — protects against a
        // refactor that loses the action branch inside describeSkipReason
        // even if the summary renderer swallows the difference.
        assertEquals(
                "onDiscoveryIncomplete=SKIPPED — discovery observed unparseable files",
                AffectedTestTask.describeSkipReason(Situation.DISCOVERY_INCOMPLETE, Action.SKIPPED));
        assertEquals(
                "onDiscoveryIncomplete=SELECTED — no affected tests matched the parsed files",
                AffectedTestTask.describeSkipReason(Situation.DISCOVERY_INCOMPLETE, Action.SELECTED));
    }

    @Test
    void nonJavaEscalationNamesTheRealTrigger() {
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(),
                Map.of(),
                Set.of("src/main/resources/application.yml"),
                Set.of(),
                Set.of(),
                Buckets.empty(),
                true,
                false,
                Situation.UNMAPPED_FILE,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String summary = render(AffectedTestTask.renderSummary(result));

        assertTrue(summary.contains("running full suite"),
                "A runAll summary must tell the reader we're running the full suite — not the "
                        + "non-runAll count-based line that would say '0 production class(es), 0 test class(es) affected'");
        assertTrue(summary.contains("runAllOnNonJavaChange=true"),
                "The real trigger must appear in the log or the message defeats its own purpose");
        assertTrue(summary.contains("non-Java or unmapped"),
                "Render must explain what runAllOnNonJavaChange actually detected, not just name the flag");
    }

    @Test
    void emptyChangesetEscalationNamesItsOwnTrigger() {
        // Guards the bug the prior amendment still carried: empty changeset +
        // runAllIfNoMatches shared the "no affected tests discovered" phrase
        // with the post-discovery empty branch, even though discovery had
        // never actually run. The two branches must produce distinct phrases.
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Buckets.empty(),
                true,
                false,
                Situation.EMPTY_DIFF,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_EMPTY_CHANGESET);

        String summary = render(AffectedTestTask.renderSummary(result));

        assertTrue(summary.contains("running full suite"));
        assertTrue(summary.contains("runAllIfNoMatches=true"),
                "Empty-changeset escalation is driven by runAllIfNoMatches, so the flag name "
                        + "must surface in the log the same way it does in the config DSL");
        assertTrue(summary.contains("no changed files"),
                "Phrase must say why we escalated — nothing had changed — instead of claiming "
                        + "'no affected tests discovered' which would imply discovery ran");
    }

    @Test
    void postDiscoveryEmptyEscalationDistinguishesItselfFromEmptyChangeset() {
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(),
                Map.of(),
                Set.of("src/main/java/com/example/Orphan.java"),
                Set.of("com.example.Orphan"),
                Set.of(),
                Buckets.empty(),
                true,
                false,
                Situation.DISCOVERY_EMPTY,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_IF_NO_MATCHES);

        String summary = render(AffectedTestTask.renderSummary(result));

        assertTrue(summary.contains("running full suite"));
        assertTrue(summary.contains("runAllIfNoMatches=true"));
        assertTrue(summary.contains("no affected tests discovered"),
                "Post-discovery empty must say discovery ran and returned nothing, so the "
                        + "reader can distinguish this case from the empty-changeset one");
    }

    @Test
    void pluralisationIsConsistentAcrossSummaryLines() {
        AffectedTestsResult escalated = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/resources/application.yml"),
                Set.of(), Set.of(),
                Buckets.empty(),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);
        AffectedTestsResult normal = new AffectedTestsResult(
                Set.of("com.example.FooTest"),
                Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of(),
                Buckets.empty(),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        // Both branches must use the same "file(s)" form; otherwise CI logs
        // drift between `changed file(s)` and `changed files` and a grep for
        // either variant misses half the runs.
        assertTrue(render(AffectedTestTask.renderSummary(escalated)).contains("changed file(s)"));
        assertTrue(render(AffectedTestTask.renderSummary(normal)).contains("changed file(s)"));

        // Same for the count tokens — the non-runAll branch must use the
        // parenthesised form instead of bare plurals.
        assertTrue(render(AffectedTestTask.renderSummary(normal)).contains("production class(es)"));
        assertTrue(render(AffectedTestTask.renderSummary(normal)).contains("test class(es)"));
    }

    @Test
    void formatPlaceholderCountMatchesArgsLengthOnEveryBranch() {
        // Regression guard for the SLF4J-placeholder contract: every `{}`
        // pair in the format string is consumed by exactly one positional
        // arg, and every arg must have a placeholder to render into.
        // A mismatch is the "log ends in {} because the format has two
        // placeholders but only one arg" or "a trailing arg disappears
        // because the format forgot its placeholder" class of bug — both
        // silent at runtime and painful in CI.
        AffectedTestsResult escalated = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/resources/application.yml"),
                Set.of(), Set.of(),
                Buckets.empty(),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);
        AffectedTestsResult normal = new AffectedTestsResult(
                Set.of("com.example.FooTest"),
                Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of(),
                Buckets.empty(),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);
        AffectedTestsResult skipped = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("README.md"), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.ALL_FILES_IGNORED, Action.SKIPPED,
                EscalationReason.NONE);

        for (AffectedTestsResult r : List.of(escalated, normal, skipped)) {
            AffectedTestTask.LogLine line = AffectedTestTask.renderSummary(r);
            assertEquals(countPlaceholders(line.format()), line.args().length,
                    "Summary branch for " + r.action() + "/" + r.situation()
                            + " must have one {} per arg so SLF4J's formatter consumes all args");
        }
    }

    private static int countPlaceholders(String format) {
        int count = 0;
        int idx = 0;
        while ((idx = format.indexOf("{}", idx)) != -1) {
            count++;
            idx += 2;
        }
        return count;
    }

    @Test
    void logLineDefensivelyCopiesIncomingArgsArray() {
        // Records auto-generate constructors that capture Object[] by
        // reference. Without the compact-constructor clone() a caller
        // could mutate the array after construction and silently change
        // what the logger renders on a later call.
        Object[] args = new Object[] { 1, "reason" };
        AffectedTestTask.LogLine line = new AffectedTestTask.LogLine("hello {} {}", args);

        args[0] = 999;
        args[1] = "tampered";

        assertEquals(1, line.args()[0],
                "LogLine must copy its args array so external mutation cannot change the rendered log");
        assertEquals("reason", line.args()[1]);
    }

    @Test
    void logLineArgsAccessorReturnsDefensiveCopy() {
        AffectedTestTask.LogLine line = new AffectedTestTask.LogLine("hello {}", new Object[] { "world" });

        Object[] snapshot = line.args();
        snapshot[0] = "mutated";

        assertEquals("world", line.args()[0],
                "LogLine.args() must hand callers a fresh copy or external mutation leaks inwards");
    }

    @Test
    void describeEscalationCoversEveryEscalationValue() {
        // Pin the contract that every *escalation* reason has a non-blank
        // phrase. NONE is deliberately excluded — it's an illegal input to
        // describeEscalation (see describeEscalationRejectsNONE) and would
        // otherwise dull this test's teeth by green-lighting a placeholder
        // value no user will ever see.
        for (EscalationReason reason : EscalationReason.values()) {
            if (reason == EscalationReason.NONE) {
                continue;
            }
            String phrase = AffectedTestTask.describeEscalation(reason);
            assertTrue(phrase != null && !phrase.isBlank(),
                    "describeEscalation must return a non-empty phrase for " + reason
                            + " — every escalation trigger must have CI-visible wording");
        }
    }

    @Test
    void describeEscalationRejectsNONE() {
        // runAll=true + reason=NONE is an engine bug. Failing loudly here
        // prevents a silent "runAll" placeholder ever reaching CI if a
        // future return path forgets to set a reason.
        assertThrows(IllegalStateException.class,
                () -> AffectedTestTask.describeEscalation(EscalationReason.NONE),
                "describeEscalation(NONE) must throw so the engine contract is enforced at compile + runtime");
    }

    @Test
    void describeEscalationRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> AffectedTestTask.describeEscalation(null));
    }
}
