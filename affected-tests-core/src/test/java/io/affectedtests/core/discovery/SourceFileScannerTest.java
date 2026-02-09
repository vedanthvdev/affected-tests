package io.affectedtests.core.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceFileScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void findsDirAtRoot() throws IOException {
        Files.createDirectories(tempDir.resolve("src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size());
        assertTrue(matches.get(0).endsWith("src/test/java"));
    }

    @Test
    void findsDirAtDepthOne() throws IOException {
        Files.createDirectories(tempDir.resolve("api/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size());
        assertTrue(matches.get(0).toString().contains("api"));
    }

    @Test
    void findsDirAtDepthTwo() throws IOException {
        Files.createDirectories(tempDir.resolve("services/payment/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size());
        assertTrue(matches.get(0).toString().contains("services/payment")
                || matches.get(0).toString().contains("services\\payment"));
    }

    @Test
    void findsDirAtDepthThree() throws IOException {
        Files.createDirectories(tempDir.resolve("platform/services/payment/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size());
    }

    @Test
    void findsMultipleDirsAtVariousDepths() throws IOException {
        Files.createDirectories(tempDir.resolve("src/test/java"));
        Files.createDirectories(tempDir.resolve("api/src/test/java"));
        Files.createDirectories(tempDir.resolve("services/payment/src/test/java"));
        Files.createDirectories(tempDir.resolve("libs/common/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(4, matches.size(), "Should find root + 3 nested modules");
    }

    @Test
    void skipsBuildAndGitDirs() throws IOException {
        // These should be skipped
        Files.createDirectories(tempDir.resolve("build/generated/src/test/java"));
        Files.createDirectories(tempDir.resolve(".git/hooks/src/test/java"));
        Files.createDirectories(tempDir.resolve(".gradle/caches/src/test/java"));

        // This should be found
        Files.createDirectories(tempDir.resolve("api/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size(), "Should only find api, not build/.git/.gradle");
        assertTrue(matches.get(0).toString().contains("api"));
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertTrue(matches.isEmpty());
    }

    @Test
    void collectsTestFilesFromDeeplyNestedModules() throws IOException {
        Path deepTestDir = tempDir.resolve("services/payment/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("PaymentTest.java"),
                "package com.example;\npublic class PaymentTest {}");

        List<Path> files = SourceFileScanner.collectTestFiles(tempDir, List.of("src/test/java"));

        assertEquals(1, files.size());
        assertTrue(files.get(0).toString().endsWith("PaymentTest.java"));
    }

    @Test
    void scansTestFqnsFromDeeplyNestedModules() throws IOException {
        Path depth1 = tempDir.resolve("api/src/test/java/com/example");
        Files.createDirectories(depth1);
        Files.writeString(depth1.resolve("ApiTest.java"),
                "package com.example;\npublic class ApiTest {}");

        Path depth2 = tempDir.resolve("services/payment/src/test/java/com/example");
        Files.createDirectories(depth2);
        Files.writeString(depth2.resolve("PaymentTest.java"),
                "package com.example;\npublic class PaymentTest {}");

        var fqns = SourceFileScanner.scanTestFqns(tempDir, List.of("src/test/java"));

        assertTrue(fqns.contains("com.example.ApiTest"));
        assertTrue(fqns.contains("com.example.PaymentTest"));
        assertEquals(2, fqns.size());
    }
}
