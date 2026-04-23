package io.affectedtests.gradle.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-scenario shared state. Cucumber creates one instance per scenario
 * via its dependency-injection container; the {@code steps/*.java}
 * classes take a {@code World} constructor argument and Cucumber wires
 * them up. This eliminates the usual BDD pitfall of "did this step
 * modify the same state the next step reads?" — every step file sees
 * the same {@code World}, and the {@code World} owns the single
 * {@link TestProject} a scenario interacts with.
 */
public final class World {

    private final Path rootTempDir;
    private TestProject project;

    public World() throws IOException {
        this.rootTempDir = Files.createTempDirectory("affected-tests-e2e-");
    }

    /**
     * Lazily initialises the underlying {@link TestProject} on first
     * access. Keeps Given steps free to add DSL snippets or files
     * before the project is "concrete", matching the way feature files
     * read: "Given a project with X" is a single declaration, not a
     * setup-then-mutate sequence.
     *
     * <p>Any failure to bootstrap the TestKit project wraps into an
     * {@code IllegalStateException} so step-def call sites don't have
     * to declare {@code throws Exception} on every assertion. The only
     * way this actually fails at runtime is if the temp-dir write path
     * is unwritable, which is a catastrophic CI environment problem,
     * not a scenario-level concern.
     */
    public TestProject project() {
        if (project == null) {
            try {
                project = TestProject.createEmptyBaseline(rootTempDir.resolve("project"));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialise e2e TestProject", e);
            }
        }
        return project;
    }

    public Path rootTempDir() { return rootTempDir; }

    /**
     * Recursively removes the scratch temp dir. Scenarios that fail
     * still run this via Cucumber's {@code @After} hook so CI runners
     * don't accumulate dangling repos across retries.
     */
    public void cleanup() throws IOException {
        if (rootTempDir != null && Files.exists(rootTempDir)) {
            try (var stream = Files.walk(rootTempDir)) {
                stream.sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignore) { }
                        });
            }
        }
    }
}
