package io.affectedtests.core;

import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
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
            assertEquals(EscalationReason.NONE, result.escalationReason(),
                    "A normal filtered selection must not report an escalation reason");
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
            assertEquals(EscalationReason.NONE, result.escalationReason());
        }
    }

    @Test
    void emptyChangesetWithRunAllIfNoMatchesReportsItsOwnReason() throws Exception {
        // Guards a subtle bug: before this test, an empty changeset with
        // runAllIfNoMatches=true shared the RUN_ALL_IF_NO_MATCHES reason with
        // the post-discovery empty branch, so the task logged
        // "no affected tests discovered" even though discovery had never
        // actually run. The two branches must be distinguishable from the
        // outside, otherwise the lifecycle log actively lies about why we
        // flipped to a full suite.
        try (Git git = initRepoWithInitialCommit()) {
            String head = git.log().call().iterator().next().getName();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(head)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .runAllIfNoMatches(true)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.runAll(),
                    "Empty changeset + runAllIfNoMatches=true must still flip runAll");
            assertTrue(result.changedFiles().isEmpty());
            assertTrue(result.testClassFqns().isEmpty());
            assertEquals(EscalationReason.RUN_ALL_ON_EMPTY_CHANGESET, result.escalationReason(),
                    "Empty changeset must have its own reason so the task log names an honest trigger");
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
            assertEquals(EscalationReason.RUN_ALL_IF_NO_MATCHES, result.escalationReason(),
                    "A discovery-empty runAll must report RUN_ALL_IF_NO_MATCHES so the task logs the right trigger");
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
    void forcesRunAllWhenNonJavaFileChangesAndFlagIsDefault() throws Exception {
        // Policy: "run more, never less" — any change to a file we cannot
        // resolve to a Java source or test class under the configured dirs
        // (e.g. application.yml, build.gradle, a Liquibase migration) must
        // force the full suite. The flag is on by default.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            // A pure YAML-only change — no Java at all.
            Path resourcesDir = tempDir.resolve("src/main/resources");
            Files.createDirectories(resourcesDir);
            Files.writeString(resourcesDir.resolve("application.yml"),
                    "spring:\n  application.name: demo\n");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add application.yml").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.runAll(),
                    "Non-Java-only change must force runAll under default policy");
            assertTrue(result.testClassFqns().isEmpty(),
                    "runAll result should not carry a filtered test FQN set");
            assertEquals(EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE, result.escalationReason(),
                    "Non-Java escalation must be tagged so the task log names the correct trigger");
        }
    }

    @Test
    void nonJavaFileAlongsideJavaChangeStillForcesRunAll() throws Exception {
        // Even when a Java change is also present (and discovery would normally
        // select a specific test), the presence of any unmapped file must still
        // escalate to runAll — we refuse to gamble that the YAML change is
        // harmless just because the Java change happens to have a named test.
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

            Path resourcesDir = tempDir.resolve("src/main/resources");
            Files.createDirectories(resourcesDir);
            Files.writeString(resourcesDir.resolve("application.yml"),
                    "spring:\n  application.name: demo\n");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add service + yaml together").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.runAll(),
                    "Mixed Java + non-Java change must still force runAll");
            assertTrue(result.testClassFqns().isEmpty(),
                    "A runAll escalation must not also carry a filtered FQN set — the downstream "
                            + "task would then have to decide which to honour, which is exactly "
                            + "the ambiguity the escalation exists to avoid");
            assertEquals(EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE, result.escalationReason());
        }
    }

    @Test
    void javaOnlyChangeDoesNotForceRunAll() throws Exception {
        // Guardrail for the opposite side: a pure Java change under a known
        // source dir must continue to produce a filtered selection, not
        // trigger the runAll escalation.
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
            git.commit().setMessage("java-only change").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertFalse(result.runAll(),
                    "Pure Java-under-sourceDirs change must not escalate to runAll");
            assertEquals(EscalationReason.NONE, result.escalationReason());
            assertTrue(result.testClassFqns().contains("com.example.FooServiceTest"));
        }
    }

    @Test
    void excludedPathsDoNotForceRunAll() throws Exception {
        // If the user has explicitly excluded a path, that's a clear opt-out
        // signal — the safety escalation must respect it and not force runAll.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path generatedDir = tempDir.resolve("src/main/java/generated");
            Files.createDirectories(generatedDir);
            Files.writeString(generatedDir.resolve("Stub.java"),
                    "package generated;\npublic class Stub {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add generated stub").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .excludePaths(java.util.List.of("**/generated/**"))
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertFalse(result.runAll(),
                    "Excluded path should not trigger the non-Java safety escalation");
        }
    }

    @Test
    void runAllOnNonJavaChangeCanBeDisabled() throws Exception {
        // Escape hatch: a user who wants the old "silent skip on YAML"
        // behaviour can explicitly opt out of the safety net.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path resourcesDir = tempDir.resolve("src/main/resources");
            Files.createDirectories(resourcesDir);
            Files.writeString(resourcesDir.resolve("application.yml"),
                    "spring:\n  application.name: demo\n");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("yaml only").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .runAllOnNonJavaChange(false)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertFalse(result.runAll(),
                    "With runAllOnNonJavaChange=false the old silent-skip behaviour must be preserved");
            assertTrue(result.testClassFqns().isEmpty());
            assertEquals(EscalationReason.NONE, result.escalationReason(),
                    "Opting out of the non-Java escalation must also clear its reason tag");
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
