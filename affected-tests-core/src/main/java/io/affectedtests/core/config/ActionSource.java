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
     * The caller set one of the legacy boolean flags
     * ({@code runAllIfNoMatches}, {@code runAllOnNonJavaChange}) and the
     * translation shim picked the action from it.
     */
    LEGACY_BOOLEAN,

    /**
     * The caller set an explicit {@link Mode} but not the more-specific
     * explicit or legacy setting, and the mode's default table supplied
     * the action.
     */
    MODE_DEFAULT,

    /**
     * No caller setting at any tier applies; the pre-v2 hardcoded
     * default was used. Zero-config installs observe this for every
     * situation until they set a mode.
     */
    HARDCODED_DEFAULT
}
