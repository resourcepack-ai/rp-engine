package ai.resourcepack.engine.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyframes for a puppet, handed in rather than named: the same shape an emote
 * file's {@code animators} and {@code root} have, so an emote drawn in Studio's
 * editor plays without being installed.
 *
 * <p>Bone keys are the rig's: {@code head}, {@code body}, {@code rightArm},
 * {@code leftArm}, {@code rightLeg}, {@code leftLeg}, and on a jointed rig
 * {@code rightForearm}, {@code leftForearm}, {@code rightShin},
 * {@code leftShin}. Each bone maps a channel ({@code rotation} in degrees,
 * {@code position} in pixels, {@code scale}) to its keyframes.
 *
 * @since 0.2.92
 */
public final class PuppetAnimation {

    private final Map<String, Map<String, List<Keyframe>>> bones;
    private final Map<String, List<Keyframe>> root;
    private final double length;
    private final boolean loop;

    public PuppetAnimation(Map<String, Map<String, List<Keyframe>>> bones,
                           Map<String, List<Keyframe>> root, double length, boolean loop) {
        this.bones = bones == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(bones));
        this.root = root == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(root));
        this.length = Double.isFinite(length) && length > 0 ? length : 0;
        this.loop = loop;
    }

    public Map<String, Map<String, List<Keyframe>>> bones() {
        return bones;
    }

    /** The whole body's own channels, or null. */
    public Map<String, List<Keyframe>> root() {
        return root;
    }

    /** Seconds; 0 holds the first frame. */
    public double length() {
        return length;
    }

    public boolean loop() {
        return loop;
    }
}
