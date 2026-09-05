package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.Set;

/**
 * What a vehicle is doing changed: it set off, stopped, took off, landed,
 * went under, started reversing.
 *
 * <p>The same {@link VehicleState} set that drives its animation and its
 * particles, delivered on the change rather than every tick — so a fuel
 * gauge that burns only while {@code MOVING}, a sound that plays on takeoff,
 * or a scoreboard that says "airborne" listens here and does nothing the
 * rest of the time. {@link Vehicle#states()} is the same answer, polled.
 *
 * <p>Not cancellable: it reports a fact about physics that has already
 * happened. The way to stop a vehicle setting off is
 * {@link Vehicle#setEnabled}, and the way to stop it moving somewhere is
 * {@link VehicleMoveEvent}.
 *
 * <p>Several states hold at once — a vehicle is usually {@code MOVING} and
 * {@code TURNING} together — so this carries the whole set before and after
 * and two helpers for the usual question, which is about one of them.
 */
public final class VehicleStateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Vehicle vehicle;
    private final Set<VehicleState> states;
    private final Set<VehicleState> previous;

    public VehicleStateEvent(Vehicle vehicle, Set<VehicleState> states, Set<VehicleState> previous) {
        this.vehicle = vehicle;
        this.states = states == null ? Collections.emptySet() : Collections.unmodifiableSet(states);
        this.previous = previous == null ? Collections.emptySet() : Collections.unmodifiableSet(previous);
    }

    /** Which vehicle. */
    public Vehicle vehicle() {
        return vehicle;
    }

    /** What it is doing now. */
    public Set<VehicleState> states() {
        return states;
    }

    /** What it was doing a tick ago. */
    public Set<VehicleState> previous() {
        return previous;
    }

    /** Whether it has just gone into this state. */
    public boolean entered(VehicleState state) {
        return states.contains(state) && !previous.contains(state);
    }

    /** Whether it has just come out of this state. */
    public boolean left(VehicleState state) {
        return previous.contains(state) && !states.contains(state);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
