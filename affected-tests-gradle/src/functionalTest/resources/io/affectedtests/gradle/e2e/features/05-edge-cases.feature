Feature: edge cases — rename detection, working-tree visibility, STRICT mode
  These scenarios cover the narrow edges where the engine's diff
  semantics matter most: what git diff modes we respect, what file
  operations we treat as diffable, and what STRICT mode forces the
  fail-closed behaviour we promise consumers who opt in.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: renaming a production class still selects its original test
    # A rename shows up in the diff as `delete old path + add new
    # path`. Crucially the delete half still maps to the test that
    # matched the old class name — so the engine keeps selecting
    # LegacyNameTest even though LegacyName.java no longer exists.
    # That's the protective behaviour we want: renames don't silently
    # orphan tests from the selected set.
    Given a production class "com.example.LegacyName" with its matching test "com.example.LegacyNameTest"
    And the baseline commit is captured
    And the diff renames "src/main/java/com/example/LegacyName.java" to "src/main/java/com/example/NewName.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the outcome is "SELECTED — 1 test class(es) will run"

  Scenario: uncommitted working-tree changes are invisible by default
    # The default `includeUncommitted=false` is load-bearing for CI/dev
    # parity: running `./gradlew affectedTest` locally must pick the
    # same tests CI will pick on the same HEAD, regardless of what's
    # sitting uncommitted in the working tree.
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the working tree has an uncommitted modification to "src/main/java/com/example/FooService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    # No committed changes on top of baseline, uncommitted edit ignored
    # by default → EMPTY_DIFF.
    And the situation is EMPTY_DIFF

  Scenario: includeUncommitted=true surfaces working-tree changes into the diff
    Given the affected-tests DSL contains:
      """
      includeUncommitted = true
      """
    And a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the working tree has an uncommitted modification to "src/main/java/com/example/FooService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the outcome is "SELECTED — 1 test class(es) will run"

  Scenario: includeStaged=true surfaces staged-but-uncommitted changes
    # Staged is the useful middle tier — a dev has run `git add` but
    # not yet committed. Enables iterative "what tests will run when
    # I commit?" exploration without requiring an actual commit.
    Given the affected-tests DSL contains:
      """
      includeStaged = true
      """
    And a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the working tree has a staged modification to "src/main/java/com/example/FooService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the outcome is "SELECTED — 1 test class(es) will run"

  Scenario: STRICT mode runs full suite even on EMPTY_DIFF
    # STRICT is the "paranoid release gate" mode: when you care more
    # about coverage than speed. An empty diff on STRICT still runs
    # everything — the fail-closed counterpart to LOCAL's fast path.
    Given the mode is strict
    And the baseline commit is captured
    And the diff contains no committed changes on top of baseline
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is EMPTY_DIFF
    And the action is FULL_SUITE
    And the action source is MODE_DEFAULT
