package ai.resourcepack.engine.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * What is mechanically wrong with a vehicle, as the handling model reads it.
 *
 * <p>Hand one to {@link Vehicle#setDamage}. The engine folds it into the
 * ordinary physics — the same tyre grip, the same steering, the same springs
 * that every vehicle already runs on — rather than into an animation or a
 * second model bolted alongside. A vehicle limping on three wheels is limping
 * because its handling model was told it has three wheels, so it leans on the
 * missing corner, drags round toward it, understeers where the tyre is gone and
 * loses the speed to the drag; none of that is drawn on top.
 *
 * <p><strong>{@link #NONE} is the vehicle exactly as its pack defines it</strong>,
 * and is what every vehicle has until something says otherwise. It is not
 * "undamaged enough to ignore" — the physics takes a different path for it, so
 * a server running no plugin that sets damage drives identically to one built
 * before this existed.
 *
 * <h2>What this is not</h2>
 *
 * <p>It is not health, and the engine has no opinion about how much punishment
 * a vehicle can take, what a crash costs it, or whether any of this is
 * remembered. That is a plugin's model to build and a plugin's to persist —
 * {@link Vehicle#data()} is where it goes. This is only the bottom of that:
 * the vocabulary for telling the handling model what state the machine is in.
 * Like {@link Vehicle#setSpeedLimit} and unlike {@link Vehicle#setEnabled}, it
 * is <strong>not remembered</strong>: a vehicle found again after a chunk
 * unload is sound until its plugin says otherwise, which it should do on the
 * tick it adopts it.
 *
 * <p>It is also not {@link Vehicle#setHandling}, which scales what the vehicle
 * may do as a fraction of its own figures and is the right tool for a speed
 * zone or a governor. The two compose; this one is asymmetric and the other is
 * not, which is the whole difference. A vehicle with a bad left-front tyre
 * pulls left. A vehicle with {@code setHandling(0.6, 0.6)} drives straight,
 * slowly.
 *
 * <h2>Building one</h2>
 *
 * <pre>{@code
 * VehicleDamage damage = VehicleDamage.builder()
 *         .wheel(VehicleCorner.FRONT_LEFT, 0)     // torn off
 *         .wheel(VehicleCorner.REAR_LEFT, 0.45)   // buckled
 *         .enginePower(0.7)
 *         .steeringPull(-3)
 *         .build();
 * vehicle.setDamage(damage);
 * }</pre>
 *
 * <p>Immutable, so one can be kept, compared and handed to more than one
 * vehicle. {@link #toBuilder()} is how a plugin changes one thing about it.
 * Every value is clamped on the way in rather than trusted — these come from
 * whatever arithmetic a plugin does to a collision, and a crash that produced
 * a NaN should cost a tyre rather than the vehicle's position.
 */
public final class VehicleDamage {

    /** As far as the steering may be pulled off centre, degrees either way. */
    public static final double MAX_STEERING_PULL = 12;

    /** As much extra retardation as damage may add, blocks per second squared. */
    public static final double MAX_DRAG = 20;

    /**
     * The condition below which the engine treats a wheel as gone rather than
     * bad, in the one place the distinction is a step rather than a slope: a
     * corner this far down has nothing holding it up and the body rests on it.
     */
    public static final double COLLAPSED = 0.05;

    /** A sound vehicle. The default, and the fast path through the physics. */
    public static final VehicleDamage NONE = new VehicleDamage(new double[] {1, 1, 1, 1}, 0, 0, 1, 0);

    private final double[] wheels;
    private final double steeringPull;
    private final double steeringSlack;
    private final double enginePower;
    private final double drag;
    private final boolean none;

    private VehicleDamage(double[] wheels, double steeringPull, double steeringSlack,
                          double enginePower, double drag) {
        this.wheels = wheels;
        this.steeringPull = steeringPull;
        this.steeringSlack = steeringSlack;
        this.enginePower = enginePower;
        this.drag = drag;
        this.none = wheels[0] == 1 && wheels[1] == 1 && wheels[2] == 1 && wheels[3] == 1
                && steeringPull == 0 && steeringSlack == 0 && enginePower == 1 && drag == 0;
    }

    public static Builder builder() {
        return new Builder(NONE);
    }

    /** A builder holding everything this one says, for changing one part of it. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * How sound the wheel at {@code corner} is: 1 for a good one, 0 for one
     * that is gone.
     *
     * <p>A continuous number rather than a set of named states, because the
     * handling model wants to scale things by it and because where a plugin
     * draws the line between "damaged" and "critical" is the plugin's business.
     * Most of the way down is a wheel that still holds the corner up and grips
     * badly; at {@link #COLLAPSED} and below the body sits on that corner.
     */
    public double wheel(VehicleCorner corner) {
        return corner == null ? 1 : wheels[corner.ordinal()];
    }

    /**
     * How far the steering sits off centre with the wheel straight, in degrees,
     * positive to the right. Bent steering, a knocked-out alignment.
     *
     * <p>Added to the steering angle rather than to the heading, so the driver
     * can hold against it and it costs them lock on one side — which is what
     * driving a car with the tracking out is actually like.
     */
    public double steeringPull() {
        return steeringPull;
    }

    /**
     * How much of the steering's precision is gone, 0 for none and 1 for all
     * of it: a loose column that answers late and does not answer fully.
     */
    public double steeringSlack() {
        return steeringSlack;
    }

    /** What fraction of its acceleration the engine still makes. 1 is a healthy one. */
    public double enginePower() {
        return enginePower;
    }

    /**
     * Extra retardation on the ground, blocks per second squared — something
     * bent rubbing on something turning. Nothing in the air: a dragging axle
     * needs a road to drag on.
     */
    public double drag() {
        return drag;
    }

    /** Whether this says anything at all is wrong. */
    public boolean none() {
        return none;
    }

    /** Whether the wheel at {@code corner} has stopped holding that corner up. */
    public boolean collapsed(VehicleCorner corner) {
        return wheel(corner) <= COLLAPSED;
    }

    /** How many of the four corners are no longer holding the vehicle up. */
    public int collapsedCorners() {
        int gone = 0;
        for (VehicleCorner corner : VehicleCorner.values()) {
            if (collapsed(corner)) {
                gone++;
            }
        }
        return gone;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VehicleDamage)) {
            return false;
        }
        VehicleDamage that = (VehicleDamage) other;
        return Arrays.equals(wheels, that.wheels)
                && steeringPull == that.steeringPull
                && steeringSlack == that.steeringSlack
                && enginePower == that.enginePower
                && drag == that.drag;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(wheels), steeringPull, steeringSlack, enginePower, drag);
    }

    @Override
    public String toString() {
        if (none) {
            return "VehicleDamage.NONE";
        }
        return "VehicleDamage[wheels=" + Arrays.toString(wheels)
                + ", steeringPull=" + steeringPull
                + ", steeringSlack=" + steeringSlack
                + ", enginePower=" + enginePower
                + ", drag=" + drag + "]";
    }

    /** Builds a {@link VehicleDamage}. Obtained from {@link VehicleDamage#builder()}. */
    public static final class Builder {

        private final double[] wheels;
        private double steeringPull;
        private double steeringSlack;
        private double enginePower;
        private double drag;

        private Builder(VehicleDamage from) {
            this.wheels = from.wheels.clone();
            this.steeringPull = from.steeringPull;
            this.steeringSlack = from.steeringSlack;
            this.enginePower = from.enginePower;
            this.drag = from.drag;
        }

        /** @param condition 1 for a sound wheel, 0 for one that is gone */
        public Builder wheel(VehicleCorner corner, double condition) {
            if (corner != null) {
                wheels[corner.ordinal()] = clamp(condition, 0, 1, 1);
            }
            return this;
        }

        /** Every wheel at once. */
        public Builder wheels(double condition) {
            for (VehicleCorner corner : VehicleCorner.values()) {
                wheel(corner, condition);
            }
            return this;
        }

        /** @param degrees positive pulls right, bounded by {@link #MAX_STEERING_PULL} */
        public Builder steeringPull(double degrees) {
            this.steeringPull = clamp(degrees, -MAX_STEERING_PULL, MAX_STEERING_PULL, 0);
            return this;
        }

        /** @param slack 0 for precise steering, 1 for as vague as it goes */
        public Builder steeringSlack(double slack) {
            this.steeringSlack = clamp(slack, 0, 1, 0);
            return this;
        }

        /** @param fraction of its acceleration the engine still makes, (0, 1] */
        public Builder enginePower(double fraction) {
            this.enginePower = clamp(fraction, 0.05, 1, 1);
            return this;
        }

        /** @param blocksPerSecondSquared of extra retardation on the ground */
        public Builder drag(double blocksPerSecondSquared) {
            this.drag = clamp(blocksPerSecondSquared, 0, MAX_DRAG, 0);
            return this;
        }

        public VehicleDamage build() {
            VehicleDamage built = new VehicleDamage(wheels.clone(), steeringPull, steeringSlack,
                    enginePower, drag);
            // So the fast path is reference-comparable and a plugin re-asserting
            // "nothing is wrong" every tick allocates nothing.
            return built.none() ? NONE : built;
        }

        private static double clamp(double value, double min, double max, double fallback) {
            if (!Double.isFinite(value)) {
                return fallback;
            }
            return Math.max(min, Math.min(max, value));
        }
    }
}
