package io.affectedtests.gradle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lifecycle-level dispatch preview emitted when
 * {@code affectedTest} resolves to {@link io.affectedtests.core.config.Action#SELECTED}.
 *
 * <p>Before this renderer existed the task printed only the module
 * summary line
 * ({@code "  application:test (47 test classes)"}) at lifecycle level
 * and every individual FQN at info level. That made the v1.9.17
 * sanity-test pass on security-service Finding 3: an operator
 * scrolling the default CI log could see the dispatch size but had to
 * either rerun with {@code --info} (slow) or open the JUnit report
 * (after the fact) to learn which tests actually ran. The preview
 * gives the first {@link AffectedTestTask#LIFECYCLE_FQN_PREVIEW_LIMIT}
 * FQNs at lifecycle level with a "… and N more (use --info for full
 * list)" tail on larger dispatches, keeping the default log
 * diagnosable without risking the 4 MiB step cap on GitHub Actions
 * that forced the info-level demotion in the first place.
 */
class AffectedTestTaskDispatchPreviewTest {

    @Test
    void emitsSummaryOnlyForEmptyDispatch() {
        // The SELECTED branch should never reach this helper with an
        // empty list — but if the caller ever does, we still produce
        // exactly the summary line and nothing else. Pinning this
        // shape prevents an accidental IndexOutOfBounds if a future
        // caller passes the empty set without its own guard.
        List<String> lines = AffectedTestTask.renderLifecycleDispatchPreview(
                "application:test", List.of());

        assertEquals(1, lines.size(),
                "Empty dispatch must produce one line — the summary — and no preview or tail");
        assertEquals("  application:test (0 test classes)", lines.get(0),
                "Summary line must use plural form even for zero to keep the format stable");
    }

    @Test
    void singletonDispatchPrintsSummaryAndOneFqnWithoutTail() {
        // One-class dispatch: summary uses singular "class", FQN is
        // printed at lifecycle level, and there is no "… and N more"
        // tail because the preview covers the whole list.
        List<String> lines = AffectedTestTask.renderLifecycleDispatchPreview(
                "application:test", List.of("com.example.FooTest"));

        assertEquals(2, lines.size(),
                "Singleton dispatch must produce summary + FQN only — no tail");
        assertTrue(lines.get(0).contains("(1 test class)"),
                "Singleton must use singular 'test class' — plural mistake is the kind of "
                        + "paper-cut that dents trust in log output");
        assertEquals("    com.example.FooTest", lines.get(1),
                "Preview FQN must be indented under the summary so the reader can see it belongs");
        assertFalse(String.join("\n", lines).contains("more"),
                "Singleton preview must not render the '… and N more' tail");
    }

    @Test
    void dispatchAtPreviewLimitPrintsAllWithoutTail() {
        // Exactly LIFECYCLE_FQN_PREVIEW_LIMIT FQNs: every FQN is in
        // the preview, and the tail is suppressed because there is
        // nothing hidden. Also pins LIFECYCLE_FQN_PREVIEW_LIMIT as
        // a package-private constant so the test stays synced with
        // the renderer on a limit change.
        int limit = AffectedTestTask.LIFECYCLE_FQN_PREVIEW_LIMIT;
        List<String> fqns = IntStream.range(0, limit)
                .mapToObj(i -> "com.example.Test" + i)
                .toList();

        List<String> lines = AffectedTestTask.renderLifecycleDispatchPreview(
                "application:test", fqns);

        assertEquals(1 + limit, lines.size(),
                "At exactly the preview limit: summary + N FQNs, no '… more' tail");
        assertFalse(String.join("\n", lines).contains("more"),
                "Tail must stay suppressed when every FQN is already in the preview");
    }

    @Test
    void oversizedDispatchEmitsPreviewPlusMoreTail() {
        // Twenty FQNs, limit is five (see LIFECYCLE_FQN_PREVIEW_LIMIT):
        // lifecycle output must contain the first five FQNs and a
        // single "… and 15 more (use --info for full list)" tail.
        int limit = AffectedTestTask.LIFECYCLE_FQN_PREVIEW_LIMIT;
        List<String> fqns = IntStream.range(0, 20)
                .mapToObj(i -> "com.example.Test" + i)
                .toList();

        List<String> lines = AffectedTestTask.renderLifecycleDispatchPreview(
                "application:test", fqns);

        assertEquals(1 + limit + 1, lines.size(),
                "Oversized dispatch must produce summary + preview + exactly one tail line");
        assertEquals("  application:test (20 test classes)", lines.get(0),
                "Summary line format (indent, count, pluralization) must be stable at oversized too — "
                        + "this is the only line a reviewer sees on a very large dispatch without --info");
        assertEquals("    com.example.Test0", lines.get(1),
                "Preview must keep input order so reviewers can map to the diff without re-sorting");
        assertEquals("    com.example.Test" + (limit - 1), lines.get(limit),
                "Preview must stop at the limit — no off-by-one into the 6th FQN");

        String tail = lines.get(lines.size() - 1);
        assertTrue(tail.contains("and " + (20 - limit) + " more"),
                "Tail must name the hidden count so the reader knows how much is omitted, got: " + tail);
        assertTrue(tail.contains("--info"),
                "Tail must point at the flag that reveals the full list — otherwise the reader "
                        + "has to guess how to see the rest");
    }

    @Test
    void extremelyLargeDispatchStillCapsPreviewAtLimit() {
        // Sanity: a two-hundred-class dispatch (plausible on a
        // utility change that ripples through the whole service)
        // must not start logging thousands of lifecycle lines. The
        // 4 MiB GitHub Actions step cap is the original reason the
        // per-FQN log was demoted to info level — the tail exists
        // to preserve that protection while still surfacing enough
        // signal at lifecycle level.
        List<String> fqns = IntStream.range(0, 200)
                .mapToObj(i -> "com.example.Test" + i)
                .toList();

        List<String> lines = AffectedTestTask.renderLifecycleDispatchPreview(
                "application:test", fqns);

        assertEquals(1 + AffectedTestTask.LIFECYCLE_FQN_PREVIEW_LIMIT + 1, lines.size(),
                "Lifecycle preview must stay bounded regardless of dispatch size — "
                        + "this is the load-bearing property for the 4 MiB step cap");
    }
}
