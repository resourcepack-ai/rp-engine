package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Vehicle;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A rider is about to be thrown off a vehicle by a bad landing.
 *
 * <p>Fired before anything happens to them, and cancellable, which is the
 * point of it: whether a server wants its riders thrown is a decision the
 * server makes, and cancelling here is how a plugin gives its owner that
 * switch without editing the pack's YAML - which an addon's next update would
 * overwrite anyway.
 *
 * <p>Cancelling leaves the rider on the vehicle and takes nothing off them.
 * The landing still happened; they simply rode it out.
 *
 * @see ai.resourcepack.engine.api.VehicleBail
 */
public final class VehicleBailEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Vehicle vehicle;
    private final double degreesOff;
    private final double speed;
    private boolean cancelled;

    public VehicleBailEvent(Player player, Vehicle vehicle, double degreesOff, double speed) {
        super(player);
        this.vehicle = vehicle;
        this.degreesOff = degreesOff;
        this.speed = speed;
    }

    /** What they were riding. */
    public Vehicle vehicle() {
        return vehicle;
    }

    /**
     * How far round from the way it was travelling the vehicle landed,
     * degrees, 0 to 180.
     *
     * <p>Nearer 90 is more sideways. Past the window's far edge is landing
     * backwards, which is not a bail at all and never reaches here.
     */
    public double degreesOff() {
        return degreesOff;
    }

    /** How fast it was going when it landed, blocks a second. */
    public double speed() {
        return speed;
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
