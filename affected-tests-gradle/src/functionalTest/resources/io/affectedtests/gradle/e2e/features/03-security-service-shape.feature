Feature: security-service-shape consumer configurations
  These scenarios mirror the exact shape of security-service's
  adoption: a multi-module Gradle build with out-of-scope quarantine
  dirs expressed as ant-style globs, a custom test-suffix list, and a
  bounded transitiveDepth. Breaking any of these assumptions breaks
  security-service's MR gate — so the scenarios here are a direct
  integration contract with the primary consumer.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: nested-glob outOfScopeTestDirs matches files at any depth
    # security-service's build isolates both api-test/** and
    # performance-test/** across many modules. The globs in their
    # DSL look like "api-test/**" — that pattern must match files
    # nested arbitrarily deep under that directory.
    Given the affected-tests DSL contains:
      """
      outOfScopeTestDirs = ['api-test/**', 'performance-test/**']
      """
    And a file at "api-test/com/example/very/deep/NestedSmokeTest.java" with content:
      """
      package com.example.very.deep;
      public class NestedSmokeTest {}
      """
    And the baseline commit is captured
    And the diff modifies "api-test/com/example/very/deep/NestedSmokeTest.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is ALL_FILES_OUT_OF_SCOPE
    And the action is SKIPPED

  Scenario: custom testSuffixes picks up IT-suffixed integration tests
    # security-service uses both *Test.java and *IT.java suffixes.
    # A production change whose only test counterpart uses the IT
    # suffix must still be discovered — otherwise integration-only
    # services are silently untested.
    Given the affected-tests DSL contains:
      """
      testSuffixes = ['Test', 'IT', 'IntegrationTest']
      """
    And a file at "src/main/java/com/example/PaymentGateway.java" with content:
      """
      package com.example;
      public class PaymentGateway {}
      """
    And a file at "src/test/java/com/example/PaymentGatewayIT.java" with content:
      """
      package com.example;
      public class PaymentGatewayIT {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/PaymentGateway.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the outcome is "SELECTED — 1 test class(es) will run"

  Scenario: transitiveDepth caps the strategy chain so large blast radii don't run everything
    # Default transitiveDepth is 4 — deep enough for most service
    # call graphs. Consumers who see their call graph explode when a
    # core util changes can clamp it. A transitiveDepth of 0 disables
    # transitive inclusion entirely: direct tests only.
    Given the affected-tests DSL contains:
      """
      transitiveDepth = 0
      """
    And a production class "com.example.CoreUtil" with its matching test "com.example.CoreUtilTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/CoreUtil.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    # With transitiveDepth=0 we still get the direct match via the
    # naming strategy, just no transitive expansion. Result: the one
    # direct test is selected.
    And the situation is DISCOVERY_SUCCESS
    And the outcome is "SELECTED — 1 test class(es) will run"

  Scenario: multi-module prod change routes to the correct sub-module's tests
    # security-service is a multi-module Gradle build. A change in
    # module-a/src/main/java/... must route the discovered test to
    # :module-a:test, not :module-b:test. Cross-module leakage is
    # the single most expensive failure mode we protect against —
    # dispatch cost scales linearly in the number of modules and
    # routing the wrong module wastes CI minutes on a green build
    # that proves nothing.
    Given the project is multi-module with sub-projects "module-a" and "module-b"
    And a file at "module-a/src/main/java/com/example/a/ServiceA.java" with content:
      """
      package com.example.a;
      public class ServiceA {}
      """
    And a file at "module-a/src/test/java/com/example/a/ServiceATest.java" with content:
      """
      package com.example.a;
      public class ServiceATest {}
      """
    And a file at "module-b/src/main/java/com/example/b/ServiceB.java" with content:
      """
      package com.example.b;
      public class ServiceB {}
      """
    And a file at "module-b/src/test/java/com/example/b/ServiceBTest.java" with content:
      """
      package com.example.b;
      public class ServiceBTest {}
      """
    And the baseline commit is captured
    And the diff modifies "module-a/src/main/java/com/example/a/ServiceA.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    # Only one test was discovered — confirms the engine did NOT
    # select module-b tests for a module-a-only diff.
    And the outcome is "SELECTED — 1 test class(es) will run"
