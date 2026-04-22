package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.AffectedTestsEngine.Buckets;
import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.config.Situation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of the {@code affectedTest --explain} trace so the
 * operator experience — the only visible contract for this flag — cannot
 * regress unnoticed. The renderer is a pure function over
 * {@link AffectedTestsConfig} and {@link AffectedTestsResult}, so the
 * tests call it directly instead of spinning up a Gradle test runtime.
 *
 * <p>Every assertion targets a separate guarantee: the trace carries the
 * bucket breakdown, names the situation, names the action, exposes which
 * tier of the priority ladder picked that action, and always emits the
 * full per-situation matrix so operators can see setting interactions at
 * a glance.
 */
class AffectedTestTaskExplainFormatTest {

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    void traceIncludesHeaderAndFooterFromAnySituation() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.startsWith("=== Affected Tests — decision trace (--explain) ==="),
                "Header must be the first line so operators (and log greps) can pin trace start");
        assertTrue(trace.endsWith("=== end --explain ==="),
                "Footer must close the trace so multi-run CI logs stay parseable");
    }

    @Test
    void bucketBreakdownReportsAllFiveCountsWithSamples() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        Buckets buckets = new Buckets(
                Set.of("README.md"),
                Set.of("api-test/src/test/java/FooSteps.java"),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("src/test/java/com/example/FooTest.java"),
                Set.of("build.gradle", "application.yml"));
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("README.md", "build.gradle", "application.yml",
                        "src/main/java/com/example/Foo.java",
                        "src/test/java/com/example/FooTest.java",
                        "api-test/src/test/java/FooSteps.java"),
                Set.of("com.example.Foo"), Set.of("com.example.FooTest"),
                buckets,
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("ignored         1"),
                "Ignored bucket count must surface — otherwise operators can't tell that a "
                        + "zero-config README-only MR didn't just 'do nothing for no reason'");
        assertTrue(trace.contains("out-of-scope    1"),
                "Out-of-scope count must surface separately from ignored — they come from "
                        + "different configuration surfaces and mixing them would mask bugs");
        assertTrue(trace.contains("production .java 1"));
        assertTrue(trace.contains("test .java      1"));
        assertTrue(trace.contains("unmapped        2"),
                "Unmapped count must always surface so Yaml/Gradle/Liquibase diffs don't hide");
        assertTrue(trace.contains("ignored sample: README.md"),
                "A non-empty bucket must list its sample so the operator sees WHICH file caused it");
    }

    @Test
    void bucketSampleIsTruncatedWithRemainderCount() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        // 15 files > EXPLAIN_SAMPLE_LIMIT (10): the trace must say "+5 more"
        // rather than dumping 15 paths into a single line and burying the
        // signal in CI scrollback.
        java.util.Set<String> many = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 15; i++) {
            many.add("build-scripts/file-" + i + ".gradle");
        }
        Buckets buckets = new Buckets(Set.of(), Set.of(), Set.of(), Set.of(), many);
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                many, Set.of(), Set.of(),
                buckets,
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("(+5 more)"),
                "Truncation suffix must name the exact remainder so a 15-file diff can't "
                        + "masquerade as a 10-file diff in the trace");
    }

    @Test
    void actionSourceSurfacesLegacyBooleanWhenOnlyLegacyFlagIsSet() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .runAllIfNoMatches(false)
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("README.md"), Set.of(), Set.of(),
                new Buckets(Set.of("README.md"), Set.of(), Set.of(), Set.of(), Set.of()),
                false, true,
                Situation.ALL_FILES_IGNORED,
                Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Action:          SKIPPED (source: legacy boolean"),
                "Setting only the legacy boolean must surface as LEGACY_BOOLEAN in the trace, "
                        + "not silently show up as a mode default or explicit setting");
    }

    @Test
    void actionSourceSurfacesExplicitSettingWhenOnXxxWins() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .onAllFilesIgnored(Action.FULL_SUITE)
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("README.md"), Set.of(), Set.of(),
                new Buckets(Set.of("README.md"), Set.of(), Set.of(), Set.of(), Set.of()),
                true, false,
                Situation.ALL_FILES_IGNORED,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_ALL_FILES_IGNORED);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("source: explicit onXxx setting"),
                "Explicit setting must surface distinctly from legacy/mode/hardcoded sources — "
                        + "otherwise the operator can't tell what survives a future default change");
    }

    @Test
    void actionSourceSurfacesModeDefaultWhenOnlyModeIsSet() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/java/com/example/Orphan.java"),
                Set.of("com.example.Orphan"), Set.of(),
                Buckets.empty(),
                true, false,
                Situation.DISCOVERY_EMPTY,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_IF_NO_MATCHES);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("source: mode default"),
                "When only mode is set the trace must name mode as the source, not guess "
                        + "'explicit' — otherwise audits of CI flakiness lose the real lead");
        assertTrue(trace.contains("Mode:            CI (effective: CI)"),
                "Mode line must always render both configured + effective so AUTO resolution "
                        + "stays visible in CI logs");
    }

    @Test
    void actionSourceSurfacesHardcodedDefaultForZeroConfig() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("source: pre-v2 hardcoded default"),
                "Zero-config installs must see the pre-v2 label — so operators know upgrading "
                        + "the plugin's defaults could silently change their behaviour if they "
                        + "do not pin a mode or onXxx setting");
    }

    @Test
    void matrixIncludesEveryNonSuccessSituationInStableOrder() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        // Every Situation must appear in the matrix so operators never have
        // to ask "what would've happened if the diff had been different?" —
        // the trace already answers it.
        for (Situation s : Situation.values()) {
            assertTrue(trace.contains(s.name()),
                    "Matrix must mention every Situation (missing: " + s + ")");
        }

        // Evaluation order is the contract. Each situation must appear
        // before the next one per the Situation javadoc.
        List<Situation> expectedOrder = List.of(
                Situation.EMPTY_DIFF,
                Situation.ALL_FILES_IGNORED,
                Situation.ALL_FILES_OUT_OF_SCOPE,
                Situation.UNMAPPED_FILE,
                Situation.DISCOVERY_EMPTY,
                Situation.DISCOVERY_SUCCESS);
        int previous = -1;
        for (Situation s : expectedOrder) {
            // Use the matrix row prefix (two leading spaces) to avoid
            // matching the "Situation: ..." header line.
            int idx = trace.indexOf("\n  " + s.name());
            assertTrue(idx > previous,
                    "Matrix must list " + s + " after every earlier Situation so the trace "
                            + "mirrors the engine's evaluation order");
            previous = idx;
        }
    }

    @Test
    void outcomeLineDistinguishesFullSuiteSkippedAndSelected() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();

        AffectedTestsResult runAll = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("build.gradle"), Set.of(), Set.of(),
                new Buckets(Set.of(), Set.of(), Set.of(), Set.of(), Set.of("build.gradle")),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);
        AffectedTestsResult skipped = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("README.md"), Set.of(), Set.of(),
                new Buckets(Set.of("README.md"), Set.of(), Set.of(), Set.of(), Set.of()),
                false, true,
                Situation.ALL_FILES_IGNORED, Action.SKIPPED,
                EscalationReason.NONE);
        AffectedTestsResult selected = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                Buckets.empty(),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String runAllTrace = joined(AffectedTestTask.renderExplainTrace(config, runAll));
        String skippedTrace = joined(AffectedTestTask.renderExplainTrace(config, skipped));
        String selectedTrace = joined(AffectedTestTask.renderExplainTrace(config, selected));

        assertTrue(runAllTrace.contains("Outcome:         FULL_SUITE"),
                "FULL_SUITE outcome must name itself so the reader never has to infer from counts");
        assertTrue(runAllTrace.contains("non-Java or unmapped"),
                "FULL_SUITE outcome must carry the same phrase as the summary line so CI greps stay stable");
        assertTrue(skippedTrace.contains("Outcome:         SKIPPED"),
                "Skipped outcome must say so explicitly — ambiguous w/ 'SELECTED with empty selection' otherwise");
        assertTrue(selectedTrace.contains("1 test class(es) will run"),
                "Selected outcome must name the actual dispatch size, not just 'SELECTED'");
        // Narrow match to the Outcome line specifically — the matrix below
        // it legitimately prints FULL_SUITE for other situations and we do
        // not want to assert on that here.
        assertFalse(selectedTrace.contains("Outcome:         FULL_SUITE"),
                "SELECTED result must not render FULL_SUITE on the Outcome line");
    }
}
