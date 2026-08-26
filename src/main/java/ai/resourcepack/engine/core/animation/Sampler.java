package ai.resourcepack.engine.core.animation;

import ai.resourcepack.engine.api.Keyframe;

import java.util.List;
import java.util.Map;

/**
 * Reads a value out of a keyframe track at a moment in time.
 *
 * <p>Ported from {@code RigAnimator.sample} in {@code the previous engine},
 * which is itself a mirror of the editor's {@code sampleChannel}.
 * <strong>Those three have to agree.</strong> The curve an author drew in the
 * editor, the curve the old plugin plays, and the curve this plays are the
 * same curve; a difference in any of them is animation that looks subtly wrong
 * on a server and correct in the preview, which is close to undebuggable.
 *
 * <p>The rules, in the order they apply:
 *
 * <ol>
 *   <li>Before the first keyframe and after the last, hold. An animation does
 *       not extrapolate off the ends of what somebody drew.</li>
 *   <li>A segment whose <em>left</em> key is {@code step} holds the left value
 *       for the whole segment.</li>
 *   <li>A segment with {@code smooth} at <em>either</em> end is Catmull-Rom
 *       through its neighbours. Either end, not both, because that is what the
 *       editor draws and therefore what the author saw.</li>
 *   <li>Otherwise, linear.</li>
 * </ol>
 *
 * <p>Free of Bukkit and free of state, so every rule above is a test rather
 * than a hope.
 */
public final class Sampler {

    private Sampler() {
    }

    /**
     * The value of one channel of {@code animator} at {@code time}.
     *
     * @param fallback returned when the channel is absent or empty, so a
     *                 channel nobody animated reads as its rest pose rather
     *                 than as zero — which for scale collapses a model to
     *                 nothing
     */
    public static float[] sample(Map<String, List<Keyframe>> animator, String channel,
                                 double time, float[] fallback) {
        if (animator == null) {
            return fallback;
        }
        return sample(animator.get(channel), time, fallback);
    }

    /** The value of {@code track} at {@code time}. */
    public static float[] sample(List<Keyframe> track, double time, float[] fallback) {
        if (track == null || track.isEmpty()) {
            return fallback;
        }
        Keyframe first = track.get(0);
        if (first.value == null) {
            return fallback;
        }
        if (time <= first.time) {
            return first.value;
        }
        Keyframe last = track.get(track.size() - 1);
        if (time >= last.time) {
            return last.value;
        }

        for (int i = 1; i < track.size(); i++) {
            Keyframe b = track.get(i);
            if (time > b.time) {
                continue;
            }
            Keyframe a = track.get(i - 1);
            if (a.value == null || b.value == null) {
                return fallback;
            }
            if ("step".equals(a.interpolation)) {
                return a.value;
            }
            double span = b.time - a.time;
            // Two keyframes at one instant are a jump, not a division by zero.
            // Landing on the right-hand value is what the editor draws.
            float t = span > 0 ? (float) ((time - a.time) / span) : 1f;

            if ("smooth".equals(a.interpolation) || "smooth".equals(b.interpolation)) {
                Keyframe p0 = i >= 2 ? track.get(i - 2) : a;
                Keyframe p3 = i + 1 < track.size() ? track.get(i + 1) : b;
                float[] out = new float[3];
                for (int axis = 0; axis < 3; axis++) {
                    out[axis] = catmullRom(p0.value[axis], a.value[axis],
                            b.value[axis], p3.value[axis], t);
                }
                return out;
            }

            float[] out = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                out[axis] = a.value[axis] + (b.value[axis] - a.value[axis]) * t;
            }
            return out;
        }
        return last.value;
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        return 0.5f * (2f * p1
                + (-p0 + p2) * t
                + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t
                + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t);
    }
}
