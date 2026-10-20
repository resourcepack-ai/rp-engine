package ai.resourcepack.engine.api;

import org.bukkit.Location;

/**
 * One VFX playing somewhere: what {@link Vfx#play} hands back.
 *
 * <p>Main thread only.
 */
public interface VfxPlay {

    /** The effect being played. */
    ContentId id();

    /** Where it was cast: the caster's feet, facing the way they faced. */
    Location location();

    /** Whether any of it is still drawn. False once it ends, and after {@link #stop}. */
    boolean running();

    /** Ends it now: its sprites go, and the sounds it started are stopped. Does nothing once it has ended. */
    void stop();

    /**
     * Ends a loop the way its options say ({@link VfxOptions.End}): on
     * {@code FINISH} it plays on to the end, out through its fade; on
     * {@code CUT} it ends now. An effect playing once ends now.
     */
    void finish();

    /** How it was cast. */
    VfxOptions options();

    /** Ticks since it started, delay not counted. */
    long age();

    /** Times a loop has gone round so far. */
    int passes();
}
