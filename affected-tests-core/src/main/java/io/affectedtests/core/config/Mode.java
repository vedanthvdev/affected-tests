package io.affectedtests.core.config;

/**
 * Execution profile that seeds the per-situation {@link Action} defaults.
 *
 * <p>{@link #AUTO} probes the environment (via
 * {@link AffectedTestsConfig.Builder#build()}) and resolves to either
 * {@link #LOCAL} or {@link #CI} based on the usual CI env vars; every other
 * mode is an explicit opt-in and its defaults are applied verbatim.
 *
 * <p>Defaults per mode (used only when the caller has set neither the
 * specific situation action nor the legacy boolean that would translate to
 * it):
 *
 * <table>
 *   <caption>Per-mode action defaults</caption>
 *   <tr><th></th><th>EMPTY_DIFF</th><th>ALL_IGNORED</th><th>ALL_OUT_OF_SCOPE</th><th>UNMAPPED_FILE</th><th>DISCOVERY_EMPTY</th></tr>
 *   <tr><td>LOCAL</td><td>SKIPPED</td><td>SKIPPED</td><td>SKIPPED</td><td>FULL_SUITE</td><td>SKIPPED</td></tr>
 *   <tr><td>CI</td><td>SKIPPED</td><td>SKIPPED</td><td>SKIPPED</td><td>FULL_SUITE</td><td>FULL_SUITE</td></tr>
 *   <tr><td>STRICT</td><td>FULL_SUITE</td><td>FULL_SUITE</td><td>SKIPPED</td><td>FULL_SUITE</td><td>FULL_SUITE</td></tr>
 * </table>
 *
 * <p>The pre-v2 zero-config baseline was
 * {@code runAllIfNoMatches=false}, {@code runAllOnNonJavaChange=true} —
 * which translates to the {@link #LOCAL} column above minus the
 * {@code DISCOVERY_EMPTY=FULL_SUITE} CI safety net. Zero-config users
 * running in CI now get the safer default without having to opt in.
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
