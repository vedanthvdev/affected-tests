package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The regression this suite locks in is the v1.9.14 bug where
 * {@code outOfScopeTestDirs = ["api-test/**"]} filtered the diff-side
 * view in {@link io.affectedtests.core.mapping.PathToClassMapper} but
 * silently did nothing on the on-disk view in {@link ProjectIndex}.
 * Net effect for the user: an MR that only touched
 * {@code api-test/...} was correctly classified as out-of-scope, yet
 * tests discovered under {@code api-test/src/test/java} still got
 * dispatched because this class never dropped them. The fix is
 * covered in production code by delegating to {@link
 * io.affectedtests.core.mapping.OutOfScopeMatchers}; this suite
 * verifies the dispatch map and file lists agree with that delegation.
 */
class ProjectIndexTest {

    @TempDir
    Path projectDir;

    @Test
    void globOutOfScopeTestDirDropsTestFqn() throws Exception {
        // Two test classes: one under api-test/** (out of scope) and
        // one under src/test/java (normal). The dispatch map must
        // contain exactly the normal one.
        writeJava(projectDir.resolve("api-test/src/test/java/com/example/ApiFoo.java"),
                "package com.example; public class ApiFoo {}");
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();

        ProjectIndex index = ProjectIndex.build(projectDir, config);

        assertTrue(index.testFqns().contains("com.example.FooTest"),
                "Normal test FQN must survive the filter");
        assertFalse(index.testFqns().contains("com.example.ApiFoo"),
                "Glob out-of-scope test dir must drop the api-test FQN on the on-disk side, "
                        + "not just the diff side — this is the bug the v1.9.14 --explain Hint "
                        + "was introduced to warn about.");
    }

    @Test
    void literalAndGlobFormsAgreeOnTheSameOutcome() throws Exception {
        // Given an identical on-disk layout, writing the config as a
        // literal prefix ("api-test") and as a glob ("api-test/**")
        // must drop the same FQN. If these ever diverge again the
        // user's only visible signal is a mysterious full test run
        // after a pure api-test MR, so the lock-in matters.
        writeJava(projectDir.resolve("api-test/src/test/java/com/example/ApiFoo.java"),
                "package com.example; public class ApiFoo {}");
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex literalIndex = ProjectIndex.build(projectDir,
                AffectedTestsConfig.builder()
                        .mode(Mode.CI)
                        .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                        .outOfScopeTestDirs(List.of("api-test"))
                        .build());
        ProjectIndex globIndex = ProjectIndex.build(projectDir,
                AffectedTestsConfig.builder()
                        .mode(Mode.CI)
                        .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                        .outOfScopeTestDirs(List.of("api-test/**"))
                        .build());

        assertEquals(literalIndex.testFqns(), globIndex.testFqns(),
                "Literal and glob forms of the same logical rule must produce the same index");
    }

    private static void writeJava(Path target, String body) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, body);
    }
}
