package io.affectedtests.core.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Shared utility for scanning source and test directories in single-module
 * and multi-module Gradle/Maven projects.
 *
 * <p>This class centralises the file-walking logic used by every
 * {@link TestDiscoveryStrategy} implementation. It is project-structure-agnostic:
 * modules can be nested at any depth (e.g. {@code services/payment/src/test/java}).
 */
public final class SourceFileScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceFileScanner.class);

    /**
     * Directories that should never be descended into when searching for modules.
     * Covers Gradle/Maven build output, IDE metadata, VCS, and common tooling
     * caches from Node, Python and JS test runners that may coexist in the repo.
     */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".gradle", ".idea", ".vscode", ".cursor",
            "build", "out", "target",
            "node_modules", "dist", "coverage", ".nx",
            "venv", ".venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".tox"
    );

    private SourceFileScanner() {
        // utility class
    }

    // ── Java file collection ────────────────────────────────────────────

    /**
     * Recursively collects all {@code .java} files under {@code dir}.
     *
     * @param dir root directory to walk
     * @return list of paths to {@code .java} files
     */
    public static List<Path> collectJavaFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        collectJavaFiles(dir, files);
        return files;
    }

    /**
     * Recursively collects all {@code .java} files under {@code dir},
     * appending them to the provided list.
     */
    public static void collectJavaFiles(Path dir, List<Path> result) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error collecting Java files from {}: {}", dir, e.getMessage());
        }
    }

    // ── Source-file collection (production) ──────────────────────────────

    /**
     * Collects all production Java files from the given source directories,
     * scanning the entire project tree at any depth.
     *
     * @param projectDir the root project directory
     * @param sourceDirs source directory suffixes (e.g. {@code ["src/main/java"]})
     * @return list of Java source files
     */
    public static List<Path> collectSourceFiles(Path projectDir, List<String> sourceDirs) {
        List<Path> files = new ArrayList<>();
        for (String sourceDir : sourceDirs) {
            for (Path resolved : findAllMatchingDirs(projectDir, sourceDir)) {
                collectJavaFiles(resolved, files);
            }
        }
        return files;
    }

    // ── Test-file collection ────────────────────────────────────────────

    /**
     * Collects all test Java files from the given test directories,
     * scanning the entire project tree at any depth.
     *
     * @param projectDir the root project directory
     * @param testDirs   test directory suffixes (e.g. {@code ["src/test/java"]})
     * @return list of Java test files
     */
    public static List<Path> collectTestFiles(Path projectDir, List<String> testDirs) {
        List<Path> files = new ArrayList<>();
        for (String testDir : testDirs) {
            for (Path resolved : findAllMatchingDirs(projectDir, testDir)) {
                collectJavaFiles(resolved, files);
            }
        }
        return files;
    }

    // ── Test FQN scanning ───────────────────────────────────────────────

    /**
     * Scans all configured test directories and returns the FQNs of every
     * {@code .java} file found.
     */
    public static Set<String> scanTestFqns(Path projectDir, List<String> testDirs) {
        return scanTestFqnsWithFiles(projectDir, testDirs).keySet();
    }

    /**
     * Scans all configured test directories and returns an ordered map of
     * test class FQN to its absolute file path. Preserves discovery order.
     *
     * @param projectDir the root project directory
     * @param testDirs   test directory suffixes
     * @return ordered map of test class FQN to its absolute file path
     */
    public static LinkedHashMap<String, Path> scanTestFqnsWithFiles(Path projectDir, List<String> testDirs) {
        LinkedHashMap<String, Path> result = new LinkedHashMap<>();
        for (String testDir : testDirs) {
            for (Path resolved : findAllMatchingDirs(projectDir, testDir)) {
                walkFqnsUnder(resolved, result);
            }
        }
        return result;
    }

    // ── FQN helpers ─────────────────────────────────────────────────────

    /**
     * Converts {@code .java} files under a source-root directory to FQNs.
     */
    public static Set<String> fqnsUnder(Path sourceRoot) {
        LinkedHashMap<String, Path> result = new LinkedHashMap<>();
        walkFqnsUnder(sourceRoot, result);
        return result.keySet();
    }

    private static void walkFqnsUnder(Path sourceRoot, Map<String, Path> accumulator) {
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        Path relative = sourceRoot.relativize(file);
                        String fqn = relative.toString()
                                .replace(java.io.File.separatorChar, '.')
                                .replace('/', '.');
                        if (fqn.endsWith(".java")) {
                            fqn = fqn.substring(0, fqn.length() - 5);
                        }
                        accumulator.putIfAbsent(fqn, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error scanning directory {}: {}", sourceRoot, e.getMessage());
        }
    }

    /**
     * Returns the simple class name from a fully-qualified name.
     */
    public static String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    /**
     * Returns the package portion of a fully-qualified name.
     */
    public static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(0, dot) : "";
    }

    // ── FQN extraction from file paths ────────────────────────────────

    /**
     * Derives a fully-qualified class name from a file path by finding the
     * first matching source directory suffix and stripping the prefix + suffix.
     *
     * @param file       absolute path to the Java file
     * @param sourceDirs source directory suffixes (e.g. {@code ["src/main/java"]})
     * @return the FQN, or {@code null} if no source dir matched
     */
    public static String pathToFqn(Path file, List<String> sourceDirs) {
        String filePath = file.toString().replace(java.io.File.separatorChar, '/');
        for (String sourceDir : sourceDirs) {
            String normalizedDir = sourceDir.replace('\\', '/');
            if (!normalizedDir.endsWith("/")) normalizedDir += "/";

            int idx = filePath.indexOf(normalizedDir);
            if (idx >= 0) {
                String relative = filePath.substring(idx + normalizedDir.length());
                if (relative.endsWith(".java")) {
                    relative = relative.substring(0, relative.length() - 5);
                }
                return relative.replace('/', '.');
            }
        }
        return null;
    }

    /**
     * Finds the root directory of a test source tree by walking up the parent
     * chain until a directory matching the given test directory suffix is found.
     *
     * @param file    absolute path to the test file
     * @param testDir test directory suffix (e.g. {@code "src/test/java"})
     * @return the matched test root path, or {@code null} if not found
     */
    public static Path findTestRoot(Path file, String testDir) {
        Path current = file.getParent();
        String normalizedTestDir = testDir.replace('/', java.io.File.separatorChar);
        while (current != null) {
            if (current.toString().endsWith(normalizedTestDir)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /**
     * Recursively walks the project tree and returns every directory that
     * matches the given relative suffix (e.g. {@code "src/test/java"}).
     *
     * <p>Directories listed in {@link #SKIP_DIRS} are pruned during the walk.
     *
     * <p>Candidates whose real path escapes the project root (typically
     * because a module's source directory is a symlink planted by an
     * attacker-controlled MR branch) are silently dropped — the plugin
     * runs on merge-gate CI against untrusted trees, and a
     * {@code src/main/java -> /} symlink would otherwise make
     * {@link Files#walkFileTree} scan the entire CI runner's filesystem
     * (DoS + path-structure leakage into {@code --explain} output).
     * See {@link #stayInsideProjectRoot}.
     *
     * @param projectDir  the root project directory
     * @param relativeDir the directory suffix to look for (e.g. {@code "src/test/java"})
     * @return list of absolute paths that exist and match
     */
    static List<Path> findAllMatchingDirs(Path projectDir, String relativeDir) {
        List<Path> matches = new ArrayList<>();

        Path projectRoot = realPathOrNull(projectDir);

        Path rootMatch = projectDir.resolve(relativeDir);
        if (stayInsideProjectRoot(rootMatch, projectRoot)) {
            matches.add(rootMatch);
        }

        try {
            Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (SKIP_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    if (dir.equals(projectDir)) {
                        return FileVisitResult.CONTINUE;
                    }

                    Path candidate = dir.resolve(relativeDir);
                    if (stayInsideProjectRoot(candidate, projectRoot)) {
                        matches.add(candidate);
                        // Don't descend into the source tree itself — there are no
                        // sub-modules inside src/main/java/com/example/...
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error searching for '{}' under {}: {}", relativeDir, projectDir, e.getMessage());
        }

        log.debug("Found {} directories matching '{}' under {}", matches.size(), relativeDir, projectDir);
        return matches;
    }

    /**
     * Returns {@code true} iff {@code candidate} exists, is a directory
     * when dereferenced, and — after symlink resolution — still lives
     * under {@code projectRoot}. The real-path containment check is the
     * load-bearing guard against an attacker-planted
     * {@code src/main/java -> /} symlink in a merge-gate MR; a plain
     * {@link Files#isDirectory(Path, java.nio.file.LinkOption...)} call
     * without {@link LinkOption#NOFOLLOW_LINKS} would follow the
     * symlink and produce a match pointing at the CI runner's
     * filesystem root, which a subsequent {@link #collectJavaFiles}
     * would then happily enumerate.
     *
     * <p>When {@code projectRoot} is {@code null} (e.g. the project
     * directory itself cannot be canonicalised) we fall back to the
     * pre-hardening behaviour — {@link Files#isDirectory} alone — so
     * legitimate users on unusual filesystems don't silently lose
     * discovery. The symlink hazard is specific to CI contexts where
     * the tree is attacker-controlled, and in that threat model
     * {@code toRealPath} on the project root is always well-defined.
     */
    static boolean stayInsideProjectRoot(Path candidate, Path projectRoot) {
        if (!Files.isDirectory(candidate)) {
            return false;
        }
        if (projectRoot == null) {
            return true;
        }
        Path candidateReal = realPathOrNull(candidate);
        if (candidateReal == null) {
            return false;
        }
        return candidateReal.startsWith(projectRoot);
    }

    private static Path realPathOrNull(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
}
