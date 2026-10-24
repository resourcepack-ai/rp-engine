package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Placement;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.util.Vector;

/**
 * A player right-clicked a placed model: a content-folder piece or a model
 * from Studio, the same event for both.
 *
 * <p>A shop that opens a menu, a lever that fires redstone: those are a
 * server's own behaviour, and this says what happened so a plugin can supply
 * it. The engine's own answers to a click (the pack's click actions, opening
 * storage, the next state, a right-click animation, sitting) all come after
 * this event, and cancelling it stops every one of them, so a listener that
 * has handled the click has it to itself.
 */
public final class ModelInteractEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId model;
    private final Interaction hitbox;
    private final Placement placement;
    private final Vector clickedPosition;
    private boolean cancelled;

    /** A click with no handle or position to name, so those two are null. */
    public ModelInteractEvent(Player player, ContentId model, Interaction hitbox) {
        this(player, model, hitbox, null, null);
    }

    public ModelInteractEvent(Player player, ContentId model, Interaction hitbox, Placement placement,
            Vector clickedPosition) {
        super(player);
        this.model = model;
        this.hitbox = hitbox;
        this.placement = placement;
        this.clickedPosition = clickedPosition == null ? null : clickedPosition.clone();
    }

    /** Which model was clicked. */
    public ContentId model() {
        return model;
    }

    /**
     * The hitbox entity that was clicked.
     *
     * <p>Its location is where the model stands, and its persistent data is
     * where anything a listener wants to remember about this particular
     * placement belongs.
     */
    public Interaction hitbox() {
        return hitbox;
    }

    /**
     * The placement that was clicked, as the handle {@code Models.at} would
     * give, so a listener can play an animation on it, read what it is
     * playing or reach its {@link Placement#data()} without a lookup.
     */
    public Placement placement() {
        return placement;
    }

    /**
     * Where on the hitbox the click landed, relative to the hitbox's feet, as
     * Bukkit's {@code PlayerInteractAtEntityEvent} reports it. A new vector on
     * every call. For a model whose top and bottom do different things, or a
     * panel with buttons on it: {@code y} is how high up the click was.
     */
    public Vector clickedPosition() {
        return clickedPosition == null ? null : clickedPosition.clone();
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
