package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Optional;

/**
 * How one of a model's animations should play.
 *
 * <p>A {@code .bbmodel} says what an animation IS — its keyframes, its length,
 * and whether it loops. It says nothing about how a particular server wants it
 * used, which is what this is: the speed to run it at, what wins when two
 * could play, and how long to ease between them.
 *
 * <p>Those are decisions about a server rather than about a model, which is
 * why they are written in the content folder beside the piece and not baked
 * into the art. The same walk cycle is a stroll on one server and a sprint on
 * another, and neither should need a second Blockbench file.
 *
 * <p>Every field has a default that means "as authored", so an animation
 * nobody mentions behaves exactly as it did before this existed.
 */
public final class AnimationSettings {

    /** What an animation does when it reaches its end. */
    public enum Mode {

        /** Starts again. */
        LOOP,

        /** Stops on its last frame and stays there. A door that stays open. */
        HOLD,

        /** Plays once and goes back to rest. */
        ONCE;

        /** The name an author writes, or empty if it is not one of these. */
        public static Optional<Mode> parse(String written) {
            if (written == null) {
                return Optional.empty();
            }
            String name = written.trim().toUpperCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.name().equals(name)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }

        /** How it is written in a content folder, and on the wire. */
        public String written() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Mode mode;
    private final double speed;
    private final int priority;
    private final double blend;
    private final int layer;

    private AnimationSettings(Mode mode, double speed, int priority, double blend, int layer) {
        this.mode = mode;
        this.speed = speed;
        this.priority = priority;
        this.blend = blend;
        this.layer = layer;
    }

    /**
     * @param mode     null to keep whatever the model was authored with
     * @param speed    0 or less for the authored speed
     * @param blend    seconds to ease in and out; 0 is a hard cut
     */
    public static AnimationSettings of(Mode mode, double speed, int priority, double blend) {
        return of(mode, speed, priority, blend, 0);
    }

    /**
     * @param layer 0 is the base animation, which is what everything is unless
     *              it says otherwise. Above 0 it plays OVER the base rather
     *              than replacing it \u2014 a wave over a walk cycle.
     */
    public static AnimationSettings of(Mode mode, double speed, int priority, double blend, int layer) {
        return new AnimationSettings(mode, speed, priority, Math.max(0, blend), Math.max(0, layer));
    }

    /** Null where the model's own answer stands. */
    public Mode mode() {
        return mode;
    }

    /** 0 where the authored speed stands. */
    public double speed() {
        return speed;
    }

    /** Higher wins when two animations claim one trigger. */
    public int priority() {
        return priority;
    }

    /** Seconds of crossfade in and out of it. */
    public double blend() {
        return blend;
    }

    /** Which layer it plays on. 0 is the base. */
    public int layer() {
        return layer;
    }

    @Override
    public String toString() {
        return "AnimationSettings[" + (mode == null ? "as authored" : mode.written())
                + ", speed=" + speed + ", priority=" + priority + ", blend=" + blend
                + ", layer=" + layer + "]";
    }
}
