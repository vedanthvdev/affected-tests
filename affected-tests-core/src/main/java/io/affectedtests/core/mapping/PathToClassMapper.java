package io.affectedtests.core.mapping;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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
    private final List<Predicate<String>> outOfScopeTestMatchers;
    private final List<Predicate<String>> outOfScopeSourceMatchers;

    public PathToClassMapper(AffectedTestsConfig config) {
        this.config = config;
        this.ignoreMatchers = config.ignorePaths().stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                .toList();
        // Out-of-scope dirs are compiled once, up-front, so every diff
        // pays only the matcher cost instead of re-parsing the config
        // strings per-file. The pre-compile step is also where we decide
        // between glob and literal-prefix semantics, so users can mix
        // both shapes in the same list without surprise.
        this.outOfScopeTestMatchers = compileOutOfScopeMatchers(config.outOfScopeTestDirs());
        this.outOfScopeSourceMatchers = compileOutOfScopeMatchers(config.outOfScopeSourceDirs());
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
            if (matchesAny(filePath, outOfScopeSourceMatchers)
                    || matchesAny(filePath, outOfScopeTestMatchers)) {
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
     * Evaluates the pre-compiled out-of-scope matcher list against the
     * normalised file path. Returns {@code true} as soon as any matcher
     * claims the path so large configs short-circuit on the first hit.
     */
    private static boolean matchesAny(String filePath, List<Predicate<String>> matchers) {
        if (matchers.isEmpty()) return false;
        String normalized = filePath.replace('\\', '/');
        for (Predicate<String> matcher : matchers) {
            if (matcher.test(normalized)) return true;
        }
        return false;
    }

    /**
     * Compiles each raw out-of-scope dir string into a {@link Predicate}
     * that answers "does this (normalised) file path sit under this
     * entry?".
     *
     * <p>Each entry is classified into one of two semantics based on
     * whether it contains any glob metacharacter ({@code *}, {@code ?},
     * {@code [}, {@code \{}):
     *
     * <ul>
     *   <li>Glob entries (e.g. {@code "api-test/&#42;&#42;"}) compile to
     *       a {@link PathMatcher} using the JVM's default file system
     *       {@code glob:} syntax, so {@code &#42;&#42;} crosses directory
     *       boundaries as users expect from Ant/Gradle conventions.</li>
     *   <li>Literal entries (e.g. {@code "api-test/src/test/java"})
     *       keep the boundary-aware prefix semantics the README has
     *       documented since v1: the entry matches only when it sits at
     *       the start of the path or is preceded by {@code '/'}, so
     *       {@code "api-test"} never claims
     *       {@code "api-test-utils/..."}.</li>
     * </ul>
     *
     * <p>Mixing both shapes in the same list is supported — this is
     * what lets existing adopters migrate at their own pace without the
     * plugin ever silently losing coverage.
     *
     * <p>{@code null} and blank entries are dropped quietly: a
     * mis-concatenated list literal on the Gradle side is not worth
     * failing a build over, and the {@code --explain} hint already
     * surfaces the more likely "configured but nothing matched" failure.
     */
    private static List<Predicate<String>> compileOutOfScopeMatchers(List<String> dirs) {
        if (dirs == null || dirs.isEmpty()) return List.of();
        List<Predicate<String>> matchers = new ArrayList<>(dirs.size());
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            String normalizedDir = dir.replace('\\', '/');
            if (hasGlobMetachar(normalizedDir)) {
                PathMatcher pm = FileSystems.getDefault()
                        .getPathMatcher("glob:" + normalizedDir);
                matchers.add(path -> {
                    try {
                        return pm.matches(java.nio.file.Path.of(path));
                    } catch (java.nio.file.InvalidPathException e) {
                        // A changed file whose path contains characters the
                        // platform refuses to parse (mostly a Windows-in-git
                        // edge case) can't match any glob — fail closed so
                        // the engine routes it through the unmapped bucket
                        // instead of pretending the glob bit.
                        return false;
                    }
                });
            } else {
                // Literal-prefix branch preserves the pre-v2 "boundary-aware
                // prefix" semantics verbatim: leading-edge or /-bounded.
                String prefix = normalizedDir.endsWith("/") ? normalizedDir : normalizedDir + "/";
                matchers.add(path ->
                        path.startsWith(prefix) || path.contains("/" + prefix));
            }
        }
        return List.copyOf(matchers);
    }

    /**
     * True if the string contains any character the JVM's
     * {@code glob:} syntax treats as a metacharacter. Kept deliberately
     * small: these four cover every pattern the README, Gradle docs,
     * and user bug reports mention. Anything more exotic still routes
     * through the literal-prefix branch and will fail closed (i.e.
     * match nothing), which is safer than inferring glob intent from a
     * stray character.
     */
    private static boolean hasGlobMetachar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') return true;
        }
        return false;
    }
}
