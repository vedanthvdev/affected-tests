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
                "api/src/main/java/com/example/api/FooDto.java",
                "application/src/test/java/com/example/FooDtoTest.java"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.api.FooDto"));
        assertTrue(result.testClasses().contains("com.example.FooDtoTest"));
    }

    @Test
    void nonJavaFilesAreRecordedAsUnmapped() {
        // README.md is deliberately omitted from this test: v2 added
        // "**}{@code /*.md}" to the default ignore list, so a markdown
        // file no longer contributes to the unmapped bucket. See
        // {@link #markdownRoutesToIgnoredBucketOnDefaults} for the
        // positive-path assertion on that.
        Set<String> changed = Set.of(
                "build.gradle",
                "src/main/resources/application.yml"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.testClasses().isEmpty());
        assertEquals(changed, result.unmappedChangedFiles(),
                "Non-Java files must be surfaced as unmapped so the engine can escalate to runAll");
    }

    @Test
    void markdownRoutesToIgnoredBucketOnDefaults() {
        // v2 default ignorePaths contains "**}{@code /*.md}" so a pure
        // markdown diff routes through Situation.ALL_FILES_IGNORED rather
        // than dragging the engine into the unmapped-file safety net.
        Set<String> changed = Set.of("README.md", "docs/guide.md");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "Markdown must not feed the unmapped bucket under v2 defaults");
        assertEquals(changed, result.ignoredFiles(),
                "Markdown must land in the ignored bucket so the engine can route to ALL_FILES_IGNORED");
    }

    @Test
    void outOfScopeTestDirsFileRoutesToOutOfScopeBucket() {
        // Regression for the api-test use case: a diff that only touches
        // api-test sources must be surfaced as "out of scope" so the
        // engine can skip the unit-test dispatch entirely, instead of
        // trying to map the file as an in-scope test class. The test
        // directory entries are the on-disk paths the plugin sees
        // (no trailing slash, one per concrete sibling dir) so mixed
        // diffs that touch {@code /java} and {@code /resources} both
        // route to the same bucket.
        AffectedTestsConfig oosConfig = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(java.util.List.of(
                        "api-test/src/test/java",
                        "api-test/src/test/resources"))
                .build();
        PathToClassMapper oosMapper = new PathToClassMapper(oosConfig);

        Set<String> changed = Set.of(
                "api-test/src/test/java/com/example/api/FooSteps.java",
                "api-test/src/test/resources/feature.feature");
        MappingResult result = oosMapper.mapChangedFiles(changed);

        assertTrue(result.testClasses().isEmpty(),
                "Out-of-scope test file must not become an in-scope test class");
        assertEquals(changed, result.outOfScopeFiles(),
                "Out-of-scope diff must populate the out-of-scope bucket");
        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "An out-of-scope file must not leak into the unmapped bucket");
    }

    @Test
    void javaFileOutsideConfiguredDirsIsUnmapped() {
        // A .java file that does not sit under any configured source or test
        // directory (e.g. a root-level scratch file or a docs/examples path)
        // can still affect runtime behaviour, so it must be surfaced as
        // unmapped rather than silently dropped.
        Set<String> changed = Set.of("docs/examples/Snippet.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.testClasses().isEmpty());
        assertEquals(changed, result.unmappedChangedFiles());
    }

    @Test
    void excludedFilesAreNotReportedAsUnmapped() {
        // Explicit opt-out must stay silent — an excluded file is neither
        // mapped nor flagged as unmapped.
        AffectedTestsConfig excludeConfig = AffectedTestsConfig.builder()
                .excludePaths(java.util.List.of("**/generated/**"))
                .build();
        PathToClassMapper excludeMapper = new PathToClassMapper(excludeConfig);

        Set<String> changed = Set.of("src/main/java/generated/Stub.java");
        MappingResult result = excludeMapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.testClasses().isEmpty());
        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "Excluded files must not trigger the runAll escalation");
    }

    @Test
    void mappedJavaFilesDoNotAppearAsUnmapped() {
        Set<String> changed = Set.of(
                "src/main/java/com/example/Foo.java",
                "src/test/java/com/example/FooTest.java"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "Properly mapped Java sources must not leak into the unmapped bucket");
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

    @Test
    void extractModuleDoesNotMatchSubstring() {
        // "someapi" should not be mis-parsed when the source dir is "src/main/java"
        // and the module name happens to contain a source dir prefix.
        assertEquals("someapi", mapper.extractModule("someapi/src/main/java/com/example/Foo.java"));
        assertEquals("my-api", mapper.extractModule("my-api/src/main/java/com/example/Foo.java"));
        assertEquals("api-gateway", mapper.extractModule("api-gateway/src/main/java/com/example/Foo.java"));
    }

    @Test
    void extractModuleHandlesNestedPaths() {
        assertEquals("services/core-api",
                mapper.extractModule("services/core-api/src/main/java/com/example/Foo.java"));
    }

    @Test
    void tryMapToClassRequiresBoundaryBeforeSourceDir() {
        // Regression: a plain indexOf("src/main/java/") treats
        // "notsrc/main/java/Foo.java" as a production hit and classifies
        // it as FQN "Foo". That would silently swallow the file — it
        // would neither appear in changedProductionFiles (no real Java
        // sitting under our configured dirs) nor in unmappedChangedFiles
        // (which drives the runAllOnNonJavaChange safety net), defeating
        // the whole "run more, never less" guarantee.
        Set<String> changed = Set.of("notsrc/main/java/Foo.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty(),
                "A path whose source-dir-like prefix is a substring of some other directory "
                        + "must not be mis-classified as production");
        assertTrue(result.testClasses().isEmpty());
        assertEquals(changed, result.unmappedChangedFiles(),
                "The boundary-violating path must land in the unmapped bucket so the engine "
                        + "can escalate to runAll under the default safety policy");
    }

    @Test
    void tryMapToClassHandlesSourceDirAsPathPrefix() {
        // Positive counterpart to the boundary test: when the source dir
        // *does* start the path, matching must still succeed.
        Set<String> changed = Set.of("src/main/java/com/example/Foo.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.Foo"));
        assertTrue(result.unmappedChangedFiles().isEmpty());
    }

    @Test
    void tryMapToClassHandlesSourceDirAfterModulePrefix() {
        // Positive counterpart: when the source dir is preceded by a `/`
        // (standard multi-module layout), matching must still succeed.
        Set<String> changed = Set.of("api-gateway/src/main/java/com/example/Foo.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.Foo"));
        assertTrue(result.unmappedChangedFiles().isEmpty());
    }
}
