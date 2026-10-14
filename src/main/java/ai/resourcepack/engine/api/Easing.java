package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Optional;

/**
 * How a move, a turn or a resize of a {@link ShaderObject} runs over its time.
 *
 * <p>Applied on the server, one step a tick, with the client smoothing the
 * steps between: so an ease is exactly the curve it names, not something the
 * client approximates.
 */
public enum Easing {

    /** The same speed throughout. */
    LINEAR,

    /** Starts slow and speeds up: something falling, something launched. */
    IN,

    /** Starts fast and slows down: something arriving, something landing softly. */
    OUT,

    /** Slow at both ends. */
    IN_OUT;

    /** Where the motion is at {@code t} of the way through its time, both 0..1. */
    public double apply(double t) {
        double x = Math.max(0, Math.min(1, t));
        switch (this) {
            case IN:
                return x * x * x;
            case OUT: {
                double u = 1 - x;
                return 1 - u * u * u;
            }
            case IN_OUT:
                return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
            default:
                return x;
        }
    }

    /** {@code linear}, {@code in}, {@code out}, {@code in_out} (or {@code in-out}), any case. */
    public static Optional<Easing> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Easing easing : values()) {
            if (easing.name().equals(key)) {
                return Optional.of(easing);
            }
        }
        return Optional.empty();
    }

    /** The name a command or a content file spells it with. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
