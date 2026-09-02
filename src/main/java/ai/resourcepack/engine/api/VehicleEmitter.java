package ai.resourcepack.engine.api;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One place on a vehicle that throws particles, and when.
 *
 * <p>An exhaust pipe, a wake behind a hull, dust off a wheel. A vehicle has a
 * list of these and they are independent: each names its own effect, its own
 * spot on the bodywork and its own {@link VehicleState}s, so a car can smoke
 * from the back while it drives and steam from the bonnet when it stops
 * without either knowing about the other.
 *
 * <h2>Why the effect is a String here</h2>
 *
 * <p>Bukkit's {@code Particle} enum is <strong>not stable across the versions
 * this engine supports</strong> — {@code SMOKE_NORMAL} became {@code SMOKE} in
 * 1.20.5, among others — so naming the type here would mean this class failed
 * to load on half of them. The name is carried as the author wrote it and
 * resolved at spawn time against an alias table, which is also what lets a
 * diagnostic quote the thing they actually typed.
 *
 * <h2>Where the offset is measured from</h2>
 *
 * <p><strong>The same frame as a seat</strong>, and that is on purpose: an
 * author placing an exhaust pipe is doing exactly what they did placing a
 * seat, and two frames would be one to get wrong. {@code x} is to the
 * vehicle's right, {@code z} is in front of it, {@code y} is above its base,
 * all in blocks, all turning with the body. Studio authors them in model
 * pixels and converts on the way out, exactly as it does for seats.
 */
public final class VehicleEmitter {

    /**
     * The most particles one burst may ask for.
     *
     * <p>Twenty bursts a second, several emitters per vehicle and several
     * vehicles on a server multiply fast, and the cost lands on every client
     * in range rather than on the server. Sixteen is more than enough for
     * exhaust and far short of a frame-rate problem.
     */
    public static final int MAX_COUNT = 16;

    /** Bounds, so a bad number is a diagnostic rather than a fog bank. */
    public static final double MAX_SPREAD = 4;
    public static final double MAX_SPEED = 4;
    public static final double MAX_OFFSET = 8;
    public static final int MAX_INTERVAL = 200;

    /** No colour asked for. A dust particle without one falls back to white. */
    public static final int NO_COLOR = -1;

    private final String effect;
    private final Set<VehicleState> states;
    private final double x;
    private final double y;
    private final double z;
    private final int count;
    private final double spread;
    private final double speed;
    private final int interval;
    private final boolean enabled;
    private final int color;
    private final double size;

    private VehicleEmitter(String effect, Set<VehicleState> states, double x, double y, double z,
                           int count, double spread, double speed, int interval,
                           boolean enabled, int color, double size) {
        this.effect = effect;
        this.states = states;
        this.x = x;
        this.y = y;
        this.z = z;
        this.count = count;
        this.spread = spread;
        this.speed = speed;
        this.interval = interval;
        this.enabled = enabled;
        this.color = color;
        this.size = size;
    }

    /**
     * One emitter, with every number clamped to something a server can
     * survive.
     *
     * <p>Clamped rather than refused, the same choice {@code VehicleDefinitions}
     * makes for a handling number: a count of 500 is somebody being optimistic
     * and has an obvious nearest legal value, and refusing it would take the
     * whole vehicle down with it.
     */
    public static VehicleEmitter of(String effect, Collection<VehicleState> states,
                                    double x, double y, double z,
                                    int count, double spread, double speed, int interval,
                                    boolean enabled, int color, double size) {
        Set<VehicleState> active = states == null || states.isEmpty()
                ? EnumSet.noneOf(VehicleState.class)
                : EnumSet.copyOf(states);
        return new VehicleEmitter(
                effect == null ? "" : effect.trim(),
                active,
                offset(x), offset(y), offset(z),
                Math.max(1, Math.min(MAX_COUNT, count)),
                clamp(spread, 0, MAX_SPREAD),
                clamp(speed, 0, MAX_SPEED),
                Math.max(1, Math.min(MAX_INTERVAL, interval)),
                enabled,
                color < 0 ? NO_COLOR : color & 0xFFFFFF,
                clamp(size, 0.1, 4));
    }

    private static double offset(double value) {
        return clamp(value, -MAX_OFFSET, MAX_OFFSET);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min > 0 ? min : 0;
        }
        return Math.min(max, Math.max(min, value));
    }

    /**
     * The particle name as the pack wrote it, uppercased.
     *
     * <p>Not resolved to anything — see the class note. Empty when the pack
     * named none, which is an emitter that can never fire.
     */
    public String effect() {
        return effect.toUpperCase(Locale.ROOT);
    }

    /**
     * Which states it fires in.
     *
     * <p><strong>Any of them, not all.</strong> An emitter naming
     * {@code moving} and {@code reversing} fires whenever the vehicle is doing
     * either, which is what makes one exhaust plume one emitter.
     */
    public Set<VehicleState> states() {
        return states;
    }

    /** Whether it fires while the vehicle is in {@code active}. */
    public boolean firesIn(Collection<VehicleState> active) {
        if (!enabled || effect.isEmpty() || states.isEmpty() || active == null) {
            return false;
        }
        for (VehicleState state : states) {
            if (active.contains(state)) {
                return true;
            }
        }
        return false;
    }

    /** Blocks to the vehicle's right; negative is left. */
    public double x() {
        return x;
    }

    /** Blocks above the vehicle's base. */
    public double y() {
        return y;
    }

    /** Blocks in front of the vehicle; negative is behind. */
    public double z() {
        return z;
    }

    /** How many particles one burst asks for. */
    public int count() {
        return count;
    }

    /** How far they scatter from the spot, in blocks. */
    public double spread() {
        return spread;
    }

    /** How fast they move away, in the units the game's own particle call takes. */
    public double speed() {
        return speed;
    }

    /** Ticks between bursts. 1 is every tick. */
    public int interval() {
        return interval;
    }

    /**
     * Whether it fires at all.
     *
     * <p>Its own switch rather than deleting the emitter, because turning one
     * plume off while tuning the others is the ordinary thing an author does
     * and losing its settings to do it is not.
     */
    public boolean enabled() {
        return enabled;
    }

    /** {@code 0xRRGGBB}, or empty when the pack asked for none. */
    public Optional<Integer> color() {
        return color == NO_COLOR ? Optional.empty() : Optional.of(color);
    }

    /** How big a coloured dust mote is drawn. Ignored by every other effect. */
    public double size() {
        return size;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VehicleEmitter)) {
            return false;
        }
        VehicleEmitter that = (VehicleEmitter) other;
        return Double.compare(x, that.x) == 0 && Double.compare(y, that.y) == 0
                && Double.compare(z, that.z) == 0 && count == that.count
                && Double.compare(spread, that.spread) == 0 && Double.compare(speed, that.speed) == 0
                && interval == that.interval && enabled == that.enabled && color == that.color
                && Double.compare(size, that.size) == 0
                && effect.equals(that.effect) && states.equals(that.states);
    }

    @Override
    public int hashCode() {
        return Objects.hash(effect, states, x, y, z, count, spread, speed, interval, enabled, color, size);
    }

    @Override
    public String toString() {
        return effect() + states + (enabled ? "" : " (off)");
    }
}
