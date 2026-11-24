Feature: JUnit 5 shapes — discovery coverage with a verified dispatch contract (issue #125)
  JUnit 5 dynamic / nested test shapes
  (@Nested, @ParameterizedTest, @TestFactory, @Tag) were exercised by
  no functional feature: whether
  the engine still selects a JUnit-5-shaped test class on a subject
  change, and whether the dispatched `--tests <outer FQN>` filter
  actually RUNS the nested / parameterised / dynamic / tagged tests
  inside it, were both unverified.

  These scenarios pin the discovery side in-harness: a subject change
  selects the JUnit-5-shaped test class, mapped to its top-level FQN
  exactly like a plain test class.

  The execution side is pinned empirically, because the e2e fixture
  deliberately omits the real-Gradle-build harness dispatch would need
  (see 11-issue-132-header-edges.feature for that rationale). The probe
  below was run on the wrapper-pinned Gradle 9.5.1 with JUnit 6.1.0 —
  the versions this repository itself builds and tests against — using
  exactly the fixture shape this issue's repro describes:

    gradle test --tests com.example.OuterTest

  with OuterTest containing an @Nested InnerTest, a two-row
  parameterised @ParameterizedTest, a two-test @TestFactory, and
  slow-tagged plus fast-tagged plain tests. The JUnit XML report
  shows every shape executed:

    com.example.OuterTest$InnerTest > something()
    com.example.OuterTest > [1] "a"          (parameterised row 1)
    com.example.OuterTest > [2] "b"          (parameterised row 2)
    com.example.OuterTest > dyn-1            (dynamic test 1)
    com.example.OuterTest > dyn-2            (dynamic test 2)
    com.example.OuterTest > slowTest()       (@Tag("slow"))
    com.example.OuterTest > fastTest()       (@Tag("fast"))

  So the plugin's current dispatch shape — one `--tests` filter per
  top-level test-class FQN — runs every JUnit 5 shape inside the
  selected classes. Gradle resolves a class-name filter to the class
  AND its nested classes, and JUnit 5 expands parameterised rows and
  dynamic tests under the filtered container. No silent failure mode,
  no follow-up bug to file.

  The @Tag interaction probe adds `useJUnitPlatform { includeTags
  'fast' }` to the consumer build and re-runs the same dispatch: only
  fastTest() executes. Gradle ANDs the `--tests` filter with the
  build's own tag filters, so the plugin's selection composes with —
  never overrides — a consumer's tag-based partitioning. Discovery
  stays tag-agnostic: the scenarios below assert the selection output
  carries no tag constraints at all.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: S125-1 — @Nested round-trip: subject change selects the outer test class
    # The headline case from the issue: InnerTest only exists inside
    # OuterTest. Discovery maps the file to the top-level FQN
    # com.example.OuterTest; the probe above shows that dispatching
    # `--tests com.example.OuterTest` runs OuterTest$InnerTest's tests.
    Given a file at "src/main/java/com/example/Outer.java" with content:
      """
      package com.example;
      public class Outer {
          public String greet() { return "hello"; }
      }
      """
    And a file at "src/test/java/com/example/OuterTest.java" with content:
      """
      package com.example;
      import org.junit.jupiter.api.Nested;
      import org.junit.jupiter.api.Test;
      public class OuterTest {
          @Nested
          class InnerTest {
              @Test
              void something() {
                  new Outer().greet();
              }
          }
      }
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Outer.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    # Selection targets the outer class only — no synthetic
    # OuterTest$InnerTest FQN is emitted, and the probe shows none is
    # needed: the class-name filter covers nested classes.
    And the selected tests include "com.example.OuterTest"
    And the output does not contain "OuterTest$"

  Scenario: S125-2 — @ParameterizedTest round-trip
    # A parameterised method is discovered as its declaring class; the
    # probe shows every row ([1] "a", [2] "b") executes under the
    # class-name filter.
    Given a file at "src/main/java/com/example/Outer.java" with content:
      """
      package com.example;
      public class Outer {
          public String greet() { return "hello"; }
      }
      """
    And a file at "src/test/java/com/example/OuterTest.java" with content:
      """
      package com.example;
      import org.junit.jupiter.params.ParameterizedTest;
      import org.junit.jupiter.params.provider.ValueSource;
      public class OuterTest {
          private final Outer outer = new Outer();
          @ParameterizedTest
          @ValueSource(strings = {"a", "b"})
          void parameterised(String s) {
              outer.greet();
          }
      }
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Outer.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the selected tests include "com.example.OuterTest"

  Scenario: S125-3 — @TestFactory round-trip
    # A dynamic-test factory is likewise discovered as its declaring
    # class; the probe shows both dynamic tests (dyn-1, dyn-2) execute
    # under the class-name filter.
    Given a file at "src/main/java/com/example/Outer.java" with content:
      """
      package com.example;
      public class Outer {
          public String greet() { return "hello"; }
      }
      """
    And a file at "src/test/java/com/example/OuterTest.java" with content:
      """
      package com.example;
      import org.junit.jupiter.api.DynamicTest;
      import org.junit.jupiter.api.TestFactory;
      import java.util.stream.Stream;
      public class OuterTest {
          @TestFactory
          Stream<DynamicTest> factory() {
              Outer outer = new Outer();
              return Stream.of(
                      DynamicTest.dynamicTest("dyn-1", outer::greet),
                      DynamicTest.dynamicTest("dyn-2", outer::greet));
          }
      }
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Outer.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the selected tests include "com.example.OuterTest"

  Scenario: S125-4 — @Tag mix: selection stays tag-agnostic
    # A class mixing @Tag("fast") and @Tag("slow") tests is selected
    # whole. The plugin never injects tag constraints into the dispatch
    # — the probe shows Gradle ANDs the consumer's own
    # useJUnitPlatform { includeTags ... } with the plugin's --tests
    # filter, so tag-based partitioning keeps working on top of the
    # plugin's selection. The assertions pin both halves: the class is
    # selected, and the selection output carries no tag tokens.
    Given a file at "src/main/java/com/example/Outer.java" with content:
      """
      package com.example;
      public class Outer {
          public String greet() { return "hello"; }
      }
      """
    And a file at "src/test/java/com/example/OuterTest.java" with content:
      """
      package com.example;
      import org.junit.jupiter.api.Tag;
      import org.junit.jupiter.api.Test;
      public class OuterTest {
          @Test
          @Tag("slow")
          void slowTest() {
              new Outer().greet();
          }
          @Test
          @Tag("fast")
          void fastTest() {
              new Outer().greet();
          }
      }
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Outer.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the selected tests include "com.example.OuterTest"
    And the output does not contain "includeTags"
    And the output does not contain "excludeTags"
