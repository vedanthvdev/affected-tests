package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImplementationStrategyTest {

    @TempDir
    Path tempDir;

    private ImplementationStrategy strategy;

    @BeforeEach
    void setUp() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        NamingConventionStrategy naming = new NamingConventionStrategy(config);
        UsageStrategy usage = new UsageStrategy(config);
        strategy = new ImplementationStrategy(config, naming, usage);
    }

    @Test
    void findsTestForImplementationClass() throws IOException {
        // Production: interface FooService
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("FooService.java"),
                "package com.example;\npublic interface FooService {}");
        Files.writeString(prodDir.resolve("FooServiceImpl.java"),
                "package com.example;\npublic class FooServiceImpl implements FooService {}");

        // Test for the impl
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooServiceImplTest.java"),
                "package com.example;\npublic class FooServiceImplTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FooService"), tempDir);

        assertTrue(result.contains("com.example.FooServiceImplTest"),
                "Should find test for the Impl class via naming");
    }

    @Test
    void findsTestViaAstExtendsScanning() throws IOException {
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("BaseService.java"),
                "package com.example;\npublic abstract class BaseService {}");
        Files.writeString(prodDir.resolve("ConcreteService.java"),
                "package com.example;\npublic class ConcreteService extends BaseService {}");

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("ConcreteServiceTest.java"),
                "package com.example;\npublic class ConcreteServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.BaseService"), tempDir);

        assertTrue(result.contains("com.example.ConcreteServiceTest"),
                "Should find test for class that extends the changed class");
    }

    @Test
    void findsTestForDefaultPrefixedImplementation() throws IOException {
        // Regression: the config ships with implementationNaming =
        // {"Impl", "Default"}, but the naming-convention loop used
        // to append both tokens as suffixes — FooServiceImpl matched,
        // FooServiceDefault did not because nobody writes
        // FooServiceDefault; real Spring/Guice code writes
        // DefaultFooService. The AST branch rescues the explicit
        // "implements FooService" case, but diffs where the super
        // type is inferred (generics-only declarations, or files
        // JavaParser couldn't parse) silently missed the Default-
        // prefixed impl. Lock in the prefix shape explicitly.
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("FooService.java"),
                "package com.example;\npublic interface FooService {}");
        // No "implements FooService" written — force the naming-
        // convention branch to do the work alone.
        Files.writeString(prodDir.resolve("DefaultFooService.java"),
                "package com.example;\npublic class DefaultFooService {}");

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("DefaultFooServiceTest.java"),
                "package com.example;\npublic class DefaultFooServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FooService"), tempDir);

        assertTrue(result.contains("com.example.DefaultFooServiceTest"),
                "Should find the DefaultFooServiceTest via the prefix-shape naming convention");
    }

    @Test
    void returnsEmptyWhenDisabled() throws IOException {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .includeImplementationTests(false)
                .build();
        NamingConventionStrategy naming = new NamingConventionStrategy(config);
        UsageStrategy usage = new UsageStrategy(config);
        ImplementationStrategy disabled = new ImplementationStrategy(config, naming, usage);

        Set<String> result = disabled.discoverTests(
                Set.of("com.example.Foo"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoImplementations() throws IOException {
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("LonelyService.java"),
                "package com.example;\npublic class LonelyService {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.LonelyService"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void findsGrandchildImplementationThroughMultiLevelHierarchy() throws IOException {
        // Hierarchy: interface A  <--  abstract class B implements A
        //                          <-- class C extends B
        // Only C has a direct test (CTest). Pre-fix the strategy found B
        // on a single pass and stopped — CTest (the only real coverage
        // of A's behaviour through an actual implementation) was
        // silently dropped. The fixpoint loop re-runs with B as a target
        // so C is found on the second pass, and naming-strategy then
        // picks up CTest.
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("A.java"),
                "package com.example;\npublic interface A {}");
        Files.writeString(prodDir.resolve("B.java"),
                "package com.example;\npublic abstract class B implements A {}");
        Files.writeString(prodDir.resolve("C.java"),
                "package com.example;\npublic class C extends B {}");

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("CTest.java"),
                "package com.example;\npublic class CTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.A"), tempDir);

        assertTrue(result.contains("com.example.CTest"),
                "Grandchild's test must be discovered through multi-level "
                        + "hierarchy — otherwise interface-level changes silently "
                        + "drop the tests of the only concrete implementor");
    }
}
