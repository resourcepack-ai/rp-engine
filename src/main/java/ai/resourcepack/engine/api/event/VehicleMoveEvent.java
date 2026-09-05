package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Vehicle;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Optional;

/**
 * A vehicle is about to move.
 *
 * <p>Once per tick for every vehicle that is going somewhere — driven,
 * coasting, falling, drifting on water — and never for one that is standing
 * still, so a car park costs nothing. Cancelling stops it dead where it is,
 * as though it had hit a wall: speed to zero, this tick's move refused. It
 * will try again next tick if its driver keeps asking, and be refused again,
 * which is exactly what a fence looks like from inside a car.
 *
 * <p>This is the event for a region a vehicle may not enter, a race track it
 * may not leave, and a road it may not leave the surface of. It is not the
 * event for fuel: cancelling every move of a dry tank works, but
 * {@link Vehicle#setEnabled} does the same with no listener at all and
 * remembers itself across a restart. Read {@link #getTo()} and decide; do
 * not do anything expensive in here, because a busy server has many of
 * these a second.
 *
 * <p>{@link #getTo()} is where it is <em>trying</em> to go — the position
 * before walls, floors and steps are consulted, so a vehicle driving at a
 * building reports the far side of the wall here and ends up against it.
 * Compare against {@link #getFrom()} for the direction; compare blocks for
 * whether it is crossing a line.
 */
public final class VehicleMoveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Vehicle vehicle;
    private final Location from;
    private final Location to;
    private boolean cancelled;

    public VehicleMoveEvent(Vehicle vehicle, Location from, Location to) {
        this.vehicle = vehicle;
        this.from = from;
        this.to = to;
    }

    /** Which vehicle. */
    public Vehicle vehicle() {
        return vehicle;
    }

    /** Whoever is driving, if anybody — a vehicle coasts and falls with nobody aboard. */
    public Optional<Player> driver() {
        return vehicle.driver();
    }

    /** Where it is. A copy; changing it changes nothing. */
    public Location getFrom() {
        return from.clone();
    }

    /**
     * Where it is trying to go this tick, facing the way it will face.
     *
     * <p>A copy; changing it changes nothing. There is no way to redirect a
     * vehicle from here — cancel, and steer it with
     * {@link Vehicle#setSpeedLimit} or {@link Vehicle#stop} if stopping dead
     * is too abrupt.
     */
    public Location getTo() {
        return to.clone();
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
