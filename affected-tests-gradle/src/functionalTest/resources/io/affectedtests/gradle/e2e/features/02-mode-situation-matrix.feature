Feature: Mode × Situation default action matrix
  The action the plugin takes for each Situation depends on the
  effective Mode (LOCAL / CI / STRICT). This feature pins every cell of
  the default decision matrix as documented in README.md and
  AffectedTestsConfig#defaultFor. Changing any cell must be a
  deliberate decision that updates both the README table and this
  scenario outline — a drifted matrix is an invisible behaviour
  change for every consumer.

  The 21 cells (7 situations × 3 modes) break down to:
    * Situations producing clear SKIPPED in LOCAL (EMPTY_DIFF,
      ALL_FILES_IGNORED, ALL_FILES_OUT_OF_SCOPE, DISCOVERY_EMPTY)
      escalate in CI/STRICT because CI cannot afford to silently
      skip coverage on a merge-gate.
    * UNMAPPED_FILE and DISCOVERY_INCOMPLETE escalate in every mode
      because the engine cannot prove coverage; LOCAL only relaxes
      DISCOVERY_INCOMPLETE to SELECTED to keep dev iteration fast.
    * DISCOVERY_SUCCESS is always SELECTED — the plugin's whole
      reason to exist.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario Outline: <mode> default for EMPTY_DIFF is <action>
    Given the mode is <mode>
    And a canned diff that produces the EMPTY_DIFF situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is EMPTY_DIFF
    And the action is <action>
    And the action source is MODE_DEFAULT

    Examples:
      | mode   | action     |
      | local  | SKIPPED    |
      | ci     | SKIPPED    |
      | strict | FULL_SUITE |

  Scenario Outline: <mode> default for ALL_FILES_IGNORED is <action>
    Given the mode is <mode>
    And a canned diff that produces the ALL_FILES_IGNORED situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is ALL_FILES_IGNORED
    And the action is <action>
    And the action source is MODE_DEFAULT

    Examples:
      | mode   | action     |
      | local  | SKIPPED    |
      | ci     | SKIPPED    |
      | strict | FULL_SUITE |

  Scenario Outline: <mode> default for ALL_FILES_OUT_OF_SCOPE is <action>
    Given the mode is <mode>
    And a canned diff that produces the ALL_FILES_OUT_OF_SCOPE situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is ALL_FILES_OUT_OF_SCOPE
    And the action is <action>
    And the action source is MODE_DEFAULT

    Examples:
      | mode   | action  |
      | local  | SKIPPED |
      | ci     | SKIPPED |
      | strict | SKIPPED |

  Scenario Outline: <mode> default for UNMAPPED_FILE is <action>
    Given the mode is <mode>
    And a canned diff that produces the UNMAPPED_FILE situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is UNMAPPED_FILE
    And the action is <action>
    And the action source is MODE_DEFAULT

    Examples:
      | mode   | action     |
      | local  | FULL_SUITE |
      | ci     | FULL_SUITE |
      | strict | FULL_SUITE |

  Scenario Outline: <mode> default for DISCOVERY_EMPTY is <action>
    Given the mode is <mode>
    And a canned diff that produces the DISCOVERY_EMPTY situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_EMPTY
    And the action is <action>
    And the action source is MODE_DEFAULT

    Examples:
      | mode   | action     |
      | local  | SKIPPED    |
      | ci     | FULL_SUITE |
      | strict | FULL_SUITE |

  Scenario Outline: <mode> default for DISCOVERY_INCOMPLETE is <action>
    Given the mode is <mode>
    And a canned diff that produces the DISCOVERY_INCOMPLETE situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_INCOMPLETE
    And the action is <action>
    And the action source is MODE_DEFAULT

    Examples:
      | mode   | action     |
      | local  | SELECTED   |
      | ci     | FULL_SUITE |
      | strict | FULL_SUITE |

  Scenario Outline: <mode> default for DISCOVERY_SUCCESS is SELECTED (EXPLICIT)
    Given the mode is <mode>
    And a canned diff that produces the DISCOVERY_SUCCESS situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    # DISCOVERY_SUCCESS is the only cell sourced as EXPLICIT regardless
    # of mode — running discovered tests is the definitional purpose
    # of the plugin, not a defaulted choice.
    And the action source is EXPLICIT

    Examples:
      | mode   |
      | local  |
      | ci     |
      | strict |
