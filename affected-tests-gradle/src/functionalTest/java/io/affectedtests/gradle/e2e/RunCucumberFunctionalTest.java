package io.affectedtests.gradle.e2e;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * JUnit 5 Platform Suite entrypoint for the Cucumber e2e feature files.
 *
 * <p>Every feature file under {@code src/functionalTest/resources/io/affectedtests/gradle/e2e/features}
 * is discovered by the Cucumber JUnit Platform engine at test time. Each
 * scenario spawns a real Gradle TestKit build via {@link TestProject}, so
 * the assertions cover the exact plugin surface that consumer builds like
 * {@code security-service} see in production, not just the decision
 * engine in isolation.
 *
 * <p>The suite is deliberately split across multiple {@code .feature}
 * files (pilot scenarios, the Mode x Situation matrix, security-service
 * consumer shape, DSL migration errors, edge cases). Splitting by theme
 * keeps feature files self-contained — a reviewer landing in
 * {@code 02-mode-situation-matrix.feature} sees every default action
 * cell in one place without having to cross-reference other files.
 *
 * <p>Glue packages point at {@code steps/} so step definitions are
 * discovered deterministically regardless of package scanning order.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("io/affectedtests/gradle/e2e/features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "io.affectedtests.gradle.e2e.steps")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,summary")
public class RunCucumberFunctionalTest {
}
