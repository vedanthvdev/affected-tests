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

class NamingConventionStrategyTest {

    @TempDir
    Path tempDir;

    private NamingConventionStrategy strategy;

    @BeforeEach
    void setUp() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        strategy = new NamingConventionStrategy(config);
    }

    @Test
    void findsTestByNamingConvention() throws IOException {
        // Create test file
        Path testDir = tempDir.resolve("src/test/java/com/example/service");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooBarTest.java"),
                "package com.example.service;\npublic class FooBarTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooBar"), tempDir);

        assertTrue(result.contains("com.example.service.FooBarTest"));
    }

    @Test
    void findsMultipleTestSuffixes() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooTest.java"),
                "package com.example;\npublic class FooTest {}");
        Files.writeString(testDir.resolve("FooIT.java"),
                "package com.example;\npublic class FooIT {}");
        Files.writeString(testDir.resolve("FooIntegrationTest.java"),
                "package com.example;\npublic class FooIntegrationTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Foo"), tempDir);

        assertEquals(3, result.size());
        assertTrue(result.contains("com.example.FooTest"));
        assertTrue(result.contains("com.example.FooIT"));
        assertTrue(result.contains("com.example.FooIntegrationTest"));
    }

    @Test
    void returnsEmptyWhenNoTestsMatch() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("UnrelatedTest.java"),
                "package com.example;\npublic class UnrelatedTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FooBar"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForEmptyInput() {
        Set<String> result = strategy.discoverTests(Set.of(), tempDir);
        assertTrue(result.isEmpty());
    }

    @Test
    void findsTestsInSubModules() throws IOException {
        Path moduleTestDir = tempDir.resolve("application/src/test/java/com/example");
        Files.createDirectories(moduleTestDir);
        Files.writeString(moduleTestDir.resolve("UserServiceTest.java"),
                "package com.example;\npublic class UserServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.UserService"), tempDir);

        assertTrue(result.contains("com.example.UserServiceTest"));
    }

    @Test
    void findsTestsInDeeplyNestedModules() throws IOException {
        // Depth 2: services/payment/src/test/java/...
        Path deepTestDir = tempDir.resolve("services/payment/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("PaymentServiceTest.java"),
                "package com.example;\npublic class PaymentServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.PaymentService"), tempDir);

        assertTrue(result.contains("com.example.PaymentServiceTest"),
                "Should find test nested 2 levels deep: services/payment/src/test/java");
    }

    @Test
    void findsTestsAcrossMultipleNestedModules() throws IOException {
        // Root level
        Path rootTestDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(rootTestDir);
        Files.writeString(rootTestDir.resolve("OrderServiceTest.java"),
                "package com.example;\npublic class OrderServiceTest {}");

        // Depth 1
        Path apiTestDir = tempDir.resolve("api/src/test/java/com/example");
        Files.createDirectories(apiTestDir);
        Files.writeString(apiTestDir.resolve("OrderServiceIT.java"),
                "package com.example;\npublic class OrderServiceIT {}");

        // Depth 2
        Path deepTestDir = tempDir.resolve("services/order/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("OrderServiceIntegrationTest.java"),
                "package com.example;\npublic class OrderServiceIntegrationTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.OrderService"), tempDir);

        assertEquals(3, result.size(), "Should find tests at root, depth 1, and depth 2");
        assertTrue(result.contains("com.example.OrderServiceTest"));
        assertTrue(result.contains("com.example.OrderServiceIT"));
        assertTrue(result.contains("com.example.OrderServiceIntegrationTest"));
    }
}
