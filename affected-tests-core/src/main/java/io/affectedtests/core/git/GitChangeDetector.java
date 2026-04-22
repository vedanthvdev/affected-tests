package io.affectedtests.core.git;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
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
import org.eclipse.jgit.revwalk.filter.RevFilter;
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
            // JGit IOException messages can embed pack-file paths, ref names,
            // or (on PackInvalidException / CorruptObjectException) raw bytes
            // from the object database — all attacker-influenceable on an MR
            // branch. Sanitise before embedding so the exception message,
            // which Gradle renders verbatim into the build log, cannot carry
            // a forged status line.
            throw new IllegalStateException(
                    "Affected Tests: I/O error while reading git metadata at '" + projectDir + "': "
                            + LogSanitizer.sanitize(e.getMessage()), e);
        } catch (GitAPIException e) {
            throw new IllegalStateException(
                    "Affected Tests: git diff against '" + config.baseRef() + "' failed: "
                            + LogSanitizer.sanitize(e.getMessage()), e);
        }

        log.info("Detected {} changed files", changedFiles.size());
        // File paths come straight from git diff entries on an
        // attacker-controllable MR branch, so sanitise even at DEBUG
        // — same rationale as every other diff-sourced log site.
        changedFiles.forEach(f -> log.debug("  Changed: {}", LogSanitizer.sanitize(f)));
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

        // Diff against the merge-base of baseRef and HEAD, not the tip
        // of baseRef. The difference matters any time master has moved
        // forward since the branch diverged: a tip-of-base diff treats
        // every post-divergence file added on master as "reverted" on
        // the feature branch, inflating the diff with paths the MR
        // never touched. In a large monorepo this routinely puts
        // hundreds of unrelated files into the mapper and — because
        // many of them are `.java` under the configured source dirs —
        // silently drags dozens of unrelated test classes into every
        // MR's dispatch. Merge-base matches how `git diff master...HEAD`
        // (three-dot form, CI-standard) computes the MR scope.
        ObjectId mergeBaseId = mergeBaseOrBase(repository, baseId, headId);
        AbstractTreeIterator baseTree = prepareTreeParser(repository, mergeBaseId);
        AbstractTreeIterator headTree = prepareTreeParser(repository, headId);

        List<DiffEntry> diffs = git.diff()
                .setOldTree(baseTree)
                .setNewTree(headTree)
                .call();

        collectPaths(diffs, files);
        return files;
    }

    /**
     * Returns the merge-base of {@code baseId} and {@code headId}, or
     * {@code baseId} as a safe fallback when no common ancestor exists
     * (unrelated histories, root-commit branches). The fallback keeps
     * behaviour identical to pre-v1.9.20 for the one genuinely
     * pathological case where there is nothing to merge-base against.
     */
    private static ObjectId mergeBaseOrBase(Repository repository,
                                            ObjectId baseId, ObjectId headId) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit baseCommit = walk.parseCommit(baseId);
            RevCommit headCommit = walk.parseCommit(headId);
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(baseCommit);
            walk.markStart(headCommit);
            RevCommit mergeBase = walk.next();
            if (mergeBase != null) {
                log.debug("Resolved merge-base {} between baseRef and HEAD",
                        mergeBase.getId().name());
                return mergeBase.getId();
            }
            // WARN (not DEBUG) because this is a correctness-equivalent
            // but semantically different code path: the diff shape is
            // whatever `baseRef tip vs HEAD` produces, which on grafted
            // or subtree-merged histories can dump the entire baseRef
            // tree. Operators need to see the escalation, not hunt for
            // it at DEBUG.
            log.warn("Affected Tests: no merge-base between baseRef and HEAD — "
                    + "falling back to baseRef tip (diff may be inflated; see "
                    + "grafted/subtree-merge histories). This mirrors pre-v1.9.20 behaviour.");
            return baseId;
        }
    }

    private Set<String> uncommittedChanges(Git git) throws GitAPIException {
        Set<String> files = new LinkedHashSet<>();
        collectPaths(git.diff().call(), files);
        return files;
    }

    private Set<String> stagedChanges(Git git) throws GitAPIException {
        Set<String> files = new LinkedHashSet<>();
        collectPaths(git.diff().setCached(true).call(), files);
        return files;
    }

    /**
     * Shared diff-entry bucketing for the three diff sources (committed,
     * uncommitted, staged). Keeping one implementation is the whole
     * point of this helper: before it existed, the committed branch
     * routed DELETEs through {@link DiffEntry#getOldPath()} so pure
     * {@code git rm} MRs reached the bucketing pipeline, while the
     * uncommitted and staged branches kept the older "skip anything
     * whose newPath is /dev/null" shape. That asymmetry was inert
     * under the v1.9.15 defaults ({@code includeUncommitted = false},
     * {@code includeStaged = false}), but any adopter who flipped
     * either knob back on to iterate on a local deletion hit exactly
     * the silent-skip hole the committed branch was patched to close.
     *
     * <p>Surfacing the old path never asks Gradle to run a missing
     * test — the engine's existing missing-file filter at
     * {@code AffectedTestsEngine} drops any discovered FQN whose
     * backing file is gone.
     */
    private static void collectPaths(List<DiffEntry> diffs, Set<String> files) {
        for (DiffEntry entry : diffs) {
            switch (entry.getChangeType()) {
                case ADD, COPY, MODIFY, RENAME -> files.add(entry.getNewPath());
                case DELETE -> files.add(entry.getOldPath());
            }
        }
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
