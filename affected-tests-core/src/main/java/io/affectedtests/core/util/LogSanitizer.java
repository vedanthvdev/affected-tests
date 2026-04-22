package io.affectedtests.core.util;

import java.util.Collection;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Escapes control characters in strings that originate from untrusted
 * input (filenames from the git diff, FQNs from a malicious source tree,
 * etc.) before they flow into a logger.
 *
 * <p>Background: POSIX filesystems and git tree entries permit newlines
 * ({@code \n} / {@code \r}), ASCII ESC ({@code \u001b}), and other
 * control characters in path components. When the plugin runs on a
 * merge-gate pipeline against an attacker-controlled MR branch and
 * writes a filename verbatim to {@code lifecycle()} or {@code warn()},
 * the attacker can:
 * <ul>
 *   <li>inject a fake authoritative-looking plugin log line
 *       (e.g. {@code Affected Tests: SELECTED (DISCOVERY_SUCCESS) — …})
 *       to defeat grep-based CI assertions that gate on the real plugin's
 *       status line;</li>
 *   <li>emit ANSI erase / cursor-up escapes that retroactively hide
 *       prior real log lines when the CI log viewer interprets escapes;</li>
 *   <li>corrupt structured logging (JSON, logfmt) with embedded
 *       newlines or delimiters.</li>
 * </ul>
 *
 * <p>All sanitised output is printable ASCII — control characters are
 * replaced with a {@code \xHH} escape (for {@code \n} etc. we use the
 * familiar {@code \\n} / {@code \\r} / {@code \\t} forms instead, since
 * operators reading the log are more likely to recognise those).
 * Non-control Unicode is left untouched because valid non-ASCII
 * filenames are legitimate and already round-trip through every other
 * log pipeline cleanly.
 *
 * <p>This class is intentionally scoped to the logging channel only.
 * Path-shaped callers that need filesystem safety (e.g. rejecting
 * {@code ..} traversal) should use dedicated validators on the
 * {@code mapping} package instead.
 */
public final class LogSanitizer {

    private LogSanitizer() {
        // utility class
    }

    /**
     * Returns {@code value} with all C0 control characters
     * ({@code NUL..US}, including {@code \n}, {@code \r}, ESC),
     * DEL ({@code 0x7F}), and the C1 control range
     * ({@code 0x80..0x9F}, which includes single-byte CSI) replaced
     * with printable escape sequences. Returns {@code "(null)"} for
     * a {@code null} input so callers don't have to null-check
     * before formatting.
     */
    public static String sanitize(String value) {
        if (value == null) {
            return "(null)";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    // Escape the C0 range (NUL..US), DEL, and the C1 range
                    // (0x80..0x9F). C1 includes single-byte CSI (0x9B);
                    // on the narrow population of legacy 8-bit terminals
                    // that interpret bare C1 bytes as escape sequences, an
                    // unescaped C1 byte is equivalent to a multi-byte ANSI
                    // escape. Practical risk is low on UTF-8 log pipelines
                    // (SLF4J emits 0x9B as the 2-byte sequence C2 9B which
                    // modern terminals don't interpret), but closing the
                    // edge keeps the "no control characters reach the log"
                    // contract honest. Printable ASCII and valid non-C1
                    // non-ASCII pass through unchanged so legitimate
                    // filenames round-trip cleanly.
                    if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                        out.append(String.format(Locale.ROOT, "\\x%02X", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /**
     * Convenience: sanitises every element of {@code values} and joins
     * them with {@code ", "} — the most common shape this helper is
     * called from (bucket sample lines, unmapped-file examples).
     * Returns an empty string for a {@code null} input, mirroring the
     * null-tolerant shape of {@link #sanitize(String)}.
     */
    public static String joinSanitized(Collection<String> values) {
        if (values == null) {
            return "";
        }
        return values.stream()
                .map(LogSanitizer::sanitize)
                .collect(Collectors.joining(", "));
    }
}
