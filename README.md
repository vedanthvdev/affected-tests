# Affected Tests

A Gradle plugin that detects changes in the current branch and runs only the unit and integration tests relevant to those changes. No seed run required — it works immediately.

**Target stack:** Gradle 9+, Spring Boot 4+, Java 21+, JUnit 5

## Quick Start

### 1. Apply the plugin

```groovy
// build.gradle
plugins {
    id 'io.affectedtests' version '1.5.0'
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
   Filter *.java files under configured source/test directories
   → production class FQNs + test class FQNs

3. DISCOVER AFFECTED TESTS (all strategies run in parallel)
   Strategy A: Naming convention
   Strategy B: Usage / import scanning
   Strategy C: Implementation discovery
   Strategy D: Reverse transitive dependencies
   → union of all discovered test class FQNs

4. RUN TESTS
   ./gradlew test --tests "com.example.FooTest" --tests "..."
   → only affected tests execute
```

### Discovery Strategies

All four strategies run against every changed production class. Their results are merged (union), so a test is run if **any** strategy identifies it. The goal is maximum coverage — running a few extra tests is always preferable to missing one.

| Strategy | What it does | Example |
|----------|-------------|---------|
| **naming** | For each changed class `Foo`, looks for test files named `FooTest`, `FooIT`, `FooITTest`, `FooIntegrationTest` (configurable suffixes). Purely file-name based — no parsing required. | `PaymentService` changed → finds `PaymentServiceTest`, `PaymentServiceIT` |
| **usage** | Parses every test file with JavaParser and checks whether it references any changed class. Uses a two-tier approach: **(1)** direct import match — if the test has `import com.example.PaymentService;`, it's affected regardless of how it uses the class; **(2)** type-reference scan for wildcard imports (`import com.example.*`) and same-package usage (no import needed). Catches fields, method parameters, return types, constructor calls, generics, and casts. | `PaymentDetails` changed → finds `OverseasPaymentDetailValidatorTest` (imports it), `InternalTest` (same package, uses it as field type) |
| **impl** | When an interface or base class changes, scans all production source files to find classes that `extends` or `implements` the changed type (via AST) and classes following the `*Impl` naming convention. Then re-runs the naming and usage strategies on those implementations. | `PaymentService` (interface) changed → finds `PaymentServiceImpl` → finds `PaymentServiceImplTest` |
| **transitive** | Builds a reverse dependency map of all production classes: for each class, which other classes depend on it (via field types). When a class changes, walks this "used-by" graph N levels deep (configurable, default 2, max 5) to find consumers. Then runs naming + usage on those consumers. | `PaymentGateway` changed → `PaymentService` uses it (depth 1) → finds `PaymentServiceTest` via naming |

### How scanning works

The plugin scans the project tree **recursively at any depth** to find source and test directories. It is completely project-structure agnostic — it does not assume any particular module layout. Whether your modules are flat (`api/src/test/java`), nested (`services/payment/src/test/java`), or deeply nested (`platform/services/payment/src/test/java`), all test files are discovered.

Directories like `.git`, `build`, `.gradle`, and `node_modules` are automatically skipped during the walk.

### Directly changed tests

Any test file that is itself modified in the diff is **always** included in the run, regardless of strategy results.

## Multi-Module Support

The plugin works out of the box with multi-module projects — it recursively scans all modules at any nesting depth. No configuration is needed for the common case.

For projects where tests for one module live in a **different** module (e.g. `api` classes are tested in `application`), you can add an explicit mapping:

```groovy
affectedTests {
    testProjectMapping = [
        ":api": ":application",       // api tests live in application module
        ":domain": ":application"     // domain tests also live in application
    ]
}
```

When a class in `:api` changes, the plugin will search for affected tests in both the root project and the `:application` module. This ensures cross-module imports (e.g. a test in `application` that imports a class from `api`) are detected correctly.

## Fallback Behavior

| Scenario | Default behavior |
|----------|-----------------|
| No changed Java files | Exit 0, skip tests |
| No matching tests found | Exit 0 (or run full suite if `runAllIfNoMatches = true`) |
| Base ref not found | **Fails with clear error** (prevents silent test skipping in CI) |
| Git not available | **Fails with clear error** |

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
