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
 * <p>This class centralises the file-walking logic that was previously
 * duplicated across every {@link TestDiscoveryStrategy} implementation.
 */
public final class SourceFileScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceFileScanner.class);

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
     * scanning both the project root and one level of sub-modules.
     *
     * @param projectDir the root project directory
     * @param sourceDirs source directory suffixes (e.g. {@code ["src/main/java"]})
     * @return list of Java source files
     */
    public static List<Path> collectSourceFiles(Path projectDir, List<String> sourceDirs) {
        List<Path> files = new ArrayList<>();
        for (String sourceDir : sourceDirs) {
            Path sourcePath = projectDir.resolve(sourceDir);
            if (Files.isDirectory(sourcePath)) {
                collectJavaFiles(sourcePath, files);
            }
            collectFromSubModules(projectDir, sourceDir, files);
        }
        return files;
    }

    // ── Test-file collection ────────────────────────────────────────────

    /**
     * Collects all test Java files from the given test directories,
     * scanning both the project root and one level of sub-modules.
     *
     * @param projectDir the root project directory
     * @param testDirs   test directory suffixes (e.g. {@code ["src/test/java"]})
     * @return list of Java test files
     */
    public static List<Path> collectTestFiles(Path projectDir, List<String> testDirs) {
        List<Path> files = new ArrayList<>();
        for (String testDir : testDirs) {
            Path testPath = projectDir.resolve(testDir);
            if (Files.isDirectory(testPath)) {
                collectJavaFiles(testPath, files);
            }
            collectFromSubModules(projectDir, testDir, files);
        }
        return files;
    }

    // ── Test FQN scanning ───────────────────────────────────────────────

    /**
     * Scans all configured test directories and returns the FQNs of every
     * {@code .java} file found. Handles both root-level and sub-module directories.
     *
     * @param projectDir the root project directory
     * @param testDirs   test directory suffixes
     * @return ordered set of test class FQNs
     */
    public static Set<String> scanTestFqns(Path projectDir, List<String> testDirs) {
        Set<String> fqns = new LinkedHashSet<>();

        for (String testDir : testDirs) {
            Path testPath = projectDir.resolve(testDir);
            if (Files.isDirectory(testPath)) {
                fqns.addAll(fqnsUnder(testPath));
            }
            // Sub-modules
            forEachSubModule(projectDir, moduleDir -> {
                Path moduleTestPath = moduleDir.resolve(testDir);
                if (Files.isDirectory(moduleTestPath)) {
                    fqns.addAll(fqnsUnder(moduleTestPath));
                }
            });
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
     * Iterates one level of sub-module directories under {@code projectDir}
     * and collects Java files from the given relative directory inside each.
     */
    private static void collectFromSubModules(Path projectDir, String relativeDir, List<Path> result) {
        forEachSubModule(projectDir, moduleDir -> {
            Path modulePath = moduleDir.resolve(relativeDir);
            if (Files.isDirectory(modulePath)) {
                collectJavaFiles(modulePath, result);
            }
        });
    }

    /**
     * Iterates the immediate child directories of {@code projectDir} (depth 1).
     */
    private static void forEachSubModule(Path projectDir, java.util.function.Consumer<Path> action) {
        try (var dirs = Files.walk(projectDir, 1)) {
            dirs.filter(Files::isDirectory)
                .filter(d -> !d.equals(projectDir))
                .forEach(action);
        } catch (IOException e) {
            log.warn("Error scanning sub-module directories under {}: {}", projectDir, e.getMessage());
        }
    }
}
