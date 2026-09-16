package ai.resourcepack.engine.core.animation;

import ai.resourcepack.engine.api.Keyframe;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Turning sampled keyframes into a display entity's transform.
 *
 * <p>Ported from the previous engine's own rig animator. Every
 * comment below is a bug somebody already found in game, which is why it is
 * copied rather than rewritten from what the maths obviously ought to be.
 */
public final class RigMath {

    private static final float[] ZERO = {0f, 0f, 0f};
    private static final float[] ONE = {1f, 1f, 1f};

    /**
     * Scaling a part to exactly 0 is the normal way to hide it mid-animation,
     * and it also makes the matrix singular: rotation extraction divides by the
     * basis length, so the client gets a NaN quaternion — and since it
     * interpolates from its rendered pose, the NaN propagates for ever and the
     * part never comes back. A thousandth of a block keeps the basis invertible
     * and is not visible.
     */
    private static final float MIN_SCALE = 1e-3f;

    private RigMath() {
    }

    /**
     * Composes one animator target's pose into {@code m}, about {@code pivot}.
     *
     * <p>Takes the animator map rather than an animation so emotes can share
     * it: an emote's keyframes are these keyframes, and its bones are animator
     * targets with a pivot. One implementation means the editor, the rigs and
     * the emotes cannot drift on what a keyframe means.
     */
    public static void applyStep(Matrix4f m, Map<String, Map<String, List<Keyframe>>> animators,
                                 String target, float[] pivot, double t) {
        if (pivot == null || pivot.length != 3) {
            return;
        }
        Map<String, List<Keyframe>> animator = animators == null ? null : animators.get(target);
        // The same px -> block-space mapping as the editor viewport: (v-8)/16,
        // with the entity sitting at the block centre.
        float px = (pivot[0] - 8f) / 16f;
        float py = (pivot[1] - 8f) / 16f;
        float pz = (pivot[2] - 8f) / 16f;
        float[] rot = Sampler.sample(animator, "rotation", t, ZERO);
        float[] pos = Sampler.sample(animator, "position", t, ZERO);
        float[] scl = Sampler.sample(animator, "scale", t, ONE);
        m.translate(px + pos[0] / 16f, py + pos[1] / 16f, pz + pos[2] / 16f);
        m.rotateXYZ((float) Math.toRadians(rot[0]),
                (float) Math.toRadians(rot[1]),
                (float) Math.toRadians(rot[2]));
        m.scale(nonSingular(scl[0]), nonSingular(scl[1]), nonSingular(scl[2]));
        m.translate(-px, -py, -pz);
    }

    /**
     * {@code ItemDisplayRenderer} rotates the rendered item 180 degrees around
     * Y after applying the display transformation. The editor's animation is
     * authored before that built-in rotation, so the complete model-space
     * transform is conjugated into the item's displayed coordinate space.
     */
    /**
     * As above, at {@code weight} of full strength.
     *
     * <p>What lets a layer be half a wave rather than a wave. Weighting is
     * applied to the SAMPLED VALUES rather than to the finished matrix, which
     * is the only place it means anything: half of a rotation is a smaller
     * rotation, and half of a matrix is not a transform at all.
     *
     * <p>Scale is weighted toward 1 rather than toward 0, because 1 is what
     * "no scaling" is. Half of a bone scaled to 2 is 1.5, not 1.
     *
     * <p>A weight of exactly 1 takes the same arithmetic as before this
     * existed, so nothing that was not weighted changed.
     */
    public static void applyStep(
            Matrix4f m,
            Map<String, Map<String, List<Keyframe>>> animators,
            String target,
            float[] pivot,
            double t,
            float weight) {
        if (pivot == null || pivot.length != 3) return;
        if (weight <= 0f) return;
        Map<String, List<Keyframe>> animator = animators == null ? null : animators.get(target);
        composeStep(m, pivot, sampleStep(animator, t, weight));
    }

    /**
     * Composes a FIXED rotation into {@code m}, about {@code pivot}.
     *
     * <p>{@link #applyStep} with the keyframes taken out of it: the same
     * pivot mapping and the same XYZ order, applied to angles the pack stated
     * once rather than to anything sampled. That is what a cube turned past
     * what a block model can say looks like from here — the model file holds
     * it untilted and this is the turn, so it is composed whether or not
     * anything is animating, and it never changes while the display lives.
     *
     * <p>It must be composed INNERMOST of a part's transform, because it is
     * the cube's rest pose: an animation on the same cube then turns the
     * already-tilted cube, exactly as it would a rotation baked into the
     * geometry by the pack.
     */
    public static void applyFixedRotation(Matrix4f m, float[] pivot, float[] degrees) {
        if (pivot == null || pivot.length != 3 || degrees == null || degrees.length != 3) return;
        if (degrees[0] == 0f && degrees[1] == 0f && degrees[2] == 0f) return;
        float px = (pivot[0] - 8f) / 16f;
        float py = (pivot[1] - 8f) / 16f;
        float pz = (pivot[2] - 8f) / 16f;
        m.translate(px, py, pz);
        m.rotateXYZ((float) Math.toRadians(degrees[0]),
                (float) Math.toRadians(degrees[1]),
                (float) Math.toRadians(degrees[2]));
        m.translate(-px, -py, -pz);
    }

    // ---- a pose as values ------------------------------------------------
    //
    // A step's pose kept as the nine numbers it is composed from — rotation
    // xyz in degrees, position xyz in px, scale xyz — rather than as the
    // matrix they compose to. That is the representation a crossfade has to
    // work in, and the reason is the one bug this file exists to remember:
    // a Transformation (translation, rotation, scale) is not continuous in
    // the matrix it came from, and neither is anything that tweens it,
    // including the client. Two poses of a wheel a hair apart decompose to
    // triples that are far apart, and interpolating THOSE puts the wheel off
    // its axle. Interpolating the angle and composing about the pivot keeps
    // it on: every intermediate pose is a pose the rig can actually hold.

    /** Values per step: rotation xyz, position xyz, scale xyz. */
    public static final int STEP_VALUES = 9;

    /** The rest pose of any step. Shared, never written to. */
    private static final float[] REST_STEP = {0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f};

    /**
     * Samples one animator's channels at {@code t}, at {@code weight} of full
     * strength, into the values {@link #composeStep} composes.
     *
     * <p>The arithmetic is {@link #applyStep}'s, moved: the weighting is
     * applied here to the sampled values and nowhere else, so a step
     * composed from these is byte for byte what applyStep composed before
     * this split, weighted or not.
     *
     * @param animator the target's channels, or null for a target nothing
     *                 animates, which samples as rest
     */
    public static float[] sampleStep(Map<String, List<Keyframe>> animator, double t, float weight) {
        float[] rot = Sampler.sample(animator, "rotation", t, ZERO);
        float[] pos = Sampler.sample(animator, "position", t, ZERO);
        float[] scl = Sampler.sample(animator, "scale", t, ONE);
        float w = Math.min(1f, weight);
        return new float[] {
                rot[0] * w, rot[1] * w, rot[2] * w,
                pos[0] * w, pos[1] * w, pos[2] * w,
                1f + (scl[0] - 1f) * w, 1f + (scl[1] - 1f) * w, 1f + (scl[2] - 1f) * w,
        };
    }

    /**
     * Composes one step's values into {@code m}, about {@code pivot}:
     * {@code T(pivot + position) * Rxyz * S * T(-pivot)}, the editor's own
     * order.
     *
     * @param values a step's nine values, or null for rest
     */
    public static void composeStep(Matrix4f m, float[] pivot, float[] values) {
        if (pivot == null || pivot.length != 3) return;
        float[] v = values == null ? REST_STEP : values;
        // Same px -> block-space mapping as the editor viewport: (v-8)/16,
        // with the entity sitting at the block center.
        float px = (pivot[0] - 8f) / 16f;
        float py = (pivot[1] - 8f) / 16f;
        float pz = (pivot[2] - 8f) / 16f;
        m.translate(px + v[3] / 16f, py + v[4] / 16f, pz + v[5] / 16f);
        m.rotateXYZ((float) Math.toRadians(v[0]),
                (float) Math.toRadians(v[1]),
                (float) Math.toRadians(v[2]));
        m.scale(nonSingular(v[6]), nonSingular(v[7]), nonSingular(v[8]));
        m.translate(-px, -py, -pz);
    }

    /**
     * A step's values {@code amount} of the way from {@code from} to
     * {@code to}, either of which may be null for rest.
     *
     * <p><strong>A rotation goes the short way round.</strong> The target
     * angle is taken plus or minus whole turns, whichever lands nearest the
     * start, so a wheel at 350 degrees asked for 10 turns twenty degrees on
     * rather than three hundred and forty back. What comes out at
     * {@code amount == 1} is therefore the target's angle up to whole turns,
     * which composes to the same rotation.
     */
    public static float[] lerpStep(float[] from, float[] to, float amount) {
        return lerpStep(from, to, null, amount);
    }

    /**
     * As above, with the whole turns each rotation goes by given as
     * {@code turns} rather than decided here — see {@link #turnsBetween}.
     * Null decides them now, which is right for a target that stands still.
     */
    public static float[] lerpStep(float[] from, float[] to, float[] turns, float amount) {
        float[] a = from == null ? REST_STEP : from;
        float[] b = to == null ? REST_STEP : to;
        float s = Math.min(1f, Math.max(0f, amount));
        float[] out = new float[STEP_VALUES];
        for (int i = 0; i < 3; i++) {
            float target = turns == null ? nearestTurn(a[i], b[i]) : b[i] + turns[i];
            out[i] = a[i] + (target - a[i]) * s;
        }
        for (int i = 3; i < STEP_VALUES; i++) {
            out[i] = a[i] + (b[i] - a[i]) * s;
        }
        return out;
    }

    /** Every step of a program, {@code amount} of the way from one pose to another. */
    public static float[][] lerpProgram(float[][] from, float[][] to, int steps, float amount) {
        return lerpProgram(from, to, null, steps, amount);
    }

    /** As above, going by the turns {@link #turnsBetween} decided when the fade began. */
    public static float[][] lerpProgram(float[][] from, float[][] to, float[][] turns, int steps, float amount) {
        float[][] out = new float[steps][];
        for (int i = 0; i < steps; i++) {
            out[i] = lerpStep(stepOf(from, i), stepOf(to, i), stepOf(turns, i), amount);
        }
        return out;
    }

    /**
     * Per step and axis, the whole turns to add to {@code to} so that each
     * rotation goes the short way round from {@code from} — in degrees, a
     * multiple of 360.
     *
     * <p><strong>Decided once, when a fade starts, and then kept.</strong> The
     * target of a fade moves: the cycle being faded into plays on underneath
     * it. Re-deciding the short way every tick against a target drifting
     * round the far side of the start flips the answer by a whole turn the
     * moment it crosses — the same bone asked for +179 on one tick and -181
     * on the next — and the pose sent that tick is half a turn from the last
     * one, which is the bulge this whole file exists to prevent, arriving
     * from inside the mechanism meant to prevent it. Taken once and kept,
     * the target moves continuously and so does the fade.
     */
    public static float[][] turnsBetween(float[][] from, float[][] to, int steps) {
        float[][] turns = new float[steps][];
        for (int i = 0; i < steps; i++) {
            float[] a = stepOf(from, i) == null ? REST_STEP : stepOf(from, i);
            float[] b = stepOf(to, i) == null ? REST_STEP : stepOf(to, i);
            turns[i] = new float[] {
                    nearestTurn(a[0], b[0]) - b[0],
                    nearestTurn(a[1], b[1]) - b[1],
                    nearestTurn(a[2], b[2]) - b[2],
            };
        }
        return turns;
    }

    /**
     * How far, in degrees, the biggest single-axis rotation between two
     * poses of a program is, the short way round.
     *
     * <p>The number a crossfade's length is set from. Position does not
     * count: a translation tweens exactly, and it is the rotation that
     * swings a part off its pivot mid-tween.
     */
    public static float turnBetween(float[][] from, float[][] to, int steps) {
        float most = 0f;
        for (int i = 0; i < steps; i++) {
            float[] a = stepOf(from, i) == null ? REST_STEP : stepOf(from, i);
            float[] b = stepOf(to, i) == null ? REST_STEP : stepOf(to, i);
            for (int axis = 0; axis < 3; axis++) {
                most = Math.max(most, Math.abs(nearestTurn(a[axis], b[axis]) - a[axis]));
            }
        }
        return most;
    }

    /**
     * How many ticks a crossfade needs so that no single send turns any bone
     * of it by more than {@code degreesPerSend}, at one send every
     * {@code period} ticks. Never less than one send.
     */
    public static int fadeTicks(float[][] from, float[][] to, int steps, int period, float degreesPerSend) {
        int sends = Math.max(1, (int) Math.ceil(turnBetween(from, to, steps) / degreesPerSend));
        return sends * period;
    }

    /**
     * {@code target} with each held step's rotation put back over it.
     *
     * <p>For a bone the playing cycle says nothing about that the last one
     * spun: its rotation is the angle it stopped at rather than the cycle's
     * (or rest's) zero, and its position and scale are whatever the cycle
     * says. Returns {@code target} itself when nothing is held.
     *
     * @param held per step, the rotation xyz to hold, or null; null as a whole
     *             for nothing held
     */
    public static float[][] holdRotations(float[][] target, float[][] held, int steps) {
        if (held == null) return target;
        float[][] out = new float[steps][];
        for (int i = 0; i < steps; i++) {
            float[] base = stepOf(target, i);
            float[] keep = stepOf(held, i);
            if (keep == null) {
                out[i] = base;
                continue;
            }
            float[] v = (base == null ? REST_STEP : base).clone();
            v[0] = keep[0];
            v[1] = keep[1];
            v[2] = keep[2];
            out[i] = v;
        }
        return out;
    }

    /** {@code to} plus whole turns, whichever is nearest {@code from}. */
    static float nearestTurn(float from, float to) {
        return to + 360f * Math.round((from - to) / 360f);
    }

    private static float[] stepOf(float[][] values, int i) {
        return values == null || i >= values.length ? null : values[i];
    }

    public static Matrix4f toItemDisplaySpace(Matrix4f modelTransform) {
        return new Matrix4f()
                .rotateY((float) Math.PI)
                .mul(modelTransform)
                .rotateY((float) -Math.PI);
    }

    /**
     * Decomposes into Bukkit's {@link Transformation}.
     *
     * <p>Exact for a single step; a nested bone-and-cube step with non-uniform
     * bone scale is approximate.
     */
    public static Transformation toTransformation(Matrix4f m) {
        Vector3f translation = m.getTranslation(new Vector3f());
        // Animated scale means the basis vectors are not unit length.
        // getNormalizedRotation assumes they are and can emit a non-unit
        // quaternion, which the client interpolates as a rapid spin.
        Quaternionf rotation = m.getUnnormalizedRotation(new Quaternionf()).normalize();
        // Belt and braces alongside nonSingular(): a bone scale and a cube
        // scale multiplying out to near zero can still degenerate the basis.
        // Identity is safe, since a basis that small is invisible anyway.
        if (!isFinite(rotation)) {
            rotation = new Quaternionf();
        }
        Vector3f scale = m.getScale(new Vector3f());
        return new Transformation(translation, rotation, scale, new Quaternionf());
    }

    /** Keeps a scale away from zero. See {@link #MIN_SCALE}. */
    public static float nonSingular(float scale) {
        if (Float.isNaN(scale)) {
            return MIN_SCALE;
        }
        if (Math.abs(scale) >= MIN_SCALE) {
            return scale;
        }
        return scale < 0 ? -MIN_SCALE : MIN_SCALE;
    }

    private static boolean isFinite(Quaternionf q) {
        return Float.isFinite(q.x) && Float.isFinite(q.y) && Float.isFinite(q.z) && Float.isFinite(q.w);
    }

    /** The same transform for a part with no animation program — see applyRigScale. */
    public static Transformation scaledTransformation(float scale) {
        Matrix4f m = new Matrix4f();
        m.scaleLocal(scale);
        m.translateLocal(0f, 0.5f * (scale - 1f), 0f);
        return toTransformation(m);
    }

}
