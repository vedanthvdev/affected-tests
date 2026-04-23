Feature: v1 → v2 DSL migration errors
  v2.0.0 removed three configuration knobs (runAllIfNoMatches,
  runAllOnNonJavaChange, excludePaths) and replaced them with
  situation-scoped onXxx settings. Consumers migrating from v1 must
  see targeted, actionable error messages at configuration time — not
  silent no-ops that leave their MR gate misconfigured.

  v2.1.0 added configuration-time validation for gradlewTimeoutSeconds
  so that a negative value fails the build before the task runs,
  instead of crashing mid-dispatch with a less obvious error.

  Each scenario here asserts both that configuration-time failure
  happens and that the error message names the specific v2
  replacement so an operator can make the migration in one step.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: legacy runAllIfNoMatches throws with v2 migration hint
    # v1 knob: runAllIfNoMatches = true
    # v2 equivalent: onEmptyDiff = 'full_suite' and/or
    #                onDiscoveryEmpty = 'full_suite' (or mode = 'ci')
    Given the affected-tests DSL contains:
      """
      runAllIfNoMatches = true
      """
    When any Gradle task is configured
    Then the task fails at configuration time
    And the output contains "runAllIfNoMatches was removed in v2.0.0"
    And the output contains "onEmptyDiff"
    And the output contains "onDiscoveryEmpty"
    # Pointer to the migration table saves operators a grep.
    And the output contains "CHANGELOG.md v2.0"

  Scenario: legacy runAllOnNonJavaChange throws with v2 migration hint
    # v1 knob: runAllOnNonJavaChange = true
    # v2 equivalent: onUnmappedFile = 'full_suite' (or mode = 'ci')
    Given the affected-tests DSL contains:
      """
      runAllOnNonJavaChange = true
      """
    When any Gradle task is configured
    Then the task fails at configuration time
    And the output contains "runAllOnNonJavaChange was removed in v2.0.0"
    And the output contains "onUnmappedFile"
    And the output contains "CHANGELOG.md v2.0"

  Scenario: legacy excludePaths throws with v2 migration hint
    # v1 knob: excludePaths = ['docs/**']
    # v2 equivalents: ignorePaths (quiet drop) OR outOfScopeTestDirs
    # (explicitly-quarantined test dirs). The error must mention both
    # so operators pick the right one for their case.
    Given the affected-tests DSL contains:
      """
      excludePaths = ['docs/**']
      """
    When any Gradle task is configured
    Then the task fails at configuration time
    And the output contains "excludePaths was removed in v2.0.0"
    And the output contains "ignorePaths"
    And the output contains "outOfScopeTestDirs"

  Scenario: negative gradlewTimeoutSeconds fails at configuration time (v2.1.0 polish)
    # Before v2.1.0 this would crash mid-dispatch with an IllegalStateException
    # from ProcessBuilder. v2.1.0 adds afterEvaluate validation so the
    # error surfaces before the task runs.
    Given the affected-tests DSL contains:
      """
      gradlewTimeoutSeconds = -1L
      """
    When any Gradle task is configured
    Then the task fails at configuration time
    And the output contains "gradlewTimeoutSeconds must be >= 0"
    # The message must explicitly call out that 0 is valid, so operators
    # who want "no timeout" don't misread the message as "must be > 0".
    And the output contains "0 disables the timeout"
