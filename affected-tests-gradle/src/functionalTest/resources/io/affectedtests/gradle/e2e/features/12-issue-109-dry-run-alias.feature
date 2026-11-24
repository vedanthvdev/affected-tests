Feature: --dry-run is Gradle's built-in flag, not an --explain alias (issue #109)
  Pre-fix, docs/DESIGN-v2.md documented `--dry-run` as a task-flag
  alias for `--explain`, but no such option exists on AffectedTestTask
  and none can: Gradle's CLI parser resolves the build-level `--dry-run`
  before any task option, so `affectedTest --dry-run` shows the
  task-graph preview and never runs the task. This feature pins that
  behaviour so the design doc cannot silently drift back to promising
  an alias, and confirms `--explain` remains the way to get the
  decision trace without executing tests.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: affectedTest --dry-run is Gradle's task-graph preview, not the decision trace
    Given the mode is ci
    And a canned diff that produces the EMPTY_DIFF situation
    When the affected-tests task runs with "--dry-run"
    Then the task succeeds
    And the output contains ":affectedTest SKIPPED"
    And the output does not contain "=== Affected Tests"

  Scenario: --explain still prints the decision trace without running tests
    Given the mode is ci
    And a canned diff that produces the EMPTY_DIFF situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is EMPTY_DIFF
    And the action is SKIPPED
