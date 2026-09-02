package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleState;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

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

    /**
     * Below this forward speed the back key stops braking and starts
     * reversing, in blocks per second.
     *
     * <p>Not zero, because {@code approach} lands exactly on zero only if a
     * tick's step happens to divide the remaining speed — so a threshold of
     * zero is a vehicle that brakes to a crawl and sits there refusing to
     * reverse. Half a block a second is slow enough that the changeover is
     * indistinguishable from stationary.
     */
    public static final double REVERSE_THRESHOLD = 0.5;

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

    /**
     * What fraction of its top speed a water vehicle does out of water.
     *
     * <p><strong>A boat used to drive on grass exactly as fast as it sailed</strong>,
     * because the medium decided only the VERTICAL rule — buoyancy against
     * gravity — and nothing ever asked whether a hull had anything to push
     * against. {@link VehicleMedium#WATER} has said "dead weight on land" since
     * it was written; this is the line that makes it true.
     *
     * <p>Not zero, and that is the whole of the number. A boat that cannot move
     * at all on land is one that beaches itself on the first shore and is stuck
     * there for ever — the driver has no way back to the water, and a vehicle
     * that can be permanently lost by driving it is a trap rather than a rule.
     * At 0.15 of top speed a hull drags over sand a shade slower than a walking
     * player: unmistakably wrong, obviously deliberate, and recoverable.
     *
     * <p>Steering is deliberately NOT reduced with it. A beached boat that
     * could crawl but not turn would be pointed away from the water as often
     * as toward it.
     */
    public static final double BEACHED_FRACTION = 0.15;

    /**
     * Below this speed a vehicle counts as {@link VehicleState#IDLE} rather
     * than moving, in blocks per second.
     *
     * <p>Its own constant rather than {@link #REVERSE_THRESHOLD}, though they
     * happen to agree today: one is about when a gearbox changes direction and
     * the other about when an animation changes, and tying them together would
     * make tuning either move the other for no reason anybody could see.
     */
    public static final double MOVING_THRESHOLD = 0.5;

    /**
     * Above this rate of turn a vehicle counts as {@link VehicleState#TURNING},
     * in degrees per second.
     *
     * <p>Measured against how fast the body ACTUALLY came round this tick, not
     * against what the driver asked for — so a vehicle held against a wall is
     * not turning, and one at its {@code turn-speed} clamp is. Fifteen degrees
     * a second is a slow, deliberate corner; the drift a look-steered vehicle
     * shows while driving straight is well under it.
     */
    public static final double TURNING_THRESHOLD = 15;

    private VehiclePhysics() {
    }

    /**
     * One tick.
     *
     * @param dt seconds this step covers, so the constants above can be quoted
     *           per second rather than per tick
     */
    public static Step step(VehicleInfo info, State state, Demand demand, Surroundings around, double dt) {
        // Two ways to steer, and which one a server gets is the same fork as
        // the throttle. Keys turn the body directly and leave the driver's
        // head alone; look-steering turns the body toward wherever they are
        // looking, which means steering IS turning your head - and a player's
        // body follows their head, so the driver visibly swings round on every
        // corner. That is the cost the key arm exists to remove.
        double yaw = demand.steersByKeys()
                ? wrap360(state.yaw() + demand.steer() * info.turnSpeed() * dt)
                : turnToward(state.yaw(), demand.yaw(), info.turnSpeed() * dt);

        boolean air = info.medium() == VehicleMedium.AIR;
        double throttle = demand.throttle();
        double lift = demand.lift();

        // <strong>An aircraft in the air descends on the back key rather than
        // reversing.</strong> There is nothing to reverse against up there, and
        // an aeroplane that flew backwards on S would be the only vehicle here
        // that did something no real one does. On the GROUND it reverses like
        // anything else, which is what taxiing is.
        //
        // Space wins if both are held: asking to climb and to descend at once
        // is asking to climb, and the alternative is a cancellation nobody can
        // see the cause of.
        if (air && !around.supported() && throttle < 0) {
            if (lift == 0) {
                lift = throttle;
            }
            throttle = 0;
        }

        double heaviness = Math.max(0.1, info.weight() / NOMINAL_WEIGHT);
        double accel = info.acceleration() / heaviness;

        // A hull out of water drags rather than sails. Applied to the top speed
        // rather than to the throttle so that braking, reversing and the coast
        // rate all scale with it for free — a beached boat that stopped like a
        // sailing one would slide the length of the beach.
        double top = beached(info, around) ? info.speed() * BEACHED_FRACTION : info.speed();
        double target = throttle >= 0
                ? throttle * top
                : throttle * top * REVERSE_FRACTION;

        // <strong>The back key brakes before it reverses.</strong> Holding it at
        // speed used to aim straight at the reverse target, so a vehicle doing
        // 20 forward crawled down through zero at ordinary acceleration and
        // then kept going — which reads as a car that will not stop rather
        // than one changing direction. Now it stops the way a brake does and
        // only engages reverse once it is actually stationary, which is also
        // what a real gearbox makes you do.
        boolean stopping = throttle < 0 && state.speed() > REVERSE_THRESHOLD;
        if (stopping) {
            target = 0;
        }

        // Braking beats the throttle rather than being averaged with it: a
        // driver holding both is asking to stop, and half of each would be a
        // vehicle that neither accelerates nor stops.
        double rate = demand.braking() || stopping
                ? accel * BRAKE_MULTIPLIER
                : throttle == 0 ? accel * COAST_FRACTION : accel;
        if (demand.braking()) {
            target = 0;
        }
        double speed = approach(state.speed(), target, rate * dt);

        double vertical = state.verticalSpeed();
        double climb = 0;
        switch (info.medium()) {
            case AIR:
                // <strong>Height is space and the back key, not the driver's
                // pitch — where the keys can be read.</strong> Vanilla's own
                // flying vehicle climbs by looking up, because it has no other
                // control to spare; a server that can read a key has one, and
                // steering by look was already costing the driver their head.
                // Tying the climb to it as well would mean glancing at the
                // scenery puts the aircraft into a dive.
                //
                // Where the keys CANNOT be read there is nothing else to use,
                // so that arm keeps vanilla's answer. `steersByKeys` stands in
                // for "this server can read the driver's keys at all", which
                // is the same question by the time it reaches here.
                climb = (demand.steersByKeys()
                                ? 0
                                : speed * Math.sin(Math.toRadians(-demand.pitch())))
                        + lift * top * AIR_CLIMB_FRACTION;
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
        // whenever you look at the sky. Only on the arm where the pitch is
        // still flying the thing: where space and the back key do the climbing,
        // the driver's head has nothing to do with how far the aircraft gets.
        double planar = air && !demand.steersByKeys()
                ? speed * Math.cos(Math.toRadians(demand.pitch()))
                : speed;

        double radians = Math.toRadians(yaw);
        // Minecraft's yaw: 0 faces +z (south) and increases clockwise, which
        // puts the forward vector at (-sin, cos). Getting this pair wrong is
        // a vehicle that drives sideways, and it is the single easiest thing
        // in the file to get wrong, so it is written out rather than inlined.
        double dx = -Math.sin(radians) * planar * dt;
        double dz = Math.cos(radians) * planar * dt;
        double dy = (air ? climb : vertical) * dt;

        return new Step(new State(yaw, speed, vertical), dx, dy, dz,
                states(info, state.yaw(), yaw, speed, around, dt));
    }

    /**
     * Whether a water vehicle is out of its element.
     *
     * <p>Only ever true for {@link VehicleMedium#WATER} — a car is not
     * "beached" for being on a road, and a submarine that flooded is not a
     * different kind of vehicle.
     */
    public static boolean beached(VehicleInfo info, Surroundings around) {
        return info.medium() == VehicleMedium.WATER && !around.inWater();
    }

    /**
     * Every {@link VehicleState} the vehicle is in this tick.
     *
     * <p>Pure, and here rather than in the runtime, for the reason the whole
     * class exists: whether a vehicle counts as moving is arithmetic over its
     * speed, and a rule that lives in the runtime is one that can only be
     * checked by driving a car around a test server. The states drive both the
     * animation a rig plays and which particle emitters fire, so being subtly
     * wrong about one is visible in two places at once.
     *
     * <p>Several are true at once and that is the point — see
     * {@link VehicleState}.
     *
     * @param wasYaw where the body pointed before this step, so TURNING is
     *               measured against what actually happened rather than
     *               against what the driver asked for
     */
    static Set<VehicleState> states(VehicleInfo info, double wasYaw, double yaw, double speed,
                                    Surroundings around, double dt) {
        Set<VehicleState> active = EnumSet.noneOf(VehicleState.class);

        if (speed > MOVING_THRESHOLD) {
            active.add(VehicleState.MOVING);
        } else if (speed < -MOVING_THRESHOLD) {
            active.add(VehicleState.REVERSING);
        } else {
            active.add(VehicleState.IDLE);
        }

        // The SHORT way round, or a vehicle crossing north from 359 to 1
        // reports a 358-degree turn and every wheel on it spins the wrong way
        // once a lap. Divided by dt so the threshold can be quoted per second
        // like every other constant here.
        if (dt > 0 && Math.abs(wrap180(yaw - wasYaw)) / dt >= TURNING_THRESHOLD) {
            active.add(VehicleState.TURNING);
        }

        if (around.inWater()) {
            active.add(VehicleState.SUBMERGED);
        }

        // An air vehicle is airborne whenever it is off the ground, which is
        // most of its life; a land or water one only when it has left both the
        // ground and the water, which is a jump or a fall. Water counts as
        // support here even though it is not solid: a boat riding the surface
        // is doing its job, not falling.
        if (!around.supported() && !around.inWater()) {
            active.add(VehicleState.AIRBORNE);
        }

        return active;
    }

    /**
     * Where a seat sits relative to the vehicle, in world x and z.
     *
     * <p>Pure, and here rather than inside the runtime, because it is the same
     * class of thing as the forward vector above: two sines and a sign, and
     * being wrong puts the driver in the passenger's lap with nothing in a log
     * to say so. The forward vector was tested from the start and this was not,
     * and this is the one that was wrong — {@code right} was pointing left.
     *
     * <p>The frame, stated once so it can be checked against the tests:
     * Minecraft yaw 0 faces <strong>south</strong>, {@code +z}. Somebody
     * facing south has <strong>west</strong> on their right, which is
     * {@code -x}. So forward is {@code (-sin, cos)} and right is
     * {@code (-cos, -sin)} — right is NOT forward's components swapped, which
     * is the shape that looks right and puts everybody on the wrong side.
     *
     * @param right   blocks to the vehicle's right; negative is left
     * @param forward blocks in front of it; negative is behind
     * @return the world offset as {@code {dx, dz}}
     */
    public static double[] seatOffset(double yaw, double right, double forward) {
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new double[] {
            right * -cos + forward * -sin,
            right * -sin + forward * cos,
        };
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
        private final double steer;
        private final boolean steersByKeys;

        /** Steering by look: the body turns toward wherever the driver faces. */
        public Demand(double yaw, double pitch, double throttle, double lift, boolean braking) {
            this(yaw, pitch, throttle, lift, braking, 0, false);
        }

        /**
         * Steering by key: {@code steer} runs -1 (left) to 1 (right) and the
         * driver's look is left out of it entirely.
         *
         * <p>The PITCH is still theirs, because an air vehicle climbs by
         * looking up and that is a different control from turning.
         */
        public static Demand steering(double yaw, double pitch, double steer,
                                      double throttle, double lift, boolean braking) {
            return new Demand(yaw, pitch, throttle, lift, braking, steer, true);
        }

        private Demand(double yaw, double pitch, double throttle, double lift, boolean braking,
                       double steer, boolean steersByKeys) {
            this.yaw = yaw;
            this.pitch = pitch;
            // Clamped here rather than trusted, because both arms of the
            // control layer build these and one of them is reading a packet.
            this.throttle = clamp(throttle);
            this.lift = clamp(lift);
            this.braking = braking;
            this.steer = clamp(steer);
            this.steersByKeys = steersByKeys;
        }

        /** -1 hard left to 1 hard right. Only read when {@link #steersByKeys}. */
        public double steer() {
            return steer;
        }

        /** Whether the body turns from a key rather than from the driver's look. */
        public boolean steersByKeys() {
            return steersByKeys;
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
        private final Set<VehicleState> states;

        Step(State state, double dx, double dy, double dz, Set<VehicleState> states) {
            this.state = state;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.states = states == null
                    ? Collections.<VehicleState>emptySet()
                    : Collections.unmodifiableSet(states);
        }

        /** Where the vehicle is going now. */
        public State state() {
            return state;
        }

        /**
         * What the vehicle is doing, which decides what it plays and what it
         * throws.
         *
         * <p>Several at once — see {@link VehicleState}. Computed here rather
         * than by the runtime because every input it needs is already in hand,
         * and because a rule about when a vehicle counts as moving is one that
         * should be checkable without a server.
         */
        public Set<VehicleState> states() {
            return states;
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
