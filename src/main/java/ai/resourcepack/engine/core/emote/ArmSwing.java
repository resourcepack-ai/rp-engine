package ai.resourcepack.engine.core.emote;

import org.joml.Matrix4f;

/**
 * Vanilla's arm swing, on a rig that has replaced the player.
 *
 * <p><b>Nothing about a swing reached the rig before this.</b> A player wearing
 * an emote is hidden behind it, so the swing vanilla plays on their own body is
 * played on an entity nobody is sent and nobody can see. Hitting things while
 * emoting looked like hitting nothing: the damage landed, the sound played, and
 * the figure on screen carried on with its walk cycle.
 *
 * <h2>Why this is not an EmoteTrigger</h2>
 *
 * A trigger is a STATE — exactly one holds, it is resolved by asking the player
 * what they are doing on each pass of the tick loop, and it names a whole emote
 * that takes the rig over. A swing is none of those things. It is a moment, it
 * coexists with whatever the body is otherwise doing (you can swing while
 * sprinting), and it lasts six ticks.
 *
 * <p>Modelling it as a state is also what makes it late, which is the thing it
 * most needs not to be: a state is discovered by a poll, so the swing would be
 * found up to a full tick after the click that caused it, and would then have to
 * take over the rig — restarting a walk cycle to play a swing and restarting it
 * again on the way back out. So this is an OVERLAY instead, armed straight from
 * {@code PlayerAnimationEvent} and composed on top of whatever emote is already
 * playing, and no manifest has to name anything for a player to swing.
 *
 * <h2>The curve is Mojang's</h2>
 *
 * Read off {@code HumanoidModel.setupAttackAnimation}. The shape matters more
 * than it looks: {@code 1 - (1-p)^4} rises almost immediately, so by the first
 * tick after the click the arm is already at 99% of its travel, and the whole
 * of the rest of the six ticks is the arm coming back down. That is why a
 * swing reads as a snap rather than as a sweep, and why sampling it a tick late
 * loses the only frame anybody sees.
 *
 * <p>One term of vanilla's is deliberately dropped: it adds
 * {@code sin(p*PI) * -(head.xRot - 0.7) * 0.75} to the pitch, coupling the
 * swing to where the player is looking. An emote's head is posed by its own
 * keyframes and does not track the mouse, so there is no pitch to couple to —
 * including it would tilt the swing by a number that is always the same.
 */
final class ArmSwing {

    private ArmSwing() {
    }

    /**
     * How many ticks a swing runs for — vanilla's own swing duration.
     *
     * <p>{@code LivingEntity.getCurrentSwingDuration} answers 6 without haste
     * or mining fatigue. Those two shorten it for the player's real body, and
     * are not read here: this is an animation on a display entity rather than
     * a mining rate, and a swing that changed length with a potion would be a
     * difference nobody would attribute to the potion.
     */
    static final int SWING_TICKS = 6;

    /** Peak forward travel, radians. Vanilla's {@code f1 * 1.2}. */
    private static final float PITCH_PEAK = 1.2f;

    /** Peak outward tilt, radians. Vanilla's {@code sin(p*PI) * -0.4}. */
    private static final float ROLL_PEAK = -0.4f;

    /**
     * The tick value meaning "no swing has ever been armed".
     *
     * <p>A named sentinel rather than a bare {@code Long.MIN_VALUE} because
     * {@link #running} has to test for it BEFORE doing any arithmetic with it:
     * {@code nowTick - Long.MIN_VALUE} overflows back to a negative, which read
     * as a swing that started long ago and had somehow not finished. Nothing
     * moved on screen — the curve returns zero outside 0..1 — so this would have
     * sat there being wrong quietly, which is the kind of thing that surfaces
     * later as an unrelated symptom.
     */
    static final long NOT_SWINGING = Long.MIN_VALUE;

    /**
     * How far through the swing a given tick is, or a value past 1 when it is
     * over.
     *
     * <p><b>The {@code +1} is the whole of "instant" and is not an off-by-one.</b>
     * The event that arms a swing arrives partway through a tick and is posed
     * immediately, so the frame that pose puts on the wire is the one the client
     * will render NEXT — the position vanilla's own client would draw one tick
     * after the click, having started the animation locally. Sampling the curve
     * at zero there would send a pose identical to the one already on screen and
     * put the first visible movement a tick later, which is the delay this whole
     * mechanism exists to remove.
     */
    static double progress(long startTick, long nowTick) {
        return (nowTick - startTick + 1) / (double) SWING_TICKS;
    }

    /**
     * Whether a swing armed at {@code startTick} is still running.
     *
     * <p>Counted in whole ticks rather than by testing {@link #progress}
     * against 1, so the sentinel and a world whose game time went backwards are
     * both answered before any subtraction happens. Six ticks means offsets 0
     * through 5, which is the six frames {@code progress} maps onto 1/6 .. 1.
     */
    static boolean running(long startTick, long nowTick) {
        if (startTick == NOT_SWINGING || nowTick < startTick) return false;
        return nowTick - startTick < SWING_TICKS;
    }

    /**
     * The forward part of the swing, radians, about the rig's x axis.
     *
     * <p>Positive is forward, and that follows from the rig's own convention
     * rather than from vanilla's sign: studio's {@code Studio's emote skeleton} states +y
     * up, the character facing -z and their right hand at +x, so a right-handed
     * turn about +x takes the hanging arm (-y) toward -z, which is in front of
     * them. Vanilla's own {@code xRot -= ...} is the same motion said in a model
     * space whose y points down.
     */
    static float pitch(double progress) {
        if (progress <= 0 || progress > 1) return 0f;
        double eased = 1.0 - Math.pow(1.0 - progress, 4);
        return (float) (Math.sin(eased * Math.PI) * PITCH_PEAK);
    }

    /**
     * The outward tilt that goes with it, radians, about the rig's z axis.
     *
     * <p>Mirrored for the off hand, because a turn that takes the right arm
     * away from the body takes the left one into it.
     */
    static float roll(double progress, boolean offHand) {
        if (progress <= 0 || progress > 1) return 0f;
        double amount = Math.sin(progress * Math.PI) * ROLL_PEAK;
        return (float) (offHand ? -amount : amount);
    }

    /**
     * Composes the swing into a bone's matrix, about that bone's joint.
     *
     * <p>Same translate-rotate-translate-back shape as
     * {@link ai.resourcepack.engine.core.animation.RigMath#applyStep}, and for
     * the same reason: a rotation that is not taken about the joint moves the
     * shoulder as well as turning it. It is applied to the upper arm AFTER the
     * emote's own keyframes, so the swing is added to whatever the emote was
     * doing rather than replacing it — and every bone below (the forearm) and
     * everything hanging off them (the held item) rides it, because they are
     * composed from this matrix.
     *
     * <p>{@code pivot} is in block-model px, exactly as it arrives from the
     * manifest.
     */
    static void applyTo(Matrix4f m, float[] pivot, double progress, boolean offHand) {
        if (pivot == null || pivot.length != 3) return;
        float pitch = pitch(progress);
        float roll = roll(progress, offHand);
        if (pitch == 0f && roll == 0f) return;

        float px = (pivot[0] - 8f) / 16f;
        float py = (pivot[1] - 8f) / 16f;
        float pz = (pivot[2] - 8f) / 16f;
        m.translate(px, py, pz);
        m.rotateX(pitch);
        m.rotateZ(roll);
        m.translate(-px, -py, -pz);
    }
}
