package io.affectedtests.core.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the shared out-of-scope matcher compilation so
 * {@link PathToClassMapper} and
 * {@link io.affectedtests.core.discovery.ProjectIndex} can never drift
 * again. Before this extraction the two classes disagreed on glob
 * entries ({@code api-test/**}) and the user-visible outcome was that
 * a mixed diff still dispatched api-test tests — the exact hole the
 * v1.9.14 {@code --explain} Hint was introduced to warn about.
 */
class OutOfScopeMatchersTest {

    @Test
    void literalEntryUsesBoundaryAwarePrefix() {
        List<Predicate<String>> matchers = OutOfScopeMatchers.compile(
                List.of("api-test"), "outOfScopeTestDirs");

        assertTrue(OutOfScopeMatchers.matchesAny("api-test/src/test/java/Foo.java", matchers));
        assertTrue(OutOfScopeMatchers.matchesAny("modules/api-test/src/test/java/Foo.java", matchers));
        assertFalse(OutOfScopeMatchers.matchesAny("api-test-utils/src/test/java/Foo.java", matchers),
                "Literal 'api-test' must not claim 'api-test-utils' — boundary semantics are the "
                        + "whole reason we don't just call String.contains.");
    }

    @Test
    void globEntryMatchesNestedPaths() {
        List<Predicate<String>> matchers = OutOfScopeMatchers.compile(
                List.of("api-test/**"), "outOfScopeTestDirs");

        assertTrue(OutOfScopeMatchers.matchesAny("api-test/src/test/java/Foo.java", matchers),
                "api-test/** must match a file several segments deep");
        assertFalse(OutOfScopeMatchers.matchesAny("api-test-utils/src/test/java/Foo.java", matchers));
    }

    @Test
    void mixedEntriesCoexistInOneList() {
        List<Predicate<String>> matchers = OutOfScopeMatchers.compile(
                List.of("api-test", "performance-test/**"), "outOfScopeTestDirs");

        assertTrue(OutOfScopeMatchers.matchesAny("api-test/src/test/java/Foo.java", matchers));
        assertTrue(OutOfScopeMatchers.matchesAny("performance-test/src/test/java/Foo.java", matchers));
        assertFalse(OutOfScopeMatchers.matchesAny("src/test/java/Foo.java", matchers));
    }

    @Test
    void blankAndNullEntriesAreDroppedQuietly() {
        // A mis-concatenated list literal in build.gradle is not worth
        // failing a build over — worst case the user sees zero matches
        // and --explain's Hint kicks in.
        List<Predicate<String>> matchers = OutOfScopeMatchers.compile(
                java.util.Arrays.asList("api-test/**", "", null, "   "),
                "outOfScopeTestDirs");

        assertEquals(1, matchers.size(), "Blank and null entries must not yield a no-op matcher");
        assertTrue(OutOfScopeMatchers.matchesAny("api-test/src/test/java/Foo.java", matchers));
    }

    @Test
    void malformedGlobFailsAtConfigTimeWithConfigKeyAndIndex() {
        // The JVM's raw PatternSyntaxException ("Unclosed character
        // class near index 4") doesn't say which config key or which
        // index is wrong — by the time the user sees it they're deep
        // in the engine stack trace. Surface the context here so they
        // can fix build.gradle without grepping.
        List<String> badDirs = List.of("api-test/**", "**[unclosed");

        IllegalStateException ise = assertThrows(
                IllegalStateException.class,
                () -> OutOfScopeMatchers.compile(badDirs, "outOfScopeTestDirs"));

        String msg = ise.getMessage();
        assertTrue(msg.contains("outOfScopeTestDirs"),
                "Error must name the config key so users know where to look. Got: " + msg);
        assertTrue(msg.contains("[1]"),
                "Error must name the index so users know which list entry failed. Got: " + msg);
        assertTrue(msg.contains("**[unclosed"),
                "Error must include the offending pattern. Got: " + msg);
    }

    @Test
    void globMatcherFailsClosedOnInvalidPathInput() {
        // A Linux-committed filename arriving on a Windows CI runner
        // can fail Path.of with InvalidPathException (colon, pipe,
        // null byte, reserved device names). The glob branch must
        // not bubble that exception up and kill the whole task —
        // it must fail closed so the file routes through the
        // unmapped bucket and the safety net takes over.
        List<Predicate<String>> matchers = OutOfScopeMatchers.compile(
                List.of("api-test/**"), "outOfScopeTestDirs");

        String badPath = "api-test/foo\0bar.java"; // NUL is illegal on every JVM

        assertFalse(OutOfScopeMatchers.matchesAny(badPath, matchers),
                "InvalidPathException must be swallowed so the engine keeps running");
    }

    @Test
    void emptyAndNullListsYieldEmptyMatchers() {
        assertTrue(OutOfScopeMatchers.compile(List.of(), "outOfScopeTestDirs").isEmpty());
        assertTrue(OutOfScopeMatchers.compile(null, "outOfScopeTestDirs").isEmpty());
    }

    @Test
    void literalEntryMatchesExactDirectoryPath() {
        // Regression for the silent-leak case reported by the correctness
        // review: a literal entry whose value equals the resolved directory's
        // relative path — exactly the shape that ProjectIndex hands the
        // matcher after Path.relativize (which never emits a trailing
        // slash) — was not filtered. Result: tests under that directory
        // slipped into sourceFqns and got discovered by strategies that
        // iterate the full source set (ImplementationStrategy), quietly
        // violating the out-of-scope contract.
        List<Predicate<String>> matchers = OutOfScopeMatchers.compile(
                List.of("sub/src/main/java"), "outOfScopeSourceDirs");

        assertTrue(OutOfScopeMatchers.matchesAny("sub/src/main/java", matchers),
                "Literal entry must match the bare directory path — that is the "
                        + "exact shape ProjectIndex relativizes to");
        assertTrue(OutOfScopeMatchers.matchesAny("sub/src/main/java/Foo.java", matchers),
                "Descendants must still match");
        assertTrue(OutOfScopeMatchers.matchesAny("monorepo/sub/src/main/java", matchers),
                "Nested parent path ending in the entry must still match");
        assertFalse(OutOfScopeMatchers.matchesAny("sub/src/main/javax/Foo.java", matchers),
                "Boundary semantics must still reject substring-only matches — "
                        + "a dir named 'javax' is not under 'java'");
    }
}
