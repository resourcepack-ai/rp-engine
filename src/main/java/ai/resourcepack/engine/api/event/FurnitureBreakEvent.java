package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A piece of furniture is about to be broken. Cancellable.
 *
 * <p>Not a {@code PlayerEvent}, because furniture can be removed by code with
 * nobody responsible — {@link #player()} answers null then. Inventing a player
 * to fill the field would hand every listener a lie about who did it.
 *
 * <p>{@link #setDropItem} exists separately from cancelling, because refusing
 * the break and allowing it without a drop are different decisions: a
 * protection plugin wants the first and a creative-mode rule wants the second.
 */
public final class FurnitureBreakEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId furniture;
    private final Location location;
    private final Player player;
    private boolean dropItem;
    private boolean cancelled;

    public FurnitureBreakEvent(ContentId furniture, Location location, Player player, boolean dropItem) {
        this.furniture = furniture;
        this.location = location;
        this.player = player;
        this.dropItem = dropItem;
    }

    /** Which piece. */
    public ContentId furniture() {
        return furniture;
    }

    /** Where it stands. */
    public Location location() {
        return location;
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
