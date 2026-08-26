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
 * <p>Ported from {@code RigAnimator} in {@code the previous engine}. Every
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
}
