package ai.resourcepack.engine.core.emote;

import org.joml.Matrix4f;

/**
 * Where the thing a player is holding sits on their rig.
 *
 * <p><b>An emote replaces the player, and until this it did not replace what
 * they were holding.</b> The body is hidden two ways — {@code hidePlayer} for
 * everybody else, invisibility for the emoter's own view — and neither takes an
 * item out of a hand: {@code hidePlayer} stops the entity being sent at all, so
 * other people saw a rig holding nothing, while the potion hides a SKIN and
 * leaves the held item rendering, so the emoter saw their own sword floating at
 * an invisible body that was standing where the rig had been led away from.
 * Two different wrong pictures from one missing piece.
 *
 * <p>So the rig grows a hand: one {@link org.bukkit.entity.ItemDisplay} per
 * hand, carrying the player's real stack, composed through the arm's bone chain
 * exactly the way a prop attached to {@code rightarm} already is. It follows a
 * bent elbow because it hangs off the forearm, and it swings when the arm
 * swings, because it is downstream of the arm in the same matrix.
 *
 * <h2>The socket is studio's, not a guess</h2>
 *
 * Every number here is read off {@code the model editor's display frames}
 * — its {@code thirdPersonSocketPx}, which is the frame that app calibrates a
 * model's {@code thirdperson_righthand} display transform against. That is the
 * one place in the product that already had to answer "where is the hand", and
 * it answered it against the real game. Deriving a second answer here is how
 * the editor and the plugin end up disagreeing about where a held thing lands,
 * which has happened more than once in this subsystem and is always found in
 * game rather than in a test.
 *
 * <p>Studio states the socket with vanilla's 22.5-degree third-person arm pose
 * baked in, because it is drawing a still. Here the arm's angle comes from the
 * emote, so what is taken is the part that is about the HAND rather than about
 * the pose: ten pixels down the arm and two forward of it — vanilla's
 * 0.625/0.125-block in-hand offsets, in {@code ItemInHandLayer}.
 *
 * <p><b>The rotation is the part that is derived rather than copied</b>, and it
 * is the one thing here worth checking in a real client before believing:
 * studio's frame folds the arm pose into it, so it cannot be lifted whole.
 * See {@link #orient}.
 */
final class HeldItem {

    private HeldItem() {
    }

    /**
     * The bone a hand hangs off, as an emote's {@code attach} names it.
     *
     * <p>Deliberately the same strings a prop uses, so both go through
     * {@link EmoteStore#attachEndBone} and land on the forearm where the
     * skeleton has one. A hand that resolved its own bone would be a second
     * opinion about which end of the arm a hand is on.
     */
    static final String MAIN_HAND_ATTACH = "rightarm";
    static final String OFF_HAND_ATTACH = "leftarm";

    /**
     * The shoulder's height in rig px — studio's {@code rightArm} pivot y.
     *
     * <p>Rig space, so feet at y=0 and +y up. Not read from the manifest even
     * though the manifest carries it, because what follows is arithmetic
     * against studio's socket and the two have to be measured from the same
     * point. A pack whose arm pivot moved would be a pack whose whole skeleton
     * moved, and the bone table would be the smaller of the problems.
     */
    private static final float SHOULDER_Y_PX = 22f;

    /** How far down the arm the hand grips — vanilla's 0.625 blocks. */
    private static final float HAND_DOWN_PX = 10f;

    /**
     * How far the grip is lifted back up, in px. <b>Calibrated in game.</b>
     *
     * <p>Everything else in this file is arithmetic against studio's socket and
     * against vanilla's own offsets, and by that arithmetic the grip belongs at
     * {@link #HAND_DOWN_PX} below the shoulder and nothing else. In a real
     * client it sits low, and this is the correction — a measured number rather
     * than a derived one, which is why it is its own constant instead of being
     * folded into the ten above. Folding it in would erase the fact that ten is
     * Mojang's and this is ours.
     *
     * <p>Part of the gap is explainable and is not worth trying to remove:
     * {@code PLAYER_SCALE} shrinks the whole rig to 15/16 about its anchor, so
     * every point on it — the hand included — sits slightly lower in the world
     * than the same point on an unscaled body. The hand is correct relative to
     * the ARM it is on, which is what matters, and chasing the rest of the
     * difference would move the item off the rig's own hand to line it up with
     * a body nobody can see.
     *
     * <p><b>If it still reads wrong, this is the number to change and the only
     * one.</b> Positive lifts.
     */
    private static final float HAND_LIFT_PX = 2f;

    /** How far in front of the arm the item sits — vanilla's 0.125 blocks. */
    private static final float HAND_FORWARD_PX = 2f;

    /** The shoulder's distance from the centre line, studio's pivot x. */
    private static final float SHOULDER_X_PX = 4f;

    /** Arm widths. A slim skin's arm is 3px where a wide one's is 4. */
    private static final float ARM_WIDTH_WIDE_PX = 4f;
    private static final float ARM_WIDTH_SLIM_PX = 3f;

    /**
     * Rig px to the block offsets a composed bone matrix works in.
     *
     * <p>Two conversions the rest of this subsystem already does separately,
     * composed once here so neither can be applied twice or forgotten:
     * studio's {@code toModelPoint} takes rig space (feet at 0) to block-model
     * space ({@code [x+8, y+8-RIG_ORIGIN_PX, z+8]}, and RIG_ORIGIN_PX is 16),
     * and {@link ai.resourcepack.engine.core.animation.RigMath#applyStep} then
     * maps a model-px coordinate {@code v} to {@code (v-8)/16} blocks. The x
     * and z halves cancel to a plain divide and only y keeps an offset, which
     * is exactly the asymmetry studio's own comment on {@code toModelPoint}
     * warns about — applying the vertical shift to all three axes puts the body
     * a block sideways.
     */
    private static float[] rigPxToBlocks(float x, float y, float z) {
        return new float[] {x / 16f, (y - 16f) / 16f, z / 16f};
    }

    /** The width of this player's arms, in px. See {@link SkinModel}. */
    private static float armWidthPx(boolean slim) {
        return slim ? ARM_WIDTH_SLIM_PX : ARM_WIDTH_WIDE_PX;
    }

    /**
     * The hand's resting place, in the space a composed bone matrix moves.
     *
     * <p>Along the arm's own centre line rather than on the shoulder's — an
     * arm is 3 or 4px wide and its pivot is on the inner edge, so a hand on the
     * pivot holds the sword inside the sleeve. That is the whole of what the
     * variant changes here, and it is why this takes {@code slim} at all.
     *
     * <p>Absolute, not a delta from the joint: a composed matrix transforms the
     * whole model space rather than being a frame at the bone, which is the
     * same reading {@code applyPropStep} depends on and the authoring format
     * states for a prop's offset ("measured from the RIG ORIGIN, not from the
     * joint it names").
     */
    static float[] socketPoint(boolean offHand, boolean slim) {
        float side = offHand ? -1f : 1f;
        float x = side * (SHOULDER_X_PX + armWidthPx(slim) / 2f);
        float y = SHOULDER_Y_PX - HAND_DOWN_PX + HAND_LIFT_PX;
        // Forward is -z: Studio's emote skeleton states the character faces -z,
        // and the entity-model convention its Studio's player rig draws agrees.
        float z = -HAND_FORWARD_PX;
        return rigPxToBlocks(x, y, z);
    }

    /**
     * Turns the item the way a hand holds it.
     *
     * <p>Vanilla's {@code ItemInHandLayer.renderArmWithItem} orients a held
     * stack with an X turn of -90 followed by a Y turn of 180, and that pair is
     * reproduced here in that order. It is NOT taken from studio's
     * {@code slotBaseFrame}, which states {@code [-90 + 22.5, 0, 0]} — that
     * value has the third-person arm POSE folded into it, because studio is
     * drawing an item on a still figure whose arm it also has to place. Here
     * the arm's angle arrives from the emote's own keyframes, so folding a
     * fixed 22.5 in would tilt every held item by the pose of a rig that is not
     * in that pose.
     *
     * <p><b>Why an ItemDisplay needs this at all</b>, when it is already told
     * the item is in a third-person right hand: that transform makes the client
     * apply the item MODEL's own {@code display.thirdperson_righthand} block —
     * a sword's own tilt in its own file — and nothing more. The socket is the
     * renderer layer's, and a display entity is not on a player, so no layer
     * runs. Which is the reason to use the transform rather than hand-tuning:
     * every item, vanilla or custom, brings its own correct in-hand orientation
     * and only the hand has to be found.
     */
    static void orient(Matrix4f m) {
        m.rotateX((float) (-Math.PI / 2.0));
        m.rotateY((float) Math.PI);
    }

    /**
     * The whole hand step, appended to whatever the arm chain has already done.
     *
     * <p>Translate to the socket, then turn about where that landed — the same
     * shape and the same order as {@link EmoteDirector#applyPropStep}, so a
     * held item and a prop attached to the same arm are composed identically
     * and cannot drift apart.
     */
    static void applyTo(Matrix4f m, boolean offHand, boolean slim) {
        float[] socket = socketPoint(offHand, slim);
        m.translate(socket[0], socket[1], socket[2]);
        orient(m);
    }
}
