package ai.resourcepack.engine.api;

/**
 * One keyframe: a time, three numbers, and how it reaches the next one.
 *
 * <p><b>Fields rather than accessors, and mutable.</b> Deliberate: gson fills
 * these straight off a manifest reflectively, and a value type with a
 * constructor gson cannot call is a value type that arrives empty. The shape
 * is set by the emote and rig manifests Studio writes.
 *
 * <p>Three numbers rather than a named triple because the same type carries
 * rotation, position and scale, and a class per channel would be three copies
 * of one thing differing only in what the axes are called.
 */
public final class Keyframe {

    /** Seconds from the start of the animation. */
    public double time;

    /** Three axes. Null on a malformed manifest, which the sampler treats as absent. */
    public float[] value;

    /**
     * {@code "smooth"}, {@code "step"}, or null for linear.
     *
     * <p>A string rather than an enum because it is what gson finds in the
     * file, and a word a newer studio invented must not fail the parse of a
     * whole pack. Anything unrecognised reads as linear.
     */
    public String interpolation;

    public Keyframe() {
    }

    public Keyframe(double time, float[] value, String interpolation) {
        this.time = time;
        this.value = value;
        this.interpolation = interpolation;
    }
}
