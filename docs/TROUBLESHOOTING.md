# Troubleshooting & Performance Tuning

This guide is the canonical place for operator-facing `affectedTest` guidance.
When a run is surprising, start with `--explain`; it prints the selected tests,
the discovery buckets that selected them, the situation classification, and the
timing for each discovery strategy.

```bash
./gradlew affectedTest --explain
./gradlew affectedTest --explain --explain-format=json
```

## Performance tuning

Use the discovery timing block from `--explain` before changing settings. A cold
run includes the initial directory scan, parser work, and cache write; run the
same command twice when measuring steady-state performance.

### Turn off parsers you do not need

If the project does not use Kotlin sources, disable Kotlin parsing:

```kotlin
affectedTests {
    kotlinEnabled = false
}
```

For versions that expose a Groovy parser flag, disable it the same way when the
project does not use Groovy sources:

```kotlin
affectedTests {
    groovyEnabled = false
}
```

Run again with `--explain` and compare the discovery timings.

### Compare parallel and serial discovery

Parallel discovery is the default tuning path for larger projects:

```kotlin
affectedTests {
    parallelDiscovery = true
}
```

If parallel discovery behaves differently on a workstation or CI image, compare
it with the serial escape hatch:

```bash
./gradlew affectedTest --explain
./gradlew affectedTest -PaffectedTestsParallelDiscovery=false --explain
```

Keep the faster setting only after comparing the `Discovery` timing block from
both runs.

## Common failure modes

### Shallow clone or missing base ref

`affectedTest` uses in-process git access. If the base ref is missing, a shallow
checkout can prevent diff calculation and the run fails before selection so CI
does not silently skip tests.

For GitHub Actions, fetch full history:

```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0
```

When needed, pass the base ref explicitly:

```bash
./gradlew affectedTest -PaffectedTestsBaseRef=origin/main --explain
```

### `DISCOVERY_INCOMPLETE`

`DISCOVERY_INCOMPLETE` means the plugin could not fully parse or map the changed
inputs. The final action depends on the configured CI/default behavior and
`onDiscoveryIncomplete` policy, so check the situation line in `--explain`.

Recipe:

```bash
./gradlew affectedTest --explain --info
```

Look for parse failures, unmapped files, and the selected fallback mode. If the
failure is expected for a generated or out-of-scope directory, exclude that tree
with `outOfScopeTestDirs`.

### `UNMAPPED_FILE`

`UNMAPPED_FILE` means a changed file did not map to a supported source/test
bucket. This is common for build files, generated fixtures, scripts, or
non-Java/non-Kotlin JVM sources.

Recipe:

```bash
./gradlew affectedTest --explain
```

If the file should affect all tests, keep the full-suite fallback. If it belongs
to an intentionally ignored test tree, route that tree through
`outOfScopeTestDirs`.

## Platform-specific notes

### Windows shells

Use the wrapper that matches the shell:

```powershell
.\gradlew.bat affectedTest --explain
```

```bash
./gradlew affectedTest --explain
```

Prefer forward slashes in Gradle DSL paths, even on Windows. If a Windows-only
path issue appears, include the exact path and shell in the bug report.

### Bare repositories and worktrees

Bare repositories and alternate worktrees without accessible `.git/objects`
storage are not supported by the in-process git path. Use a normal checkout, or
make sure the worktree has access to the base ref and object database. Otherwise
expect git diff calculation to fail before test selection.

## Cache invalidation

The persistent index cache lives at:

```text
build/affected-tests/index/v1/snapshot.tsv
```

It is invalidated when schema or configuration inputs change, including plugin
version/schema changes, parser flags such as `kotlinEnabled`, source/test
directory settings, and discovery strategy knobs.

To force a rebuild on Unix-like shells:

```bash
rm -rf build/affected-tests/index
./gradlew affectedTest --explain
```

To force a rebuild in PowerShell:

```powershell
Remove-Item -Recurse -Force build/affected-tests/index
.\gradlew.bat affectedTest --explain
```

To disable the cache for one run:

```bash
./gradlew affectedTest -Daffected-tests.indexCache.enabled=false --explain
```

## Diagnostic recipes

### Why was this test selected?

```bash
./gradlew affectedTest --explain --info
```

Check the discovery buckets, changed classes, and situation classification.
`--info` expands lists that are shortened in normal lifecycle logging.

### Why was this test not selected?

```bash
./gradlew affectedTest --explain --explain-format=json
```

Inspect the JSON for the changed file, mapped class, discovery strategy output,
and fallback situation. If a type is missing from the map, check parser support
and source directory configuration.

### Why did the first run look slow?

Clear the cache, run once cold, then run again warm:

```bash
rm -rf build/affected-tests/index
./gradlew affectedTest --explain
./gradlew affectedTest --explain
```

Use the second run as the steady-state comparison unless you are specifically
measuring cold-start behavior.

## Filing a useful bug report

Include enough information for maintainers to reproduce the selection decision:

- Plugin version and commit SHA, if testing a snapshot.
- Gradle version from `./gradlew --version`.
- Java version from `java -version`.
- Operating system and shell.
- Exact command, including `-PaffectedTestsBaseRef`, parser flags, and cache
  flags.
- `--explain` output, or JSON from `--explain --explain-format=json`.
- Relevant `affectedTests { ... }` configuration.
- A minimal reproduction, or the changed files list from the failing run.

For performance reports, include cold and warm timings, whether the cache was
cleared, and whether parallel discovery was enabled.
