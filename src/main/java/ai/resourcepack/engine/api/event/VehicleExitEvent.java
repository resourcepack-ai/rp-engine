package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleSeat;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player has got out of one of our vehicles, or been taken out.
 *
 * <p>Not cancellable, deliberately. Refusing to let somebody out traps them,
 * and every reason a server might want to — a race that has not finished, a
 * bus between stops — is better served by putting them back with
 * {@link Vehicle#seat} than by a veto that leaves a player stuck in a seat
 * with the dismount key doing nothing.
 *
 * <p>By the time this fires they are already out and standing: the seat is
 * free, {@link Vehicle#occupants()} no longer lists them, and their own body
 * is back. {@link #getCause()} says why, and the one to read carefully is
 * {@link Cause#RELOADED} — that player is about to be put back.
 */
public final class VehicleExitEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why they are out. */
    public enum Cause {
        /** They got out themselves — sneak, or whatever their client's dismount is. */
        DISMOUNTED,
        /** A plugin took them out: {@link Vehicle#eject} or {@link Vehicle#ejectAll}. */
        EJECTED,
        /** They left the server while aboard. */
        QUIT,
        /**
         * The content was reloaded or re-synced and the vehicle was rebuilt
         * under them. <strong>They are put back in the same seat a tick
         * later</strong> — no {@link VehicleEnterEvent} fires for that, so a
         * plugin counting people aboard should treat this pair as nothing
         * having happened.
         */
        RELOADED,
        /** The vehicle was removed: {@link Vehicle#remove}, or the command. */
        REMOVED,
        /**
         * The vehicle went away under them: its chunk unloaded, or something
         * else removed the chassis entity. It is still parked where it was
         * and comes back when the chunk does; they do not.
         */
        UNLOADED,
        /** The plugin is disabling. Everybody gets out; the vehicles stay parked. */
        SHUTDOWN
    }

    private final Vehicle vehicle;
    private final int seatIndex;
    private final Cause cause;

    public VehicleExitEvent(Player player, Vehicle vehicle, int seatIndex, Cause cause) {
        super(player);
        this.vehicle = vehicle;
        this.seatIndex = seatIndex;
        this.cause = cause;
    }

    /** The vehicle they were in. */
    public Vehicle vehicle() {
        return vehicle;
    }

    /** Which seat they were in, counting from zero in the pack's order. Zero is the driver's. */
    public int seatIndex() {
        return seatIndex;
    }

    /** The seat they were in. */
    public VehicleSeat seat() {
        return vehicle.info().seats().get(seatIndex);
    }

    /** Whether they were driving. */
    public boolean wasDriving() {
        return seat().isDriver();
    }

    /** Why they are out. */
    public Cause getCause() {
        return cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
