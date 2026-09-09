package ai.resourcepack.engine.api;

/**
 * How an aircraft gets off the ground and how it comes back down.
 *
 * <p>Only {@link VehicleMedium#AIR} reads any of it. A car carries one of these
 * and never looks at it, exactly as a vehicle with no rig carries an animation
 * map it never uses — the alternative is a nullable field that every reader has
 * to ask about.
 *
 * <p><strong>These four exist because "air" was one behaviour and an aircraft
 * is at least two.</strong> A vehicle that holds its height from a standstill
 * is a helicopter; one that has to reach a speed before it will fly, and that
 * sinks when it drops below that speed again, is an aeroplane. Both are worth
 * building and the difference between them is not a medium, it is these
 * numbers.
 *
 * <p><strong>The defaults are the old behaviour, not a new opinion.</strong>
 * {@link #forSpeed} is what a pack that says nothing gets, and it is exactly
 * what every air vehicle written before this file did: lifts from a standstill,
 * climbs at half its top speed, never stalls. A hand-authored plane must not
 * start needing a runway because its server updated.
 */
public final class VehicleFlight {

    /** Nothing here is ever negative. */
    public static final double MIN = 0;

    /** Bounds, so a bad number is a diagnostic rather than an aircraft nobody can land. */
    public static final double MAX_TAKEOFF_SPEED = 40;
    public static final double MAX_CLIMB_RATE = 30;
    public static final double MAX_DIVE_RATE = 40;
    public static final double MAX_STALL_SINK = 30;

    /**
     * What a climb was worth before there was a number for it: half the
     * vehicle's top speed.
     *
     * <p>Kept as a named constant rather than folded into {@link #forSpeed}
     * because it is the whole of the backward compatibility story — an
     * aircraft loaded from a pack with no {@code flight:} block climbs at
     * exactly the rate it always did, and this is the line that says so.
     */
    public static final double LEGACY_CLIMB_FRACTION = 0.5;

    private final double takeoffSpeed;
    private final double climbRate;
    private final double diveRate;
    private final double stallSink;

    /**
     * A descent a plugin has imposed, blocks per second, or NaN for none.
     * See {@link #withDescent}.
     */
    private final double descent;

    private VehicleFlight(double takeoffSpeed, double climbRate, double diveRate, double stallSink) {
        this(takeoffSpeed, climbRate, diveRate, stallSink, Double.NaN);
    }

    private VehicleFlight(double takeoffSpeed, double climbRate, double diveRate, double stallSink,
                          double descent) {
        this.takeoffSpeed = takeoffSpeed;
        this.climbRate = climbRate;
        this.diveRate = diveRate;
        this.stallSink = stallSink;
        this.descent = descent;
    }

    /**
     * What an air vehicle that says nothing about flight gets, given its top
     * speed.
     *
     * <p>A hovering aircraft: no takeoff run, no stall, and the climb rate the
     * engine used to hard-code. See the class note — this is a compatibility
     * shape rather than a recommendation, and studio always writes all four.
     */
    public static VehicleFlight forSpeed(double speed) {
        double climb = Double.isFinite(speed) && speed > 0 ? speed * LEGACY_CLIMB_FRACTION : 0;
        return new VehicleFlight(0, clamp(climb, MAX_CLIMB_RATE), clamp(climb, MAX_DIVE_RATE), 0);
    }

    /** Clamped to its bounds; anything unreadable becomes zero. */
    public static VehicleFlight of(double takeoffSpeed, double climbRate, double diveRate, double stallSink) {
        return new VehicleFlight(
                clamp(takeoffSpeed, MAX_TAKEOFF_SPEED),
                clamp(climbRate, MAX_CLIMB_RATE),
                clamp(diveRate, MAX_DIVE_RATE),
                clamp(stallSink, MAX_STALL_SINK));
    }

    private static double clamp(double value, double max) {
        if (!Double.isFinite(value)) {
            return MIN;
        }
        return Math.min(max, Math.max(MIN, value));
    }

    /**
     * How fast it has to be going before it will fly, in blocks per second.
     *
     * <p>Below it the aircraft cannot leave the ground, and in the air it
     * cannot hold its height — it sinks at {@link #stallSink()}. Zero is a
     * helicopter: it lifts from a standstill and never stalls, which is what
     * every air vehicle did before this existed.
     *
     * <p><strong>The takeoff RUN is this and the acceleration together</strong>,
     * which is why there is no "takeoff time" field: a time would be a third
     * number that could disagree with the other two, and the only way to honour
     * it would be to override the acceleration the pack already stated.
     */
    public double takeoffSpeed() {
        return takeoffSpeed;
    }

    /** How fast it gains height on the climb key, in blocks per second. */
    public double climbRate() {
        return climbRate;
    }

    /**
     * How fast it loses height on the back key, in blocks per second.
     *
     * <p>Its own number rather than the climb rate negated, because an
     * aircraft does not go down as slowly as it goes up.
     */
    public double diveRate() {
        return diveRate;
    }

    /**
     * How fast it sinks while it is slower than {@link #takeoffSpeed()}, in
     * blocks per second.
     *
     * <p><strong>This is what stops an aeroplane parking in mid-air.</strong>
     * A stalled aircraft keeps whatever speed it had and loses height; it does
     * not stop, and it does not fall at gravity either — a stall here is a
     * glide down to the ground rather than a rock dropping out of the sky,
     * because the driver has to be able to recover from it with the throttle.
     */
    public double stallSink() {
        return stallSink;
    }

    /**
     * The same numbers with a descent imposed: the vehicle comes down at
     * {@code blocksPerSecond} whatever its speed, and never flies.
     *
     * <p>This is {@link Vehicle#setDescent}'s shape, and it is a different
     * kind of number from the four above, which is why it is not a fifth
     * field in a pack's {@code flight:} block. Those describe an aircraft
     * — a thing that can hold its height once it is going fast enough. A
     * vehicle with a descent is one that <em>cannot</em>: a parachute, a
     * glider, a helicopter whose engine a plugin has just failed. Its
     * takeoff speed no longer means anything, the climb and dive keys do
     * nothing to its height, and it sinks at this rate until it is on the
     * ground — where it sits, exactly as a stalled aeroplane does.
     *
     * <p><strong>Not clamped to {@link #MAX_STALL_SINK}.</strong> That bound
     * is the editor's slider, and a stall is a slow thing; a freefall is not.
     * Anything unreadable, or under zero, clears it.
     */
    public VehicleFlight withDescent(double blocksPerSecond) {
        double wanted = Double.isFinite(blocksPerSecond) && blocksPerSecond >= 0 ? blocksPerSecond : Double.NaN;
        if (Double.isNaN(wanted) && Double.isNaN(descent) || wanted == descent) {
            return this;
        }
        return new VehicleFlight(takeoffSpeed, climbRate, diveRate, stallSink, wanted);
    }

    /** Whether a plugin has told this vehicle how fast to come down. See {@link #withDescent}. */
    public boolean descends() {
        return !Double.isNaN(descent);
    }

    /**
     * The imposed descent, blocks per second, or {@link #stallSink} when
     * there is none: what an aircraft that is not flying comes down at.
     */
    public double descent() {
        return descends() ? descent : stallSink;
    }

    /** Whether it needs a run-up at all. */
    public boolean needsTakeoffRun() {
        return takeoffSpeed > 0;
    }

    /**
     * Whether an aircraft doing {@code speed} is going fast enough to fly.
     *
     * <p>Never, once a descent has been imposed: a vehicle that has been
     * told how fast to come down is not asked whether it could stay up.
     */
    public boolean flies(double speed) {
        return !descends() && Math.abs(speed) >= takeoffSpeed;
    }

    @Override
    public String toString() {
        return "takeoff " + takeoffSpeed + ", climb " + climbRate
                + ", dive " + diveRate + ", stall " + stallSink
                + (descends() ? ", descending " + descent : "");
    }
}
