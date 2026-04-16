package io.affectedtests.core;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AffectedTestsEngineTest {

    @TempDir
    Path tempDir;

    private Git initRepoWithInitialCommit() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        File readme = tempDir.resolve("README.md").toFile();
        Files.writeString(readme.toPath(), "# init");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("initial commit").call();
        return git;
    }

    @Test
    void discoversTestByNamingFromCommittedChange() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("FooServiceTest.java"),
                    "package com.example;\npublic class FooServiceTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add FooService").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.testClassFqns().contains("com.example.FooServiceTest"),
                    "Should discover test via naming strategy");
            assertFalse(result.runAll());
            // Result must expose the test file path for per-module routing.
            assertNotNull(result.testFqnToPath().get("com.example.FooServiceTest"));
        }
    }

    @Test
    void returnsEmptyWhenNoChanges() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String head = git.log().call().iterator().next().getName();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(head)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.testClassFqns().isEmpty());
            assertTrue(result.changedFiles().isEmpty());
            assertFalse(result.runAll());
        }
    }

    @Test
    void runAllWhenNoMatchesAndFlagEnabled() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("Orphan.java"),
                    "package com.example;\npublic class Orphan {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add orphan").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .runAllIfNoMatches(true)
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.runAll(), "Should set runAll when flag is enabled and no tests match");
        }
    }

    @Test
    void deletedTestFilesAreNotReportedAsAffected() throws Exception {
        // Regression test for C2: an old FQN for a deleted test file must not
        // propagate into the result set — otherwise Gradle's --tests filter
        // fails with "No tests found".
        try (Git git = initRepoWithInitialCommit()) {
            // Create + commit both a production class and its test
            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Path oldTest = testDir.resolve("FooServiceTest.java");
            Files.writeString(oldTest,
                    "package com.example;\npublic class FooServiceTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add service + test").call();
            String base = git.log().call().iterator().next().getName();

            // Modify the service and delete the test file in the next commit
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService { void bar() {} }");
            Files.delete(oldTest);
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            git.commit().setMessage("modify service; delete test").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertFalse(result.testClassFqns().contains("com.example.FooServiceTest"),
                    "Deleted test FQN must not be returned to callers");
            assertFalse(result.testFqnToPath().containsKey("com.example.FooServiceTest"));
        }
    }

    @Test
    void multiModuleTestPathsArePreservedInResult() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            // Create a production class + matching test in nested module layout.
            Path prodDir = tempDir.resolve("moduleA/src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("moduleA/src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("FooServiceTest.java"),
                    "package com.example;\npublic class FooServiceTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add module A").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            Path expected = tempDir.resolve("moduleA/src/test/java/com/example/FooServiceTest.java");
            Path actual = result.testFqnToPath().get("com.example.FooServiceTest");
            assertNotNull(actual, "Should expose absolute file path for each discovered test");
            assertEquals(expected.toAbsolutePath().normalize(),
                    actual.toAbsolutePath().normalize());
        }
    }
}
