# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed — behaviour flip, read this

- **`includeUncommitted` and `includeStaged` now default to `false`
  (committed-only).** Previously both defaulted to `true`, meaning a
  local `./gradlew affectedTest` would expand the diff boundary with
  whatever happened to be sitting in the dev's working tree. That made
  local and CI runs of the same HEAD pick different test sets, and the
  inclusion was invisible in the summary log.

  The new default mirrors CI reality (where the tree is clean after
  checkout anyway) and makes two runs on the same commit deterministic
  regardless of workstation state. Adopters who iterate on tests
  locally and want WIP to seed the diff flip the two knobs back on in
  `build.gradle`:

  ```groovy
  affectedTests {
      includeUncommitted = true  // opt back in for local WIP runs
      includeStaged = true
  }
  ```

  No migration is required for anyone who was already setting these
  explicitly — the config resolver always preferred the explicit value
  over the convention. Adopters who relied on the old `true` default
  without setting anything will see a smaller local-run test set until
  they commit or opt back in; CI selection is unchanged.

### Added

- `outOfScopeTestDirs` and `outOfScopeSourceDirs` now accept Ant-style
  globs (`api-test/**`, `**/api-test/**`, `{api,perf}-test/**`) in
  addition to the existing literal directory prefixes. Each list entry
  is classified independently, so users can mix both shapes in the
  same config. Surfaced after a real adopter configured
  `outOfScopeTestDirs = ['api-test/**']` and the engine silently
  treated it as a literal prefix — which never matched.
- New `Hint:` line on `affectedTest --explain` when
  `outOfScopeTestDirs` / `outOfScopeSourceDirs` are configured but
  zero files in the diff landed in the out-of-scope bucket. Points at
  the configured knob so the operator learns about the silent
  misconfiguration on the trace instead of after a 30-minute full-suite
  CI run. The hint is suppressed on empty diffs, on runs where the
  bucket is non-empty, and on zero-config installs — so its rarity is
  itself a signal.
- `OutOfScopeMatchers` — an internal utility shared between
  `PathToClassMapper` and `ProjectIndex`. Not a public API, but
  called out here because it is the structural fix behind the
  glob-alignment bug in the Fixed section below, and because its
  malformed-glob error path is now the single source of truth for
  `Affected Tests: invalid glob at outOfScope*Dirs[N]` messages.

### Fixed — post-v1.9.15 review batch

- `outOfScopeTestDirs` / `outOfScopeSourceDirs` glob entries now work
  on both sides of the pipeline. Before this fix the diff-side
  classifier in `PathToClassMapper` honoured `"api-test/**"` but the
  on-disk classifier in `ProjectIndex` treated the same string as a
  literal prefix, so a mixed diff (one production file + a refactor
  under `api-test/`) bucketed the api-test file correctly yet still
  dispatched tests discovered under `api-test/src/test/java`. Both
  sides now delegate to a shared compiler, with a regression test that
  exercises the literal and glob shapes against identical on-disk
  layouts.
- `git rm`-only MRs no longer silently skip all tests. `DiffEntry.DELETE`
  entries now surface through their old path, so ignore/out-of-scope
  rules apply normally and deleted production classes reach the
  transitive strategy instead of routing the whole MR through
  `EMPTY_DIFF → SKIPPED` under `local`/`ci` mode. The existing engine
  filter still drops FQNs whose backing file is gone, so surfacing
  deletions never asks Gradle to run a missing test.
- `ImplementationStrategy` now recognises the `DefaultFooService`
  prefix shape, not only the `FooServiceImpl` suffix. The plugin has
  always shipped `implementationNaming = ["Impl", "Default"]` and the
  Javadoc documents both shapes, but the naming-convention loop used
  to append both tokens as suffixes (`FooServiceDefault`), matching
  nothing real. The AST-scan branch rescued explicit
  `implements FooService` cases; generics-only declarations and files
  that JavaParser couldn't parse silently missed the Default-prefixed
  impl.
- Turkish (and any other locale whose case-folding tables differ from
  US English) no longer turn `mode = "ci"` into `Unknown
  affectedTests.mode 'ci'`. `parseMode` and `parseAction` now force
  `Locale.ROOT`, matching `AffectedTestsConfig`'s own parsing. The
  Windows detection in the Gradle-command resolver was pinned to
  `Locale.ROOT` for the same reason.
- `PathToClassMapper.isIgnored` and the `OutOfScopeMatchers` glob
  matchers now fail closed on `InvalidPathException` (NUL bytes,
  Linux-committed filenames like `foo:bar.md` arriving on a Windows
  CI runner, Windows reserved names). Before this fix the unhandled
  exception killed the whole `affectedTest` task with a stack trace;
  now the offending file falls through to the unmapped bucket and the
  safety net escalates normally.
- `GitChangeDetector` now translates JGit's `MissingObjectException`
  into a targeted message naming the likely cause: a shallow clone in
  CI that doesn't know the base ref. Before, users saw the raw JGit
  exception and had to guess whether the problem was the ref, the
  clone depth, or a corrupt repo.
- Malformed globs in `outOfScopeTestDirs` / `outOfScopeSourceDirs` /
  `ignorePaths` now fail at config-time with an
  `IllegalStateException` naming the config key, list index, and
  offending pattern. The raw `PatternSyntaxException` the JVM throws
  is useless on its own because it doesn't say which config entry
  caused the regex error.
- The `--tests` argv assembly now skips and warns on any discovered
  FQN that isn't shaped like a Java identifier. Defense-in-depth
  against a buggy custom strategy or parser anomaly injecting a shell
  metacharacter, whitespace, or a hyphen into Gradle's test filter —
  such strings either crashed the test runner with an obscure glob-
  expansion error or silently matched zero tests.
- Per-FQN dispatch lines in the task output demoted from `lifecycle`
  to `info`. Each MR now gets one `lifecycle` summary per Gradle task
  (`:api:test (3 test classes)`); the individual FQNs print only when
  the user opts in with `--info`. Before, a 200-class MR produced 200
  console lines and drowned the single line the user actually cared
  about — the outcome/situation summary.

### Documentation

- `Mode` Javadoc now matches the mode-defaults table in the design
  doc. The post-v2 table said zero-config CI users get
  `DISCOVERY_EMPTY = FULL_SUITE` on top of `LOCAL`, but the class-
  level doc still described `LOCAL` as the pre-v2 baseline "minus"
  the safety net.
- `Situation` Javadoc cross-reference for `ALL_FILES_OUT_OF_SCOPE`
  updated — the old reference pointed at a since-renamed constant.
- `TransitiveStrategy` and `AffectedTestsExtension` now document the
  actual `transitiveDepth` default of `4`, not the pre-v2 value of
  `2`. Consumers reading Javadoc were being told they needed to set
  `transitiveDepth = 4` explicitly; they do not.

## [1.9.12] — 2026-04-22

Metadata and release-pipeline release — no plugin behaviour change. Introduces
this file, fixes the README's outdated guidance on forcing minor/major bumps,
and teaches the release workflow a proper entry point for non-patch releases
so the next minor bump can actually be requested end-to-end.

### Added

- `CHANGELOG.md` itself, plus a README link so it's discoverable from the
  landing page.
- `workflow_dispatch` trigger on `release.yml` with an optional `version`
  input. Supplying `version: 1.10.0` runs
  `./gradlew release -Prelease.version=1.10.0` and overrides the default patch
  auto-increment. Manually triggered runs still go through the same
  portal-check and GH-release steps, so they stay idempotent.

### Fixed

- README Versioning table previously claimed
  `./gradlew markNextVersion -Prelease.version=X.Y.0` would force a minor bump
  on the next release. It did not — axion-release's `markNextVersion` creates
  a `release-X.Y.Z` marker tag that only influences the `currentVersion`
  *display*, not the `release` task. The table now reflects the three
  mechanisms that actually work: default patch (merge to master), explicit
  version (Actions → Release → Run workflow), and manual local
  (`./gradlew release -Prelease.version=X.Y.Z` with a Personal Access Token).

## Release history before this file existed

The v2 configuration model landed additively across the preceding patch
releases. The full per-tag auto-generated notes are on
[GitHub Releases](https://github.com/vedanthvdev/affected-tests/releases). In
broad strokes:

- **v1.9.11** — Deprecation warnings on the three legacy knobs
  (`runAllIfNoMatches`, `runAllOnNonJavaChange`, `excludePaths`), pointing at
  the v2 replacements. README examples flipped to v2-native.
  See [`docs/DESIGN-v2.md`](docs/DESIGN-v2.md) for the design doc and the
  "Migrating from v1 config" section in `README.md` for the worked example,
  before/after table, and decision tree. The legacy knobs are scheduled for
  removal in v2.0.0.
- **v1.9.10** — Named outcome and situation on every `affectedTest` summary
  log line:
  `Affected Tests: <OUTCOME> (<SITUATION>) — <details>`. Matches the
  vocabulary `--explain` reports.
- **v1.9.9** — `--explain` CLI flag on `affectedTest`. Prints the full
  decision trace — bucketed diff, resolved action per situation with the
  priority-ladder tier that picked each
  (`EXPLICIT` / `LEGACY_BOOLEAN` / `MODE_DEFAULT` / `HARDCODED_DEFAULT`), and
  the final outcome — without running any tests.
- **v1.9.8** — Core v2 configuration API: the `Action` / `Situation` / `Mode`
  vocabulary, per-situation `onXxx` actions, `ignorePaths` (replaces
  `excludePaths` with a broader default list), and
  `outOfScopeTestDirs` / `outOfScopeSourceDirs` for Cucumber / API-test
  exclusions that should skip rather than escalate to the full suite.
- **v1.9.7 and earlier** — Plugin bring-up, Gradle Plugin Portal publishing,
  safety hardening, multi-module scanning, axion-release versioning. See the
  Releases page for detail.

[Unreleased]: https://github.com/vedanthvdev/affected-tests/compare/v1.9.12...HEAD
[1.9.12]: https://github.com/vedanthvdev/affected-tests/releases/tag/v1.9.12
