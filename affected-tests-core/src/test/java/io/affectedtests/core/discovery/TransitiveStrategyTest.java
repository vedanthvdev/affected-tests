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

class TransitiveStrategyTest {

    @TempDir
    Path tempDir;

    private TransitiveStrategy strategy;

    @BeforeEach
    void setUp() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .transitiveDepth(2)
                .build();
        NamingConventionStrategy naming = new NamingConventionStrategy(config);
        UsageStrategy usage = new UsageStrategy(config);
        strategy = new TransitiveStrategy(config, naming, usage);
    }

    @Test
    void discoversTestViaTransitiveDependency() throws IOException {
        // OrderService depends on PaymentService (via field)
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("PaymentService.java"),
                "package com.example;\npublic class PaymentService {}");
        Files.writeString(prodDir.resolve("OrderService.java"), """
                package com.example;
                
                public class OrderService {
                    private PaymentService paymentService;
                }
                """);

        // Test for OrderService (transitive dependency of PaymentService)
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("OrderServiceTest.java"),
                "package com.example;\npublic class OrderServiceTest {}");

        // Changed: PaymentService
        Set<String> result = strategy.discoverTests(
                Set.of("com.example.PaymentService"), tempDir);

        assertTrue(result.contains("com.example.OrderServiceTest"),
                "Should discover test for OrderService (depends on changed PaymentService)");
    }

    @Test
    void skipsWhenDepthIsZero() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .transitiveDepth(0)
                .build();
        NamingConventionStrategy naming = new NamingConventionStrategy(config);
        UsageStrategy usage = new UsageStrategy(config);
        TransitiveStrategy zeroDepth = new TransitiveStrategy(config, naming, usage);

        Set<String> result = zeroDepth.discoverTests(
                Set.of("com.example.Foo"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoDependents() throws IOException {
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("Isolated.java"),
                "package com.example;\npublic class Isolated {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Isolated"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void respectsMaxDepthLimit() throws IOException {
        // A -> B -> C (depth 2 chain)
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("A.java"),
                "package com.example;\npublic class A {}");
        Files.writeString(prodDir.resolve("B.java"), """
                package com.example;
                
                public class B {
                    private A a;
                }
                """);
        Files.writeString(prodDir.resolve("C.java"), """
                package com.example;
                
                public class C {
                    private B b;
                }
                """);

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("CTest.java"),
                "package com.example;\npublic class CTest {}");

        // Depth-1 strategy should NOT find CTest (C is 2 hops from A)
        AffectedTestsConfig depth1Config = AffectedTestsConfig.builder()
                .transitiveDepth(1)
                .build();
        NamingConventionStrategy naming = new NamingConventionStrategy(depth1Config);
        UsageStrategy usage = new UsageStrategy(depth1Config);
        TransitiveStrategy depth1 = new TransitiveStrategy(depth1Config, naming, usage);

        Set<String> depth1Result = depth1.discoverTests(Set.of("com.example.A"), tempDir);
        assertFalse(depth1Result.contains("com.example.CTest"),
                "Depth 1 should not reach CTest (2 hops away)");

        // Depth-2 strategy SHOULD find CTest
        Set<String> depth2Result = strategy.discoverTests(Set.of("com.example.A"), tempDir);
        assertTrue(depth2Result.contains("com.example.CTest"),
                "Depth 2 should reach CTest");
    }
}
