package io.affectedtests.core.mapping;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps file paths (from git diff output) to fully-qualified Java class names.
 * Separates production sources from test sources, tags files as ignored /
 * out-of-scope where the config says so, and surfaces everything else as
 * unmapped.
 *
 * <p>The five mutually-exclusive buckets of {@link MappingResult} are what
 * {@link io.affectedtests.core.AffectedTestsEngine} uses to pick a
 * {@link io.affectedtests.core.config.Situation}. The mapper MUST NOT drop
 * a changed file into silence: every input path appears in exactly one
 * bucket, so the engine can always answer "why did we route to X?" with
 * one bucket count.
 */
public final class PathToClassMapper {

    private static final Logger log = LoggerFactory.getLogger(PathToClassMapper.class);

    private final AffectedTestsConfig config;
    private final List<PathMatcher> ignoreMatchers;

    public PathToClassMapper(AffectedTestsConfig config) {
        this.config = config;
        this.ignoreMatchers = config.ignorePaths().stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                .toList();
    }

    /**
     * Result of mapping changed files, split into five mutually-exclusive
     * buckets so the engine can map any diff to exactly one
     * {@link io.affectedtests.core.config.Situation}.
     *
     * <p>{@link #ignoredFiles()} captures paths matched by
     * {@link AffectedTestsConfig#ignorePaths()}. {@link #outOfScopeFiles()}
     * captures paths that sit under
     * {@link AffectedTestsConfig#outOfScopeTestDirs()} or
     * {@link AffectedTestsConfig#outOfScopeSourceDirs()}.
     * {@link #unmappedChangedFiles()} is the "fallthrough" bucket — any
     * file that wasn't ignored, out-of-scope, a production Java source or
     * a test Java source ends up here (YAML, build scripts, Liquibase
     * migrations, stray {@code .java} files outside the configured source
     * trees).
     */
    public record MappingResult(
            Set<String> productionClasses,
            Set<String> testClasses,
            Set<String> changedProductionFiles,
            Set<String> changedTestFiles,
            Set<String> ignoredFiles,
            Set<String> outOfScopeFiles,
            Set<String> unmappedChangedFiles
    ) {}

    /**
     * Maps a set of changed file paths to production and test class FQNs.
     *
     * @param changedFiles relative file paths from git diff
     * @return mapping result with production and test class FQNs
     */
    public MappingResult mapChangedFiles(Set<String> changedFiles) {
        Set<String> productionClasses = new LinkedHashSet<>();
        Set<String> testClasses = new LinkedHashSet<>();
        Set<String> changedProductionFiles = new LinkedHashSet<>();
        Set<String> changedTestFiles = new LinkedHashSet<>();
        Set<String> ignoredFiles = new LinkedHashSet<>();
        Set<String> outOfScopeFiles = new LinkedHashSet<>();
        Set<String> unmappedChangedFiles = new LinkedHashSet<>();

        for (String filePath : changedFiles) {
            // Ignore rules are evaluated FIRST: a user's explicit
            // {@code ignorePaths} entry is a contract that nothing about
            // the file should influence the engine, including nudging it
            // into the out-of-scope bucket.
            if (isIgnored(filePath)) {
                ignoredFiles.add(filePath);
                log.debug("Ignored by pattern: {}", filePath);
                continue;
            }

            // Out-of-scope dirs are evaluated BEFORE the Java mapper so a
            // {@code .java} file under {@code api-test/src/test/java} is
            // not mis-filed as an in-scope test class. Source-dir check is
            // first because real code is more common in diffs than test
            // code under an out-of-scope test dir.
            if (isUnder(filePath, config.outOfScopeSourceDirs())
                    || isUnder(filePath, config.outOfScopeTestDirs())) {
                outOfScopeFiles.add(filePath);
                log.debug("Out-of-scope: {}", filePath);
                continue;
            }

            if (!filePath.endsWith(".java")) {
                log.debug("Non-Java file flagged as unmapped: {}", filePath);
                unmappedChangedFiles.add(filePath);
                continue;
            }

            String testFqn = tryMapToClass(filePath, config.testDirs());
            if (testFqn != null) {
                testClasses.add(testFqn);
                changedTestFiles.add(filePath);
                log.debug("Mapped test file: {} → {}", filePath, testFqn);
                continue;
            }

            String prodFqn = tryMapToClass(filePath, config.sourceDirs());
            if (prodFqn != null) {
                productionClasses.add(prodFqn);
                changedProductionFiles.add(filePath);
                log.debug("Mapped production file: {} → {}", filePath, prodFqn);
                continue;
            }

            // A .java file outside the configured source/test dirs — still
            // unmappable, still a potential safety escalation trigger.
            log.debug("Java file outside configured source/test dirs flagged as unmapped: {}", filePath);
            unmappedChangedFiles.add(filePath);
        }

        log.info("Mapped {} production, {} test, {} ignored, {} out-of-scope, {} unmapped "
                        + "(total {} changed files)",
                productionClasses.size(), testClasses.size(),
                ignoredFiles.size(), outOfScopeFiles.size(), unmappedChangedFiles.size(),
                changedFiles.size());

        return new MappingResult(productionClasses, testClasses,
                changedProductionFiles, changedTestFiles,
                ignoredFiles, outOfScopeFiles, unmappedChangedFiles);
    }

    /**
     * Tries to map a file path to an FQN given a list of source directories.
     * Handles multi-module paths like "module/src/main/java/com/example/Foo.java".
     *
     * <p>Matching is <em>boundary-aware</em> — the source dir must either
     * start the path or be preceded by {@code '/'}. A plain
     * {@code indexOf(sourceDir)} would happily pick up
     * {@code "notsrc/main/java/Foo.java"} and classify it as production
     * {@code Foo}, which in turn would keep it out of the "unmapped"
     * bucket that drives the {@code runAllOnNonJavaChange} safety net —
     * exactly the silent-skip behaviour that flag exists to prevent.
     */
    private String tryMapToClass(String filePath, java.util.List<String> sourceDirs) {
        String normalized = filePath.replace('\\', '/');

        for (String sourceDir : sourceDirs) {
            String normalizedDir = sourceDir.replace('\\', '/');
            if (!normalizedDir.endsWith("/")) {
                normalizedDir += "/";
            }

            int idx;
            if (normalized.startsWith(normalizedDir)) {
                idx = 0;
            } else {
                int boundary = normalized.indexOf("/" + normalizedDir);
                if (boundary < 0) {
                    continue;
                }
                idx = boundary + 1;
            }

            String relativePath = normalized.substring(idx + normalizedDir.length());
            if (relativePath.endsWith(".java")) {
                relativePath = relativePath.substring(0, relativePath.length() - 5);
            }
            return relativePath.replace('/', '.');
        }
        return null;
    }

    /**
     * Extracts the module prefix from a file path (e.g., "api" from "api/src/main/java/...").
     * Uses boundary-aware matching to avoid false positives when a source directory name
     * appears as a substring of a module name (e.g., "someapi/src/main/java" won't match "api/src/main/java").
     *
     * @param filePath the relative file path from git diff
     * @return the module prefix, or empty string if no module prefix
     */
    public String extractModule(String filePath) {
        String normalized = filePath.replace('\\', '/');
        String module = extractModuleFromDirs(normalized, config.sourceDirs());
        if (!module.isEmpty()) {
            return module;
        }
        return extractModuleFromDirs(normalized, config.testDirs());
    }

    private String extractModuleFromDirs(String normalizedPath, java.util.List<String> dirs) {
        for (String dir : dirs) {
            String normalizedDir = dir.replace('\\', '/');
            // Ensure the source dir is preceded by a "/" boundary or is at the start
            String withSlash = "/" + normalizedDir;
            int idx = normalizedPath.indexOf(withSlash);
            if (idx > 0) {
                // Module is everything before the "/" that precedes the source dir
                return normalizedPath.substring(0, idx);
            }
            // Also check if the path starts directly with the source dir (no module prefix)
            if (normalizedPath.startsWith(normalizedDir)) {
                return "";
            }
        }
        return "";
    }

    private boolean isIgnored(String filePath) {
        java.nio.file.Path path = java.nio.file.Path.of(filePath);
        for (PathMatcher matcher : ignoreMatchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Boundary-aware "is this file under any of the given dirs?" check.
     * Uses the same normalisation as {@link #tryMapToClass} so
     * {@code "api-test/src/test/java/..."} matches {@code "api-test/src/test/java"}
     * but {@code "my-api-test/..."} does not.
     */
    private static boolean isUnder(String filePath, List<String> dirs) {
        if (dirs.isEmpty()) return false;
        String normalized = filePath.replace('\\', '/');
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            String normalizedDir = dir.replace('\\', '/');
            if (!normalizedDir.endsWith("/")) normalizedDir += "/";
            if (normalized.startsWith(normalizedDir)) return true;
            if (normalized.contains("/" + normalizedDir)) return true;
        }
        return false;
    }
}
