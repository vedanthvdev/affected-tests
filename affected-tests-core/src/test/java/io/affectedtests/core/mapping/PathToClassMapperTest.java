package io.affectedtests.core.mapping;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.mapping.PathToClassMapper.MappingResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathToClassMapperTest {

    private final AffectedTestsConfig config = AffectedTestsConfig.builder().build();
    private final PathToClassMapper mapper = new PathToClassMapper(config);

    @Test
    void mapsProductionJavaFileToFqn() {
        Set<String> changed = Set.of("src/main/java/com/example/service/FooBar.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.service.FooBar"));
        assertTrue(result.testClasses().isEmpty());
    }

    @Test
    void mapsTestJavaFileToFqn() {
        Set<String> changed = Set.of("src/test/java/com/example/service/FooBarTest.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.testClasses().contains("com.example.service.FooBarTest"));
        assertTrue(result.productionClasses().isEmpty());
    }

    @Test
    void mapsMultiModulePaths() {
        Set<String> changed = Set.of(
                "api/src/main/java/com/example/api/UserDto.java",
                "application/src/test/java/com/example/UserDtoTest.java"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.api.UserDto"));
        assertTrue(result.testClasses().contains("com.example.UserDtoTest"));
    }

    @Test
    void skipsNonJavaFiles() {
        Set<String> changed = Set.of(
                "README.md",
                "build.gradle",
                "src/main/resources/application.yml"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.testClasses().isEmpty());
    }

    @Test
    void handlesBothProductionAndTestChanges() {
        Set<String> changed = Set.of(
                "src/main/java/com/example/Foo.java",
                "src/test/java/com/example/FooTest.java",
                "src/main/java/com/example/Bar.java"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertEquals(2, result.productionClasses().size());
        assertEquals(1, result.testClasses().size());
        assertTrue(result.productionClasses().contains("com.example.Foo"));
        assertTrue(result.productionClasses().contains("com.example.Bar"));
        assertTrue(result.testClasses().contains("com.example.FooTest"));
    }

    @Test
    void extractsModuleFromPath() {
        assertEquals("api", mapper.extractModule("api/src/main/java/com/example/Foo.java"));
        assertEquals("application", mapper.extractModule("application/src/test/java/com/example/FooTest.java"));
        assertEquals("", mapper.extractModule("src/main/java/com/example/Foo.java"));
    }
}
