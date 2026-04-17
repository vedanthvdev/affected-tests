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

        List<Path> sourceFiles = SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs());
        List<Path> testFiles = SourceFileScanner.collectTestFiles(projectDir, config.testDirs());
        LinkedHashMap<String, Path> testFqnToPath = SourceFileScanner.scanTestFqnsWithFiles(
                projectDir, config.testDirs());

        Set<String> sourceFqns = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            for (Path resolved : SourceFileScanner.findAllMatchingDirs(projectDir, sourceDir)) {
                sourceFqns.addAll(SourceFileScanner.fqnsUnder(resolved));
            }
        }

        log.info("Project index: {} source files, {} test files, {} source FQNs, {} test FQNs",
                sourceFiles.size(), testFiles.size(), sourceFqns.size(), testFqnToPath.size());

        return new ProjectIndex(
                Collections.unmodifiableList(sourceFiles),
                Collections.unmodifiableList(testFiles),
                Collections.unmodifiableMap(testFqnToPath),
                Collections.unmodifiableSet(sourceFqns)
        );
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
