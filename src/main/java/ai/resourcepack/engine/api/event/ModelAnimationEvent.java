package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Placement;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired just before a placed model starts an animation. Cancel it and the rig
 * stays where it was.
 *
 * <p>Fires for every start, whatever asked for it: a click, a player walking
 * into range, the placement itself, or another plugin calling
 * {@link Placement#play}. It does NOT fire for an idle loop resuming on its own
 * when a one-shot finishes - that is the rig going back to rest, not a new
 * animation, and a listener that cancelled it would pin the model on its last
 * frame for ever.
 */
public final class ModelAnimationEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** What asked for the animation. */
    public enum Cause {
        /** The rig was just placed. */
        PLACE,
        /** Somebody right-clicked it. */
        RIGHT_CLICK,
        /** Somebody left-clicked it. */
        LEFT_CLICK,
        /** Somebody walked into its range. */
        RANGE,
        /** Another plugin called {@link Placement#play}. */
        API
    }

    private final Placement placement;
    private final String animation;
    private final Cause cause;
    private final Player player;
    private boolean cancelled;

    public ModelAnimationEvent(Placement placement, String animation, Cause cause, Player player) {
        this.placement = placement;
        this.animation = animation;
        this.cause = cause;
        this.player = player;
    }

    /** The rig about to move. */
    public Placement getPlacement() {
        return placement;
    }

    /** Which animation, by the name the editor gave it. */
    public String getAnimation() {
        return animation;
    }

    /** What asked for it. */
    public Cause getCause() {
        return cause;
    }

    /**
     * The player who caused it, or null - {@link Cause#PLACE} and
     * {@link Cause#API} need not have one.
     */
    public Player getPlayer() {
        return player;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
