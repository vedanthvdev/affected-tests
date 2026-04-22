package io.affectedtests.gradle;

import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.Mode;
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
}
