package io.affectedtests.core.discovery;

import java.nio.file.Path;
import java.util.Set;

/**
 * Strategy interface for discovering test classes relevant to a set of
 * changed production classes.
 */
public interface TestDiscoveryStrategy {

    /**
     * @return the strategy name (e.g. "naming", "usage", "impl")
     */
    String name();

    /**
     * Discover test class FQNs to run for the given changed production classes.
     *
     * @param changedProductionClasses FQNs of changed production classes
     * @param projectDir              root directory of the project / module
     * @return set of test class FQNs that should be executed
     */
    Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir);
}
