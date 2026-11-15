package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A vehicle is being taken out of the world for good: by
 * {@code /rp vehicle remove}, or by a plugin through {@code Vehicle.remove}.
 *
 * <p>Fires first, while the vehicle is whole: its occupants are still seated
 * (each gets a {@code VehicleExitEvent} with {@code REMOVED} straight after)
 * and its {@code data()} can still be read, which is where a plugin keeps what
 * it has to settle now, an owner or a fuel level.
 *
 * <p>Not cancellable: whoever removed it has already decided. A vehicle that
 * is only unloaded with its chunk does not fire this; it is still there.
 */
public final class VehicleRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Vehicle vehicle;

    public VehicleRemoveEvent(Vehicle vehicle) {
        this.vehicle = vehicle;
    }

    /** The vehicle, still standing for the length of this event. */
    public Vehicle vehicle() {
        return vehicle;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
