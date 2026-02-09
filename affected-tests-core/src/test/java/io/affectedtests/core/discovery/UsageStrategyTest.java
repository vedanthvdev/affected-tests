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

class UsageStrategyTest {

    @TempDir
    Path tempDir;

    private UsageStrategy strategy;

    @BeforeEach
    void setUp() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        strategy = new UsageStrategy(config);
    }

    @Test
    void findsTestThatUsesChangedClassAsField() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("UserControllerIT.java"), """
                package com.example;

                import com.example.service.UserService;

                public class UserControllerIT {
                    private UserService userService;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.UserService"), tempDir);

        assertTrue(result.contains("com.example.UserControllerIT"));
    }

    @Test
    void findsTestWithAutowiredField() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("ServiceIT.java"), """
                package com.example;

                import com.example.service.OrderService;
                import org.springframework.beans.factory.annotation.Autowired;

                public class ServiceIT {
                    @Autowired
                    private OrderService orderService;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.OrderService"), tempDir);

        assertTrue(result.contains("com.example.ServiceIT"));
    }

    @Test
    void findsTestWithMockField() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooBarTest.java"), """
                package com.example;

                import com.example.service.FooBar;
                import org.mockito.Mock;

                public class FooBarTest {
                    @Mock
                    private FooBar fooBar;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooBar"), tempDir);

        assertTrue(result.contains("com.example.FooBarTest"));
    }

    @Test
    void doesNotMatchUnrelatedTypes() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("OtherTest.java"), """
                package com.example;

                import com.other.UnrelatedService;

                public class OtherTest {
                    private UnrelatedService service;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooBar"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForEmptyInput() {
        Set<String> result = strategy.discoverTests(Set.of(), tempDir);
        assertTrue(result.isEmpty());
    }
}
