# Affected Tests

A Gradle plugin that detects changes in the current branch and runs only the unit and integration tests relevant to those changes. No seed run required — it works immediately.

**Target stack:** Gradle 8+ (primary support: 9.x), Java 21+, JUnit 5

## Quick Start

### 1. Apply the plugin

```groovy
// build.gradle
plugins {
    id 'io.github.vedanthvdev.affectedtests' version 'x.y.z'
}
```

> Check [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.vedanthvdev.affectedtests) for the latest version.

### 2. Run affected tests

```bash
./gradlew affectedTest
```

That's it. With zero config, the plugin will:
- Diff against `origin/master`
- Include uncommitted and staged changes
- Use naming, usage, implementation, and transitive strategies to discover affected tests
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

    // Run the full suite whenever there is nothing specific to run — either
    // because the diff produced no changed files at all OR because discovery
    // ran and returned an empty set. The two branches produce distinct
    // escalation reasons in the CI log so an operator can tell them apart.
    // Default: false (skip tests silently in both cases).
    runAllIfNoMatches = false

    // Force a full run when the diff contains any file we cannot resolve to
    // a Java class under sourceDirs/testDirs. This covers both non-Java
    // resources (application.yml, build.gradle, Liquibase changelogs,
    // logback config) AND stray .java files living outside the configured
    // source/test dirs (e.g. buildSrc sources when buildSrc is not in
    // sourceDirs). Excluded paths are honoured and do not trigger the
    // escalation. This is independent of runAllIfNoMatches — the two
    // safety nets fire on different conditions. Default: true.
    runAllOnNonJavaChange = true

    // Discovery strategies: "naming", "usage", "impl", "transitive" (default: all four)
    strategies = ["naming", "usage", "impl", "transitive"]

    // Transitive dependency depth — used when the "transitive" strategy is enabled
    // (default: 2, max: 5, 0 = disabled)
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
}
```

## How It Works

The pipeline is four stages: **detect** what changed, **map** each path to a Java class (or declare it unmappable), **discover** the tests impacted by those classes, and **execute** only that subset — or the full suite if safety rules demand it.

<p align="center">
  <img src="docs/architecture.svg" alt="Affected Tests architecture: git diff feeds PathToClassMapper, which routes Java files into 4 discovery strategies for per-module test dispatch; two safety gates (runAllIfNoMatches on empty changesets or empty discovery, runAllOnNonJavaChange on unmapped files) escalate to a full run" width="100%">
</p>

<sub>Source: [`docs/architecture.mmd`](docs/architecture.mmd) · regenerate with `npx --yes @mermaid-js/mermaid-cli -i docs/architecture.mmd -o docs/architecture.svg -b transparent`</sub>

### Discovery Strategies

All four strategies run against every changed production class. Their results are merged (union), so a test is run if **any** strategy identifies it. The goal is maximum coverage — running a few extra tests is always preferable to missing one.

| Strategy | What it does | Example |
|----------|-------------|---------|
| **naming** | For each changed class `Foo`, looks for test files named `FooTest`, `FooIT`, `FooITTest`, `FooIntegrationTest` (configurable suffixes). Purely file-name based — no parsing required. | `FooService` changed → finds `FooServiceTest`, `FooServiceIT` |
| **usage** | Parses every test file with JavaParser and checks whether it references any changed class. Uses a two-tier approach: **(1)** direct import match — if the test has `import com.example.FooService;`, it's affected regardless of how it uses the class; **(2)** type-reference scan for wildcard imports (`import com.example.*`) and same-package usage (no import needed). Catches fields, method parameters, return types, constructor calls, generics, and casts. | `BarModel` changed → finds `BarValidatorTest` (imports it), `BazMapperTest` (same package, uses it as field type) |
| **impl** | When an interface or base class changes, scans all production source files to find classes that `extends` or `implements` the changed type (via AST) and classes following the `*Impl` naming convention. Then re-runs the naming and usage strategies on those implementations. | `FooService` (interface) changed → finds `FooServiceImpl` → finds `FooServiceImplTest` |
| **transitive** | Builds a reverse dependency map of all production classes: for each class, which other classes depend on it (via field types). When a class changes, walks this "used-by" graph N levels deep (configurable, default 2, max 5) to find consumers. Then runs naming + usage on those consumers. | `BazGateway` changed → `FooService` uses it (depth 1) → finds `FooServiceTest` via naming |

### How scanning works

The plugin scans the project tree **recursively at any depth** to find source and test directories. It is completely project-structure agnostic — it does not assume any particular module layout. Whether your modules are flat (`api/src/test/java`), nested (`services/orders/src/test/java`), or deeply nested (`platform/services/orders/src/test/java`), all test files are discovered.

Directories like `.git`, `build`, `.gradle`, and `node_modules` are automatically skipped during the walk.

### Directly changed tests

Any test file that is itself modified in the diff is **always** included in the run, regardless of strategy results.

## Multi-Module Support

The plugin works out of the box with multi-module projects — it recursively scans all modules at any nesting depth, so no configuration is needed.

Internally, each discovered test FQN is traced back to the Gradle subproject that owns its file, and tests are then dispatched per module:

```
./gradlew :moduleA:test --tests com.example.FooTest \
          :moduleB:test --tests com.example.BarTest
```

This makes `--tests` filters scope cleanly to their owning module, instead of being applied globally and failing on any subproject that doesn't happen to contain the FQN. Cross-module imports (e.g. a test in `application` that imports a class from `api`) are still detected correctly via the `usage` and `impl` strategies.

## Fallback Behavior

| Scenario | Default behavior |
|----------|-----------------|
| Change set contains only mapped Java sources | Run the filtered set of affected tests |
| Change set contains any non-Java or unmapped file — YAML, Gradle, Liquibase, logback, **or a `.java` file outside the configured `sourceDirs`/`testDirs`** | **Run the full test suite** (via `runAllOnNonJavaChange = true`) — opt out with `runAllOnNonJavaChange = false` to restore silent skip |
| No changed files at all | Exit 0 (or run full suite if `runAllIfNoMatches = true`) — reported to CI as `runAllIfNoMatches=true — no changed files detected`, distinct from the "discovery ran and found nothing" case below |
| No matching tests found after discovery | Exit 0 (or run full suite if `runAllIfNoMatches = true`) — reported to CI as `runAllIfNoMatches=true — no affected tests discovered` |
| Changed file matches an entry in `excludePaths` | Silently ignored — excluded paths are an explicit opt-out and never trigger the non-Java escalation |
| Base ref not found | **Fails with clear error** (prevents silent test skipping in CI) |
| Git not available | **Fails with clear error** |

The `runAllOnNonJavaChange` default follows the "run more, never run less" principle: a change to `application.yml` can alter production behaviour just as surely as a change to a `.java` file, so the plugin cannot safely pick a subset from an empty Java mapping. Projects that prefer the older "silent skip" behaviour can set `runAllOnNonJavaChange = false`.

## Project Structure

```
affected-tests/
├── affected-tests-core/          # Git integration, change detection, test discovery
├── affected-tests-gradle/        # Gradle plugin (io.github.vedanthvdev.affectedtests)
├── docs/
│   ├── architecture.mmd          # Mermaid source for the architecture diagram
│   └── architecture.svg          # Rendered diagram embedded in README
├── build.gradle
├── settings.gradle
└── README.md
```

## Requirements

| Component | Version |
|-----------|---------|
| Gradle | 8.x+, primary support on 9.x |
| Java | 21+ |
| JUnit | 5.x |
| Spring Boot | Compatible (no Boot-specific code) |

## Dependencies

- **JGit** — Git change detection (no native git binary required)
- **JavaParser** — Source-level test discovery (usage + implementation strategies)
- **SLF4J** — Logging

## Versioning

Versions are managed automatically via [axion-release](https://github.com/allegro/axion-release-plugin) — derived from git tags, never hardcoded in source.

| Goal | Command |
|------|---------|
| Check current version | `./gradlew currentVersion` |
| Auto patch release | Just merge to master (CI does it) |
| Force minor bump next | `./gradlew markNextVersion -Prelease.version=X.Y.0` |
| Force major bump next | `./gradlew markNextVersion -Prelease.version=X.0.0` |
| Manual release | `./gradlew release` |
| Release as RC | `./gradlew release -Prelease.versionIncrementer=incrementPrerelease` |

## License

Apache 2.0
