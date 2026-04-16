package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Pre-scanned index of source and test files for a project directory.
 * Built once per engine run to avoid redundant file-tree walks across strategies.
 */
public final class ProjectIndex {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndex.class);

    private final List<Path> sourceFiles;
    private final List<Path> testFiles;
    private final Set<String> testFqns;
    private final Set<String> sourceFqns;

    private ProjectIndex(List<Path> sourceFiles, List<Path> testFiles,
                         Set<String> testFqns, Set<String> sourceFqns) {
        this.sourceFiles = sourceFiles;
        this.testFiles = testFiles;
        this.testFqns = testFqns;
        this.sourceFqns = sourceFqns;
    }

    public static ProjectIndex build(Path projectDir, AffectedTestsConfig config) {
        log.info("Building project index for {}", projectDir);

        List<Path> sourceFiles = SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs());
        List<Path> testFiles = SourceFileScanner.collectTestFiles(projectDir, config.testDirs());
        Set<String> testFqns = SourceFileScanner.scanTestFqns(projectDir, config.testDirs());

        Set<String> sourceFqns = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            for (Path resolved : SourceFileScanner.findAllMatchingDirs(projectDir, sourceDir)) {
                sourceFqns.addAll(SourceFileScanner.fqnsUnder(resolved));
            }
        }

        log.info("Project index: {} source files, {} test files, {} source FQNs, {} test FQNs",
                sourceFiles.size(), testFiles.size(), sourceFqns.size(), testFqns.size());

        return new ProjectIndex(
                Collections.unmodifiableList(sourceFiles),
                Collections.unmodifiableList(testFiles),
                Collections.unmodifiableSet(testFqns),
                Collections.unmodifiableSet(sourceFqns)
        );
    }

    public List<Path> sourceFiles() { return sourceFiles; }
    public List<Path> testFiles() { return testFiles; }
    public Set<String> testFqns() { return testFqns; }
    public Set<String> sourceFqns() { return sourceFqns; }
}
