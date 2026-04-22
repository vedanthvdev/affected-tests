# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [1.10.0] — 2026-04-21

Closes Phase 1 and Phase 2 of the [v2 config redesign](docs/DESIGN-v2.md). The
new v2 decision vocabulary (`mode`, per-situation `onXxx`, `ignorePaths`,
`outOfScopeTestDirs`, `outOfScopeSourceDirs`, `--explain`, and the
`ActionSource` priority-ladder tags) landed additively across the v1.9.8 and
v1.9.9 patch releases. This minor bump completes the story by making every
operator-facing log line speak that same vocabulary and by warning existing
users off the legacy knobs ahead of the v2.0.0 removal.

### Added

- **Named outcome and situation on the summary log.** Every `affectedTest` run
  now prints `Affected Tests: <OUTCOME> (<SITUATION>) — …`, for example
  `Affected Tests: FULL_SUITE (UNMAPPED_FILE) — 3 changed file(s); running
  full suite (unmapped file changes forced full run)`. Makes CI dashboards
  greppable and uses the exact same outcome + situation names that `--explain`
  reports, so a dashboard line and a decision trace are readable against each
  other.

### Deprecated

These legacy knobs keep working unchanged and produce identical test
selections. The plugin now emits one `WARN`-level line per explicitly-set knob
pointing at the v2 replacement. Zero-config installs see no new warnings.

- `runAllIfNoMatches` → per-situation actions
  (`onEmptyDiff` / `onAllFilesIgnored` / `onAllFilesOutOfScope` /
  `onDiscoveryEmpty`).
- `runAllOnNonJavaChange` → `onUnmappedFile`.
- `excludePaths` → rename to `ignorePaths` (semantics identical; leaving
  `ignorePaths` unset picks up the broader v2 default list — markdown,
  `generated/`, licence / changelog, common images — instead of the previous
  markdown-only default).

The legacy knobs are scheduled for removal in **v2.0.0**. See the
*"Migrating from v1 config"* section in `README.md` for the worked example,
the before/after table, and the decision tree.

### Documentation

- README examples are all v2-native. The "Migrating from v1 config" section is
  the single source of truth for operators moving off legacy knobs.
- The `AffectedTestsExtension` javadoc sample mirrors the README canonical
  config, so IDE autocomplete no longer steers new users toward deprecated
  knobs.

## Earlier releases

The v2 configuration API and `--explain` flag landed in the v1.9.8 and v1.9.9
patch releases. The full v1.0 – v1.9.9 history, with auto-generated release
notes linked from every PR, is on
[GitHub Releases](https://github.com/vedanthvdev/affected-tests/releases).

[1.10.0]: https://github.com/vedanthvdev/affected-tests/releases/tag/v1.10.0
