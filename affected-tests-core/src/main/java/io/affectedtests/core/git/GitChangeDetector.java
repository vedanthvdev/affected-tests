package io.affectedtests.core.git;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects changed files using JGit by comparing the current HEAD (and optionally
 * uncommitted changes) against a configurable base ref.
 *
 * <p>{@code DELETE} changes surface through the old path so a pure-deletion
 * MR is NOT bucketed as an empty diff. The engine's dispatch filter at
 * {@link io.affectedtests.core.AffectedTestsEngine} drops any FQN whose
 * test file no longer exists on disk, so emitting the deleted path is safe
 * — it routes the diff through the normal ignore / out-of-scope / unmapped
 * logic (so a deleted {@code *.md} still gets ignored, a deleted
 * {@code api-test/**} still counts as out-of-scope, and a deleted production
 * class still pushes discovery through the transitive strategy for callers
 * whose tests may have started failing).
 *
 * <p>The old path of a {@code RENAME} is still dropped in favour of the
 * new path alone; the new path is the file currently on disk and the
 * file the diff actually modified, so adding the old path would only
 * double-count.
 */
public final class GitChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(GitChangeDetector.class);

    private final Path projectDir;
    private final AffectedTestsConfig config;

    public GitChangeDetector(Path projectDir, AffectedTestsConfig config) {
        this.projectDir = projectDir;
        this.config = config;
    }

    /**
     * Returns the set of file paths (relative to the repo root) that have changed
     * between the base ref and HEAD, plus optionally uncommitted/staged changes.
     *
     * @throws IllegalStateException if the repository cannot be opened, the base
     *         ref cannot be resolved, or JGit fails to compute the diff. The
     *         underlying cause is chained so CI logs can surface the root cause.
     */
    public Set<String> detectChangedFiles() {
        Set<String> changedFiles = new LinkedHashSet<>();

        try (Repository repository = new FileRepositoryBuilder()
                    .findGitDir(projectDir.toFile())
                    .build();
             Git git = new Git(repository)) {

            changedFiles.addAll(committedChanges(repository, git));

            if (config.includeUncommitted()) {
                changedFiles.addAll(uncommittedChanges(git));
            }

            if (config.includeStaged()) {
                changedFiles.addAll(stagedChanges(git));
            }

        } catch (NoWorkTreeException | IllegalArgumentException e) {
            // IllegalArgumentException is what FileRepositoryBuilder.build()
            // throws when findGitDir() couldn't locate a .git — i.e. the path
            // is not inside a git work tree at all.
            throw new IllegalStateException(
                    "Affected Tests: directory '" + projectDir + "' is not a git work tree.", e);
        } catch (MissingObjectException e) {
            // Surfaces separately from generic IOException because this is
            // overwhelmingly the "shallow clone" failure mode — JGit resolved
            // the ref to an ObjectId but the commit object isn't in the local
            // pack. The actions/checkout default is fetch-depth=1, so the
            // first CI run on a new pipeline hits this and the generic
            // "I/O error while reading git metadata" message sends operators
            // hunting for disk or JGit problems instead of the real fix.
            throw new IllegalStateException(
                    "Affected Tests: base commit " + e.getObjectId().name()
                            + " for ref '" + config.baseRef() + "' is not available locally. "
                            + "This usually means the repository is a shallow clone. "
                            + "Run 'git fetch --unshallow' locally, or set fetch-depth: 0 on "
                            + "actions/checkout (GitHub) / GIT_DEPTH: 0 (GitLab).",
                    e);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Affected Tests: I/O error while reading git metadata at '" + projectDir + "': "
                            + e.getMessage(), e);
        } catch (GitAPIException e) {
            throw new IllegalStateException(
                    "Affected Tests: git diff against '" + config.baseRef() + "' failed: "
                            + e.getMessage(), e);
        }

        log.info("Detected {} changed files", changedFiles.size());
        changedFiles.forEach(f -> log.debug("  Changed: {}", f));
        return changedFiles;
    }

    private Set<String> committedChanges(Repository repository, Git git) throws IOException, GitAPIException {
        Set<String> files = new LinkedHashSet<>();

        ObjectId headId = repository.resolve("HEAD");
        if (headId == null) {
            log.warn("HEAD not found — is this an empty repository?");
            return files;
        }

        ObjectId baseId = resolveBaseRef(repository);
        if (baseId == null) {
            throw new IllegalStateException(
                    "Base ref '" + config.baseRef() + "' could not be resolved. "
                    + "Ensure the ref exists (e.g. run 'git fetch origin' in CI).");
        }

        AbstractTreeIterator baseTree = prepareTreeParser(repository, baseId);
        AbstractTreeIterator headTree = prepareTreeParser(repository, headId);

        List<DiffEntry> diffs = git.diff()
                .setOldTree(baseTree)
                .setNewTree(headTree)
                .call();

        for (DiffEntry entry : diffs) {
            switch (entry.getChangeType()) {
                case ADD, COPY, MODIFY -> files.add(entry.getNewPath());
                case DELETE -> {
                    // Before we surfaced DELETEs, a pure 'git rm' MR routed
                    // through EMPTY_DIFF and — in LOCAL/CI modes — silently
                    // skipped all tests. Now the old path flows through the
                    // normal bucketing so ignore/out-of-scope rules still
                    // apply (deleted '*.md' stays ignored, deleted
                    // 'api-test/**' stays out-of-scope, deleted production
                    // classes reach the transitive strategy). The engine's
                    // existing filter at AffectedTestsEngine drops any
                    // discovered FQN whose test file no longer exists, so
                    // surfacing the deleted path never asks Gradle to run a
                    // missing test.
                    files.add(entry.getOldPath());
                }
                case RENAME -> files.add(entry.getNewPath());
            }
        }

        return files;
    }

    private Set<String> uncommittedChanges(Git git) throws GitAPIException {
        Set<String> files = new LinkedHashSet<>();
        List<DiffEntry> diffs = git.diff().call();
        for (DiffEntry entry : diffs) {
            // Only include new paths (i.e. the file as it exists on disk now).
            // Skipping old paths prevents us from trying to run tests for files
            // that the developer has deleted locally.
            String newPath = entry.getNewPath();
            if (newPath != null && !newPath.equals("/dev/null")) {
                files.add(newPath);
            }
        }
        return files;
    }

    private Set<String> stagedChanges(Git git) throws GitAPIException {
        Set<String> files = new LinkedHashSet<>();
        List<DiffEntry> diffs = git.diff().setCached(true).call();
        for (DiffEntry entry : diffs) {
            String newPath = entry.getNewPath();
            if (newPath != null && !newPath.equals("/dev/null")) {
                files.add(newPath);
            }
        }
        return files;
    }

    private ObjectId resolveBaseRef(Repository repository) throws IOException {
        ObjectId id = repository.resolve(config.baseRef());
        if (id != null) return id;

        id = repository.resolve("refs/remotes/" + config.baseRef());
        return id;
    }

    private AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(objectId);
            ObjectId treeId = commit.getTree().getId();

            try (ObjectReader reader = repository.newObjectReader()) {
                CanonicalTreeParser parser = new CanonicalTreeParser();
                parser.reset(reader, treeId);
                return parser;
            }
        }
    }
}
