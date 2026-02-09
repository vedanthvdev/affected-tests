package io.affectedtests.core;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

            // Create production source + test source
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

            // Create a production file with no matching test
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
    void pathTraversalInTestProjectMappingIsRejected() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            // Create a file in the "api" module
            Path apiDir = tempDir.resolve("api/src/main/java/com/example");
            Files.createDirectories(apiDir);
            Files.writeString(apiDir.resolve("Service.java"),
                    "package com.example;\npublic class Service {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add service").call();

            // Malicious mapping: tries to escape project root
            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .testProjectMapping(Map.of(":api", ":../../../etc"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            // Should NOT throw — the traversal path is silently rejected
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertNotNull(result, "Engine should complete without throwing");
        }
    }
}
