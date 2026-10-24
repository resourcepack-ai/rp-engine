package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Placement;
import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A placed model is about to be broken. Cancellable.
 *
 * <p>Not a {@code PlayerEvent}, because a model can be removed by code with
 * nobody responsible — {@link #player()} answers null then. Inventing a player
 * to fill the field would hand every listener a lie about who did it.
 *
 * <p>{@link #setDropItem} exists separately from cancelling, because refusing
 * the break and allowing it without a drop are different decisions: a
 * protection plugin wants the first and a creative-mode rule wants the second.
 */
public final class ModelBreakEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId model;
    private final Location location;
    private final Player player;
    private final Interaction hitbox;
    private final Placement placement;
    private boolean dropItem;
    private boolean cancelled;

    /** A break with no hitbox to name, so {@link #hitbox()} and {@link #placement()} are null. */
    public ModelBreakEvent(ContentId model, Location location, Player player, boolean dropItem) {
        this(model, null, null, location, player, dropItem);
    }

    public ModelBreakEvent(ContentId model, Interaction hitbox, Placement placement, Location location,
            Player player, boolean dropItem) {
        this.model = model;
        this.hitbox = hitbox;
        this.placement = placement;
        this.location = location;
        this.player = player;
        this.dropItem = dropItem;
    }

    /** Which piece. */
    public ContentId model() {
        return model;
    }

    /** Where it stands. */
    public Location location() {
        return location;
    }

    /**
     * The hitbox entity of the model being broken: the same Interaction
     * {@link ModelInteractEvent#hitbox()} hands over when it is clicked, and
     * {@link Placement#hitbox()} answers. Its UUID is the placement's identity,
     * so this is what tells which of two copies of one model was hit, and its
     * persistent data still holds whatever a listener stored on it.
     */
    public Interaction hitbox() {
        return hitbox;
    }

    /**
     * The placement being broken, as the handle {@code Models.at} would give:
     * what it is playing, its own {@link Placement#data()}, and the rest. Still
     * valid while the event runs, since nothing has been taken apart yet.
     */
    public Placement placement() {
        return placement;
    }

    /** Who broke it, or null if nobody did. */
    public Player player() {
        return player;
    }

    /** Whether it drops its item. */
    public boolean isDropItem() {
        return dropItem;
    }

    /** Sets whether it drops its item. */
    public void setDropItem(boolean dropItem) {
        this.dropItem = dropItem;
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
