package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Central factory for {@link JavaParser} instances used across the discovery
 * strategies and {@link ProjectIndex}.
 *
 * <p>Motivating bug: JavaParser 3.28's default {@link LanguageLevel} is
 * {@code JAVA_11}. Every strategy that instantiated {@code new JavaParser()}
 * silently failed to parse any file using records, sealed types, or pattern
 * matching — the parser reported {@code isSuccessful() == false} for the
 * whole compilation unit, every call site then discarded the result, and the
 * file contributed nothing to Transitive/Implementation/Usage discovery. Any
 * consumer test whose only path to a changed class went through a record
 * (e.g. {@code record UsdMoney(long cents) implements Money}) was silently
 * dropped on every MR.
 *
 * <p>Setting the level to {@link LanguageLevel#JAVA_21} (or the highest
 * stable level the parser understands) makes all stable Java language
 * features parse cleanly. Language levels newer than the parser's build
 * produce an {@code IllegalArgumentException}, which is preferable to the
 * silent-drop failure mode we had before.
 */
final class JavaParsers {

    /**
     * Highest stable (non-preview) language level supported by the bundled
     * JavaParser build (3.28.0 ships {@code JAVA_1 … JAVA_25} + {@code
     * BLEEDING_EDGE}; {@code JAVA_25} is the newest stable constant). Kept
     * in one place so when the parser dependency is bumped we only move
     * this constant up.
     *
     * <p>We deliberately avoid {@code BLEEDING_EDGE}: it lets preview
     * syntax leak in, which is both slower to parse and more likely to
     * behave inconsistently across JavaParser point releases. Every stable
     * level up to {@link LanguageLevel#JAVA_25} is a strict syntactic
     * superset of older levels, so setting the constant at the top still
     * parses legacy projects cleanly.
     */
    static final LanguageLevel LANGUAGE_LEVEL = LanguageLevel.JAVA_25;

    private static final Logger log = LoggerFactory.getLogger(JavaParsers.class);

    private JavaParsers() {
    }

    /**
     * Creates a new {@link JavaParser} configured with the plugin-wide
     * language level. Use this in every place a parser is constructed; do
     * not call {@code new JavaParser()} directly.
     */
    static JavaParser newParser() {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(LANGUAGE_LEVEL);
        return new JavaParser(config);
    }

    /**
     * Parses {@code file} with {@code parser} and returns the resulting
     * {@link CompilationUnit}, or {@code null} if the file could not be
     * parsed. Emits a single {@code WARN} line on unsuccessful parses so
     * that the parse-failure silent-drop class of bug — a single file
     * unparseable at {@link #LANGUAGE_LEVEL} silently removes itself from
     * discovery, taking every test that depended on it along — is visible
     * at the plugin's default log level instead of hiding under
     * {@code DEBUG} the way the pre-v1.9.20 call sites did.
     *
     * <p>Label is prepended to the log line so the operator can see which
     * discovery phase failed to parse the file (e.g. {@code transitive},
     * {@code impl}, {@code usage}, {@code index}).
     */
    static CompilationUnit parseOrWarn(JavaParser parser, Path file, String label) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return result.getResult().get();
            }
            // isSuccessful == false → JavaParser produced a result but
            // flagged one or more problems. Most often on Java-version
            // mismatches and partial source. Surface the first problem
            // so operators can tell whether the file needs a dependency
            // bump or is genuinely malformed.
            String firstProblem = result.getProblems().isEmpty()
                    ? "no diagnostics"
                    : result.getProblems().get(0).getMessage();
            log.warn("Affected Tests: [{}] failed to parse {} at language level {}: {}",
                    label, file, LANGUAGE_LEVEL, firstProblem);
            return null;
        } catch (Exception e) {
            // Preserve the pre-v1.9.20 behaviour of degrading the
            // exception path to DEBUG (I/O races, file-deleted-under-
            // JGit, etc. are noisy on CI) while still surfacing the
            // much-more-common isSuccessful==false branch above.
            log.debug("Affected Tests: [{}] error parsing {}: {}", label, file, e.getMessage());
            return null;
        }
    }
}
