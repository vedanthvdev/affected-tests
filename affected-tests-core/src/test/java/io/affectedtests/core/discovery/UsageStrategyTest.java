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
    void findsTestThatImportsChangedClass() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BazValidatorTest.java"), """
                package com.example;

                import com.example.service.FooModel;

                public class BazValidatorTest {
                    public void testValidate(FooModel details) {}
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooModel"), tempDir);

        assertTrue(result.contains("com.example.BazValidatorTest"),
                "Should match test that imports the changed class");
    }

    @Test
    void findsTestThatUsesChangedClassAsField() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BarControllerIT.java"), """
                package com.example;

                import com.example.service.FooService;

                public class BarControllerIT {
                    private FooService fooService;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooService"), tempDir);

        assertTrue(result.contains("com.example.BarControllerIT"));
    }

    @Test
    void findsTestWithAutowiredField() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("ServiceIT.java"), """
                package com.example;

                import com.example.service.FooService;
                import org.springframework.beans.factory.annotation.Autowired;

                public class ServiceIT {
                    @Autowired
                    private FooService fooService;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooService"), tempDir);

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
    void findsTestThatUsesChangedClassAsMethodParameter() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BazMapperTest.java"), """
                package com.example;

                import com.example.service.BarModel;

                public class BazMapperTest {
                    public void testMap(BarModel item) {
                        // uses BarModel as method param only
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.BazMapperTest"),
                "Should match test that imports the changed class even without a field");
    }

    @Test
    void findsTestThatUsesChangedClassInConstructorCall() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FactoryTest.java"), """
                package com.example;

                import com.example.service.BarModel;

                public class FactoryTest {
                    public void testCreate() {
                        BarModel bm = new BarModel();
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.FactoryTest"),
                "Should match test that creates instances of the changed class");
    }

    @Test
    void findsTestWithWildcardImportAndTypeReference() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("WildcardTest.java"), """
                package com.example;

                import com.example.service.*;

                public class WildcardTest {
                    private BarModel item;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.WildcardTest"),
                "Should match test with wildcard import covering the changed class's package");
    }

    @Test
    void findsTestInSamePackageWithoutImport() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example/service");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("InternalTest.java"), """
                package com.example.service;

                public class InternalTest {
                    private BarModel item;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.service.InternalTest"),
                "Should match test in same package (no import needed)");
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

    @Test
    void findsCrossModuleTestViaImport() throws IOException {
        // Simulate: FooModel in api module, test in application module
        Path appTestDir = tempDir.resolve("application/src/test/java/com/example/tests");
        Files.createDirectories(appTestDir);
        Files.writeString(appTestDir.resolve("BazValidatorTest.java"), """
                package com.example.tests;

                import com.example.api.FooModel;

                public class BazValidatorTest {
                    public void testValidate(FooModel fm) {}
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.api.FooModel"), tempDir);

        assertTrue(result.contains("com.example.tests.BazValidatorTest"),
                "Should find test in sub-module that imports the changed class");
    }

    @Test
    void findsTestInDeeplyNestedModule() throws IOException {
        // Depth 2: services/core/src/test/java/...
        Path deepTestDir = tempDir.resolve("services/core/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("BazGatewayIT.java"), """
                package com.example;

                import com.example.service.BazGateway;

                public class BazGatewayIT {
                    private BazGateway gateway;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BazGateway"), tempDir);

        assertTrue(result.contains("com.example.BazGatewayIT"),
                "Should find test nested 2 levels deep: services/core/src/test/java");
    }

    @Test
    void findsTestsAcrossMultipleDepths() throws IOException {
        // Depth 1
        Path apiTestDir = tempDir.resolve("api/src/test/java/com/example");
        Files.createDirectories(apiTestDir);
        Files.writeString(apiTestDir.resolve("ShallowTest.java"), """
                package com.example;

                import com.example.service.QuxService;

                public class ShallowTest {
                    private QuxService svc;
                }
                """);

        // Depth 3
        Path deepTestDir = tempDir.resolve("platform/services/core/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("DeepTest.java"), """
                package com.example;

                import com.example.service.QuxService;

                public class DeepTest {
                    private QuxService svc;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.QuxService"), tempDir);

        assertTrue(result.contains("com.example.ShallowTest"),
                "Should find test at depth 1");
        assertTrue(result.contains("com.example.DeepTest"),
                "Should find test at depth 3");
        assertEquals(2, result.size());
    }
}
