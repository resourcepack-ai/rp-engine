package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Placement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * An animation on a placed model stopped.
 *
 * <p>The other half of {@link ModelAnimationEvent}, and the half a plugin
 * doing anything in sequence needs: a door that opens and then unlocks, a
 * machine that finishes its cycle and gives you something. Without this the
 * only way to know is to guess the length and hope, and the length is in
 * somebody's Blockbench file rather than in your code.
 *
 * <p>Not cancellable — it says an animation ended, and an animation cannot be
 * un-ended. To keep one running, do not stop it.
 *
 * <h2>What does and does not end</h2>
 *
 * <p>Only a placement's main animation, once per placement rather than once
 * per part. A loop does not end on its own and neither does a {@code hold},
 * which stops on its last frame and stays there — both of those end when
 * something replaces or stops them. An animation still running when the server
 * stops does not end: the placement resumes from its own stored clock on the
 * next start, and there is nothing left in memory that knew it was playing.
 */
public final class ModelAnimationEndEvent extends Event {

    /** Why it stopped. */
    public enum Cause {

        /** It ran to its end. Only a one-shot does this. */
        FINISHED,

        /** Something asked for a different animation while it was playing. */
        REPLACED,

        /** It was stopped — by the API, a command, or a mechanic. */
        STOPPED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Placement placement;
    private final String animation;
    private final Cause cause;

    public ModelAnimationEndEvent(Placement placement, String animation, Cause cause) {
        this.placement = placement;
        this.animation = animation;
        this.cause = cause;
    }

    /** The model this was playing on. */
    public Placement placement() {
        return placement;
    }

    /** Which animation ended, by the name it has in the pack. */
    public String animation() {
        return animation;
    }

    /** Whether it finished, was replaced, or was stopped. */
    public Cause cause() {
        return cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
