# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed — post-v1.9.18 code-review pass

A four-reviewer sweep (correctness, security, maintainability, simplicity)
ran against the plugin source tree as it landed in v1.9.18. Ten findings
were triaged into two tiers — safety-critical (Tier 1) and correctness
gaps that silently dropped tests in real hierarchies (Tier 2) — and both
tiers are resolved in this release. Tier 3 polish items ship in the same
PR because they touch adjacent code and keep v2.0 from carrying dead
weight.

- **Transitive strategy no longer drops consumer tests when a production
  class is deleted.** `TransitiveStrategy.buildReverseDependencyMap`
  built its `allKnownFqns` set purely from files currently on disk.
  A pure `git rm FooService.java` MR ended up with `FooService` in the
  changed set (surfaced by `GitChangeDetector` via the old path) but
  nowhere in the reverse map, so the consumers of `FooService` — the
  only tests that would have caught the breakage — were silently
  excluded. The fix unions `changedProductionClasses` into
  `allKnownFqns` before walking. Regression lives in
  `TransitiveStrategyTest#discoversConsumerTestsForDeletedProductionClass`.
- **`SourceFileScanner` no longer follows symlinks out of the project
  root.** An MR that introduced `src/main/java` as a symlink to `/`
  (or any path outside the project) would walk the victim's filesystem,
  enumerating arbitrary `.java` files in the scanner's match list and
  inflating the scan to the point of DoS on laptops. Matched filenames
  then flowed into `--explain` samples and the WARN log, which is where
  the leak would have become visible to the attacker on CI. 
  `findAllMatchingDirs` now canonicalises every candidate via
  `Path.toRealPath()` and rejects anything whose real path does not
  start with the project root's real path. Two regressions:
  `rejectsSymlinkEscapingProjectRoot` covers the exploit;
  `acceptsSymlinkThatStaysInsideProjectRoot` prevents the cure from
  being worse than the disease for monorepos that symlink between
  sibling modules.
- **Log output is now sanitised.** Filenames from the git diff can
  legally contain newlines, carriage returns, and ANSI escape bytes —
  JGit faithfully preserves them. Before this release the
  `--explain` sample lines, the unmapped-file WARN, the malformed-FQN
  WARN in the Gradle task, and the per-test INFO line in the engine
  all fed those bytes straight into SLF4J, which means a hostile MR
  could forge whole log lines (including fake
  `Affected Tests: SELECTED` headers) or colour legitimate log lines
  to hide warnings from a human reviewer. New `LogSanitizer` escapes
  the C0 range (NUL..US), DEL (0x7F), and the C1 range (0x80..0x9F,
  which includes single-byte CSI) into visible `\\xHH` / `\\n` / `\\r`
  / `\\t` forms, and every filename-derived log site — four in total
  — now routes through it.
- **`AffectedTestsConfig#effectiveMode()` now always returns a concrete
  mode.** The Javadoc promised one of `LOCAL`, `CI`, `STRICT`; the
  getter returned `null` for zero-config callers, so the documented
  switch shape NPE'd on anyone who followed the Javadoc to the letter.
  The public getter now falls back to `Builder.detectMode()` when the
  internal field is unset — preserving the nullable internal signal
  that `resolveSituationActions` uses to distinguish "mode was
  explicitly set" from "we inferred it" — and a regression test
  (`effectiveModeIsAlwaysConcreteForZeroConfigCallers`) locks the
  contract down.
- **`outOfScopeTestDirs`/`outOfScopeSourceDirs` now catch literal
  entries whose value equals the resolved directory path.** The
  matcher treated `sub/src/main/java` as a prefix only and relied on
  `contains("/sub/src/main/java/")`. `ProjectIndex` relativises source
  directories with `Path.relativize`, which never emits a trailing
  slash, so the contain-check missed its only real input shape. The
  literal branch now also accepts `path.equals(bare)` and
  `path.endsWith("/" + bare)`. Regression:
  `OutOfScopeMatchersTest#literalEntryMatchesExactDirectoryPath`.
- **`UsageStrategy` now selects tests that import inner classes or use
  static imports of the changed class.** Pre-fix, `import x.Outer.Inner`
  and `import static x.Constants.MAX` did not register as a reference
  to `x.Outer` / `x.Constants`, so a genuinely affected test whose only
  reference to the changed surface was a static constant was silently
  dropped. `testReferencesChangedClass` now strips the last segment
  from static imports (handling both the named-member and
  `x.C.*` shapes) and prefix-matches inner-class imports against
  changed FQNs. Three regressions cover the inner-class direct import,
  the static-member import, and the static wildcard.
- **`ImplementationStrategy` now performs a fixpoint walk of the
  subtype closure.** Given `interface A <- abstract B implements A
  <- class C extends B`, only B is a direct subtype of A. A single
  pass stopped at B, so `CTest` — the only concrete-implementor test
  — was silently dropped whenever A changed. The strategy now seeds
  each subsequent pass with the freshly-found impls, bounded by
  `transitiveDepth` as a sanity cap. Regression:
  `findsGrandchildImplementationThroughMultiLevelHierarchy`.
- **`PathToClassMapper` rejects path-traversal segments in diff
  input.** Git never emits `..` as a standalone segment in diff paths,
  so one appearing there is either a malformed ref or an attempt to
  confuse the mapper. Pre-fix, `../../etc/passwd.java` was handed to
  `tryMapToClass`, which produced an FQN starting with `..` and
  classified it as production — a shape the test dispatcher would then
  try to run. The segment check now routes those inputs to the
  unmapped bucket (at `debug` level, so a malicious diff cannot flood
  the build log), which trips the `UNMAPPED_FILE` situation and
  escalates to a full run on the engine's normal WARN path. A
  segment-aware check keeps legitimate filenames like `foo..bar.java`
  working.
- **`baseRef` validation is now JGit-native.** The v1.x check rejected
  the single shape `contains("../")` and accepted everything else —
  including control characters, leading slashes, and trailing
  `.lock`, all of which JGit's own refname rules already forbid. The
  builder now delegates to `Repository.isValidRefName` (canonical
  `refs/...` names), accepts 7-40 char hex SHAs directly, and accepts
  short rev-expressions like `HEAD~3` and `master^2`. The
  error message on rejection explicitly names the three accepted
  shapes so consumers have something actionable instead of "suspicious
  path traversal".

### Removed

- `PathToClassMapper#extractModule` and its three unit tests. The
  method had no in-tree caller and the `extractModuleFromDirs` branch
  under it duplicated logic that `tryMapToClass` already performs more
  carefully. Dead code by construction; removing it keeps the mapper's
  surface aligned with what the engine actually uses.

## [1.9.18]

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

### Fixed — post-v1.9.17 sanity-test pass

- The `Hint:` line on `affectedTest --explain` no longer fires on
  situations where an out-of-scope pattern could not have mattered.
  Running v1.9.17 through nine representative MR shapes on the
  security-service pilot (empty diff, api-test-only, performance-test-
  only, production Java only, markdown only, mixed api-test + prod,
  test-only, `git rm` markdown, gradle + prod) showed the hint firing
  on five of them — including a bare markdown-only MR where the diff
  contained nothing a source-tree matcher could have bitten. That
  5-of-9 false-positive rate trains reviewers to ignore the line,
  defeating its purpose.

  The hint now requires the situation to be `DISCOVERY_SUCCESS` or
  `DISCOVERY_EMPTY` (the only two branches where a correctly-configured
  out-of-scope pattern could have changed the outcome) in addition to
  the existing "diff non-empty AND out-of-scope bucket empty AND at
  least one out-of-scope dir configured" guard. `EMPTY_DIFF`,
  `ALL_FILES_IGNORED`, `ALL_FILES_OUT_OF_SCOPE`, and `UNMAPPED_FILE`
  all suppress the hint — none of them is a case where an operator
  could act on it.

- Lifecycle output for `SELECTED` dispatches now previews the first
  five FQNs per module with a "… and N more (use --info for full list)"
  tail on larger dispatches. Pre-v1.9.18 the task printed only the
  module summary at lifecycle level and demoted every FQN to info,
  leaving a reviewer scrolling the default CI log unable to see *which*
  tests were selected without either rerunning with `--info` or opening
  the JUnit report after the fact. The info-level per-FQN log is
  retained for `--info` users, and the bounded preview keeps total
  lifecycle output well under the 4 MiB GitHub Actions step cap that
  forced the demotion in the first place.

- Dispatch-side FQN validation was hoisted out of the per-module log
  loop so the "Running N affected test classes across M module(s)"
  header now reports the post-validation count and stays arithmetically
  consistent with the per-module previews underneath it. If any FQNs
  were skipped (an `isValidFqn` WARN was already logged) the header
  gains a ` (K malformed FQN skipped — see WARN above)` suffix so the
  mismatch between "discovered" and "dispatched" is visible at a
  glance rather than a puzzle only the WARN log can solve. Two latent
  side-effects fall out of the refactor:
  - A module whose entire discovered FQN set fails validation is now
    dropped from dispatch. Pre-v1.9.18 the task appended that module's
    bare `taskPath` with no `--tests` filter, which silently degraded
    into a full module test suite run — the exact safety posture
    `isValidFqn` was added to prevent. Operators relying on this
    accidental fallback should configure `runAllIfNoMatches = true` /
    `Action.FULL_SUITE` explicitly instead.
  - A dispatch where zero FQNs survive validation across ALL modules
    now throws `GradleException` instead of invoking Gradle with an
    empty task list (environment-dependent behaviour, ranging from
    "fail with No tasks specified" to "silently run the default task").
    The WARN logs above the failure name every rejected FQN, so the
    mis-discovery is diagnosable from the same console output.

### Fixed — post-v1.9.16 review batch

- `GitChangeDetector.uncommittedChanges` and `stagedChanges` now
  surface `DELETE` entries through the old path, the same way
  `committedChanges` has since v1.9.16. The v1.9.16 release patched
  the committed branch only, which was inert under its own
  committed-only defaults but re-opened the silent-skip hole the
  moment any adopter flipped `includeUncommitted` or `includeStaged`
  back on to iterate on a local `git rm`. All three diff sources
  now share one `collectPaths` helper so future bucketing changes
  can't drift between them.
- Regression tests added for the previously-untested error branches:
  `GitChangeDetector`'s shallow-clone `MissingObjectException` hint,
  `PathToClassMapper.isIgnored`'s `InvalidPathException` guard (NUL
  bytes and friends), and the config-time error message for
  malformed globs in `ignorePaths`. No code change — only coverage
  for branches whose existing behaviour the batch-1 fix relied on.

### Documentation

- `AffectedTestTask.getTransitiveDepth()` Javadoc now documents the
  actual default of `4` with rationale. The v1.9.16 pass updated
  `TransitiveStrategy` and `AffectedTestsExtension` but missed this
  third site — consumers reading `AffectedTestTask` Javadoc were
  still being told they needed to set `transitiveDepth = 4`
  explicitly; they do not.
- `OutOfScopeMatchers` class Javadoc replaces the Javadoc-safe
  `&#42;&#42;` escapes with literal `**` inside `{@code}` (the
  comment-terminator was never a risk — only `*/` is, and `{@code}`
  only cares about matching braces). The opening brace still has to
  be escaped because Javadoc closes `{@code}` at the first `}`; a
  one-line comment now explains why the escape is unavoidable.
- `OutOfScopeMatchers.hasGlobMetachar` now documents why only the
  opening forms `[` and `{` are treated as glob indicators — a
  literal directory name that happens to contain an unbalanced `]`
  or `}` is a far more likely reality than a user meaning "glob",
  and promoting it to glob compilation would convert
  typo-in-literal into a build-breaking glob syntax error.
- `AffectedTestTask.JAVA_FQN` now documents the intentional
  non-validation of Java reserved words. An FQN shaped like a
  keyword can't be produced by the discovery strategies (they read
  real filenames); the only way one reaches the filter is
  adversarially, and Gradle's downstream `--tests` matcher reports
  "no tests found" for it — never a compile failure or RCE.

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
