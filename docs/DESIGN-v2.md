# Affected Tests v2 — Design Phase

> Design document for evolving the `affected-tests` Gradle plugin into a more
> configurable, predictable tool that covers every real-world scenario a team
> might throw at it. This is a **design-only** document — no code changes yet.

---

## 0. TL;DR

The current plugin reduces every MR to one of four outcomes (`SELECTED`,
`FULL_SUITE`, `SKIPPED`, `FAILED`), but it picks between them using only two
blunt boolean knobs (`runAllIfNoMatches`, `runAllOnNonJavaChange`) that
conflate several genuinely-different situations. Real teams (multi-module
Spring services, projects with Cucumber/Gatling source sets, projects with
docs-only MRs) can't get the behaviour they want without either accepting
wasted full-suite runs or accepting silent-skip risk.

v2 proposes:

1. **Six explicit "situation" branches** instead of two booleans, each with
   its own `SELECTED | FULL_SUITE | SKIPPED` action knob. Every outcome is
   named, logged, and independently configurable.
2. **Three kinds of path bucket** (`ignorePaths`, `outOfScopeTestDirs`,
   `outOfScopeSourceDirs`) so users can distinguish "drop this from
   analysis" from "this test source set is not our business" — a distinction
   `excludePaths` can't express today.
3. **Profile-based defaults** (`mode = auto | local | ci | strict`) with CI
   auto-detection so the right defaults appear in the right environment.
4. **Richer ignore defaults** out of the box (`*.md`, `CODEOWNERS`,
   `.editorconfig`, `bin/**`, …) plus `transitiveDepth = 4` default so the
   common Spring-DI case is covered without explicit config.
5. **An `--explain` task flag** that prints the decision tree for the
   current diff, so operators don't have to crank debug logs to understand
   why a particular MR chose the outcome it did.

Strictly back-compatible in Phase 1: every existing config keeps working.

---

## 1. Current engine — what it does today

For each MR diff, the engine produces exactly one of these outcomes. Every
entry in this table is something the code actually does, verified by
reading `AffectedTestsEngine.java` + `PathToClassMapper.java` +
`AffectedTestTask.java`.

| Input situation | Trigger | Current outcome | Controlled by |
|---|---|---|---|
| Git diff is empty | `changedFiles.isEmpty()` | `FULL_SUITE` if `runAllIfNoMatches=true`, else `SKIPPED` | `runAllIfNoMatches` |
| Diff contains a `.java` file outside `sourceDirs`/`testDirs` | `unmappedChangedFiles` non-empty | `FULL_SUITE` if `runAllOnNonJavaChange=true`, else discovery proceeds *and* safety is weakened | `runAllOnNonJavaChange` |
| Diff contains non-`.java` file not matching `excludePaths` | Same bucket as above | `FULL_SUITE` if `runAllOnNonJavaChange=true`, else discovery proceeds | `runAllOnNonJavaChange` |
| Diff contains only production + test `.java` files under configured dirs | Mapping succeeds | Run discovery pipeline | n/a |
| All changed files matched `excludePaths` | `changedFiles` is non-empty, mapping yields no production/test/unmapped | Same as "empty diff" — engine proceeds to discovery-empty path | `runAllIfNoMatches` |
| Discovery strategies return zero tests | `allTestsToRun.isEmpty()` | `FULL_SUITE` if `runAllIfNoMatches=true`, else `SKIPPED` | `runAllIfNoMatches` |
| Discovery returns N tests | | `SELECTED` (dispatches `:mod:test --tests` per module) | n/a |
| `baseRef` can't be resolved | `resolveBaseRef` returns null | `FAILED` (hard error, clear message) | — |
| Not a git work tree | `FileRepositoryBuilder` throws | `FAILED` | — |
| JGit I/O error | `IOException` | `FAILED` | — |

The key observation: **`runAllIfNoMatches` is a single boolean that fires
in three different situations** (empty diff, all-excluded diff,
discovery-empty after mapping). Users who want different actions in
different situations can't express that.

### Current defaults (reproduced from `AffectedTestsConfig.Builder` and `AffectedTestsPlugin`)

| Property | Default |
|---|---|
| `baseRef` | `"origin/master"` (overridable via `-PaffectedTestsBaseRef`) |
| `includeUncommitted` | `true` |
| `includeStaged` | `true` |
| `runAllIfNoMatches` | `false` |
| `runAllOnNonJavaChange` | `true` |
| `strategies` | `[naming, usage, impl, transitive]` |
| `transitiveDepth` | `2` (range 0–5) |
| `testSuffixes` | `[Test, IT, ITTest, IntegrationTest]` |
| `sourceDirs` | `[src/main/java]` |
| `testDirs` | `[src/test/java]` |
| `excludePaths` | `[**/generated/**]` |
| `includeImplementationTests` | `true` |
| `implementationNaming` | `[Impl]` |

---

## 2. Gaps identified

### Gap 1 — `runAllIfNoMatches` conflates three situations

`runAllIfNoMatches=true` escalates on **all** of:

- **Empty changeset** (user committed nothing, or only deletions that got
  dropped from the diff)
- **Everything excluded** (user only changed files that match
  `excludePaths` — e.g. docs-only MR)
- **Discovery returned empty** (the diff had real production changes but
  none of the four strategies found any test)

These three have very different risk profiles:

- Empty changeset: escalating to a full suite is never useful — the user
  literally changed nothing.
- Everything excluded: the user explicitly marked these paths as
  irrelevant; escalating contradicts that intent.
- Discovery empty: the only legitimate safety-net case. A production class
  did change, the plugin just didn't find a test for it, so running
  everything is the conservative choice.

Today users must pick one setting that applies to all three. In practice
they set `true` for the safety net and accept wasted full runs on
docs-only MRs, or set `false` and accept silent-skip risk on genuine
discovery gaps.

### Gap 2 — `runAllOnNonJavaChange` is all-or-nothing

Any file the mapper can't classify (non-`.java`, or a `.java` outside
`sourceDirs`/`testDirs`) triggers a full run. That's usually right
(`application.yml`, `build.gradle`, Helm chart, Liquibase changelog) but
overzealous for:

- `README.md`, `CHANGELOG.md`, docs
- `CODEOWNERS`, `.gitignore`, `.editorconfig`
- `LICENSE`, `NOTICE`
- GitHub/GitLab workflow YAML that only affects CI metadata
- IDE config (`.vscode/**`, `.idea/**`)
- IDE-generated `bin/**` directories with `.class` files

Users can technically list all of these in `excludePaths`, but that's:

- Tedious boilerplate every team has to re-invent.
- Easy to get wrong (one missing glob and a docs-only MR runs the full
  suite for an hour).
- Indistinguishable in the logs from "actual" excluded paths.

### Gap 3 — No way to model "this test source set is not our business"

In a project like Modulr's `security-service`, there are three test source
sets:

- `application/src/test/java/**` — unit + integration (plugin should
  manage this).
- `api-test/src/test/java/**` — Cucumber step definitions (runs under a
  separate `apiTest` Gradle task in a separate CI job; plugin should NOT
  touch).
- `performance-test/src/test/java/**` — Gatling (same story).

Today `testDirs = ["src/test/java"]` scans recursively and picks up
**all three** as equivalent. Consequences:

- Changing a Cucumber step file → directly-changed-test rule fires →
  plugin dispatches `:api-test:test --tests …`, which is not what CI
  expects.
- A production class change → `usage` strategy parses every test file
  including Cucumber steps → any Cucumber step that imports the class
  gets added to the dispatch → same wrong-task problem.

Narrowing `testDirs` to only `application/src/test/java` fixes dispatch,
but then changes in `api-test/**` become "unmapped `.java` files outside
configured source/test dirs" → `runAllOnNonJavaChange=true` fires → full
suite runs. Still wrong.

Adding `api-test/**` to `excludePaths` fixes that, but then an api-test-
only MR falls under "all excluded" → `runAllIfNoMatches` decides →
conflation problem from Gap 1.

What's missing: a **first-class concept of "test source sets this plugin
does not manage"**. Changes inside such a dir should be dropped from
mapping, excluded from the ProjectIndex (so discovery strategies never
look at them), and — if they're the *only* changes — route to a dedicated
outcome the user can set independently.

### Gap 4 — `excludePaths` is semantically overloaded

It currently means "drop this file from the diff before anything else
sees it". That single behaviour gets used for at least three different
user intents:

1. "This file can never affect production behaviour" (docs, licensing).
2. "This file is a build artifact I happened to commit" (`bin/**`,
   `generated/**`).
3. "This path belongs to a different plugin/pipeline" (`api-test/**`
   when that runs under a separate CI job).

Treating these identically means users can't route them to different
outcomes. Split-by-intent is more expressive.

### Gap 5 — Defaults assume local dev

`includeUncommitted = true` / `includeStaged = true` is correct for local
`./gradlew affectedTest` runs where devs want their working tree tested.
In CI those two are almost always `false` (CI works on a clean checkout,
and untracked artefacts like IDE `bin/**` cause false escalations). Every
CI adopter has to remember to flip them. A CI-detecting profile would
eliminate that footgun.

### Gap 6 — `transitiveDepth = 2` is short for DI-heavy code

Spring `@Service` → `@Component` → repository chains routinely hit depth
3–4. `2` is a safe academic default but practically misses tests. Modulr
rolls out with `transitiveDepth = 4`; every user in a similar stack
probably does the same. Good candidate for a raised default.

### Gap 7 — No explainability surface

Everything the engine logs at `info` level is a summary. The per-file
classification ("this file went to unmapped because …"), the per-strategy
hits, and the per-test reason are all at `debug`. Users who want to
understand a surprising outcome have to:

1. Turn on `--debug` globally (noisy — JGit, JavaParser, etc. all log
   too).
2. Re-run the task.
3. Read a wall of text.

A dedicated `./gradlew affectedTest --explain` (or a `dryRun` flag that
doesn't execute tests and prints a structured report) would be a
significantly better onboarding surface.

### Gap 8 — Hidden run-all on non-Java *and* unmapped `.java` files

Reading the code: `runAllOnNonJavaChange=true` escalates on both
**non-`.java` files** AND **`.java` files outside `sourceDirs`/`testDirs`**.
The current docstring names both cases but the property name only hints
at the former. Users reading the name assume it covers YAML/Gradle etc.
Consider renaming (`runAllOnUnmappedFile`?) or splitting.

---

## 3. Proposed design — v2

### 3.1 Canonical outcomes

Every engine run produces exactly one outcome, named and logged:

```
┌─────────────┬─────────────────────────────────────────────────────────┐
│ SELECTED    │ Run the discovered subset (N tests across M modules)   │
│ FULL_SUITE  │ Run everything (with a named escalation reason)         │
│ SKIPPED     │ Run nothing (with a named reason)                       │
│ FAILED      │ Reserved for hard errors (git missing, base ref bad)    │
└─────────────┴─────────────────────────────────────────────────────────┘
```

### 3.2 Situations → configurable actions

Six mutually-exclusive "situations" the engine can find itself in at the
end of the mapping phase. Each has an action setting (`SELECTED` /
`FULL_SUITE` / `SKIPPED`) that users can override:

| Situation | Meaning | Default action | Rationale |
|---|---|---|---|
| `onEmptyDiff` | `git diff` returned zero files (nothing at all, or only deletions we dropped) | `SKIPPED` | Nothing changed; running tests is pointless |
| `onAllFilesIgnored` | Every changed file landed in `ignorePaths` (docs, license, `.editorconfig`, etc.) | `SKIPPED` | User explicitly said "don't analyse these" |
| `onAllFilesOutOfScope` | Every changed file landed in `outOfScopeTestDirs` or `outOfScopeSourceDirs` | `SKIPPED` | A different plugin/pipeline owns these |
| `onUnmappedFile` | At least one changed file is non-Java or outside configured source/test dirs (and not ignored / out-of-scope) | `FULL_SUITE` | Unknown territory; safest to run everything |
| `onDiscoveryEmpty` | Mapping found production changes, but discovery returned zero tests | `FULL_SUITE` | Real safety net — mapping gap or incomplete test coverage |
| `onDiscoverySuccess` | Discovery returned ≥ 1 test | `SELECTED` | The happy path |

**Key properties of this design:**

- Every situation has a distinct name, so logs say exactly what happened:
  `Affected Tests: SKIPPED (onAllFilesOutOfScope — 3 file(s) in api-test/,
  performance-test/)`.
- Users can mix-and-match freely: Modulr's ask ("skip on api-test-only,
  full suite on discovery failure") is literally the defaults in the
  table above.
- Situations are computed in a fixed priority order so there's no
  ambiguity when a diff has files in multiple buckets:
  1. Any unignored, in-scope production/test file? → route through
     discovery → either `onDiscoverySuccess` or `onDiscoveryEmpty`.
  2. Any unmapped file (non-Java / outside dirs) not matched by
     ignore/out-of-scope rules? → `onUnmappedFile`.
  3. Changed files, all of them in ignore rules? → `onAllFilesIgnored`.
  4. Changed files, all of them in out-of-scope rules? →
     `onAllFilesOutOfScope`.
  5. Mixed ignored + out-of-scope? → `onAllFilesIgnored` wins (it's the
     safer interpretation; user can override per situation anyway).
  6. No changed files? → `onEmptyDiff`.

### 3.3 Path categories

Three distinct user-facing buckets, each with its own glob list:

| Category | Behaviour | Example defaults |
|---|---|---|
| `ignorePaths` | Dropped silently before any mapping. Never triggers `onUnmappedFile`. Contributes to `onAllFilesIgnored` when the entire diff is in this bucket. | `**/generated/**`, `**/*.md`, `**/LICENSE`, `**/LICENCE`, `**/NOTICE`, `**/CODEOWNERS`, `**/.gitignore`, `**/.gitattributes`, `**/.editorconfig`, `**/bin/**`, `**/.vscode/**`, `**/.idea/**` |
| `outOfScopeTestDirs` | Test source directories this plugin does not manage. Files inside are dropped from mapping, **and** the discovery ProjectIndex explicitly excludes them so `usage`/`impl`/`transitive` never consider them. | `[]` (users opt in per-repo, e.g. `[api-test/src/test/java, performance-test/src/test/java]`) |
| `outOfScopeSourceDirs` | Production source dirs the plugin should ignore. Same treatment as above. | `[]` (rare, but symmetric; useful for generated-code modules) |

`excludePaths` remains supported as a deprecated alias for `ignorePaths`.

**Why a separate `outOfScope*` bucket rather than overloading
`ignorePaths`?** Because the engine needs to know **at ProjectIndex build
time** that these test dirs should not be scanned at all. `ignorePaths`
applies only at diff-mapping time — it still lets the scanner walk
`api-test/src/test/java` and pull every Cucumber step into the AST
cache, which then pollutes the `usage` strategy's dispatch targets.
`outOfScopeTestDirs` takes the dir out of scanner + index + dispatch in
one move.

### 3.4 Profile modes

One top-level `mode` property selects a profile. Individual properties
still override.

| Mode | When | Key overrides |
|---|---|---|
| `local` | Default when no CI env var detected | `includeUncommitted=true`, `includeStaged=true`, `onDiscoveryEmpty=SKIPPED`, `onUnmappedFile=FULL_SUITE` |
| `ci` | Default when `CI`, `GITHUB_ACTIONS`, `GITLAB_CI`, `JENKINS_HOME`, `BUILDKITE`, `CIRCLECI`, or `TEAMCITY_VERSION` is set | `includeUncommitted=false`, `includeStaged=false`, `onDiscoveryEmpty=FULL_SUITE`, `onUnmappedFile=FULL_SUITE` |
| `strict` | Opt-in for regulated/sensitive codebases | `mode=ci` + also `onAllFilesIgnored=FULL_SUITE`, `onAllFilesOutOfScope=FULL_SUITE` (never skip — always run everything when unsure) |
| `auto` (default) | Picks `ci` or `local` based on env detection | — |

Rationale: right now every CI adopter re-invents `includeUncommitted=false, includeStaged=false` in their `build.gradle`. Making the right
defaults automatic removes a class of footguns.

### 3.5 Revised defaults

Changes from current plugin:

| Property | Old default | New default | Why |
|---|---|---|---|
| `transitiveDepth` | `2` | `4` | Matches common Spring DI chain depths |
| `ignorePaths` (was `excludePaths`) | `[**/generated/**]` | Extended list (see 3.3) | Common-sense docs/meta files should never escalate |
| `mode` | n/a (new) | `auto` | Auto-detect CI for correct defaults |
| `includeUncommitted` / `includeStaged` | `true` | Determined by `mode` | No more "CI accidentally tests `bin/**`" |
| `implementationNaming` | `[Impl]` | `[Impl, Default]` | `Default` is the second most common convention (used by Spring's own utility classes) |
| `testSuffixes` | `[Test, IT, ITTest, IntegrationTest]` | Unchanged | Already covers the common cases |

Legacy `runAllIfNoMatches` / `runAllOnNonJavaChange` booleans:
- If set, translated into the new situation config at build time, with a
  deprecation warning in Phase 2.
- Translation rules:
  - `runAllIfNoMatches=true` → sets `onEmptyDiff=FULL_SUITE`,
    `onDiscoveryEmpty=FULL_SUITE`, `onAllFilesIgnored=FULL_SUITE`,
    `onAllFilesOutOfScope=FULL_SUITE` (preserves today's semantics).
  - `runAllOnNonJavaChange=true` → sets `onUnmappedFile=FULL_SUITE`
    (same as today).
  - Same with `false` values.

### 3.6 Explain / dry-run

New CLI option on the `affectedTest` task:

```bash
./gradlew affectedTest --explain   # prints decision tree, does not execute tests
./gradlew affectedTest --dry-run   # alias
```

Output format (example):

```
=== Affected Tests: decision trace ===
Base ref       : origin/master (resolved to abc123d)
Mode           : ci (auto-detected via GITLAB_CI)
Changed files  : 12

Bucket routing:
  ignored (matched ignorePaths)          : 3 file(s)
    - README.md                          → **/*.md
    - CODEOWNERS                         → **/CODEOWNERS
    - .editorconfig                      → **/.editorconfig
  out-of-scope (outOfScopeTestDirs)      : 2 file(s)
    - api-test/src/test/java/…/FooSteps.java
    - api-test/src/test/java/…/BarSteps.java
  production (mapped to sourceDirs)      : 4 file(s)
    - application/src/main/java/…/PaymentService.java  → com.modulr.PaymentService
    - …
  test       (mapped to testDirs)        : 2 file(s)
    - application/src/test/java/…/FeeCalculatorTest.java
    - …
  unmapped                               : 1 file(s)
    - helm/common-values.yaml
    - (will trigger onUnmappedFile action)

Situation          : onUnmappedFile
Configured action  : FULL_SUITE
Outcome            : FULL_SUITE (runAllOnUnmappedFile — helm/common-values.yaml)

Would execute (dry-run): ./gradlew test -x compileJava
```

### 3.7 Migration path — worked example for Modulr

Today's Modulr `security-service` config:

```groovy
affectedTests {
    includeUncommitted = false
    includeStaged = false
    runAllIfNoMatches = true
    transitiveDepth = 4
    excludePaths = ['**/generated/**', '**/*.md']
}
```

After v2, with auto-detection + new defaults, the equivalent becomes:

```groovy
affectedTests {
    outOfScopeTestDirs = [
        'api-test/src/test/java',
        'performance-test/src/test/java',
    ]
    onAllFilesIgnored      = 'SKIPPED'       // docs-only MR → skip
    onAllFilesOutOfScope   = 'SKIPPED'       // api-test-only MR → skip
    // onDiscoveryEmpty = 'FULL_SUITE' is the default — safety net preserved
    // onUnmappedFile   = 'FULL_SUITE' is the default — full suite on Helm/Gradle changes
    // transitiveDepth  = 4 is the new default — no need to set
    // includeUncommitted/includeStaged = false automatically because mode=ci
    // ignorePaths now includes *.md by default — no need to set
}
```

All three user goals satisfied:
1. api-test-only MR → `SKIPPED`, zero unit/integration runs.
2. Production MR with discovery gap → `FULL_SUITE`, safety net preserved.
3. Mixed (Gradle + production) MR → `FULL_SUITE` via `onUnmappedFile`.

Five lines of config vs today's five — but now expressing the intent
directly instead of working around the tool.

---

## 4. Implementation phases

### Phase 1 — Additive, back-compat (minor version, e.g. v1.10.0)

- Add `mode` property (`auto` default, detects CI env vars).
- Add new situation-action properties (`onEmptyDiff`, `onAllFilesIgnored`,
  `onAllFilesOutOfScope`, `onUnmappedFile`, `onDiscoveryEmpty`). Each is a
  typed enum property (`SELECTED | FULL_SUITE | SKIPPED`).
- Add `outOfScopeTestDirs` and `outOfScopeSourceDirs` list properties.
  Plumb them into `PathToClassMapper` (extra bucket) and `ProjectIndex`
  (exclude when collecting).
- Rename `excludePaths` → `ignorePaths` (keep `excludePaths` as alias).
- Extend default `ignorePaths` with the richer list in §3.3.
- Bump `transitiveDepth` default to `4`.
- Add `Default` to default `implementationNaming`.
- Legacy `runAllIfNoMatches` / `runAllOnNonJavaChange` booleans: if set,
  they translate into the new situation config. No deprecation warning
  yet.
- Add `--explain` / `--dry-run` task option. Implementation: new task
  property that short-circuits `executeTests` and prints the structured
  trace shown in §3.6.
- New `EscalationReason` enum values:
  `SKIPPED_EMPTY_DIFF`, `SKIPPED_ALL_IGNORED`, `SKIPPED_ALL_OUT_OF_SCOPE`,
  `FULL_SUITE_UNMAPPED`, `FULL_SUITE_DISCOVERY_EMPTY`. Extend
  `describeEscalation` accordingly.
- Logging: every run prints a one-liner naming the outcome + situation,
  e.g. `Affected Tests: SELECTED — 12 tests across 3 modules` or
  `Affected Tests: FULL_SUITE (onUnmappedFile — helm/common-values.yaml)`.

### Phase 2 — Deprecations (next minor)

- Legacy booleans emit a deprecation warning when set, pointing to the
  new config.
- Docs flip all examples to new config.
- README adds a migration section.

### Phase 3 — Breaking (next major, e.g. v2.0.0)

- Remove legacy `runAllIfNoMatches` / `runAllOnNonJavaChange` / `excludePaths`.
- Rename `runAllOnNonJavaChange`'s successor if we choose a better name
  (e.g. `onUnmappedFile` — already the Phase 1 name, so this collapses
  naturally).

---

## 5. Testing strategy for the design

The existing test suite (`AffectedTestsEngineTest`, `PathToClassMapperTest`,
per-strategy tests) already covers most of the mechanics. Phase 1 must
add:

- **Situation routing tests**: one test per situation × action combo.
  Should be parameterised so it's obvious when a new situation is added.
- **Out-of-scope dir tests**: verify `outOfScopeTestDirs` removes paths
  from `ProjectIndex.testFiles`, `testFqnToPath`, and from
  `usage`/`impl`/`transitive` strategy inputs.
- **Profile tests**: given a set of env vars, assert the resolved config
  matches the expected profile.
- **Back-compat tests**: set `runAllIfNoMatches=true` and verify all four
  corresponding situations escalate.
- **Explain output snapshot test**: fix the exact format so CI logs stay
  parseable.

---

## 6. Open questions

- **Should `onDiscoveryEmpty` default to `FULL_SUITE` or `SELECTED` with
  warning?** The conservative default is `FULL_SUITE`, but an alternative
  is to run nothing + emit a loud WARNING that CI can grep for. We should
  probably stay with `FULL_SUITE` for safety, but worth documenting the
  rationale.
- **Should we add a `onDeletionsOnly` situation?** Today deletions are
  dropped from the diff entirely (to avoid Gradle "No tests found"
  errors), so a pure-deletion MR looks like `onEmptyDiff`. Separating the
  two is a small UX win. Low priority for Phase 1.
- **Profile precedence**: should env-var detection ever override an
  explicitly-set `mode`? Proposed: no — explicit `mode` always wins.
  Auto-detection only applies when `mode = auto` (the default).
- **Should `outOfScope*` affect `runAllOnNonJavaChange` equivalence for
  non-Java files inside those dirs?** E.g. `api-test/helmfile.yaml`. Yes
  — treating the whole subtree as out-of-scope is the most intuitive
  semantic. Implementation: check the path against `outOfScope*` globs
  before the non-Java escalation check.

---

## 7. Non-goals for v2

- **Per-module config overrides.** Useful long-term (e.g. "this one
  flaky module always runs full") but doubles the config surface and
  doesn't block any current user's use case. Defer to v3.
- **Replacing JGit with native `git`.** Portability win, stability risk.
  Not on the critical path.
- **Changing the dispatch strategy** (how tests are routed to `:mod:test
  --tests FQN`). Works well today; out of scope.
- **Kotlin source support.** Changes the parser story — tracked as a
  separate concern.

---

## 8. Appendix — Decision tree (v2)

```
git diff baseRef..HEAD (+ optional uncommitted/staged per mode)
  │
  ▼
changedFiles.isEmpty() ? ─── yes ──▶ apply onEmptyDiff action
  │
  no
  │
  ▼
for each changed file:
  matches ignorePaths?       ─── yes ──▶ bucket=IGNORED
  matches outOfScopeTestDirs? ── yes ──▶ bucket=OUT_OF_SCOPE
  matches outOfScopeSourceDirs? yes ──▶ bucket=OUT_OF_SCOPE
  is .java under testDirs?   ─── yes ──▶ bucket=TEST
  is .java under sourceDirs? ─── yes ──▶ bucket=PRODUCTION
                                  else ─▶ bucket=UNMAPPED
  │
  ▼
any PRODUCTION or TEST files? ── no ──▶ pick situation:
  │                                      all IGNORED → onAllFilesIgnored
  │                                      all OUT_OF_SCOPE → onAllFilesOutOfScope
  │                                      any UNMAPPED → onUnmappedFile
  │                                      mixed → onAllFilesIgnored wins
  yes
  │
  ▼
any UNMAPPED files? ─── yes ──▶ apply onUnmappedFile action
  │
  no
  │
  ▼
run discovery strategies (naming, usage, impl, transitive)
  │
  ▼
discovery result empty? ─── yes ──▶ apply onDiscoveryEmpty action
  │
  no
  │
  ▼
apply onDiscoverySuccess action (default: SELECTED)
```

Every leaf in that tree has a named situation and a user-overridable
action. No decisions are hidden in boolean combinations.
