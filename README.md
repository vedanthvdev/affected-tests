# Affected Tests

A Gradle plugin that detects changes in the current branch and runs only the unit and integration tests relevant to those changes. No seed run required — it works immediately.

**Target stack:** Gradle 9+, Spring Boot 4+, Java 21+, JUnit 5

## Quick Start

### 1. Apply the plugin

```groovy
// build.gradle
plugins {
    id 'io.affectedtests' version '1.1.0'
}
```

### 2. Run affected tests

```bash
./gradlew affectedTest
```

That's it. With zero config, the plugin will:
- Diff against `origin/master`
- Include uncommitted and staged changes
- Use naming, usage, and implementation strategies to discover affected tests
- Follow 2 levels of transitive dependencies

## CI Integration

### GitHub Actions

```yaml
- run: ./gradlew affectedTest -PaffectedTestsBaseRef=${{ github.event.pull_request.base.sha }}
```

### GitLab CI / Jenkins

```bash
./gradlew affectedTest -PaffectedTestsBaseRef=origin/main
```

> Make sure to use `fetch-depth: 0` so `git diff` has access to the full history.

## Configuration

All settings have sensible defaults. Override only what you need:

```groovy
affectedTests {
    // Git base ref to diff against (default: "origin/master")
    baseRef = "origin/main"

    // Include uncommitted/staged changes (default: true)
    includeUncommitted = true
    includeStaged = true

    // Run full suite if no matches found (default: false)
    runAllIfNoMatches = false

    // Discovery strategies: "naming", "usage", "impl" (default: all three)
    strategies = ["naming", "usage", "impl"]

    // Transitive dependency depth (default: 2, max: 5, 0 = disabled)
    transitiveDepth = 2

    // Test class suffixes (default: ["Test", "IT", "ITTest", "IntegrationTest"])
    testSuffixes = ["Test", "IT", "ITTest", "IntegrationTest"]

    // Source directories (default: ["src/main/java"])
    sourceDirs = ["src/main/java"]

    // Test directories (default: ["src/test/java"])
    testDirs = ["src/test/java"]

    // Exclude patterns (default: ["**/generated/**"])
    excludePaths = ["**/generated/**", "**/*Dto.java"]

    // Include tests for implementations of changed interfaces (default: true)
    includeImplementationTests = true

    // Implementation naming suffixes (default: ["Impl"])
    implementationNaming = ["Impl"]

    // Multi-module: map source project to test project
    testProjectMapping = [":api": ":application"]
}
```

## How It Works

```
1. DETECT CHANGES
   git diff <baseRef> HEAD + uncommitted/staged
   → list of changed file paths

2. MAP TO CLASSES
   Filter *.java under src/main/java and src/test/java
   → production classes + test classes

3. DISCOVER AFFECTED TESTS
   Strategy A: Naming convention (FooBar → FooBarTest, FooBarIT, …)
   Strategy B: Usage scanning (parse test sources for field references)
   Strategy C: Implementation discovery (interface → impl → impl tests)
   Strategy D: Transitive dependencies (controller → service → repo tests)
   → list of test class FQNs

4. RUN TESTS
   Gradle test { filter { includeTests("...") } }
   → only affected tests execute
```

### Discovery Strategies

| Strategy | What it does |
|----------|-------------|
| **naming** | Looks for `FooBarTest`, `FooBarIT`, etc. in test directories |
| **usage** | Parses test files for field declarations that reference the changed class |
| **impl** | When an interface/base class changes, also tests its implementations |
| **transitive** | Follows the dependency graph N levels deep (configurable) |

## Multi-Module Support

For projects where tests for one module live in another:

```groovy
affectedTests {
    testProjectMapping = [
        ":api": ":application",       // api tests live in application module
        ":domain": ":application"     // domain tests also live in application
    ]
}
```

## Fallback Behavior

| Scenario | Default behavior |
|----------|-----------------|
| No changed Java files | Exit 0, skip tests |
| No matching tests found | Exit 0 (or run full suite if `runAllIfNoMatches = true`) |
| Git not available | Clear error message |

## Project Structure

```
affected-tests/
├── affected-tests-core/          # Git integration, change detection, test discovery
├── affected-tests-gradle/        # Gradle plugin (io.affectedtests)
├── build.gradle
├── settings.gradle
└── README.md
```

## Requirements

| Component | Version |
|-----------|---------|
| Gradle | 9.x (primary), 8.x (best-effort) |
| Java | 21+ (including 25) |
| JUnit | 5.x |
| Spring Boot | 4.x (compatible, no Boot-specific code) |

## Dependencies

- **JGit** — Git change detection (no native git binary required)
- **JavaParser** — Source-level test discovery (usage + implementation strategies)
- **SLF4J** — Logging

## License

Apache 2.0
