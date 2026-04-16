package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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
        Set<String> allTestFqns = SourceFileScanner.scanTestFqns(projectDir, config.testDirs());
        return matchTests(changedProductionClasses, allTestFqns);
    }

    /**
     * Discovers tests using a pre-built project index (avoids redundant file walks).
     */
    public Set<String> discoverTests(Set<String> changedProductionClasses, ProjectIndex index) {
        return matchTests(changedProductionClasses, index.testFqns());
    }

    private Set<String> matchTests(Set<String> changedProductionClasses, Set<String> allTestFqns) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        Map<String, Set<String>> expectedTestNames = new HashMap<>();
        for (String fqn : changedProductionClasses) {
            String simpleName = SourceFileScanner.simpleClassName(fqn);
            Set<String> candidates = new LinkedHashSet<>();
            for (String suffix : config.testSuffixes()) {
                candidates.add(simpleName + suffix);
            }
            expectedTestNames.put(fqn, candidates);
        }

        for (String testFqn : allTestFqns) {
            String testSimpleName = SourceFileScanner.simpleClassName(testFqn);
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
}
