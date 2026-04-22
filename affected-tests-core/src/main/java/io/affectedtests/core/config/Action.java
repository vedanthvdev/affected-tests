package io.affectedtests.core.config;

/**
 * What the plugin should do when a given {@link Situation} fires.
 *
 * <p>The three actions are exhaustive — every decision branch in the engine
 * resolves to exactly one of them — and are the primary knob users reach for
 * in v2 to express intent without having to know which internal flag used to
 * drive that outcome.
 *
 * <ul>
 *   <li>{@link #SELECTED} — execute only the tests discovered by the pipeline.
 *       On branches where discovery never produced anything (e.g. empty diff)
 *       this degenerates to "no tests run" because the selection set is
 *       empty by definition; it is distinct from {@link #SKIPPED} so that
 *       logs and the {@code --explain} trace can still name the situation
 *       honestly (selection succeeded, set happened to be empty) rather than
 *       claim the run was skipped.</li>
 *   <li>{@link #FULL_SUITE} — flip to running every test the project knows
 *       about. The legacy {@code runAllIfNoMatches} / {@code runAllOnNonJavaChange}
 *       booleans map onto this action via the shim in
 *       {@link AffectedTestsConfig.Builder#build()}.</li>
 *   <li>{@link #SKIPPED} — run no tests at all. Previously impossible to
 *       express without also disabling the plugin; the situation-specific
 *       knobs in v2 make it first-class.</li>
 * </ul>
 */
public enum Action {
    /** Execute the discovered (possibly empty) affected-test selection. */
    SELECTED,

    /** Flip to running the full test suite. */
    FULL_SUITE,

    /** Do not run any tests for this invocation. */
    SKIPPED
}
