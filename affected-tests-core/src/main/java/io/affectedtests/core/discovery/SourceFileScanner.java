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

    /** Directories that should never be descended into when searching for modules. */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".gradle", ".idea", "build", "out", "node_modules", ".cursor"
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
     * <p>For example, with {@code sourceDirs = ["src/main/java"]}, this finds
     * Java files under:
     * <ul>
     *   <li>{@code root/src/main/java/}</li>
     *   <li>{@code root/api/src/main/java/}</li>
     *   <li>{@code root/services/payment/src/main/java/}</li>
     * </ul>
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
     * {@code .java} file found. Handles root-level, flat, and deeply nested
     * module directories.
     *
     * @param projectDir the root project directory
     * @param testDirs   test directory suffixes
     * @return ordered set of test class FQNs
     */
    public static Set<String> scanTestFqns(Path projectDir, List<String> testDirs) {
        Set<String> fqns = new LinkedHashSet<>();
        for (String testDir : testDirs) {
            for (Path resolved : findAllMatchingDirs(projectDir, testDir)) {
                fqns.addAll(fqnsUnder(resolved));
            }
        }
        return fqns;
    }

    // ── FQN helpers ─────────────────────────────────────────────────────

    /**
     * Converts {@code .java} files under a source-root directory to FQNs.
     */
    public static Set<String> fqnsUnder(Path sourceRoot) {
        Set<String> fqns = new LinkedHashSet<>();
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
                        fqns.add(fqn);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error scanning directory {}: {}", sourceRoot, e.getMessage());
        }
        return fqns;
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

    // ── Internal helpers ────────────────────────────────────────────────

    /**
     * Recursively walks the project tree and returns every directory that
     * matches the given relative suffix (e.g. {@code "src/test/java"}).
     *
     * <p>This replaces the old depth-1 sub-module scan. It finds modules at
     * any nesting level — flat ({@code api/src/test/java}), nested
     * ({@code services/payment/src/test/java}), or root ({@code src/test/java}).
     *
     * <p>Directories listed in {@link #SKIP_DIRS} are pruned during the walk
     * to avoid scanning build output, VCS metadata, and other irrelevant trees.
     *
     * @param projectDir  the root project directory
     * @param relativeDir the directory suffix to look for (e.g. {@code "src/test/java"})
     * @return list of absolute paths that exist and match
     */
    static List<Path> findAllMatchingDirs(Path projectDir, String relativeDir) {
        List<Path> matches = new ArrayList<>();

        // Check the root itself first
        Path rootMatch = projectDir.resolve(relativeDir);
        if (Files.isDirectory(rootMatch)) {
            matches.add(rootMatch);
        }

        // Walk the tree looking for sub-module directories that contain relativeDir
        try {
            Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip irrelevant directories
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (SKIP_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // Don't re-check the root (already handled above)
                    if (dir.equals(projectDir)) {
                        return FileVisitResult.CONTINUE;
                    }

                    // Check if this directory, when combined with relativeDir, yields an
                    // existing directory. If so, record it and skip descending further into
                    // the matched source tree (its contents are Java packages, not modules).
                    Path candidate = dir.resolve(relativeDir);
                    if (Files.isDirectory(candidate)) {
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
}
