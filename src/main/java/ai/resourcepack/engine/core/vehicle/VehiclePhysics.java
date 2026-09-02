package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;

/**
 * How a vehicle moves, as arithmetic.
 *
 * <p><strong>Pure, and deliberately so.</strong> Nothing here touches Bukkit,
 * reads a block or knows what a world is: it takes where the vehicle is going,
 * what the driver is asking for and what is around it, and returns the next
 * state and the displacement to try. Everything about the WORLD — is there
 * ground under it, is it in water, did it hit a wall — is the runtime's job
 * and arrives as {@link Surroundings}.
 *
 * <p>That split is what makes any of this testable. A vehicle that accelerates
 * wrongly, coasts for ever or sinks through the floor is a defect in a few
 * lines of arithmetic, and the alternative — finding it by driving a car around
 * a test server — is how these systems end up with numbers nobody dares touch.
 *
 * <p>The one thing to hold in mind when changing a constant here: the driver
 * cannot feel any of it directly. Their throttle is a server tick behind
 * whatever they pressed, so what they judge the vehicle by is the CURVE, not
 * the moment. A vehicle that reaches its speed in two ticks feels broken even
 * though it is doing exactly what it was asked.
 */
public final class VehiclePhysics {

    /**
     * Downward acceleration, blocks per second squared.
     *
     * <p>Gentler than vanilla's (about 32 for a falling player) on purpose: a
     * car dropping off a kerb at vanilla gravity slams into the ground hard
     * enough to read as a bug, and nothing here is trying to simulate a fall.
     */
    public static final double GRAVITY = 28;

    /** As fast as anything falls, blocks per second. */
    public static final double TERMINAL_FALL = 30;

    /**
     * How much of its top speed a vehicle does in reverse.
     *
     * <p>Named rather than folded into the maths because it is the kind of
     * number somebody will want to change, and a bare 0.4 in an expression is
     * the kind of number nobody can.
     */
    public static final double REVERSE_FRACTION = 0.4;

    /** Braking is this much harder than accelerating. */
    public static final double BRAKE_MULTIPLIER = 2.5;

    /** With no throttle at all, a vehicle sheds speed at this fraction of its acceleration. */
    public static final double COAST_FRACTION = 0.35;

    /**
     * The weight a vehicle's stated acceleration is quoted at.
     *
     * <p>{@code weight} scales acceleration and braking around this, so a
     * vehicle at 10 gets exactly the acceleration its pack asked for and the
     * number means something on its own.
     */
    public static final double NOMINAL_WEIGHT = 10;

    /** How hard water pushes a submerged hull back up, blocks per second squared. */
    public static final double BUOYANCY = 26;

    /**
     * What fraction of vertical speed survives a tick in water.
     *
     * <p>Without damping, buoyancy is a spring: a boat dropped in bobs for
     * ever and looks like it is glitching rather than floating.
     */
    public static final double WATER_DAMPING = 0.75;

    /** How fast an air vehicle climbs on the jump key, as a fraction of its top speed. */
    public static final double AIR_CLIMB_FRACTION = 0.5;

    private VehiclePhysics() {
    }

    /**
     * One tick.
     *
     * @param dt seconds this step covers, so the constants above can be quoted
     *           per second rather than per tick
     */
    public static Step step(VehicleInfo info, State state, Demand demand, Surroundings around, double dt) {
        double yaw = turnToward(state.yaw(), demand.yaw(), info.turnSpeed() * dt);

        double heaviness = Math.max(0.1, info.weight() / NOMINAL_WEIGHT);
        double accel = info.acceleration() / heaviness;

        double top = info.speed();
        double target = demand.throttle() >= 0
                ? demand.throttle() * top
                : demand.throttle() * top * REVERSE_FRACTION;

        // Braking beats the throttle rather than being averaged with it: a
        // driver holding both is asking to stop, and half of each would be a
        // vehicle that neither accelerates nor stops.
        double rate = demand.braking()
                ? accel * BRAKE_MULTIPLIER
                : demand.throttle() == 0 ? accel * COAST_FRACTION : accel;
        if (demand.braking()) {
            target = 0;
        }
        double speed = approach(state.speed(), target, rate * dt);

        double vertical = state.verticalSpeed();
        double climb = 0;
        switch (info.medium()) {
            case AIR:
                // Vanilla's own answer for a flying multi-seat vehicle, and
                // worth copying rather than inventing: forward goes where the
                // driver is LOOKING, and jump goes straight up. No stall, no
                // airspeed, no lift curve — see VehicleMedium.AIR.
                climb = speed * Math.sin(Math.toRadians(-demand.pitch()))
                        + demand.lift() * top * AIR_CLIMB_FRACTION;
                vertical = 0;
                break;
            case WATER:
                if (around.inWater()) {
                    // Toward the surface, damped. A hull pushed under rises,
                    // which is what makes going over a waterfall look right
                    // instead of leaving the boat at the height it entered.
                    vertical = (vertical + BUOYANCY * around.submersion() * dt) * WATER_DAMPING;
                } else if (around.supported()) {
                    vertical = 0;
                } else {
                    vertical = fall(vertical, dt);
                }
                break;
            case LAND:
            default:
                vertical = around.supported() ? 0 : fall(vertical, dt);
                break;
        }

        // The horizontal component shrinks as the nose comes up, so a climbing
        // aircraft covers less ground rather than the same ground plus a
        // vertical bonus — the alternative reads as the vehicle speeding up
        // whenever you look at the sky.
        double planar = info.medium() == VehicleMedium.AIR
                ? speed * Math.cos(Math.toRadians(demand.pitch()))
                : speed;

        double radians = Math.toRadians(yaw);
        // Minecraft's yaw: 0 faces +z (south) and increases clockwise, which
        // puts the forward vector at (-sin, cos). Getting this pair wrong is
        // a vehicle that drives sideways, and it is the single easiest thing
        // in the file to get wrong, so it is written out rather than inlined.
        double dx = -Math.sin(radians) * planar * dt;
        double dz = Math.cos(radians) * planar * dt;
        double dy = (info.medium() == VehicleMedium.AIR ? climb : vertical) * dt;

        return new Step(new State(yaw, speed, vertical), dx, dy, dz);
    }

    private static double fall(double vertical, double dt) {
        return Math.max(-TERMINAL_FALL, vertical - GRAVITY * dt);
    }

    /** Moves {@code from} toward {@code to} by at most {@code step}, never past it. */
    static double approach(double from, double to, double step) {
        if (from < to) {
            return Math.min(to, from + Math.abs(step));
        }
        return Math.max(to, from - Math.abs(step));
    }

    /**
     * Turns {@code from} toward {@code to} the short way round, by at most
     * {@code step} degrees.
     *
     * <p>The short way matters: without it, a driver flicking their view from
     * 350 to 10 sends the vehicle 340 degrees the other way, which at a
     * realistic turn speed is a car spinning on the spot for two seconds.
     */
    static double turnToward(double from, double to, double step) {
        double delta = wrap180(to - from);
        if (Math.abs(delta) <= step) {
            return wrap360(to);
        }
        return wrap360(from + Math.signum(delta) * step);
    }

    /**
     * To (-180, 180].
     *
     * <p><strong>Half-open at the negative end on purpose.</strong> The
     * obvious spelling — {@code ((d + 180) mod 360) - 180} — maps exactly 180
     * to MINUS 180, so a driver asking for a U-turn gets one anticlockwise
     * while every other turn to their right goes clockwise. Both directions
     * are equally short at 180, so either is defensible and neither is
     * discoverable; what is not defensible is the answer flipping at a
     * boundary nobody can see. This way a U-turn is always to the right.
     */
    static double wrap180(double degrees) {
        double wrapped = wrap360(degrees);
        return wrapped > 180 ? wrapped - 360 : wrapped;
    }

    /** To [0, 360). */
    static double wrap360(double degrees) {
        double wrapped = degrees % 360;
        return wrapped < 0 ? wrapped + 360 : wrapped;
    }

    /** Where the vehicle is going, between ticks. */
    public static final class State {

        private final double yaw;
        private final double speed;
        private final double verticalSpeed;

        public State(double yaw, double speed, double verticalSpeed) {
            this.yaw = yaw;
            this.speed = speed;
            this.verticalSpeed = verticalSpeed;
        }

        /** A vehicle standing still, facing {@code yaw}. */
        public static State still(double yaw) {
            return new State(wrap360(yaw), 0, 0);
        }

        /** Which way the body points, degrees. */
        public double yaw() {
            return yaw;
        }

        /** Along its own heading, blocks per second. Negative is reverse. */
        public double speed() {
            return speed;
        }

        /** Blocks per second, positive up. */
        public double verticalSpeed() {
            return verticalSpeed;
        }

        /**
         * The same, stopped dead.
         *
         * <p>What the runtime applies when the move it tried was refused by a
         * wall: the maths cannot know that happened, and a vehicle that kept
         * its speed against a wall would shoot off the moment the driver
         * turned away from it.
         */
        public State stopped() {
            return new State(yaw, 0, verticalSpeed);
        }

        /** The same, no longer falling — what landing looks like. */
        public State landed() {
            return new State(yaw, speed, 0);
        }
    }

    /** What the driver is asking for this tick. */
    public static final class Demand {

        private final double yaw;
        private final double pitch;
        private final double throttle;
        private final double lift;
        private final boolean braking;

        public Demand(double yaw, double pitch, double throttle, double lift, boolean braking) {
            this.yaw = yaw;
            this.pitch = pitch;
            // Clamped here rather than trusted, because both arms of the
            // control layer build these and one of them is reading a packet.
            this.throttle = clamp(throttle);
            this.lift = clamp(lift);
            this.braking = braking;
        }

        /** A vehicle nobody is driving: it keeps its heading and coasts to a stop. */
        public static Demand idle(double yaw) {
            return new Demand(yaw, 0, 0, 0, false);
        }

        private static double clamp(double value) {
            if (!Double.isFinite(value)) {
                return 0;
            }
            return Math.max(-1, Math.min(1, value));
        }

        /** Where the driver is looking, which IS the steering. */
        public double yaw() {
            return yaw;
        }

        /** Up or down, degrees. Only an air vehicle reads it. */
        public double pitch() {
            return pitch;
        }

        /** -1 full reverse to 1 full ahead. */
        public double throttle() {
            return throttle;
        }

        /** -1 down to 1 up. Only an air vehicle reads it. */
        public double lift() {
            return lift;
        }

        /** Whether they are asking to stop, which beats the throttle. */
        public boolean braking() {
            return braking;
        }
    }

    /** What the world is doing around the vehicle, read by the runtime. */
    public static final class Surroundings {

        private final boolean supported;
        private final boolean inWater;
        private final double submersion;

        public Surroundings(boolean supported, boolean inWater, double submersion) {
            this.supported = supported;
            this.inWater = inWater;
            this.submersion = submersion;
        }

        /** Nothing under it and nothing holding it up. */
        public static Surroundings falling() {
            return new Surroundings(false, false, 0);
        }

        /** Whether something solid is holding it up. */
        public boolean supported() {
            return supported;
        }

        /** Whether its base is in water. */
        public boolean inWater() {
            return inWater;
        }

        /**
         * How far below the surface the base is, in blocks, clamped to a
         * block either way.
         *
         * <p>Signed, so a hull riding slightly proud of the water is pulled
         * back down by the same term that pushes a submerged one up — which is
         * what makes the resting position the surface rather than somewhere
         * above it.
         */
        public double submersion() {
            return submersion;
        }
    }

    /** The next state, and the move to try. */
    public static final class Step {

        private final State state;
        private final double dx;
        private final double dy;
        private final double dz;

        Step(State state, double dx, double dy, double dz) {
            this.state = state;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        /** Where the vehicle is going now. */
        public State state() {
            return state;
        }

        /** East, in blocks, this tick. */
        public double dx() {
            return dx;
        }

        /** Up, in blocks, this tick. */
        public double dy() {
            return dy;
        }

        /** South, in blocks, this tick. */
        public double dz() {
            return dz;
        }

        /** Whether this step asks the vehicle to move at all. */
        public boolean moves() {
            return dx != 0 || dy != 0 || dz != 0;
        }
    }
}
