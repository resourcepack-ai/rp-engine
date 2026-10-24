package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Placement;
import ai.resourcepack.engine.api.Vehicle;
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
    private final Placement placement;
    private final Vehicle vehicle;
    private boolean cancelled;

    /** A seat that belongs to nothing this can name. */
    public ModelSeatEvent(Player player, Location seat) {
        this(player, seat, null, null);
    }

    public ModelSeatEvent(Player player, Location seat, Placement placement, Vehicle vehicle) {
        super(player);
        this.seat = seat;
        this.placement = placement;
        this.vehicle = vehicle;
    }

    /** Where they are about to sit. */
    public Location seat() {
        return seat;
    }

    /**
     * The placed model they are sitting on: the chair, or the rig whose seat
     * bone it is. Null for a vehicle, and for a seat bone on a model worn by a
     * mob, which is not a placement.
     */
    public Placement placement() {
        return placement;
    }

    /**
     * The vehicle, when the seat is one of a vehicle's; null otherwise.
     * {@link VehicleEnterEvent} follows with the seat's index.
     */
    public Vehicle vehicle() {
        return vehicle;
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
