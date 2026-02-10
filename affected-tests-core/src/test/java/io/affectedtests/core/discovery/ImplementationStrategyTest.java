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
}
