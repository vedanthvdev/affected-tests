Feature: Pilot scenarios — the situations security-service-like consumers hit in real MRs
  These scenarios replay the end-to-end contract the `affected-tests` plugin
  is meant to honour for every real-world consumer — a security-service-
  shaped micro-service that wires `io.github.vedanthvdev.affectedtests`
  into its MR pipeline and expects deterministic test-selection
  behaviour across EMPTY_DIFF, DISCOVERY_SUCCESS, UNMAPPED_FILE and the
  rest of the v2 situation taxonomy. Every scenario spawns a real
  Gradle TestKit build, so the assertions cover the same surface the
  consumer sees — task log lines, `--explain` trace, exit code — not
  just the decision engine in isolation.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: S01 — empty diff on top of baseline skips tests in LOCAL mode
    # LOCAL is the default mode when `CI` env isn't set. The EMPTY_DIFF
    # default there is SKIPPED so a developer running `./gradlew
    # affectedTest` with no committed changes pays zero wall-time.
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff contains no committed changes on top of baseline
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is EMPTY_DIFF
    And the action is SKIPPED
    And the action source is MODE_DEFAULT

  Scenario: S02 — production change with a matching naming-strategy test selects only that test
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    # DISCOVERY_SUCCESS sources as EXPLICIT by design — running the discovered
    # tests is the plugin's definitional purpose, not a mode-default.
    And the action source is EXPLICIT
    And the outcome is "SELECTED — 1 test class(es) will run"

  Scenario: S03 — test-only diff selects only the touched test class
    # Tests in the diff are themselves "affected tests" — no production-
    # mapping needed, the test file IS the signal. Guards against a
    # regression where test-file-only diffs accidentally fell through
    # to UNMAPPED_FILE.
    Given a production class "com.example.BarService" with its matching test "com.example.BarServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/test/java/com/example/BarServiceTest.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the outcome is "SELECTED — 1 test class(es) will run"

  Scenario: S04 — yaml-only diff escalates to FULL_SUITE under UNMAPPED_FILE
    # Non-Java files have no safe routing; LOCAL default for UNMAPPED_FILE
    # is FULL_SUITE so consumers never silently miss tests touched by a
    # config change. CI stays aligned with this default.
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And a file at "src/main/resources/application.yml" with content:
      """
      server:
        port: 8080
      """
    And the baseline commit is captured
    And the diff modifies "src/main/resources/application.yml"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is UNMAPPED_FILE
    And the action is FULL_SUITE
    And the action source is MODE_DEFAULT
    And the output contains "onUnmappedFile=FULL_SUITE"

  Scenario: S05 — diff fully inside ignorePaths short-circuits to SKIPPED in LOCAL
    # ignorePaths is the "paper-cut" knob: flaky test outputs, docs, etc.
    # A diff of only-ignored-files in LOCAL should not run anything.
    Given the affected-tests DSL contains:
      """
      ignorePaths = ['docs/**']
      """
    And a file at "docs/architecture.md" with content:
      """
      # Architecture
      """
    And the baseline commit is captured
    And the diff modifies "docs/architecture.md"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is ALL_FILES_IGNORED
    And the action is SKIPPED
    And the action source is MODE_DEFAULT

  Scenario: S06 — diff fully inside outOfScopeTestDirs short-circuits to SKIPPED in LOCAL
    # outOfScopeTestDirs is what security-service uses to quarantine
    # flaky api-test/** and performance-test/** suites out of normal
    # selection. A diff of only-out-of-scope files is by construction
    # untouched by the rest of the suite, so SKIPPED is correct.
    Given the affected-tests DSL contains:
      """
      outOfScopeTestDirs = ['api-test/**']
      """
    And a file at "api-test/com/example/SmokeApiTest.java" with content:
      """
      package com.example;
      public class SmokeApiTest {}
      """
    And the baseline commit is captured
    And the diff modifies "api-test/com/example/SmokeApiTest.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is ALL_FILES_OUT_OF_SCOPE
    And the action is SKIPPED
    And the action source is MODE_DEFAULT

  Scenario: S07 — CI mode escalates DISCOVERY_EMPTY to FULL_SUITE
    # A production change with no matching test exercises DISCOVERY_EMPTY.
    # CI's default for DISCOVERY_EMPTY is FULL_SUITE because trusting a
    # green result from an empty selection would under-test real MRs.
    # This is the exact safety net security-service relies on when its
    # pipeline sets `mode = 'ci'`.
    Given the affected-tests DSL contains:
      """
      mode = 'ci'
      """
    And a production class "com.example.BazService" with no matching test on disk
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/BazService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_EMPTY
    And the action is FULL_SUITE
    And the action source is MODE_DEFAULT

  Scenario: S08 — explicit onEmptyDiff override wins over mode default
    # Consumers who want LOCAL mode behaviour for most situations but
    # prefer FULL_SUITE on empty diffs must be able to pin that single
    # row. This is the "override wins" contract: action source flips
    # to EXPLICIT to make the cause traceable in --explain.
    Given the affected-tests DSL contains:
      """
      onEmptyDiff = 'full_suite'
      """
    And the baseline commit is captured
    And the diff contains no committed changes on top of baseline
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is EMPTY_DIFF
    And the action is FULL_SUITE
    And the action source is EXPLICIT

  Scenario: S09 — deleted production class lands in DISCOVERY_EMPTY, not a crash
    # S07 in the pilot surfaced a bug where deleting a file silently
    # crashed file scanning. The contract today: deletion is just
    # another diff entry — the engine routes it through DISCOVERY_EMPTY
    # (no production → no mapping → no tests) without blowing up.
    #
    # Mode pinned to LOCAL because DISCOVERY_EMPTY is one of the two
    # situations where LOCAL and CI defaults diverge (LOCAL=SKIPPED,
    # CI=FULL_SUITE). Leaving it on AUTO would make this scenario
    # pass locally but FULL_SUITE on GitHub Actions. The 21-row
    # matrix in 02-mode-situation-matrix.feature exercises the
    # CI/STRICT columns explicitly; this scenario only needs to
    # pin the LOCAL default-action behaviour.
    Given the mode is local
    And a production class "com.example.ObsoleteService" with no matching test on disk
    And the baseline commit is captured
    And the diff deletes the file "src/main/java/com/example/ObsoleteService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_EMPTY
    And the action is SKIPPED
    And the action source is MODE_DEFAULT

  Scenario: S10 — mixed yaml + production diff classifies as UNMAPPED_FILE and escalates
    # Order-of-evaluation scenario. UNMAPPED_FILE beats DISCOVERY_* so a
    # single yaml file alongside a hundred happy prod changes still
    # trips full-suite — the conservative choice consumers depend on.
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And a file at "src/main/resources/application.yml" with content:
      """
      server:
        port: 8080
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    And the diff modifies "src/main/resources/application.yml"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is UNMAPPED_FILE
    And the action is FULL_SUITE
