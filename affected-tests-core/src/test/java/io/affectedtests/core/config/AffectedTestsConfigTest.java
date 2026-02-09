package io.affectedtests.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AffectedTestsConfigTest {

    @Test
    void defaultsAreApplied() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();

        assertEquals("origin/master", config.baseRef());
        assertTrue(config.includeUncommitted());
        assertTrue(config.includeStaged());
        assertFalse(config.runAllIfNoMatches());
        assertEquals(Set.of("naming", "usage", "impl"), config.strategies());
        assertEquals(2, config.transitiveDepth());
        assertEquals(List.of("Test", "IT", "ITTest", "IntegrationTest"), config.testSuffixes());
        assertEquals(List.of("src/main/java"), config.sourceDirs());
        assertEquals(List.of("src/test/java"), config.testDirs());
        assertEquals(List.of("**/generated/**"), config.excludePaths());
        assertTrue(config.includeImplementationTests());
        assertEquals(List.of("Impl"), config.implementationNaming());
        assertTrue(config.testProjectMapping().isEmpty());
    }

    @Test
    void customValuesArePreserved() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef("origin/main")
                .includeUncommitted(false)
                .includeStaged(false)
                .runAllIfNoMatches(true)
                .strategies(Set.of("naming"))
                .transitiveDepth(0)
                .testSuffixes(List.of("Test"))
                .sourceDirs(List.of("src/main/java", "src/main/kotlin"))
                .testDirs(List.of("src/test/java", "src/test/kotlin"))
                .excludePaths(List.of("**/generated/**", "**/*Dto.java"))
                .includeImplementationTests(false)
                .implementationNaming(List.of("Impl", "Default"))
                .testProjectMapping(Map.of(":api", ":application"))
                .build();

        assertEquals("origin/main", config.baseRef());
        assertFalse(config.includeUncommitted());
        assertFalse(config.includeStaged());
        assertTrue(config.runAllIfNoMatches());
        assertEquals(Set.of("naming"), config.strategies());
        assertEquals(0, config.transitiveDepth());
        assertEquals(List.of("Test"), config.testSuffixes());
        assertEquals(2, config.sourceDirs().size());
        assertEquals(2, config.testDirs().size());
        assertEquals(2, config.excludePaths().size());
        assertFalse(config.includeImplementationTests());
        assertEquals(2, config.implementationNaming().size());
        assertEquals(":application", config.testProjectMapping().get(":api"));
    }

    @Test
    void transitiveDepthIsClamped() {
        AffectedTestsConfig high = AffectedTestsConfig.builder().transitiveDepth(10).build();
        assertEquals(5, high.transitiveDepth());

        AffectedTestsConfig negative = AffectedTestsConfig.builder().transitiveDepth(-1).build();
        assertEquals(0, negative.transitiveDepth());
    }

    @Test
    void configIsImmutable() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();

        assertThrows(UnsupportedOperationException.class, () ->
                config.testSuffixes().add("Spec"));
        assertThrows(UnsupportedOperationException.class, () ->
                config.excludePaths().add("foo"));
    }
}
