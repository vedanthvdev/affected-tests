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
    void detectsUncommittedDeletesByOldPath() throws Exception {
        // Symmetric regression: the committed branch started surfacing
        // DELETEs in batch-1, but uncommittedChanges still filtered
        // anything whose newPath was /dev/null. With the v1.9.15
        // committed-only defaults this hole was inert, but any adopter
        // iterating on a local `git rm`-before-commit with
        // includeUncommitted = true would see their MR route through
        // EMPTY_DIFF → SKIPPED under LOCAL/CI mode. Lock in that the
        // unstaged deletion now flows through the normal bucketing.
        try (Git git = initRepoWithInitialCommit()) {
            File doomed = tempDir.resolve("src/main/java/com/example/Doomed.java").toFile();
            doomed.getParentFile().mkdirs();
            Files.writeString(doomed.toPath(), "package com.example;\npublic class Doomed {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add Doomed").call();

            String baseCommit = git.log().call().iterator().next().getName();

            // Delete on disk without staging — this is the
            // "uncommitted" diff that JGit's plain git.diff().call()
            // surfaces.
            assertTrue(doomed.delete(), "fixture precondition");

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(baseCommit)
                    .includeUncommitted(true)
                    .includeStaged(false)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);
            Set<String> changed = detector.detectChangedFiles();

            assertTrue(changed.contains("src/main/java/com/example/Doomed.java"),
                    "Uncommitted deletion must surface through its old path");
        }
    }

    @Test
    void detectsStagedDeletesByOldPath() throws Exception {
        // Same regression as above, on the staged diff source. A
        // developer running `git rm foo.java` without committing puts
        // the deletion in the index, and includeStaged = true must see
        // it for the same reasons the committed branch does.
        try (Git git = initRepoWithInitialCommit()) {
            File doomed = tempDir.resolve("src/main/java/com/example/Doomed.java").toFile();
            doomed.getParentFile().mkdirs();
            Files.writeString(doomed.toPath(), "package com.example;\npublic class Doomed {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add Doomed").call();

            String baseCommit = git.log().call().iterator().next().getName();

            // git rm — the deletion is staged in the index.
            git.rm().addFilepattern("src/main/java/com/example/Doomed.java").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(baseCommit)
                    .includeUncommitted(false)
                    .includeStaged(true)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);
            Set<String> changed = detector.detectChangedFiles();

            assertTrue(changed.contains("src/main/java/com/example/Doomed.java"),
                    "Staged deletion must surface through its old path");
        }
    }

    @Test
    void explainsShallowCloneWhenBaseObjectMissing() throws Exception {
        // Regression for the MissingObjectException branch added in
        // batch-1. Instead of spinning up a shallow clone (slow and
        // relies on system git), we synthesise the exact JGit state a
        // shallow clone produces at the parseCommit call site: a ref
        // that resolves cleanly to an ObjectId whose underlying commit
        // object is not in the local pack. The resolve step succeeds
        // (so we do not hit the "could not resolve baseRef" branch
        // earlier in the chain), but parseCommit throws
        // MissingObjectException the moment we try to walk the tree.
        try (Git git = initRepoWithInitialCommit()) {
            File refsHeads = tempDir.resolve(".git/refs/heads").toFile();
            assertTrue(refsHeads.exists() || refsHeads.mkdirs(), "fixture precondition");
            // Any syntactically valid 40-char hex string that is not a
            // real object in this repo's object store triggers the
            // MissingObjectException at parseCommit time.
            String bogusSha = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
            Files.writeString(new File(refsHeads, "ghost").toPath(), bogusSha + "\n");

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef("ghost")
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    detector::detectChangedFiles);

            String message = error.getMessage();
            assertTrue(message.contains("shallow clone"),
                    "Expected shallow-clone hint in error message, got: " + message);
            assertTrue(message.contains("fetch-depth: 0") && message.contains("GIT_DEPTH: 0"),
                    "Expected both GitHub and GitLab fix hints, got: " + message);
            assertTrue(error.getCause() instanceof org.eclipse.jgit.errors.MissingObjectException,
                    "Expected cause to be MissingObjectException, got: " + error.getCause());
        }
    }

    @Test
    void diffsAgainstMergeBaseNotBaseRefTip() throws Exception {
        // Regression: the old path diffed HEAD directly against the
        // tip of baseRef. If master moved on after the branch
        // diverged, every file that landed on master post-divergence
        // showed up as a "change" on the feature branch — even though
        // the feature branch never touched those files. Modulr
        // typically ships 20-100 merges/day on master, so a week-old
        // feature branch would see the whole backlog of unrelated
        // production changes dumped into its diff, inflating the
        // affected-tests set to essentially "everything" and
        // destroying the whole point of selective testing. The fix
        // computes the merge-base between baseRef and HEAD first, so
        // the diff only reflects what the feature branch actually
        // changed relative to where it diverged.
        try (Git git = initRepoWithInitialCommit()) {
            // --- Shared history: create foo on master ---
            Path foo = tempDir.resolve("src/main/java/com/example/Foo.java");
            Files.createDirectories(foo.getParent());
            Files.writeString(foo, "package com.example;\npublic class Foo {}");
            git.add().addFilepattern(".").call();
            String divergencePoint = git.commit().setMessage("add Foo").call().getName();

            // --- Branch off and commit a feature change ---
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            Path bar = tempDir.resolve("src/main/java/com/example/Bar.java");
            Files.writeString(bar, "package com.example;\npublic class Bar {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("feature: add Bar").call();

            // --- Master advances with an unrelated change ---
            // The feature branch must NOT see this in its diff.
            git.checkout().setName("master").call();
            Path unrelated = tempDir.resolve("src/main/java/com/example/Unrelated.java");
            Files.writeString(unrelated, "package com.example;\npublic class Unrelated {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("master: unrelated change").call();

            // --- Detect changes from feature branch against master ---
            git.checkout().setName("feature").call();
            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef("master")
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            GitChangeDetector detector = new GitChangeDetector(tempDir, config);
            Set<String> changed = detector.detectChangedFiles();

            assertTrue(changed.contains("src/main/java/com/example/Bar.java"),
                    "Feature branch's own change must surface, got: " + changed);
            assertFalse(changed.contains("src/main/java/com/example/Unrelated.java"),
                    "Unrelated master-only change must NOT appear in the "
                            + "feature branch's diff — merge-base is the boundary, "
                            + "got: " + changed);
            assertFalse(divergencePoint.isEmpty(),
                    "sanity: divergence point must exist");
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
