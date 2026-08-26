package ai.resourcepack.engine.api;

/**
 * What happened when a skin was applied.
 *
 * <p>Typed rather than a message, for the same reason {@link EmoteResult} is:
 * {@link #NEEDS_PAPER} is something a server owner can change and
 * {@link #INVALID} is a bad signature, and those are not the same problem.
 */
public enum SkinResult {

    /** On, and visible to everybody including the wearer. */
    APPLIED,

    /**
     * This server is Spigot, which cannot change a skin after a player has
     * joined.
     *
     * <p>The call is made entirely by reflection against Paper's
     * {@code Player#setPlayerProfile}, on purpose: compiling against paper-api
     * would stop the jar loading on Spigot at all, which is a worse trade than
     * one feature reporting this.
     */
    NEEDS_PAPER,

    /** The value or signature was empty, malformed, or rejected by the server. */
    INVALID,

    /** The player went offline, or the server threw on the way. */
    FAILED
}
