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
        Files.writeString(testDir.resolve("OverseasValidatorTest.java"), """
                package com.example;

                import com.example.service.PaymentDetails;

                public class OverseasValidatorTest {
                    public void testValidate(PaymentDetails details) {}
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.PaymentDetails"), tempDir);

        assertTrue(result.contains("com.example.OverseasValidatorTest"),
                "Should match test that imports the changed class");
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
    void findsTestThatUsesChangedClassAsMethodParameter() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("PaymentMapperTest.java"), """
                package com.example;

                import com.modulr.modulo.payment.services.PaymentDetails;

                public class PaymentMapperTest {
                    public void testMap(PaymentDetails details) {
                        // uses PaymentDetails as method param only
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.modulr.modulo.payment.services.PaymentDetails"), tempDir);

        assertTrue(result.contains("com.example.PaymentMapperTest"),
                "Should match test that imports the changed class even without a field");
    }

    @Test
    void findsTestThatUsesChangedClassInConstructorCall() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FactoryTest.java"), """
                package com.example;

                import com.example.service.PaymentDetails;

                public class FactoryTest {
                    public void testCreate() {
                        PaymentDetails pd = new PaymentDetails();
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.PaymentDetails"), tempDir);

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
                    private PaymentDetails details;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.PaymentDetails"), tempDir);

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
                    private PaymentDetails details;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.PaymentDetails"), tempDir);

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
        // Simulate: PaymentDetails in api module, test in application module
        Path appTestDir = tempDir.resolve("application/src/test/java/com/example/tests");
        Files.createDirectories(appTestDir);
        Files.writeString(appTestDir.resolve("OverseasPaymentDetailValidatorTest.java"), """
                package com.example.tests;

                import com.modulr.modulo.payment.services.PaymentDetails;

                public class OverseasPaymentDetailValidatorTest {
                    public void testValidate(PaymentDetails pd) {}
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.modulr.modulo.payment.services.PaymentDetails"), tempDir);

        assertTrue(result.contains("com.example.tests.OverseasPaymentDetailValidatorTest"),
                "Should find test in sub-module that imports the changed class");
    }

    @Test
    void findsTestInDeeplyNestedModule() throws IOException {
        // Depth 2: services/payment/src/test/java/...
        Path deepTestDir = tempDir.resolve("services/payment/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("PaymentGatewayIT.java"), """
                package com.example;

                import com.example.service.PaymentGateway;

                public class PaymentGatewayIT {
                    private PaymentGateway gateway;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.PaymentGateway"), tempDir);

        assertTrue(result.contains("com.example.PaymentGatewayIT"),
                "Should find test nested 2 levels deep: services/payment/src/test/java");
    }

    @Test
    void findsTestsAcrossMultipleDepths() throws IOException {
        // Depth 1
        Path apiTestDir = tempDir.resolve("api/src/test/java/com/example");
        Files.createDirectories(apiTestDir);
        Files.writeString(apiTestDir.resolve("ShallowTest.java"), """
                package com.example;

                import com.example.service.UserService;

                public class ShallowTest {
                    private UserService svc;
                }
                """);

        // Depth 3
        Path deepTestDir = tempDir.resolve("platform/services/user/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("DeepTest.java"), """
                package com.example;

                import com.example.service.UserService;

                public class DeepTest {
                    private UserService svc;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.UserService"), tempDir);

        assertTrue(result.contains("com.example.ShallowTest"),
                "Should find test at depth 1");
        assertTrue(result.contains("com.example.DeepTest"),
                "Should find test at depth 3");
        assertEquals(2, result.size());
    }
}
