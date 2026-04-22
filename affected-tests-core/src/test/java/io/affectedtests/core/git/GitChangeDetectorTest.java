package io.affectedtests.core.git;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GitChangeDetectorTest {

    @TempDir
    Path tempDir;

    /**
     * Utility: initialises a bare-bones git repo with an initial commit so
     * that HEAD and the base ref are resolvable.
     */
    private Git initRepoWithInitialCommit() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        // Create an initial commit so HEAD exists
        File readme = tempDir.resolve("README.md").toFile();
        Files.writeString(readme.toPath(), "# init");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("initial commit").call();
        return git;
    }

    @Test
    void detectsCommittedChanges() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            // Record the initial commit as our "base"
            String baseCommit = git.log().call().iterator().next().getName();

            // Make a change on a new commit
            File javaFile = tempDir.resolve("src/main/java/com/example/Foo.java").toFile();
            javaFile.getParentFile().mkdirs();
            Files.writeString(javaFile.toPath(), "package com.example;\npublic class Foo {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add Foo").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(baseCommit)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);
            Set<String> changed = detector.detectChangedFiles();

            assertTrue(changed.contains("src/main/java/com/example/Foo.java"),
                    "Should detect the newly committed file");
        }
    }

    @Test
    void detectsStagedChanges() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String baseCommit = git.log().call().iterator().next().getName();

            File javaFile = tempDir.resolve("Staged.java").toFile();
            Files.writeString(javaFile.toPath(), "class Staged {}");
            git.add().addFilepattern("Staged.java").call(); // stage but don't commit

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(baseCommit)
                    .includeUncommitted(false)
                    .includeStaged(true)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);
            Set<String> changed = detector.detectChangedFiles();

            assertTrue(changed.contains("Staged.java"),
                    "Should detect the staged file");
        }
    }

    @Test
    void failsLoudlyOnInvalidBaseRef() throws Exception {
        try (Git ignored = initRepoWithInitialCommit()) {
            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef("nonexistent-ref-abc123")
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    detector::detectChangedFiles);
            assertTrue(ex.getMessage().contains("nonexistent-ref-abc123"),
                    "Error should mention the bad ref");
        }
    }

    @Test
    void returnsEmptyWhenBaseEqualsHead() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String head = git.log().call().iterator().next().getName();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(head)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);
            Set<String> changed = detector.detectChangedFiles();

            assertTrue(changed.isEmpty(), "No changes when base == HEAD");
        }
    }

    @Test
    void detectsUncommittedChanges() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String baseCommit = git.log().call().iterator().next().getName();

            // Create and track a file, then modify it without staging
            File javaFile = tempDir.resolve("Tracked.java").toFile();
            Files.writeString(javaFile.toPath(), "class Tracked {}");
            git.add().addFilepattern("Tracked.java").call();
            git.commit().setMessage("add Tracked").call();

            // Modify without staging
            Files.writeString(javaFile.toPath(), "class Tracked { int x; }");

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(baseCommit)
                    .includeUncommitted(true)
                    .includeStaged(false)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);
            Set<String> changed = detector.detectChangedFiles();

            assertTrue(changed.contains("Tracked.java"),
                    "Should detect the unstaged modification");
        }
    }

    @Test
    void detectsDeletedFilesByOldPath() throws Exception {
        // Regression: before this fix, a pure `git rm` MR produced zero
        // entries in the diff (because DELETE's newPath is /dev/null and
        // the detector only recorded newPath). That routed the whole MR
        // through EMPTY_DIFF, which in LOCAL/CI modes silently skips all
        // tests — the exact "run more, never less" violation the plugin
        // is designed to prevent. Surfacing the old path lets the engine
        // bucket the deletion correctly: ignored globs still ignore it,
        // out-of-scope dirs still ignore it, and production-code deletes
        // reach the transitive strategy like any other change.
        try (Git git = initRepoWithInitialCommit()) {
            File doomed = tempDir.resolve("src/main/java/com/example/Doomed.java").toFile();
            doomed.getParentFile().mkdirs();
            Files.writeString(doomed.toPath(), "package com.example;\npublic class Doomed {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add Doomed").call();

            String baseCommit = git.log().call().iterator().next().getName();

            // Delete the file on a follow-up commit
            git.rm().addFilepattern("src/main/java/com/example/Doomed.java").call();
            git.commit().setMessage("remove Doomed").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(baseCommit)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);
            Set<String> changed = detector.detectChangedFiles();

            assertTrue(changed.contains("src/main/java/com/example/Doomed.java"),
                    "Deleted file must surface through its old path so the engine can bucket it");
        }
    }

    @Test
    void failsLoudlyOnNonGitDirectory() {
        Path nonGitDir = tempDir.resolve("not-a-repo");
        nonGitDir.toFile().mkdirs();

        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        GitChangeDetector detector = new GitChangeDetector(nonGitDir, config);

        assertThrows(IllegalStateException.class, detector::detectChangedFiles,
                "Should throw when the directory is not a git repository");
    }
}
