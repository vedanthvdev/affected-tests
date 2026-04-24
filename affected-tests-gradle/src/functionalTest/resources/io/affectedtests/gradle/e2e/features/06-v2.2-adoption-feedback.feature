Feature: v2.2 — adoption-feedback fixes surfaced on security-service
  These scenarios pin the five user-facing fixes raised by the
  security-service adoption in CAR-5190. Each scenario triggers the
  exact shape the adopter ran into so a future regression on any of
  the five resurfaces as a loud Cucumber failure rather than as
  silent drift in operator-experience quality.

  Background:
    Given a freshly initialised project with a committed baseline

  # ------------------------------------------------------------------
  # Bug A — `--explain` must not force a compile
  #
  # On a security-service-shaped repo the unconditional `testClasses`
  # dependency turned a 3-second diagnostic into a 4-minute build, so
  # operators stopped using --explain. The fix wires the dependency
  # through a {@code Callable} that short-circuits when `--explain` is
  # set, pruning compile from the task graph entirely. We prove it
  # with a real dispatch (no `-x compileJava` CLI escape) and assert
  # that `:compileJava` is absent from the executed-task list.
  # ------------------------------------------------------------------
  Scenario: --explain runs without scheduling compileJava
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    And the Gradle command-line argument "--explain"
    When the affected-tests task runs with live task dependencies
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the executed task list does not include ":compileJava"
    And the executed task list does not include ":compileTestJava"

  # Paired negative case (compile dependency still required on
  # non-explain dispatch) is covered by the unit test on the
  # Callable return shape in AffectedTestsPluginTest — asserting
  # "compile did run" via Cucumber would force a full nested
  # ./gradlew dispatch in the test project, which is flaky in CI.

  Scenario: --explain prunes compileJava on every subproject, not just the root
    # The Callable is registered per-java-plugin via `allprojects { ... }`,
    # so a regression that pinned it to the root (or forgot to iterate)
    # would pass the single-module scenario above but force compiles on
    # every subproject. A security-service-shaped repo with 10 modules
    # would take the 4-minute hit the fix was raised to prevent — this
    # scenario catches that specific drift by running the same check on
    # a two-subproject project graph.
    Given the project is multi-module with sub-projects "api" and "application"
    And a file at "application/src/main/java/com/example/UserService.java" with content:
      """
      package com.example;
      public class UserService {}
      """
    And a file at "application/src/test/java/com/example/UserServiceTest.java" with content:
      """
      package com.example;
      public class UserServiceTest {}
      """
    And the baseline commit is captured
    And the diff modifies "application/src/main/java/com/example/UserService.java"
    And the Gradle command-line argument "--explain"
    When the affected-tests task runs with live task dependencies
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the executed task list does not include ":compileJava"
    And the executed task list does not include ":compileTestJava"
    And the executed task list does not include ":api:compileJava"
    And the executed task list does not include ":api:compileTestJava"
    And the executed task list does not include ":application:compileJava"
    And the executed task list does not include ":application:compileTestJava"

  # ------------------------------------------------------------------
  # Bug B — situation-specific hints
  #
  # Pre-v2.2, every mapper-touching situation printed the same
  # "outOfScopeTestDirs is configured..." hint — actively misleading
  # on runs where OOS was not the cause. v2.2 replaces the one-size
  # hint with three targeted branches. Each scenario pins the
  # situation-appropriate wording so hint drift fails a scenario
  # rather than leaking into production.
  # ------------------------------------------------------------------
  Scenario: DISCOVERY_EMPTY hint names testSuffixes and testDirs, not OOS knobs
    # Production class changed, no matching test on disk. v2.1
    # printed "outOfScopeTestDirs configured" which was irrelevant.
    # v2.2 leads with "mapped 0 test classes" and lists the three
    # realistic causes.
    Given a production class "com.example.Orphan" with no matching test on disk
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Orphan.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_EMPTY
    And the output contains "Hint:            discovery mapped 0 test classes"
    And the output contains "testSuffixes"
    And the output contains "testDirs"
    And the output contains "no test coverage yet"
    And the output does not contain "outOfScopeTestDirs is configured"
    And the output does not contain "outOfScopeSourceDirs is configured"

  Scenario: DISCOVERY_INCOMPLETE hint flags partial-selection risk on SELECTED
    # Parse failure means the mapper ran with missing inputs. On
    # SELECTED (LOCAL mode's default, or an explicit operator opt-in)
    # the selection is definitionally partial, so the hint must
    # surface that risk plus the exact escalation knob.
    #
    # Mode is pinned explicitly — under AUTO the effective mode
    # depends on CI-environment heuristics, and a CI runner would
    # land FULL_SUITE and route through the paired scenario below.
    # Pinning makes the assertion about hint wording, not about
    # which mode the scenario picked.
    Given the affected-tests DSL contains:
      """
      mode = 'local'
      """
    And a canned diff that produces the DISCOVERY_INCOMPLETE situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_INCOMPLETE
    And the output contains "failed to parse"
    And the output contains "selection is necessarily partial"
    And the output contains "onDiscoveryIncomplete"

  Scenario: DISCOVERY_INCOMPLETE hint on FULL_SUITE drops the partial-selection wording
    # Paired scenario for the v2.2.1 H3 fix: CI/STRICT escalate
    # DISCOVERY_INCOMPLETE to FULL_SUITE, so the old "selection is
    # necessarily partial — set onDiscoveryIncomplete=full_suite to
    # escalate" wording was factually wrong on two counts (the whole
    # suite runs, and escalation already happened). The hint now
    # names the parse failure as the root cause for next time and
    # stops there — no circular escalation advice.
    Given the affected-tests DSL contains:
      """
      mode = 'ci'
      """
    And a canned diff that produces the DISCOVERY_INCOMPLETE situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_INCOMPLETE
    And the output contains "failed to parse"
    And the output does not contain "selection is necessarily partial"
    And the output does not contain "onDiscoveryIncomplete = 'full_suite' to escalate"

  Scenario: DISCOVERY_SUCCESS keeps the v2.1 OOS-misconfig hint
    # Regression guard: the one DISCOVERY_SUCCESS case where v2.1 was
    # right — OOS configured, bucket empty, config probably silently
    # broken — must still fire in v2.2.
    Given the affected-tests DSL contains:
      """
      outOfScopeTestDirs = ['api-test/**']
      """
    And a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the output contains "outOfScopeTestDirs is configured (1 entry) but no file in the diff matched"

  # ------------------------------------------------------------------
  # Risk C — LOCAL + DISCOVERY_INCOMPLETE silently trusts partial
  #
  # LOCAL mode keeps `onDiscoveryIncomplete = SELECTED` by design
  # (devs iterating on WIP want fast feedback). The risk: an operator
  # running locally doesn't realise the parser dropped a file, so the
  # green "SELECTED" summary overstates what actually ran. v2.2 emits
  # a WARN in this exact combination — visible at Gradle's default
  # log level without --info.
  # ------------------------------------------------------------------
  Scenario: LOCAL mode warns loudly when a partial selection is accepted
    Given the affected-tests DSL contains:
      """
      mode = 'local'
      """
    And a canned diff that produces the DISCOVERY_INCOMPLETE situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_INCOMPLETE
    # WARN marker renders at lifecycle level; operators see it
    # without --info. Asserting the exact marker (not just "WARN")
    # keeps the assertion pinned to this specific safety check.
    And the output contains "affectedTest: LOCAL mode accepted a partial selection"
    And the output contains "Fix the parse error"

  Scenario: CI mode does not emit the LOCAL-only warning on DISCOVERY_INCOMPLETE
    # CI's default is FULL_SUITE — no partial selection is accepted,
    # so the WARN would be misinformation. Pins the mode-gate so
    # someone doesn't accidentally move the warn outside the LOCAL
    # branch.
    Given the affected-tests DSL contains:
      """
      mode = 'ci'
      """
    And a canned diff that produces the DISCOVERY_INCOMPLETE situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_INCOMPLETE
    And the output does not contain "LOCAL mode accepted a partial selection"

  # ------------------------------------------------------------------
  # Feature D — -PaffectedTestsMode=... runtime override
  #
  # Mirrors the baseRef pattern: lets adopters A/B two modes without
  # editing build.gradle. DSL-declared mode still wins (Gradle
  # Property semantics: explicit set > convention > unset), which
  # the "DSL beats -P" scenario pins.
  # ------------------------------------------------------------------
  Scenario: -PaffectedTestsMode overrides the mode when the DSL leaves it unset
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    And the Gradle command-line argument "-PaffectedTestsMode=strict"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    # STRICT's matrix escalates EMPTY_DIFF to FULL_SUITE (unique to
    # STRICT) — asserting that row proves -P actually threaded
    # through to the mode.
    And the output contains "EMPTY_DIFF               FULL_SUITE"
    And the output contains "Mode:            STRICT"

  Scenario: DSL-declared mode beats -PaffectedTestsMode
    # Gradle Property semantics: explicit set in the DSL is an
    # "explicit value", which wins over a provider-supplied
    # convention (the -P fallback). Pins this precedence so an
    # adopter can reliably freeze their CI mode in build.gradle
    # without a stray -P leaking past.
    Given the affected-tests DSL contains:
      """
      mode = 'ci'
      """
    And a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    And the Gradle command-line argument "-PaffectedTestsMode=strict"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the output contains "Mode:            CI"
    And the output does not contain "Mode:            STRICT"

  # ------------------------------------------------------------------
  # Polish E — :module:test breakdown in --explain
  #
  # The pre-v2.2 trace named the total count but not the module
  # distribution, forcing adopters to dry-run dispatch to answer
  # "which tasks will Gradle actually kick off?". v2.2 pipes the
  # real dispatch grouping (same helper as the non-explain path) into
  # the trace so it answers itself.
  # ------------------------------------------------------------------
  Scenario: --explain shows the :module:test breakdown for a SELECTED run
    Given the project is multi-module with sub-projects "api" and "application"
    And a file at "application/src/main/java/com/example/UserService.java" with content:
      """
      package com.example;
      public class UserService {}
      """
    And a file at "application/src/test/java/com/example/UserServiceTest.java" with content:
      """
      package com.example;
      public class UserServiceTest {}
      """
    And the baseline commit is captured
    And the diff modifies "application/src/main/java/com/example/UserService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the output contains "Modules:         1 module, 1 test class to dispatch"
    And the output contains ":application:test (1 test class)"
    And the output contains "    com.example.UserServiceTest"

  Scenario: --explain suppresses the Modules block when there is no selection
    # Non-SELECTED runs (empty diff, docs-only, unmapped config file)
    # have no dispatch grouping to show, and printing a
    # "Modules: 0 modules" line is pure noise. Pins the empty-map
    # fast path. We capture the baseline first so the empty diff is
    # measured against an explicit ref rather than the plugin's
    # default `origin/master`, which doesn't exist inside the
    # TestKit scratch repo.
    Given the baseline commit is captured
    And the diff contains no committed changes on top of baseline
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is EMPTY_DIFF
    And the output does not contain "Modules:"
