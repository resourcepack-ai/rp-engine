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
        // Same px -> block-space mapping as the editor viewport: (v-8)/16,
        // with the entity sitting at the block center.
        float px = (pivot[0] - 8f) / 16f;
        float py = (pivot[1] - 8f) / 16f;
        float pz = (pivot[2] - 8f) / 16f;
        float[] rot = Sampler.sample(animator, "rotation", t, ZERO);
        float[] pos = Sampler.sample(animator, "position", t, ZERO);
        float[] scl = Sampler.sample(animator, "scale", t, ONE);
        float w = Math.min(1f, weight);
        m.translate(px + pos[0] * w / 16f, py + pos[1] * w / 16f, pz + pos[2] * w / 16f);
        m.rotateXYZ((float) Math.toRadians(rot[0] * w),
                (float) Math.toRadians(rot[1] * w),
                (float) Math.toRadians(rot[2] * w));
        m.scale(nonSingular(1f + (scl[0] - 1f) * w),
                nonSingular(1f + (scl[1] - 1f) * w),
                nonSingular(1f + (scl[2] - 1f) * w));
        m.translate(-px, -py, -pz);
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

    /**
     * A pose {@code amount} of the way from {@code from} to {@code to}.
     *
     * <p><strong>Rotations are slerped, not lerped.</strong> A component-wise
     * average of two quaternions is not a rotation: it shortens as the two
     * diverge, which shows up as a limb shrinking into itself halfway through
     * a transition and springing back out. Position and scale are ordinary
     * linear interpolation, where an average IS the answer.
     */
    public static Transformation mix(Transformation from, Transformation to, float amount) {
        float t = Math.min(1f, Math.max(0f, amount));
        return new Transformation(
                new Vector3f(from.getTranslation()).lerp(to.getTranslation(), t),
                new Quaternionf(from.getLeftRotation()).slerp(to.getLeftRotation(), t),
                new Vector3f(from.getScale()).lerp(to.getScale(), t),
                new Quaternionf(from.getRightRotation()).slerp(to.getRightRotation(), t));
    }
}
