package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

/**
 * One stinger playing on one player: what {@link Stingers#play} and
 * {@link Stingers#cover} hand back.
 *
 * <p>Any thread: each method only marks what should happen and the steps run
 * on the player's own thread.
 */
public interface StingerPlay {

    /** The stinger being played. */
    ContentId id();

    /** Who it is playing on. */
    Player player();

    /**
     * Starts the cover leaving. Before the screen is covered, it leaves as soon
     * as it is; while it holds, now. For {@link Stingers#play} this shortens
     * the hold, for {@link Stingers#cover} it is the only thing that ends it.
     * Does nothing once it is leaving or over.
     */
    void release();

    /** Whether the screen is fully covered now: after the cover came in and before it starts to leave. */
    boolean isCovered();

    /** Whether it is over: played out, cancelled, replaced by another, or the player left. */
    boolean isFinished();

    /** Ends it now: the title is cleared at once, nothing more runs, and the covered callback is not called if it has not been. */
    void cancel();
}
