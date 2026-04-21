package io.affectedtests.core.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration for affected test detection.
 * Immutable value object — use the {@link Builder} to construct.
 */
public final class AffectedTestsConfig {

    /** The built-in discovery strategy names. */
    public static final String STRATEGY_NAMING = "naming";
    public static final String STRATEGY_USAGE = "usage";
    public static final String STRATEGY_IMPL = "impl";
    public static final String STRATEGY_TRANSITIVE = "transitive";

    private final String baseRef;
    private final boolean includeUncommitted;
    private final boolean includeStaged;
    private final boolean runAllIfNoMatches;
    private final boolean runAllOnNonJavaChange;
    private final Set<String> strategies;
    private final int transitiveDepth;
    private final List<String> testSuffixes;
    private final List<String> sourceDirs;
    private final List<String> testDirs;
    private final List<String> excludePaths;
    private final boolean includeImplementationTests;
    private final List<String> implementationNaming;

    private AffectedTestsConfig(Builder builder) {
        this.baseRef = builder.baseRef;
        this.includeUncommitted = builder.includeUncommitted;
        this.includeStaged = builder.includeStaged;
        this.runAllIfNoMatches = builder.runAllIfNoMatches;
        this.runAllOnNonJavaChange = builder.runAllOnNonJavaChange;
        this.strategies = Set.copyOf(builder.strategies);
        this.transitiveDepth = builder.transitiveDepth;
        this.testSuffixes = List.copyOf(builder.testSuffixes);
        this.sourceDirs = List.copyOf(builder.sourceDirs);
        this.testDirs = List.copyOf(builder.testDirs);
        this.excludePaths = List.copyOf(builder.excludePaths);
        this.includeImplementationTests = builder.includeImplementationTests;
        this.implementationNaming = List.copyOf(builder.implementationNaming);
    }

    public String baseRef() { return baseRef; }
    public boolean includeUncommitted() { return includeUncommitted; }
    public boolean includeStaged() { return includeStaged; }
    public boolean runAllIfNoMatches() { return runAllIfNoMatches; }

    /**
     * Whether to force a full test run whenever the change set contains any
     * file that cannot be resolved to a Java class under the configured
     * {@link #sourceDirs()} or {@link #testDirs()} — for example
     * {@code application.yml}, {@code build.gradle}, a Liquibase changelog,
     * or a logback config. Files matching {@link #excludePaths()} are
     * treated as an explicit opt-out and do not trigger the escalation.
     *
     * <p>Default: {@code true} — "run more, never run less".
     *
     * @return the run-all-on-non-java-change property
     */
    public boolean runAllOnNonJavaChange() { return runAllOnNonJavaChange; }
    public Set<String> strategies() { return strategies; }
    public int transitiveDepth() { return transitiveDepth; }
    public List<String> testSuffixes() { return testSuffixes; }
    public List<String> sourceDirs() { return sourceDirs; }
    public List<String> testDirs() { return testDirs; }
    public List<String> excludePaths() { return excludePaths; }
    public boolean includeImplementationTests() { return includeImplementationTests; }
    public List<String> implementationNaming() { return implementationNaming; }

    /** Creates a builder with sensible defaults. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseRef = "origin/master";
        private boolean includeUncommitted = true;
        private boolean includeStaged = true;
        private boolean runAllIfNoMatches = false;
        private boolean runAllOnNonJavaChange = true;
        private Set<String> strategies = Set.of(STRATEGY_NAMING, STRATEGY_USAGE, STRATEGY_IMPL, STRATEGY_TRANSITIVE);
        private int transitiveDepth = 2;
        private List<String> testSuffixes = List.of("Test", "IT", "ITTest", "IntegrationTest");
        private List<String> sourceDirs = List.of("src/main/java");
        private List<String> testDirs = List.of("src/test/java");
        private List<String> excludePaths = List.of("**/generated/**");
        private boolean includeImplementationTests = true;
        private List<String> implementationNaming = List.of("Impl");

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
        public Builder runAllOnNonJavaChange(boolean v) { this.runAllOnNonJavaChange = v; return this; }
        public Builder strategies(Set<String> v) {
            this.strategies = Objects.requireNonNull(v, "strategies must not be null");
            return this;
        }
        public Builder transitiveDepth(int v) { this.transitiveDepth = Math.max(0, Math.min(v, 5)); return this; }
        public Builder testSuffixes(List<String> v) {
            this.testSuffixes = Objects.requireNonNull(v, "testSuffixes must not be null");
            return this;
        }
        public Builder sourceDirs(List<String> v) {
            this.sourceDirs = Objects.requireNonNull(v, "sourceDirs must not be null");
            return this;
        }
        public Builder testDirs(List<String> v) {
            this.testDirs = Objects.requireNonNull(v, "testDirs must not be null");
            return this;
        }
        public Builder excludePaths(List<String> v) {
            this.excludePaths = Objects.requireNonNull(v, "excludePaths must not be null");
            return this;
        }
        public Builder includeImplementationTests(boolean v) { this.includeImplementationTests = v; return this; }
        public Builder implementationNaming(List<String> v) {
            this.implementationNaming = Objects.requireNonNull(v, "implementationNaming must not be null");
            return this;
        }

        public AffectedTestsConfig build() {
            return new AffectedTestsConfig(this);
        }
    }
}
