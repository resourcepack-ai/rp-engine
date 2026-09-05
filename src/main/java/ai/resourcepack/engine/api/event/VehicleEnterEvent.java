package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleSeat;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player is about to get into one of our vehicles.
 *
 * <p>Cancelling leaves them standing beside it. This is the event for "is
 * this your car", "no vehicles in the arena", "the driver's seat needs a
 * licence": rules about who may ride what, which the engine cannot know.
 *
 * <p>It fires after {@link ModelSeatEvent} for the same seat, and only if
 * that one was not cancelled. That event is the broad question — may this
 * player sit on anything of ours, chairs included — and this one is the
 * narrow one, with the vehicle and the seat attached. A plugin that refuses
 * all sitting needs only the first; a plugin about vehicles wants this.
 *
 * <p>Fires for every way in: a click on a seat, a click on the body, and
 * {@link Vehicle#seat}. It does <em>not</em> fire when the engine puts
 * somebody back into the seat they were already in after a reload has
 * rebuilt the vehicle under them — they never left, as far as your rule is
 * concerned.
 */
public final class VehicleEnterEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Vehicle vehicle;
    private final int seatIndex;
    private boolean cancelled;

    public VehicleEnterEvent(Player player, Vehicle vehicle, int seatIndex) {
        super(player);
        this.vehicle = vehicle;
        this.seatIndex = seatIndex;
    }

    /** The vehicle they are getting into. */
    public Vehicle vehicle() {
        return vehicle;
    }

    /**
     * Which seat, counting from zero in the pack's order. Zero is the driver's.
     */
    public int seatIndex() {
        return seatIndex;
    }

    /** The seat itself: its role, its pose, its name. */
    public VehicleSeat seat() {
        return vehicle.info().seats().get(seatIndex);
    }

    /** Whether this is the driver's seat — the one that will move the vehicle. */
    public boolean isDriverSeat() {
        return seat().isDriver();
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
