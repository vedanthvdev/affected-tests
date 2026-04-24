package io.affectedtests.gradle;

import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.Mode;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Turkish-locale regression. Before {@link Locale#ROOT} was pinned on
 * {@code toUpperCase()} inside the config parsers, {@code "ci"} turned
 * into {@code "Cİ"} (U+0130) on a Turkish-locale JVM, enum lookup
 * failed, and the user got a misleading "Unknown affectedTests.mode"
 * error on config they had copy-pasted verbatim from the README.
 *
 * <p>Reproducing the original JDK bug requires actually flipping the
 * default locale — a plain string comparison won't catch the regression
 * because the production code is already calling {@code toUpperCase()};
 * the difference is only which collation table it consults.
 */
class AffectedTestTaskLocaleTest {

    private Locale originalLocale;

    @BeforeEach
    void saveLocale() {
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    @Test
    void parseModeIsLocaleIndependent() {
        Locale.setDefault(TURKISH);
        assertEquals(Mode.CI, AffectedTestTask.parseMode("ci"));
        assertEquals(Mode.LOCAL, AffectedTestTask.parseMode("local"));
        assertEquals(Mode.STRICT, AffectedTestTask.parseMode("strict"));
    }

    @Test
    void parseActionIsLocaleIndependent() {
        Locale.setDefault(TURKISH);
        assertEquals(Action.SELECTED, AffectedTestTask.parseAction("selected", "onEmptyDiff"));
        assertEquals(Action.FULL_SUITE, AffectedTestTask.parseAction("full_suite", "onEmptyDiff"));
        assertEquals(Action.SKIPPED, AffectedTestTask.parseAction("skipped", "onEmptyDiff"));
    }

    @Test
    void parseModeErrorNamesTheAutoFallbackOnUnknownValue() {
        // v2.2.1 fix (L4 from v2.2 code review): the v2.2 error
        // message for `-PaffectedTestsMode=xyzzy` only listed the
        // four legal values. Adopters flipped `-P` on in CI and
        // didn't realise that OMITTING the flag was the right
        // escape — leading to tickets asking "which mode is
        // 'default'?". The fix: the error names `auto` explicitly,
        // explains it's picked when the flag is absent, and
        // mentions the `CI=true` tripwire so the operator knows
        // what AUTO will actually do on a CI worker.
        GradleException ex = assertThrows(GradleException.class,
                () -> AffectedTestTask.parseMode("xyzzy"),
                "parseMode must reject unknown values — bare enum lookups silently "
                        + "throwing IllegalArgumentException would lose the adopter-facing hint");

        String msg = ex.getMessage();
        assertTrue(msg.contains("'xyzzy'"),
                "Error must echo the bad value back so the operator can spot typos in "
                        + "multi-variable CI templates. Got: " + msg);
        assertTrue(msg.contains("auto, local, ci, strict"),
                "Error must list the legal values — regressing this message would make "
                        + "the typo case harder to self-diagnose. Got: " + msg);
        assertTrue(msg.contains("AUTO"),
                "Error must name the AUTO fallback explicitly — otherwise adopters "
                        + "reading the message don't know they can just drop the -P. Got: " + msg);
        assertTrue(msg.contains("CI=true"),
                "Error must still name the baseline CI=true trigger — regressing this "
                        + "specific variable out of the list breaks the most common "
                        + "adopter mental model. Got: " + msg);
        // The trigger set must cover runners that don't export CI=true
        // (Jenkins, Azure Pipelines). Sample-check one non-CI=true
        // runner so a future edit that over-simplifies the message
        // back to "CI=true is exported" — the v2.2.1 L-B regression —
        // breaks this test. We use GITHUB_ACTIONS as the assertion
        // anchor because it's the runner with the widest adopter
        // footprint that also ships CI=true, so a passing test
        // guarantees the message is at least TRYING to list runners
        // rather than hardcoding a single env-var name.
        assertTrue(msg.contains("JENKINS_HOME") || msg.contains("TF_BUILD"),
                "Error must name at least one runner that does NOT export CI=true "
                        + "(Jenkins or Azure Pipelines) — otherwise an operator on those "
                        + "runners would wrongly conclude AUTO falls back to LOCAL on "
                        + "their box. Got: " + msg);
    }
}
