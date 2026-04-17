package io.affectedtests.core.git;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
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
 * <p>Paths for {@code DELETE} changes and the old path of a {@code RENAME} are
 * intentionally omitted from the result. Including them would lead the engine to
 * try and re-run tests that no longer exist on disk, which Gradle rejects with
 * {@code No tests found for given includes}.
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
                    // Deleted files have no matching class on disk; adding them would
                    // cause downstream "No tests found" failures for deleted tests
                    // and is pointless for deleted production classes.
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
