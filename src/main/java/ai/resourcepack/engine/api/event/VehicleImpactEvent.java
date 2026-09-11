package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleImpactArea;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.util.Vector;

/**
 * Fired once when the authoritative vehicle runtime resolves a meaningful
 * collision.
 *
 * <p>One event represents one collision. For vehicle-to-vehicle contact it
 * carries both outcomes, so a listener must not infer a second hit or apply
 * the same damage twice. Resting overlaps and contacts already moving apart do
 * not fire.
 */
public final class VehicleImpactEvent extends Event {

    public enum Cause { VEHICLE, WORLD, LANDING }

    /** What the solver measured for one participant. */
    public static final class Outcome {
        private final Vehicle vehicle;
        private final Vector velocity;
        private final Vector velocityChange;
        private final Vector normal;
        private final double closingSpeed;
        private final double impulse;
        private final double spinChange;
        private final VehicleImpactArea area;

        public Outcome(Vehicle vehicle, Vector velocity, Vector velocityChange, Vector normal,
                       double closingSpeed, double impulse, double spinChange, VehicleImpactArea area) {
            this.vehicle = vehicle;
            this.velocity = finite(velocity);
            this.velocityChange = finite(velocityChange);
            this.normal = finite(normal);
            this.closingSpeed = finite(closingSpeed);
            this.impulse = finite(impulse);
            this.spinChange = finite(spinChange);
            this.area = area == null ? VehicleImpactArea.UNKNOWN : area;
        }

        public Vehicle vehicle() { return vehicle; }
        /** Velocity immediately before the collision, blocks per second. */
        public Vector velocity() { return velocity.clone(); }
        /** What the solver added to that velocity. */
        public Vector velocityChange() { return velocityChange.clone(); }
        /** Unit direction from this vehicle toward what it hit. */
        public Vector normal() { return normal.clone(); }
        public double closingSpeed() { return closingSpeed; }
        public double impulse() { return impulse; }
        public double spinChange() { return spinChange; }
        public VehicleImpactArea area() { return area; }

        /** A stable damage input: actual closing speed and actual delta-v, whichever is larger. */
        public double severity() {
            return Math.max(closingSpeed, velocityChange.length());
        }

        private static Vector finite(Vector value) {
            if (value == null || !Double.isFinite(value.getX()) || !Double.isFinite(value.getY())
                    || !Double.isFinite(value.getZ())) return new Vector();
            return value.clone();
        }

        private static double finite(double value) {
            return Double.isFinite(value) ? value : 0;
        }
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Cause cause;
    private final Location point;
    private final Outcome first;
    private final Outcome second;

    public VehicleImpactEvent(Cause cause, Location point, Outcome first, Outcome second) {
        this.cause = cause == null ? Cause.WORLD : cause;
        this.point = point == null ? null : point.clone();
        this.first = first;
        this.second = second;
    }

    public Cause cause() { return cause; }
    public Location point() { return point == null ? null : point.clone(); }
    public Outcome first() { return first; }
    /** The other vehicle's outcome, or null for world/landing impacts. */
    public Outcome second() { return second; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
