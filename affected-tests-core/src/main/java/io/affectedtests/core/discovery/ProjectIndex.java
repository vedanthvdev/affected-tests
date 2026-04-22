package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

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

        List<String> oosSource = config.outOfScopeSourceDirs();
        List<String> oosTest = config.outOfScopeTestDirs();

        List<Path> sourceFiles = filterOutOfScope(
                SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs()),
                projectDir, oosSource, oosTest);
        List<Path> testFiles = filterOutOfScope(
                SourceFileScanner.collectTestFiles(projectDir, config.testDirs()),
                projectDir, oosSource, oosTest);

        LinkedHashMap<String, Path> testFqnToPath = SourceFileScanner.scanTestFqnsWithFiles(
                projectDir, config.testDirs());
        if (!oosSource.isEmpty() || !oosTest.isEmpty()) {
            // Drop out-of-scope test FQNs from the dispatch map. Without
            // this, discovery strategies could still return FQNs living
            // under {@code api-test/src/test/java} and the task would then
            // try to run them — the entire point of the out-of-scope knob
            // is that those tests never reach the affected-test dispatch.
            testFqnToPath.entrySet().removeIf(entry -> isUnderAny(
                    entry.getValue(), projectDir, oosSource, oosTest));
        }

        Set<String> sourceFqns = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            for (Path resolved : SourceFileScanner.findAllMatchingDirs(projectDir, sourceDir)) {
                if (isUnderAny(resolved, projectDir, oosSource, oosTest)) continue;
                sourceFqns.addAll(SourceFileScanner.fqnsUnder(resolved));
            }
        }

        log.info("Project index: {} source files, {} test files, {} source FQNs, {} test FQNs"
                        + " (out-of-scope source dirs: {}, out-of-scope test dirs: {})",
                sourceFiles.size(), testFiles.size(), sourceFqns.size(), testFqnToPath.size(),
                oosSource.size(), oosTest.size());

        return new ProjectIndex(
                Collections.unmodifiableList(sourceFiles),
                Collections.unmodifiableList(testFiles),
                Collections.unmodifiableMap(testFqnToPath),
                Collections.unmodifiableSet(sourceFqns)
        );
    }

    private static List<Path> filterOutOfScope(List<Path> files, Path projectDir,
                                               List<String> oosSource, List<String> oosTest) {
        if (oosSource.isEmpty() && oosTest.isEmpty()) {
            return files;
        }
        List<Path> filtered = new ArrayList<>(files.size());
        for (Path file : files) {
            if (!isUnderAny(file, projectDir, oosSource, oosTest)) {
                filtered.add(file);
            }
        }
        return filtered;
    }

    /**
     * Normalised, boundary-aware "does this absolute path sit under any of
     * the given project-relative dirs?" check. Mirrors
     * {@link io.affectedtests.core.mapping.PathToClassMapper} semantics so
     * a diff file and an indexed file that point to the same location are
     * routed the same way.
     */
    static boolean isUnderAny(Path file, Path projectDir, List<String> oosSource, List<String> oosTest) {
        if (oosSource.isEmpty() && oosTest.isEmpty()) return false;
        String rel;
        try {
            rel = projectDir.toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return false;
        }
        String normalized = rel.replace(java.io.File.separatorChar, '/');
        return startsWithAny(normalized, oosSource) || startsWithAny(normalized, oosTest);
    }

    private static boolean startsWithAny(String normalized, List<String> dirs) {
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            String d = dir.replace('\\', '/');
            if (!d.endsWith("/")) d += "/";
            if (normalized.startsWith(d)) return true;
            if (normalized.contains("/" + d)) return true;
        }
        return false;
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
