package io.affectedtests.core.config;

/**
 * Records which tier of the priority ladder picked the
 * {@link Action} for a given {@link Situation}. Surfaced so
 * {@code --explain} can tell an operator why a decision was made, not
 * just what it was, without cracking open debug logs.
 *
 * <p>The ladder is evaluated strictly in the order below; the first tier
 * that has a non-null value wins, and the source is stamped with that
 * tier's entry in this enum. See
 * {@link AffectedTestsConfig#actionSourceFor(Situation)}.
 */
public enum ActionSource {
    /**
     * The caller set an explicit {@code onXxx(Action)} on the builder.
     * This is the only tier that survives every future default change —
     * if the plugin's defaults shift again, this entry still wins.
     */
    EXPLICIT,

    /**
     * The caller did not set an explicit action for this situation, and
     * the resolved {@link Mode} default supplied the action. When no
     * mode is configured, {@link Mode#AUTO} detects CI vs. local from
     * the environment and applies the corresponding default table.
     */
    MODE_DEFAULT
}
