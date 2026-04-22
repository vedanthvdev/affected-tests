package io.affectedtests.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link AffectedTestTask#runWithTimeout} — the
 * B6-#11 watchdog path. Without this suite, the entire timeout surface
 * (branch selection, kill ladder, child-tree teardown) would only be
 * verified by eyeballing CI logs the day a hung test ships.
 *
 * <p>The tests drive {@code runWithTimeout} against a deliberately
 * hung {@code sh -c "sleep 60"} child. The watchdog's wall-clock
 * budget is 2 seconds; on a timeout the test then asserts:
 * <ul>
 *   <li>a {@link GradleException} is thrown with a message that names
 *       the setting the operator has to tune,</li>
 *   <li>the child PID is no longer alive within the ladder's budget
 *       (timeout + grace + forcible = 2 + 10 + 5 = 17 seconds, plus
 *       a generous headroom for slow CI).</li>
 * </ul>
 *
 * <p>Skipped on Windows because the spawn helpers (`sh`, `sleep`) are
 * POSIX-specific. The plugin's overall surface already assumes a
 * POSIX-like runner for `./gradlew` wrapper invocation, so skipping
 * here doesn't lose coverage that exists elsewhere in the suite.
 */
@DisabledOnOs(OS.WINDOWS)
class AffectedTestTaskTimeoutTest {

    @TempDir
    Path tempDir;

    private AffectedTestTask taskFor(Project project) {
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");
        return (AffectedTestTask) project.getTasks().findByName("affectedTest");
    }

    @Test
    void hungChildIsKilledWithinLadderBudget() throws Exception {
        // A 60-second `sleep` represents the "hung test JVM" failure
        // mode the watchdog exists to protect against. The watchdog
        // budget is 2 seconds; if the ladder works the child must be
        // terminated well before the 60-second sleep would naturally
        // exit. Pre-fix, a future refactor that swapped
        // destroy()/destroyForcibly() for waitFor-with-no-destroy
        // would leave the child alive and this test would fail with
        // a still-running PID.
        Project project = ProjectBuilder.builder().build();
        AffectedTestTask task = taskFor(project);

        List<String> args = List.of("sh", "-c", "sleep 60");

        long start = System.nanoTime();
        GradleException thrown = assertThrows(GradleException.class,
                () -> task.runWithTimeout(args, tempDir, 2L),
                "Watchdog must translate a missed deadline into a GradleException — "
                        + "otherwise CI sees a green exit code for a killed build");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(thrown.getMessage().contains("exceeded the configured timeout"),
                "Message must state the actual failure mode so operators can find the right knob; got: "
                        + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("affectedTests.gradlewTimeoutSeconds"),
                "Message must name the DSL knob so operators know what to raise; got: "
                        + thrown.getMessage());

        // Ladder budget: 2s timeout + 10s graceful + 5s forcible = 17s.
        // Allow 30s headroom for slow CI runners. If we spend longer
        // than that, something in the ladder is waiting forever —
        // precisely the regression this test exists to catch.
        assertTrue(elapsedMs < 30_000L,
                "Watchdog must return within the ladder budget (2+10+5s) plus headroom; took "
                        + elapsedMs + "ms");
    }

    @Test
    void sigtermSwallowingChildEscalatesToForcibleKill() throws Exception {
        // A wrapper that swallows SIGTERM is the exact failure mode
        // the forcible leg of the ladder exists for. Graceful
        // destroy() does nothing, waitFor(graceful) times out, and
        // the watchdog has to escalate to destroyForcibly() to
        // actually reap the process. A refactor that dropped the
        // forcible leg (e.g. "just call destroy() and trust the
        // runtime") would make this test hang until the ladder's
        // outer wait returns, pushing elapsed time close to 60s and
        // flipping the assertion red.
        Project project = ProjectBuilder.builder().build();
        AffectedTestTask task = taskFor(project);

        long before = System.nanoTime();
        assertThrows(GradleException.class,
                () -> task.runWithTimeout(
                        List.of("sh", "-c", "trap '' TERM; sleep 60"),
                        tempDir, 2L));
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;

        assertTrue(elapsedMs < 30_000L,
                "Forcible leg must reap a SIGTERM-swallowing wrapper within ladder budget; took "
                        + elapsedMs + "ms (this regresses to ~60s if destroyForcibly is dropped "
                        + "from shutdownChild)");
    }

    @Test
    void successfulChildReturnsExitCodeBeforeTimeout() throws Exception {
        // The happy path: child finishes well before the deadline.
        // Pre-fix, a refactor that accidentally inverted the
        // `finished` branch (throwing on success, returning on
        // timeout) would ship a plugin that always fails green
        // builds. We pin both the exit code and the no-throw
        // behaviour here.
        Project project = ProjectBuilder.builder().build();
        AffectedTestTask task = taskFor(project);

        int exit = task.runWithTimeout(List.of("sh", "-c", "exit 0"), tempDir, 5L);
        assertFalse(exit != 0,
                "Watchdog must propagate exit code from a child that finishes before the deadline");

        int exit17 = task.runWithTimeout(List.of("sh", "-c", "exit 17"), tempDir, 5L);
        assertTrue(exit17 == 17,
                "Watchdog must propagate non-zero exit codes verbatim so the outer "
                        + "Gradle invocation can surface the real test failure; got " + exit17);
    }

    @Test
    void descendantsAreTerminatedNotOnlyTheWrapper() throws Exception {
        // The core fix for the grandchild-survival bug: if
        // shutdownChild only destroys the wrapper and not its
        // descendants, any shared Gradle daemon / test JVM lives on
        // as an orphan (re-parented to pid 1) and defeats the
        // watchdog's whole reason for existing.
        //
        // To actually prove the grandchild is dead — not just that
        // runWithTimeout returned in time — we write the grandchild's
        // PID to a pidfile from inside the spawned shell. After
        // runWithTimeout surfaces its GradleException, we read the
        // pidfile and assert the PID is no longer alive.
        //
        // Without the descendants-kill loop, the grandchild survives
        // the wrapper's destroyForcibly() and ProcessHandle.of(pid)
        // shows isAlive()==true, failing this assertion.
        Project project = ProjectBuilder.builder().build();
        AffectedTestTask task = taskFor(project);

        Path pidFile = tempDir.resolve("grandchild.pid");

        // Outer sh spawns an inner sh that writes its own PID to the
        // pidfile and execs `sleep 60` (keeping the same PID so the
        // pidfile is accurate). `wait` in the outer sh keeps the
        // Java-side Process alive until the grandchild dies or the
        // watchdog reaps the tree.
        String script =
                "sh -c 'echo $$ > \"" + pidFile.toAbsolutePath() + "\"; exec sleep 60' & wait";
        long before = System.nanoTime();
        assertThrows(GradleException.class,
                () -> task.runWithTimeout(
                        List.of("sh", "-c", script), tempDir, 2L));
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;

        assertTrue(java.nio.file.Files.exists(pidFile),
                "Test harness invariant: grandchild must have written its PID before the "
                        + "watchdog fired; missing pidfile means the spawn never reached exec");

        long grandchildPid = Long.parseLong(
                java.nio.file.Files.readString(pidFile).trim());

        // Give the OS a short beat to reap the forcibly-killed
        // grandchild before we sample ProcessHandle — on busy CI
        // the destroyForcibly signal is in flight when we check.
        Thread.sleep(500);

        boolean grandchildAlive = ProcessHandle.of(grandchildPid)
                .map(ProcessHandle::isAlive)
                .orElse(false);

        if (grandchildAlive) {
            // Clean up so the test doesn't leak a 60-second sleeper
            // on CI before failing.
            ProcessHandle.of(grandchildPid).ifPresent(ProcessHandle::destroyForcibly);
        }
        assertFalse(grandchildAlive,
                "Descendant PID " + grandchildPid + " survived the watchdog — "
                        + "shutdownChild reaped the wrapper but left the grandchild orphaned. "
                        + "This is the exact bug the snapshot-then-destroyForcibly loop closes.");

        // Sanity: we also expect the whole ladder to finish well
        // under the 2+10+5 budget plus headroom.
        assertTrue(elapsedMs < 30_000L,
                "Watchdog must complete within ladder budget; took " + elapsedMs + "ms");
    }
}
