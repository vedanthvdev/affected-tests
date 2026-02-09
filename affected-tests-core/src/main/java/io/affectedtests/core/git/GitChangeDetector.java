package io.affectedtests.core.git;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
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
     */
    public Set<String> detectChangedFiles() {
        Set<String> changedFiles = new LinkedHashSet<>();

        try (Repository repository = new FileRepositoryBuilder()
                    .findGitDir(projectDir.toFile())
                    .build();
             Git git = new Git(repository)) {

            // 1. Committed changes: baseRef..HEAD
            changedFiles.addAll(committedChanges(repository, git));

            // 2. Uncommitted (unstaged) changes
            if (config.includeUncommitted()) {
                changedFiles.addAll(uncommittedChanges(git));
            }

            // 3. Staged changes
            if (config.includeStaged()) {
                changedFiles.addAll(stagedChanges(git));
            }

        } catch (Exception e) {
            log.error("Failed to detect git changes: {}", e.getMessage());
            throw new IllegalStateException(
                    "Affected Tests: unable to detect git changes. "
                    + "Verify the repository exists and the base ref '" + config.baseRef() + "' is valid.",
                    e);
        }

        log.info("Detected {} changed files", changedFiles.size());
        changedFiles.forEach(f -> log.debug("  Changed: {}", f));
        return changedFiles;
    }

    private Set<String> committedChanges(Repository repository, Git git) throws Exception {
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
                case DELETE -> files.add(entry.getOldPath());
                case RENAME -> {
                    files.add(entry.getOldPath());
                    files.add(entry.getNewPath());
                }
            }
        }

        return files;
    }

    private Set<String> uncommittedChanges(Git git) throws Exception {
        Set<String> files = new LinkedHashSet<>();
        List<DiffEntry> diffs = git.diff().call(); // working tree vs index
        for (DiffEntry entry : diffs) {
            if (entry.getNewPath() != null && !entry.getNewPath().equals("/dev/null")) {
                files.add(entry.getNewPath());
            }
            if (entry.getOldPath() != null && !entry.getOldPath().equals("/dev/null")) {
                files.add(entry.getOldPath());
            }
        }
        return files;
    }

    private Set<String> stagedChanges(Git git) throws Exception {
        Set<String> files = new LinkedHashSet<>();
        List<DiffEntry> diffs = git.diff().setCached(true).call(); // index vs HEAD
        for (DiffEntry entry : diffs) {
            if (entry.getNewPath() != null && !entry.getNewPath().equals("/dev/null")) {
                files.add(entry.getNewPath());
            }
            if (entry.getOldPath() != null && !entry.getOldPath().equals("/dev/null")) {
                files.add(entry.getOldPath());
            }
        }
        return files;
    }

    private ObjectId resolveBaseRef(Repository repository) throws IOException {
        // Try resolving as-is first (e.g. "origin/master", a SHA, a tag)
        ObjectId id = repository.resolve(config.baseRef());
        if (id != null) return id;

        // Try with refs/remotes prefix
        id = repository.resolve("refs/remotes/" + config.baseRef());
        return id;
    }

    private AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws Exception {
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
