package ai.resourcepack.engine.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player is about to sit on something of ours — a chair placed as a model,
 * or a seat bone on a rig.
 *
 * <p>Cancelling leaves them standing. That is the point of it: sitting is the
 * one thing a placed model does to a player without asking, and a server with
 * an arena, a jail or a minigame has good reasons to refuse one.
 *
 * <p>There is no matching event for standing up, deliberately. Refusing to let
 * somebody stand traps them, and a plugin wanting to know when they did has
 * Bukkit's {@code EntityDismountEvent}.
 */
public final class ModelSeatEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location seat;
    private boolean cancelled;

    public ModelSeatEvent(Player player, Location seat) {
        super(player);
        this.seat = seat;
    }

    /** Where they are about to sit. */
    public Location seat() {
        return seat;
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
