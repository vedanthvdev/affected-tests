package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.Situation;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                false,
                false,
                Situation.DISCOVERY_SUCCESS,
                Action.SELECTED,
                EscalationReason.NONE);

        String summary = render(AffectedTestTask.renderSummary(result));

        assertEquals(
                "Affected Tests: 1 changed file(s), 1 production class(es), 2 test class(es) affected",
                summary,
                "Non-runAll summary must spell out the exact selection the downstream test "
                        + "task will receive, so operators can cross-check against the module-routed "
                        + "logs below — and the pluralisation form must match the runAll branch to "
                        + "keep CI greps stable");
    }

    @Test
    void nonJavaEscalationNamesTheRealTrigger() {
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(),
                Map.of(),
                Set.of("src/main/resources/application.yml"),
                Set.of(),
                Set.of(),
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
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);
        AffectedTestsResult normal = new AffectedTestsResult(
                Set.of("com.example.FooTest"),
                Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of(),
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
    void formatPlaceholderCountMatchesArgsLengthOnBothBranches() {
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
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);
        AffectedTestsResult normal = new AffectedTestsResult(
                Set.of("com.example.FooTest"),
                Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of(),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        AffectedTestTask.LogLine escalatedLine = AffectedTestTask.renderSummary(escalated);
        AffectedTestTask.LogLine normalLine = AffectedTestTask.renderSummary(normal);

        assertEquals(countPlaceholders(escalatedLine.format()), escalatedLine.args().length,
                "runAll branch must have one {} per arg so SLF4J's formatter consumes all args");
        assertEquals(countPlaceholders(normalLine.format()), normalLine.args().length,
                "non-runAll branch must have one {} per arg so SLF4J's formatter consumes all args");
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
