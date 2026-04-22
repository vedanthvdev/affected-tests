package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.mapping.OutOfScopeMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Pre-scanned index of source and test files for a project directory.
 * Built once per engine run to avoid redundant file-tree walks and AST parses
 * across strategies.
 *
 * <p>{@link CompilationUnit}s are parsed lazily on first access and cached.
 * Callers MUST NOT use the returned CU from multiple threads concurrently —
 * JavaParser ASTs are not thread-safe.
 */
public final class ProjectIndex {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndex.class);

    private final List<Path> sourceFiles;
    private final List<Path> testFiles;
    private final Map<String, Path> testFqnToPath;
    private final Set<String> sourceFqns;

    // Lazy AST cache. null entries mean "parsed but invalid/empty".
    private final Map<Path, CompilationUnit> cuCache = new HashMap<>();
    private final JavaParser parser = new JavaParser();

    private ProjectIndex(List<Path> sourceFiles, List<Path> testFiles,
                         Map<String, Path> testFqnToPath, Set<String> sourceFqns) {
        this.sourceFiles = sourceFiles;
        this.testFiles = testFiles;
        this.testFqnToPath = testFqnToPath;
        this.sourceFqns = sourceFqns;
    }

    public static ProjectIndex build(Path projectDir, AffectedTestsConfig config) {
        log.info("Building project index for {}", projectDir);

        // Share the exact matcher compilation PathToClassMapper uses so
        // diff-side bucketing and indexed-file-side filtering agree on
        // every entry — glob form, literal form, or mixed. Before this
        // shared source of truth the two sides silently disagreed on
        // glob entries: the mapper compiled "api-test/**" into a
        // PathMatcher while this class treated it as a literal prefix
        // and matched nothing, so mixed diffs still dispatched tests
        // under "api-test/".
        List<Predicate<String>> oosSourceMatchers = OutOfScopeMatchers.compile(
                config.outOfScopeSourceDirs(), "outOfScopeSourceDirs");
        List<Predicate<String>> oosTestMatchers = OutOfScopeMatchers.compile(
                config.outOfScopeTestDirs(), "outOfScopeTestDirs");
        boolean hasOutOfScope = !oosSourceMatchers.isEmpty() || !oosTestMatchers.isEmpty();

        List<Path> sourceFiles = filterOutOfScope(
                SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs()),
                projectDir, oosSourceMatchers, oosTestMatchers, hasOutOfScope);
        List<Path> testFiles = filterOutOfScope(
                SourceFileScanner.collectTestFiles(projectDir, config.testDirs()),
                projectDir, oosSourceMatchers, oosTestMatchers, hasOutOfScope);

        LinkedHashMap<String, Path> testFqnToPath = SourceFileScanner.scanTestFqnsWithFiles(
                projectDir, config.testDirs());
        if (hasOutOfScope) {
            // Drop out-of-scope test FQNs from the dispatch map. Without
            // this, discovery strategies could still return FQNs living
            // under {@code api-test/src/test/java} and the task would then
            // try to run them — the entire point of the out-of-scope knob
            // is that those tests never reach the affected-test dispatch.
            testFqnToPath.entrySet().removeIf(entry -> isUnderAny(
                    entry.getValue(), projectDir, oosSourceMatchers, oosTestMatchers));
        }

        Set<String> sourceFqns = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            for (Path resolved : SourceFileScanner.findAllMatchingDirs(projectDir, sourceDir)) {
                if (hasOutOfScope && isUnderAny(resolved, projectDir, oosSourceMatchers, oosTestMatchers)) continue;
                sourceFqns.addAll(SourceFileScanner.fqnsUnder(resolved));
            }
        }

        log.info("Project index: {} source files, {} test files, {} source FQNs, {} test FQNs"
                        + " (out-of-scope source dirs: {}, out-of-scope test dirs: {})",
                sourceFiles.size(), testFiles.size(), sourceFqns.size(), testFqnToPath.size(),
                config.outOfScopeSourceDirs().size(), config.outOfScopeTestDirs().size());

        return new ProjectIndex(
                Collections.unmodifiableList(sourceFiles),
                Collections.unmodifiableList(testFiles),
                Collections.unmodifiableMap(testFqnToPath),
                Collections.unmodifiableSet(sourceFqns)
        );
    }

    private static List<Path> filterOutOfScope(List<Path> files, Path projectDir,
                                               List<Predicate<String>> oosSourceMatchers,
                                               List<Predicate<String>> oosTestMatchers,
                                               boolean hasOutOfScope) {
        if (!hasOutOfScope) {
            return files;
        }
        List<Path> filtered = new ArrayList<>(files.size());
        for (Path file : files) {
            if (!isUnderAny(file, projectDir, oosSourceMatchers, oosTestMatchers)) {
                filtered.add(file);
            }
        }
        return filtered;
    }

    /**
     * Normalised "does this absolute path sit under any of the compiled
     * out-of-scope matchers?" check. Evaluates exactly the matchers
     * {@link io.affectedtests.core.mapping.PathToClassMapper} uses on
     * the diff side, so a file and an indexed file pointing to the
     * same location route the same way whether the entry was written
     * as {@code api-test}, {@code api-test/**}, or {@code **&#47;api-test/**}.
     */
    static boolean isUnderAny(Path file, Path projectDir,
                              List<Predicate<String>> oosSourceMatchers,
                              List<Predicate<String>> oosTestMatchers) {
        if (oosSourceMatchers.isEmpty() && oosTestMatchers.isEmpty()) return false;
        String rel;
        try {
            rel = projectDir.toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return false;
        }
        String normalized = rel.replace(java.io.File.separatorChar, '/');
        return OutOfScopeMatchers.matchesAny(normalized, oosSourceMatchers)
                || OutOfScopeMatchers.matchesAny(normalized, oosTestMatchers);
    }

    public List<Path> sourceFiles() { return sourceFiles; }
    public List<Path> testFiles() { return testFiles; }
    public Set<String> testFqns() { return testFqnToPath.keySet(); }
    public Map<String, Path> testFqnToPath() { return testFqnToPath; }
    public Set<String> sourceFqns() { return sourceFqns; }

    /**
     * Parses {@code file} with JavaParser, caching the result. Returns
     * {@code null} if the file cannot be parsed (malformed source, I/O error).
     *
     * <p>Results are shared across strategies so the same test file is parsed
     * at most once per engine run.
     */
    public CompilationUnit compilationUnit(Path file) {
        if (cuCache.containsKey(file)) {
            return cuCache.get(file);
        }
        CompilationUnit cu = null;
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                cu = result.getResult().get();
            } else {
                log.debug("Failed to parse {}", file);
            }
        } catch (Exception e) {
            log.debug("Error parsing {}: {}", file, e.getMessage());
        }
        cuCache.put(file, cu);
        return cu;
    }
}
