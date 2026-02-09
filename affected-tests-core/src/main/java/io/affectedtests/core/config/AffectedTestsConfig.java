package io.affectedtests.core.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for affected test detection.
 * Immutable value object — use the {@link Builder} to construct.
 */
public final class AffectedTestsConfig {

    private final String baseRef;
    private final boolean includeUncommitted;
    private final boolean includeStaged;
    private final boolean runAllIfNoMatches;
    private final Set<String> strategies;
    private final int transitiveDepth;
    private final List<String> testSuffixes;
    private final List<String> sourceDirs;
    private final List<String> testDirs;
    private final List<String> excludePaths;
    private final boolean includeImplementationTests;
    private final List<String> implementationNaming;
    private final Map<String, String> testProjectMapping;

    private AffectedTestsConfig(Builder builder) {
        this.baseRef = builder.baseRef;
        this.includeUncommitted = builder.includeUncommitted;
        this.includeStaged = builder.includeStaged;
        this.runAllIfNoMatches = builder.runAllIfNoMatches;
        this.strategies = Set.copyOf(builder.strategies);
        this.transitiveDepth = builder.transitiveDepth;
        this.testSuffixes = List.copyOf(builder.testSuffixes);
        this.sourceDirs = List.copyOf(builder.sourceDirs);
        this.testDirs = List.copyOf(builder.testDirs);
        this.excludePaths = List.copyOf(builder.excludePaths);
        this.includeImplementationTests = builder.includeImplementationTests;
        this.implementationNaming = List.copyOf(builder.implementationNaming);
        this.testProjectMapping = Map.copyOf(builder.testProjectMapping);
    }

    public String baseRef() { return baseRef; }
    public boolean includeUncommitted() { return includeUncommitted; }
    public boolean includeStaged() { return includeStaged; }
    public boolean runAllIfNoMatches() { return runAllIfNoMatches; }
    public Set<String> strategies() { return strategies; }
    public int transitiveDepth() { return transitiveDepth; }
    public List<String> testSuffixes() { return testSuffixes; }
    public List<String> sourceDirs() { return sourceDirs; }
    public List<String> testDirs() { return testDirs; }
    public List<String> excludePaths() { return excludePaths; }
    public boolean includeImplementationTests() { return includeImplementationTests; }
    public List<String> implementationNaming() { return implementationNaming; }
    public Map<String, String> testProjectMapping() { return testProjectMapping; }

    /** Creates a builder with sensible defaults. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseRef = "origin/master";
        private boolean includeUncommitted = true;
        private boolean includeStaged = true;
        private boolean runAllIfNoMatches = false;
        private Set<String> strategies = Set.of("naming", "usage", "impl");
        private int transitiveDepth = 2;
        private List<String> testSuffixes = List.of("Test", "IT", "ITTest", "IntegrationTest");
        private List<String> sourceDirs = List.of("src/main/java");
        private List<String> testDirs = List.of("src/test/java");
        private List<String> excludePaths = List.of("**/generated/**");
        private boolean includeImplementationTests = true;
        private List<String> implementationNaming = List.of("Impl");
        private Map<String, String> testProjectMapping = Map.of();

        public Builder baseRef(String baseRef) {
            if (baseRef == null || baseRef.isBlank()) {
                throw new IllegalArgumentException("baseRef must not be null or blank");
            }
            if (baseRef.contains("..") && baseRef.contains("/")) {
                // Prevent path traversal in ref names like "../../etc/passwd"
                // (normal refs like "origin/master" or SHAs are fine)
                String normalized = baseRef.replace("\\", "/");
                if (normalized.contains("../")) {
                    throw new IllegalArgumentException("baseRef contains suspicious path traversal: " + baseRef);
                }
            }
            this.baseRef = baseRef;
            return this;
        }
        public Builder includeUncommitted(boolean v) { this.includeUncommitted = v; return this; }
        public Builder includeStaged(boolean v) { this.includeStaged = v; return this; }
        public Builder runAllIfNoMatches(boolean v) { this.runAllIfNoMatches = v; return this; }
        public Builder strategies(Set<String> v) { this.strategies = v; return this; }
        public Builder transitiveDepth(int v) { this.transitiveDepth = Math.max(0, Math.min(v, 5)); return this; }
        public Builder testSuffixes(List<String> v) { this.testSuffixes = v; return this; }
        public Builder sourceDirs(List<String> v) { this.sourceDirs = v; return this; }
        public Builder testDirs(List<String> v) { this.testDirs = v; return this; }
        public Builder excludePaths(List<String> v) { this.excludePaths = v; return this; }
        public Builder includeImplementationTests(boolean v) { this.includeImplementationTests = v; return this; }
        public Builder implementationNaming(List<String> v) { this.implementationNaming = v; return this; }
        public Builder testProjectMapping(Map<String, String> v) { this.testProjectMapping = v; return this; }

        public AffectedTestsConfig build() {
            return new AffectedTestsConfig(this);
        }
    }
}
