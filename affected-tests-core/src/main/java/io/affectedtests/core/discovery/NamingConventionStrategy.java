package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Strategy: Naming Convention.
 * <p>
 * For each changed production class {@code com.example.FooBar}, looks for test
 * classes named {@code FooBarTest}, {@code FooBarIT}, {@code FooBarITTest},
 * {@code FooBarIntegrationTest}, etc. in the configured test directories.
 */
public final class NamingConventionStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(NamingConventionStrategy.class);

    private final AffectedTestsConfig config;

    public NamingConventionStrategy(AffectedTestsConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "naming";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        // Build a map of simple class name → set of expected test simple names
        Map<String, Set<String>> expectedTestNames = new HashMap<>();
        for (String fqn : changedProductionClasses) {
            String simpleName = simpleClassName(fqn);
            Set<String> candidates = new LinkedHashSet<>();
            for (String suffix : config.testSuffixes()) {
                candidates.add(simpleName + suffix);
            }
            expectedTestNames.put(fqn, candidates);
        }

        // Scan test directories for matching files
        Set<String> allTestFqns = scanTestDirectories(projectDir);

        for (String testFqn : allTestFqns) {
            String testSimpleName = simpleClassName(testFqn);
            for (var entry : expectedTestNames.entrySet()) {
                if (entry.getValue().contains(testSimpleName)) {
                    discoveredTests.add(testFqn);
                    log.debug("Naming match: {} → {}", entry.getKey(), testFqn);
                }
            }
        }

        log.info("[naming] Discovered {} tests for {} changed classes",
                discoveredTests.size(), changedProductionClasses.size());
        return discoveredTests;
    }

    /**
     * Scans all configured test directories under the project and returns FQNs
     * of all .java files found.
     */
    private Set<String> scanTestDirectories(Path projectDir) {
        Set<String> testFqns = new LinkedHashSet<>();

        for (String testDir : config.testDirs()) {
            Path testPath = projectDir.resolve(testDir);
            if (!Files.isDirectory(testPath)) {
                // Also check sub-modules
                try (var dirs = Files.walk(projectDir, 1)) {
                    dirs.filter(Files::isDirectory)
                        .filter(d -> !d.equals(projectDir))
                        .forEach(moduleDir -> {
                            Path modulTestPath = moduleDir.resolve(testDir);
                            if (Files.isDirectory(modulTestPath)) {
                                testFqns.addAll(scanDirectory(modulTestPath));
                            }
                        });
                } catch (IOException e) {
                    log.warn("Error scanning module directories under {}", projectDir, e);
                }
                continue;
            }
            testFqns.addAll(scanDirectory(testPath));
        }

        return testFqns;
    }

    private Set<String> scanDirectory(Path testRoot) {
        Set<String> fqns = new LinkedHashSet<>();
        try {
            Files.walkFileTree(testRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        Path relative = testRoot.relativize(file);
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
            log.warn("Error scanning test directory: {}", testRoot, e);
        }
        return fqns;
    }

    private static String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
