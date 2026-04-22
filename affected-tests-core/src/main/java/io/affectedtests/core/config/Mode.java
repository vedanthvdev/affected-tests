package io.affectedtests.core.config;

/**
 * Execution profile that seeds the per-situation {@link Action} defaults.
 *
 * <p>{@link #AUTO} probes the environment (via
 * {@link AffectedTestsConfig.Builder#build()}) and resolves to either
 * {@link #LOCAL} or {@link #CI} based on the usual CI env vars; every other
 * mode is an explicit opt-in and its defaults are applied verbatim.
 *
 * <p>Defaults per mode (used only when the caller has not set the
 * specific {@code onXxx} situation action):
 *
 * <table>
 *   <caption>Per-mode action defaults</caption>
 *   <tr><th></th><th>EMPTY_DIFF</th><th>ALL_IGNORED</th><th>ALL_OUT_OF_SCOPE</th><th>UNMAPPED_FILE</th><th>DISCOVERY_EMPTY</th><th>DISCOVERY_INCOMPLETE</th></tr>
 *   <tr><td>LOCAL</td><td>SKIPPED</td><td>SKIPPED</td><td>SKIPPED</td><td>FULL_SUITE</td><td>SKIPPED</td><td>SELECTED</td></tr>
 *   <tr><td>CI</td><td>SKIPPED</td><td>SKIPPED</td><td>SKIPPED</td><td>FULL_SUITE</td><td>FULL_SUITE</td><td>FULL_SUITE</td></tr>
 *   <tr><td>STRICT</td><td>FULL_SUITE</td><td>FULL_SUITE</td><td>SKIPPED</td><td>FULL_SUITE</td><td>FULL_SUITE</td><td>FULL_SUITE</td></tr>
 * </table>
 *
 * <p>Zero-config installs land on {@link #AUTO} and therefore get either
 * the LOCAL or CI column based on whether the usual CI env vars are
 * set. {@link #STRICT} tightens the CI column further for users that
 * want ambiguity to always escalate to a full suite.
 */
public enum Mode {
    /** Detect CI vs. local at build() time based on common CI env vars. */
    AUTO,

    /** Developer defaults: skip more, run fewer tests by default. */
    LOCAL,

    /** CI defaults: run full suite on ambiguity (unmapped, discovery empty). */
    CI,

    /** Tightest defaults: also escalate ALL_IGNORED to FULL_SUITE. */
    STRICT
}
