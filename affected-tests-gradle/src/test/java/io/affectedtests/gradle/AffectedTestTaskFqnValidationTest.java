package io.affectedtests.gradle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defense-in-depth for the path between discovery and
 * {@code test --tests <fqn>}. Discovery should only ever produce
 * Java-shaped identifiers, but a mis-configured custom strategy or a
 * parser bug could in principle yield a string with a shell
 * metacharacter or whitespace. Before this guard, such a string would
 * be appended verbatim to the {@code test} task's argv and either
 * crash the runner with an obscure glob-expansion error or — on the
 * bad-luck path — match no test and silently reduce coverage.
 */
class AffectedTestTaskFqnValidationTest {

    @Test
    void acceptsStandardJavaFqns() {
        assertTrue(AffectedTestTask.isValidFqn("com.example.FooTest"));
        assertTrue(AffectedTestTask.isValidFqn("com.example.inner.Foo$BarTest"));
        assertTrue(AffectedTestTask.isValidFqn("Simple"));
        assertTrue(AffectedTestTask.isValidFqn("_weird.$Names"));
    }

    @Test
    void rejectsNullAndEmpty() {
        assertFalse(AffectedTestTask.isValidFqn(null));
        assertFalse(AffectedTestTask.isValidFqn(""));
    }

    @Test
    void rejectsShellMetacharsAndWhitespace() {
        // Each of these would be a real problem if passed to
        // Gradle's --tests arg: the shell-glob ones would either
        // expand on some OSes or silently match nothing on others;
        // whitespace in an FQN cannot correspond to a real Java
        // class; the hyphen reaches the JUnit filter and quietly
        // matches zero classes.
        assertFalse(AffectedTestTask.isValidFqn("com.example.*"));
        assertFalse(AffectedTestTask.isValidFqn("com.example.Foo Test"));
        assertFalse(AffectedTestTask.isValidFqn("com/example/Foo"));
        assertFalse(AffectedTestTask.isValidFqn("com.example;DROP TABLE"));
        assertFalse(AffectedTestTask.isValidFqn("com.example.foo-bar"));
    }

    @Test
    void rejectsLeadingDotOrTrailingDot() {
        assertFalse(AffectedTestTask.isValidFqn(".LeadingDot"));
        assertFalse(AffectedTestTask.isValidFqn("trailing.Dot."));
        assertFalse(AffectedTestTask.isValidFqn("double..dot"));
    }
}
