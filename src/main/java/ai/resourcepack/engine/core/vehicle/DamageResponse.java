package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.VehicleCorner;
import ai.resourcepack.engine.api.VehicleDamage;

/**
 * What a {@link VehicleDamage} does to the handling, as arithmetic.
 *
 * <p><strong>Pure, on the same terms as {@link VehiclePhysics}</strong>: four
 * wheel conditions and four numbers in, a handful of factors out, nothing
 * touched and nothing remembered. It sits between the api and the physics
 * because the two want different things from it — a plugin describes a
 * MACHINE ("the front left wheel is gone"), the handling model wants
 * COEFFICIENTS ("multiply the front tyre limit by 0.25, lean 8 degrees left,
 * lose 3 blocks a second of speed") — and the translation between them is
 * engine policy that should be free to move without changing what a plugin
 * says.
 *
 * <p>Which is why it is here and not in {@code api}. Everything in this class
 * is tuning, and tuning changes in patch releases.
 *
 * <h2>The one rule</h2>
 *
 * <p><strong>{@link #NONE} must be exactly neutral</strong> — every factor
 * precisely 1 or precisely 0, and {@link #any()} false so the physics can skip
 * the additive terms altogether. A vehicle nothing has damaged has to drive
 * bit-for-bit as it did before any of this existed, and
 * {@code PhysicsGoldenTest} is what holds this to it.
 *
 * <h2>Why damage is not one number</h2>
 *
 * <p>A single "condition" scalar can only make a vehicle worse symmetrically,
 * and nothing that happens to a vehicle in a crash is symmetric. The four
 * corners are what make the difference between a car that is slow and a car
 * that is BROKEN: the front pair decide whether it will turn, the rear pair
 * decide whether it will hold, and the left/right difference between them is
 * what makes it pull. Each of those reaches the physics at a different place,
 * and they are separate methods here for that reason rather than for tidiness.
 */
final class DamageResponse {

    /**
     * How far a corner with nothing holding it up leans the body, in degrees.
     *
     * <p>Not the geometric answer. A wheel is most of half a metre and the
     * track is under two, so a corner resting on its hub is really twenty-odd
     * degrees over — which, on a model the size of a car, looks like the
     * vehicle has been thrown rather than damaged, and puts the driver's head
     * through the roof. This is the angle that reads as a broken axle.
     */
    private static final double CORNER_LEAN = 8;

    /** How far the whole body settles when every corner has gone, in blocks. */
    private static final double CORNER_DROP = 0.22;

    /**
     * How hard a dragging REAR corner pulls the vehicle round, degrees per
     * second at speed.
     *
     * <p>Rear only. A dragging front corner is steering — it goes through
     * {@link #steerBias()}, where the driver can fight it — and a rear one is a
     * yaw the wheels have no answer to, which is exactly the difference between
     * a car that wanders and a car that spins.
     */
    private static final double REAR_YAW_PULL = 9;

    /** Above this speed a dragging corner pulls with its full authority. */
    private static final double PULL_SPEED = 6;

    /** How far a dragging FRONT corner pulls the steering off centre, degrees. */
    private static final double FRONT_STEER_PULL = 5;

    /** How much retardation a corner scraping the road adds, blocks per second squared. */
    private static final double CORNER_DRAG = 3;

    /** What fraction of the tyre grip survives losing every wheel on that axle. */
    private static final double BARE_GRIP = 0.25;

    /** How much of the steering's rate and lock the worst slack takes. */
    private static final double SLACK_RATE = 0.6;
    private static final double SLACK_LOCK = 0.35;

    /** A sound vehicle: every factor neutral, nothing to add. */
    static final DamageResponse NONE = new DamageResponse(VehicleDamage.NONE);

    private final boolean any;
    private final double frontGrip;
    private final double rearGrip;
    private final double drag;
    private final double rollBias;
    private final double pitchBias;
    private final double rideDrop;
    private final double yawPull;
    private final double steerBias;
    private final double steerRate;
    private final double steerLock;
    private final double power;

    private DamageResponse(VehicleDamage damage) {
        this.any = !damage.none();
        double fl = damage.wheel(VehicleCorner.FRONT_LEFT);
        double fr = damage.wheel(VehicleCorner.FRONT_RIGHT);
        double rl = damage.wheel(VehicleCorner.REAR_LEFT);
        double rr = damage.wheel(VehicleCorner.REAR_RIGHT);

        // Grip falls with the axle's mean condition and never to nothing: a car
        // on a bare rim still scrubs round a corner, badly. Exactly 1 when the
        // axle is sound, which is what keeps NONE neutral.
        this.frontGrip = BARE_GRIP + (1 - BARE_GRIP) * ((fl + fr) / 2);
        this.rearGrip = BARE_GRIP + (1 - BARE_GRIP) * ((rl + rr) / 2);

        // How far each corner has sunk. SQUARED, deliberately: a soft tyre is
        // not half a missing wheel, and a linear reading had a vehicle visibly
        // listing over cosmetic damage. Most of the lean arrives in the last
        // quarter of the wheel's life, where the axle is actually on the road.
        double dropFl = sink(fl);
        double dropFr = sink(fr);
        double dropRl = sink(rl);
        double dropRr = sink(rr);

        // Right-side-down positive and nose-up positive, matching the body's
        // own attitude everywhere else in the physics.
        this.rollBias = ((dropFr + dropRr) - (dropFl + dropRl)) / 2 * CORNER_LEAN;
        this.pitchBias = ((dropRl + dropRr) - (dropFl + dropFr)) / 2 * CORNER_LEAN;
        this.rideDrop = (dropFl + dropFr + dropRl + dropRr) / 4 * CORNER_DROP;

        this.yawPull = (dropRr - dropRl) * REAR_YAW_PULL;
        this.steerBias = damage.steeringPull() + (dropFr - dropFl) * FRONT_STEER_PULL;

        this.drag = damage.drag()
                + (dropFl + dropFr + dropRl + dropRr) * CORNER_DRAG;

        this.steerRate = 1 - SLACK_RATE * damage.steeringSlack();
        this.steerLock = 1 - SLACK_LOCK * damage.steeringSlack();
        this.power = damage.enginePower();
    }

    static DamageResponse of(VehicleDamage damage) {
        return damage == null || damage.none() ? NONE : new DamageResponse(damage);
    }

    private static double sink(double condition) {
        double lost = 1 - condition;
        return lost * lost;
    }

    /**
     * Whether anything here is worth adding. False for {@link #NONE}, which is
     * how the physics skips every additive term and stays exactly as it was.
     */
    boolean any() {
        return any;
    }

    /** Scales the front tyres' cornering limit — the understeer bound. */
    double frontGrip() {
        return frontGrip;
    }

    /** Scales the rear tyres' hold on the slip — how readily the back steps out. */
    double rearGrip() {
        return rearGrip;
    }

    /** Extra retardation on the ground, blocks per second squared. */
    double drag() {
        return drag;
    }

    /** Degrees of standing roll, right-side-down positive. */
    double rollBias() {
        return rollBias;
    }

    /** Degrees of standing pitch, nose-up positive. */
    double pitchBias() {
        return pitchBias;
    }

    /** How far the body settles, in blocks. */
    double rideDrop() {
        return rideDrop;
    }

    /**
     * The yaw a dragging rear corner adds at {@code speed}, degrees per second.
     *
     * <p>Ramped in with speed rather than applied flat, because a corner can
     * only drag the vehicle round if the vehicle is going somewhere: a wreck
     * standing still that slowly rotated on the spot was the first version of
     * this, and it read as the physics having lost its mind.
     */
    double yawPull(double speed) {
        if (yawPull == 0) {
            return 0;
        }
        double ramp = Math.min(1, Math.abs(speed) / PULL_SPEED);
        return yawPull * ramp;
    }

    /** Degrees the steering sits off centre: bent steering plus a dragging front corner. */
    double steerBias() {
        return steerBias;
    }

    /** Scales how quickly the steering answers. */
    double steerRate() {
        return steerRate;
    }

    /** Scales how much lock is available. */
    double steerLock() {
        return steerLock;
    }

    /** Scales the drive, and only the drive — damage to an engine is not damage to the brakes. */
    double power() {
        return power;
    }
}
