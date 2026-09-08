package ai.resourcepack.engine.api;

/**
 * When a landing throws the rider off.
 *
 * <p>Land a jump a long way round from the way you are travelling and you do
 * not land it: the vehicle goes one way, you go the other. Straight is fine,
 * and so is straight BACKWARDS - riding away backwards from a jump is a trick
 * rather than a crash, which is why this is a window either side of sideways
 * and not simply "not straight".
 *
 * <p><strong>Opt-in, always.</strong> A vehicle that throws its rider is a
 * game rule rather than a physical fact, and a server owner who never asked
 * for one should not find it under somebody at speed. A pack that wants it
 * says so:
 *
 * <pre>
 * bail:
 *   from: 50        # degrees off the direction of travel
 *   to: 130
 *   min-speed: 3.0  # slower than this is a stumble, not a fall
 *   damage: 1.0     # half a heart
 * </pre>
 *
 * <p>The engine fires {@link ai.resourcepack.engine.api.event.VehicleBailEvent}
 * before it happens, so a plugin can veto one - which is how a server toggle
 * for it is written without the pack's YAML changing.
 */
public final class VehicleBail {

    private final double from;
    private final double to;
    private final double minSpeed;
    private final double damage;

    public VehicleBail(double from, double to, double minSpeed, double damage) {
        this.from = clamp(from, 0, 180);
        this.to = Math.max(this.from, clamp(to, 0, 180));
        this.minSpeed = Math.max(0, minSpeed);
        this.damage = Math.max(0, damage);
    }

    /** The narrow edge of the window, degrees off the way the vehicle was going. */
    public double from() {
        return from;
    }

    /** The wide edge of it. Past this is landing backwards, which is allowed. */
    public double to() {
        return to;
    }

    /** Below this speed a sideways landing is a stumble rather than a fall, blocks a second. */
    public double minSpeed() {
        return minSpeed;
    }

    /** What it costs the rider, in half hearts. 0 for nothing but the fall. */
    public double damage() {
        return damage;
    }

    private static double clamp(double value, double low, double high) {
        return !Double.isFinite(value) ? low : Math.max(low, Math.min(high, value));
    }
}
