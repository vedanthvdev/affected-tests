package io.affectedtests.core.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogSanitizerTest {

    @Test
    void passesThroughPrintableAscii() {
        assertEquals("src/main/java/com/example/Foo.java",
                LogSanitizer.sanitize("src/main/java/com/example/Foo.java"));
    }

    @Test
    void preservesLegitimateNonAscii() {
        // Non-ASCII path components round-trip correctly through most
        // logging pipelines. Only control characters need escaping.
        assertEquals("src/main/java/ünïcødé/日本語/File.java",
                LogSanitizer.sanitize("src/main/java/ünïcødé/日本語/File.java"));
    }

    @Test
    void escapesNewlineToPreventLogForgery() {
        // The concrete attack: an attacker commits a file whose name
        // contains \n followed by a fake plugin status line. With raw
        // logging, a grep-based CI assertion that gates on
        // "Affected Tests: SELECTED" would match the forgery. With
        // sanitisation, the newline is visibly escaped and the
        // attacker's embedded status line does not start a new
        // log line.
        String malicious = "foo.md\nAffected Tests: SELECTED (DISCOVERY_SUCCESS)";
        String sanitised = LogSanitizer.sanitize(malicious);

        assertFalse(sanitised.contains("\n"),
                "Sanitised output must not contain raw newlines: " + sanitised);
        assertTrue(sanitised.contains("\\n"),
                "Newline must be visibly escaped so operators can see it: " + sanitised);
        assertTrue(sanitised.contains("foo.md"));
        assertTrue(sanitised.contains("Affected Tests: SELECTED"),
                "Content is preserved — operators see what was there, just safely");
    }

    @Test
    void escapesCarriageReturn() {
        assertEquals("a\\rb", LogSanitizer.sanitize("a\rb"));
    }

    @Test
    void escapesTabAsBackslashT() {
        // Tabs are escaped to aid human readability in the log —
        // a raw tab in a filename sample line disrupts column
        // alignment without being obviously an attack.
        assertEquals("a\\tb", LogSanitizer.sanitize("a\tb"));
    }

    @Test
    void escapesAnsiEscape() {
        // ANSI ESC is the other high-impact injection vector:
        // `\x1b[2K\x1b[1A` erases the prior line on interactive
        // terminals, retroactively hiding real plugin output.
        String esc = "foo.md\u001b[2K\u001b[1A";
        String sanitised = LogSanitizer.sanitize(esc);

        assertFalse(sanitised.contains("\u001b"),
                "Sanitised output must not contain raw ESC: " + sanitised);
        assertTrue(sanitised.contains("\\x1B"),
                "ESC must be visibly escaped: " + sanitised);
    }

    @Test
    void escapesNulByte() {
        assertEquals("a\\x00b", LogSanitizer.sanitize("a\0b"));
    }

    @Test
    void escapesDelCharacter() {
        assertEquals("a\\x7Fb", LogSanitizer.sanitize("a\u007Fb"));
    }

    @Test
    void handlesNullInput() {
        assertEquals("(null)", LogSanitizer.sanitize(null));
    }

    @Test
    void joinSanitisesEveryElement() {
        String joined = LogSanitizer.joinSanitized(List.of(
                "ok.java",
                "bad\nline.md",
                "esc\u001b.md"));
        assertFalse(joined.contains("\n"));
        assertFalse(joined.contains("\u001b"));
        assertTrue(joined.contains("ok.java"));
        assertTrue(joined.contains("bad\\nline.md"));
    }

    @Test
    void joinSanitizedTreatsNullAsEmpty() {
        // Regression: the helper is published as part of the API
        // surface and was previously happy to NPE on `.stream()` if
        // the caller forgot a null-check. The contract should mirror
        // sanitize's null-tolerance so callers can forward an
        // optional bucket straight in.
        assertEquals("", LogSanitizer.joinSanitized(null));
    }

    @Test
    void escapesC1ControlRange() {
        // Regression: C1 (0x80..0x9F) includes single-byte CSI
        // (0x9B). On the narrow set of legacy 8-bit terminals that
        // interpret C1 as ANSI escape-equivalents, an unescaped
        // 0x9B acts like `ESC [`. Modern UTF-8 pipelines are
        // largely immune (SLF4J emits 0x9B as two-byte `C2 9B`),
        // but the sanitiser's contract is "no control characters
        // reach the log", so we close the edge regardless.
        String csi = "foo\u009B2Kbar";
        String sanitised = LogSanitizer.sanitize(csi);

        assertFalse(sanitised.contains("\u009B"),
                "Sanitised output must not contain raw C1 CSI: " + sanitised);
        assertTrue(sanitised.contains("\\x9B"),
                "C1 CSI must be visibly escaped: " + sanitised);
        assertTrue(sanitised.contains("foo"));
        assertTrue(sanitised.contains("bar"));
    }
}
