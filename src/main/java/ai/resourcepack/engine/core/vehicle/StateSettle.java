package ai.resourcepack.engine.core.vehicle;

import java.util.Objects;

/**
 * A wait for what a vehicle's state asks for to HOLD before anything acts on
 * it.
 *
 * <p><strong>A state the vehicle passes THROUGH is not a state it should be
 * dressed or animated for.</strong> Going from forwards into reverse is the
 * case that found this: the back key brakes before it reverses, and
 * {@link VehiclePhysics#MOVING_THRESHOLD} and
 * {@link VehiclePhysics#REVERSE_THRESHOLD} are both half a block a second — so
 * the vehicle crosses from +0.5 to -0.5 through {@code idle} at its
 * acceleration rate, which on an ordinary car is about three ticks and on
 * something quick is two. Long enough to fire a swap; nowhere near long enough
 * to be anything anybody meant.
 *
 * <p>It only ever suppresses answers that are genuinely brief, which is the
 * property that makes it safe: a vehicle that really does sit at a standstill
 * for two seconds between a forward and a reverse is idle, and gets the idle
 * answer, because <strong>the wait is in TICKS and not in transitions</strong>.
 *
 * <p>Pure, and its own class, for the reason {@link VehiclePhysics} is: the
 * alternative way of checking it is reversing a car on a test server and
 * watching for a single frame of the wrong thing. Its one consumer now is a
 * seat's pose (see {@code VehicleRuntime.Ride.dress}). The vehicle's own
 * animation waited here too and no longer does: the rig animator crossfades
 * it in pose space, and a fade that has set off towards a state the vehicle
 * is only passing through re-aims from wherever it has got to, which is
 * nothing anybody can see (see {@code VehicleRuntime.Ride.animate}).
 */
final class StateSettle {

    /**
     * How long an answer has to hold before it is acted on.
     *
     * <p>Four ticks, which is a fifth of a second and is chosen against the one
     * window that has to be covered: the brake-through-idle crossing described
     * above is three ticks on a default car and two on a quick one.
     *
     * <p>For seats it is latency on every genuine change. For the vehicle body
     * it is only the time before a drive cycle accepts IDLE as a real stop.
     */
    static final int TICKS = 4;

    /**
     * Whether a wait is running at all, as opposed to {@link #waitingFor} being
     * an answer of null.
     *
     * <p>The two are different and the difference is a missed wait: "nothing at
     * all" is a perfectly ordinary answer — a state the pack mapped to nothing
     * — so a cleared gate that only remembered null would let the next null
     * through on its first tick, off whatever {@link #since} happened to be
     * left over.
     */
    private boolean waiting;

    /** The answer being waited on, which may be null. */
    private String waitingFor;

    /** The tick that answer was first asked for. */
    private long since;

    /**
     * Whether {@code wanted} has been the answer for long enough to act on, as
     * of {@code tick}.
     *
     * <p>Called once a tick with the vehicle's own age. A new answer restarts
     * the clock and reports false; the same answer reports true once it has
     * held for {@link #TICKS}.
     */
    boolean settled(String wanted, long tick) {
        if (!waiting || !Objects.equals(wanted, waitingFor)) {
            waiting = true;
            waitingFor = wanted;
            since = tick;
            return false;
        }
        return tick - since >= TICKS;
    }

    /**
     * Forget whatever is being waited on.
     *
     * <p>For the caller that has found the answer settling back onto what is
     * already running: what was being waited out never happened, so an
     * oscillation must not accumulate towards acting on it.
     */
    void clear() {
        waiting = false;
        waitingFor = null;
        since = 0;
    }
}
