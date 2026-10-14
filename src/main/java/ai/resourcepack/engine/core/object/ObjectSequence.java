package ai.resourcepack.engine.core.object;

import ai.resourcepack.engine.api.Easing;

import java.util.List;

/**
 * A sequence a content file wrote for an object: steps run in order, one
 * after another, by {@link ObjectRuntime}.
 *
 * <p>A move, turn or resize holds the sequence until it has finished, unless
 * it says {@code async: true}, which is how two of them run at once. A
 * {@code move to} is measured from where the object stood when the sequence
 * began, so one sequence works wherever the object was put down.
 */
public record ObjectSequence(String name, List<Step> steps) {

    public ObjectSequence {
        steps = List.copyOf(steps);
    }

    /** One step. */
    public sealed interface Step permits State, Seed, Move, Turn, Resize, Wait, Sound, Command, Settle, Remove, Restart {
    }

    /** Enter a state; {@code replay} starts it again when it is the state already. */
    public record State(String state, boolean replay) implements Step {
    }

    public record Seed(int seed) implements Step {
    }

    /**
     * Move to {@code to} (blocks from where the sequence began) or by
     * {@code by} (blocks from where it is now). One of the two is null.
     */
    public record Move(double[] to, double[] by, int ticks, Easing easing, boolean async) implements Step {
    }

    /** Turn to (or, with {@code by}, by) a yaw, pitch and roll; a null axis is left where it is. */
    public record Turn(Float yaw, Float pitch, Float roll, boolean by, int ticks, Easing easing, boolean async)
            implements Step {
    }

    /** Change the diameter to {@code size} blocks, or multiply it by {@code times}. */
    public record Resize(Double size, Double times, int ticks, Easing easing, boolean async) implements Step {
    }

    public record Wait(int ticks) implements Step {
    }

    /** Play a sound at the object, for everybody near it. */
    public record Sound(String sound, float volume, float pitch) implements Step {
    }

    /**
     * Run a console command, with {@code {x} {y} {z}} the object's middle and
     * {@code {world}} its world: the door to everything else a server can do.
     */
    public record Command(String command) implements Step {
    }

    /** Show the current state as long finished. */
    public record Settle() implements Step {
    }

    public record Remove() implements Step {
    }

    /** Back to the first step: a sequence that loops. */
    public record Restart() implements Step {
    }
}
