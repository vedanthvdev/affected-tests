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
 * Separates production sources from test sources and filters by exclusion patterns.
 */
public final class PathToClassMapper {

    private static final Logger log = LoggerFactory.getLogger(PathToClassMapper.class);

    private final AffectedTestsConfig config;
    private final List<PathMatcher> excludeMatchers;

    public PathToClassMapper(AffectedTestsConfig config) {
        this.config = config;
        this.excludeMatchers = config.excludePaths().stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                .toList();
    }

    /**
     * Result of mapping changed files: production classes and test classes.
     *
     * <p>{@link #unmappedChangedFiles()} captures paths that a Java-source
     * walk cannot interpret — typically non-Java files (YAML, build scripts,
     * Liquibase migrations) but also any {@code .java} file outside the
     * configured source/test directories. Files matched by
     * {@link AffectedTestsConfig#excludePaths()} are deliberately omitted so
     * that explicit opt-outs stay silent.
     */
    public record MappingResult(
            Set<String> productionClasses,
            Set<String> testClasses,
            Set<String> changedProductionFiles,
            Set<String> changedTestFiles,
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
        Set<String> unmappedChangedFiles = new LinkedHashSet<>();

        for (String filePath : changedFiles) {
            if (isExcluded(filePath)) {
                // Explicit opt-out — stay silent and do not treat as unmapped.
                log.debug("Excluded by pattern: {}", filePath);
                continue;
            }

            if (!filePath.endsWith(".java")) {
                log.debug("Non-Java file flagged as unmapped: {}", filePath);
                unmappedChangedFiles.add(filePath);
                continue;
            }

            // Check if it's under a test source dir
            String testFqn = tryMapToClass(filePath, config.testDirs());
            if (testFqn != null) {
                testClasses.add(testFqn);
                changedTestFiles.add(filePath);
                log.debug("Mapped test file: {} → {}", filePath, testFqn);
                continue;
            }

            // Check if it's under a production source dir
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

        log.info("Mapped {} production classes and {} test classes from {} changed files ({} unmapped)",
                productionClasses.size(), testClasses.size(), changedFiles.size(), unmappedChangedFiles.size());

        return new MappingResult(productionClasses, testClasses,
                changedProductionFiles, changedTestFiles, unmappedChangedFiles);
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

    private boolean isExcluded(String filePath) {
        java.nio.file.Path path = java.nio.file.Path.of(filePath);
        for (PathMatcher matcher : excludeMatchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
