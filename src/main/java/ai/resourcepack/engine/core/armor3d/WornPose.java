package ai.resourcepack.engine.core.armor3d;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where the game is drawing each limb of a player, worked out on the server.
 *
 * <p><strong>Every number here is Mojang's, from {@code HumanoidModel},
 * {@code LivingEntityRenderer} and {@code PlayerRenderer}, and none of them may
 * be tuned by eye.</strong> 3D armour is a set of display entities that have
 * to sit on limbs the CLIENT is animating, from the same movement the server
 * relays to it. Nothing tells a plugin where a player's arm is. The only way to
 * put a pauldron on it is to run the arithmetic the client runs, from the same
 * inputs, and arrive at the same answer — so a constant that "looks about right"
 * is a pauldron that drifts off its shoulder at exactly the speed people walk.
 *
 * <p>Pure: no Bukkit, so every rule is a test ({@code WornPoseTest}).
 *
 * <h2>Frames</h2>
 *
 * <p>Two, and the conversion between them is the one place to be careful.
 *
 * <ul>
 *   <li><b>Model space</b> is the game's: pixels, Y DOWN, the face on -Z, the
 *       right arm at -X. {@link #pose} computes in it because every line of
 *       {@code setupAnim} does.</li>
 *   <li><b>The wearer's frame</b> is Studio's, and the manifest's: pixels,
 *       feet at 0, Y UP, the front on +Z, the right hand at -X. A standing
 *       player facing south is drawn in exactly this frame, scaled.</li>
 * </ul>
 *
 * <p>The renderer's chain — turn to {@code 180 - bodyYaw}, scale by
 * {@code (-1, -1, 1)}, scale by 0.9375, drop by 1.501 — collapses, once the
 * model's Y and Z are flipped into the wearer's frame, to "turn by
 * {@code -bodyYaw} and scale by 0.9375/16". A rotation carried across the same
 * flip keeps its X angle and negates its Y and Z angles.
 */
public final class WornPose {

    /** A player model is drawn at 15/16 of a block per 16 pixels. */
    static final float PLAYER_SCALE = 0.9375f;

    /** One wearer-frame pixel, in blocks, at the player's size. */
    static final float PIXEL = PLAYER_SCALE / 16f;

    /**
     * The renderer drops a model by 1.501 blocks rather than 1.5, so every
     * player stands a thousandth of a block (scaled) off the ground. Carried
     * because the tests hold this to the game's own chain, and a constant
     * difference is exactly what they exist to catch.
     */
    static final float RENDER_LIFT = 0.001f * PLAYER_SCALE;

    /** How far a crouching player is drawn below their position. {@code PlayerRenderer.getRenderOffset}. */
    static final float CROUCH_DROP = 2f / 16f;

    /** Vanilla's walk cycle: {@code limbSwing * 0.6662}. */
    private static final float STRIDE = 0.6662f;

    /** The six boxes a player is drawn with. */
    public enum Limb {
        HEAD(0, 0, 0),
        BODY(0, 0, 0),
        RIGHT_ARM(-5, 2, 0),
        LEFT_ARM(5, 2, 0),
        RIGHT_LEG(-1.9f, 12, 0),
        LEFT_LEG(1.9f, 12, 0);

        /** The part's default offset in MODEL space — {@code PlayerModel}'s layer definition. */
        final float x;
        final float y;
        final float z;

        Limb(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** Where it turns when the body stands still, in the wearer's frame. */
        public Vector3f restPivot() {
            return new Vector3f(x, 24 - y, -z);
        }
    }

    /**
     * Which limb a bone rides.
     *
     * <p>MythicArmors' nine names over the game's six boxes: the waist is part
     * of the torso, a foot is the bottom of its leg. Anything else is null,
     * which the runtime skips rather than guessing at.
     */
    public static Limb limbOf(String bone) {
        if (bone == null) {
            return null;
        }
        switch (bone) {
            case "head":
                return Limb.HEAD;
            case "body":
            case "waist":
                return Limb.BODY;
            case "right_arm":
                return Limb.RIGHT_ARM;
            case "left_arm":
                return Limb.LEFT_ARM;
            case "right_leg":
            case "right_foot":
                return Limb.RIGHT_LEG;
            case "left_leg":
            case "left_foot":
                return Limb.LEFT_LEG;
            default:
                return null;
        }
    }

    /** What an arm is doing with what it holds. {@code HumanoidModel.ArmPose}, the ones worth drawing. */
    public enum ArmPose {
        EMPTY, ITEM, BLOCK, BOW
    }

    /** Everything the pose depends on, sampled at one moment. */
    public static final class State {
        /** {@code WalkAnimationState.position}: how far the legs have walked. */
        public float walkPosition;
        /** {@code WalkAnimationState.speed}: how hard they are walking, 0 to 1. */
        public float walkSpeed;
        /** Ticks alive, for the idle arm sway. */
        public float age;
        /** Where the head looks, relative to the BODY, in degrees. */
        public float headYaw;
        public float headPitch;
        public boolean crouching;
        public boolean riding;
        /** Whose main hand is the left one. */
        public boolean leftHanded;
        public ArmPose rightArm = ArmPose.EMPTY;
        public ArmPose leftArm = ArmPose.EMPTY;
        /** How far through a swing, 0 to 1; 0 is not swinging. */
        public float attackTime;
        /** Which arm is swinging. */
        public boolean attackLeft;
    }

    /** Every limb, posed: where it turns and how far, both in the wearer's frame. */
    public static final class Pose {
        final Vector3f[] pivot = new Vector3f[Limb.values().length];
        final Quaternionf[] rotation = new Quaternionf[Limb.values().length];

        public Vector3f pivot(Limb limb) {
            return pivot[limb.ordinal()];
        }

        public Quaternionf rotation(Limb limb) {
            return rotation[limb.ordinal()];
        }
    }

    private WornPose() {
    }

    /**
     * {@code HumanoidModel.setupAnim}, for the parts of it that move a body a
     * piece of armour is glued to.
     *
     * <p>In vanilla's order, because the order is load-bearing: an arm's item
     * pose halves the walk swing it is given, the swing then adds to that, and
     * crouching adds to the lot. Left out on purpose: swimming, gliding and
     * sleeping, which turn the whole body and during which the runtime hides
     * the displays rather than chase it; and the spyglass, crossbow and horn
     * poses, which are held rarely and briefly.
     */
    public static Pose pose(State s) {
        int n = Limb.values().length;
        float[] px = new float[n];
        float[] py = new float[n];
        float[] pz = new float[n];
        float[] rx = new float[n];
        float[] ry = new float[n];
        float[] rz = new float[n];
        for (Limb limb : Limb.values()) {
            px[limb.ordinal()] = limb.x;
            py[limb.ordinal()] = limb.y;
            pz[limb.ordinal()] = limb.z;
        }
        int head = Limb.HEAD.ordinal();
        int body = Limb.BODY.ordinal();
        int ra = Limb.RIGHT_ARM.ordinal();
        int la = Limb.LEFT_ARM.ordinal();
        int rl = Limb.RIGHT_LEG.ordinal();
        int ll = Limb.LEFT_LEG.ordinal();

        rx[head] = rad(s.headPitch);
        ry[head] = rad(s.headYaw);

        float walk = s.walkPosition;
        float amount = Math.min(s.walkSpeed, 1f);
        rx[ra] = cos(walk * STRIDE + PI) * 2f * amount * 0.5f;
        rx[la] = cos(walk * STRIDE) * 2f * amount * 0.5f;
        rx[rl] = cos(walk * STRIDE) * 1.4f * amount;
        rx[ll] = cos(walk * STRIDE + PI) * 1.4f * amount;
        ry[rl] = 0.005f;
        ry[ll] = -0.005f;
        rz[rl] = 0.005f;
        rz[ll] = -0.005f;

        if (s.riding) {
            rx[ra] += -PI / 5f;
            rx[la] += -PI / 5f;
            rx[rl] = -1.4137167f;
            ry[rl] = PI / 10f;
            rz[rl] = 0.07853982f;
            rx[ll] = -1.4137167f;
            ry[ll] = -PI / 10f;
            rz[ll] = -0.07853982f;
        }

        // Main arm first, which only matters for the two-handed bow.
        if (s.leftHanded) {
            poseArm(s.leftArm, true, la, ra, head, rx, ry);
            poseArm(s.rightArm, false, ra, la, head, rx, ry);
        } else {
            poseArm(s.rightArm, false, ra, la, head, rx, ry);
            poseArm(s.leftArm, true, la, ra, head, rx, ry);
        }

        if (s.attackTime > 0f) {
            // setupAttackAnimation, line for line.
            float bodyYRot = sin(sqrt(s.attackTime) * PI * 2f) * 0.2f;
            if (s.attackLeft) {
                bodyYRot *= -1f;
            }
            ry[body] = bodyYRot;
            pz[ra] = sin(bodyYRot) * 5f;
            px[ra] = -cos(bodyYRot) * 5f;
            pz[la] = -sin(bodyYRot) * 5f;
            px[la] = cos(bodyYRot) * 5f;
            ry[ra] += bodyYRot;
            ry[la] += bodyYRot;
            rx[la] += bodyYRot;
            float f = 1f - s.attackTime;
            f *= f;
            f *= f;
            f = 1f - f;
            float lift = sin(f * PI);
            float nod = sin(s.attackTime * PI) * -(rx[head] - 0.7f) * 0.75f;
            int arm = s.attackLeft ? la : ra;
            rx[arm] -= lift * 1.2f + nod;
            ry[arm] += bodyYRot * 2f;
            rz[arm] += sin(s.attackTime * PI) * -0.4f;
        }

        if (s.crouching) {
            rx[body] = 0.5f;
            rx[ra] += 0.4f;
            rx[la] += 0.4f;
            pz[rl] += 4f;
            pz[ll] += 4f;
            py[head] += 4.2f;
            py[body] += 3.2f;
            py[la] += 3.2f;
            py[ra] += 3.2f;
        }

        // AnimationUtils.bobModelPart: the idle sway, on both arms.
        rz[ra] += cos(s.age * 0.09f) * 0.05f + 0.05f;
        rx[ra] += sin(s.age * 0.067f) * 0.05f;
        rz[la] -= cos(s.age * 0.09f) * 0.05f + 0.05f;
        rx[la] -= sin(s.age * 0.067f) * 0.05f;

        Pose pose = new Pose();
        for (int i = 0; i < n; i++) {
            // Model to wearer: Y and Z flip, so the pivot's Y is measured up
            // from the feet and its Z negated, and a rotation keeps its X angle
            // and loses the sign of the other two. ModelPart turns Z, then Y,
            // then X (rotationZYX), and that order survives the flip.
            pose.pivot[i] = new Vector3f(px[i], 24f - py[i], -pz[i]);
            pose.rotation[i] = new Quaternionf().rotateZ(-rz[i]).rotateY(-ry[i]).rotateX(rx[i]);
        }
        return pose;
    }

    /** {@code HumanoidModel.poseRightArm} / {@code poseLeftArm}. */
    private static void poseArm(ArmPose arm, boolean left, int self, int other, int head,
                                float[] rx, float[] ry) {
        float side = left ? -1f : 1f;
        switch (arm) {
            case ITEM:
                rx[self] = rx[self] * 0.5f - PI / 10f;
                ry[self] = 0f;
                break;
            case BLOCK:
                rx[self] = rx[self] * 0.5f - 0.9424779f;
                ry[self] = side * (-PI / 6f);
                break;
            case BOW:
                // Two-handed: whichever arm holds the bow poses both.
                ry[self] = side * -0.1f + ry[head];
                ry[other] = side * (0.1f + 0.4f) + ry[head];
                rx[self] = -PI / 2f + rx[head];
                rx[other] = -PI / 2f + rx[head];
                break;
            case EMPTY:
            default:
                ry[self] = 0f;
                break;
        }
    }

    // ---- the walk and the body, as the client works them out --------------

    /**
     * {@code WalkAnimationState}, advanced one tick.
     *
     * <p>The client drives it off how far a player moved horizontally in the
     * tick — {@code min(distance * 4, 1)}, chased at 0.4 — and the legs are a
     * cosine of the running total. Fed the same positions, this lands on the
     * same stride.
     *
     * @param state {@code {position, speed}}, updated in place
     */
    public static void walk(float[] state, double distance) {
        float target = (float) Math.min(distance * 4.0, 1.0);
        state[1] += (target - state[1]) * 0.4f;
        state[0] += state[1];
    }

    /**
     * {@code LivingEntity}'s body turn, one tick.
     *
     * <p>A player's body is not where they look: it lags a look by up to 50°
     * and swings round to face the way they are walking, backing up without
     * turning round. The client works it out for every player it draws, from
     * the movement and the look it is sent; this is the same arithmetic on the
     * same inputs.
     *
     * @param body     the body's yaw before this tick, degrees
     * @param look     where they are looking, degrees
     * @param dx       how far they moved in X this tick
     * @param dz       how far they moved in Z this tick
     * @param swinging whether an arm is mid-swing, which squares the body up
     * @return the body's yaw after it
     */
    public static float bodyYaw(float body, float look, double dx, double dz, boolean swinging) {
        float target = body;
        if (dx * dx + dz * dz > 0.0025000002) {
            float moving = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            float off = Math.abs(wrap(look) - moving);
            target = off > 95f && off < 265f ? moving - 180f : moving;
        }
        if (swinging) {
            target = look;
        }
        body += wrap(target - body) * 0.3f;
        float neck = wrap(look - body);
        if (Math.abs(neck) > 50f) {
            body += neck - Math.signum(neck) * 50f;
        }
        return body;
    }

    /** {@code Mth.wrapDegrees}: into [-180, 180). */
    public static float wrap(float degrees) {
        float w = degrees % 360f;
        if (w >= 180f) {
            w -= 360f;
        }
        if (w < -180f) {
            w += 360f;
        }
        return w;
    }

    // ---- one part's display ------------------------------------------------

    /**
     * The transform one part's display carries, relative to a display standing
     * at the player's feet with no yaw of its own.
     *
     * <p>The part's model is centred on its {@code anchor}; standing still,
     * that anchor is {@code anchor} in the wearer's frame, and it moves with
     * its limb as {@code pivot + R_limb · (anchor − restPivot)}. The part's
     * own fixed turn is innermost. Then the renderer's own half-turn: an item
     * display draws its item spun 180° about Y ({@code ItemDisplayRenderer}),
     * so a matching half-turn is put back — the same conjugation
     * {@code RigMath.toItemDisplaySpace} makes for a rig. The scale, uniform
     * and so free to sit either side of the rotation, is
     * {@code PLAYER_SCALE / part.scale}.
     *
     * @param bodyYaw        the BODY's yaw, not the look, degrees
     * @param crouched       whether the renderer drops the player by
     *                       {@link #CROUCH_DROP}
     * @param outTranslation filled with the translation, in blocks
     * @return the rotation
     */
    public static Quaternionf place(Pose pose, Limb limb, float[] anchor, float[] turn, float bodyYaw,
                                    boolean crouched, Vector3f outTranslation) {
        Quaternionf yaw = new Quaternionf().rotateY((float) Math.toRadians(-bodyYaw));
        Quaternionf limbTurn = pose.rotation(limb);
        Vector3f offset = new Vector3f(anchor[0], anchor[1], anchor[2]).sub(limb.restPivot());
        limbTurn.transform(offset);
        offset.add(pose.pivot(limb)).mul(PIXEL);
        yaw.transform(offset);
        offset.y += RENDER_LIFT;
        if (crouched) {
            offset.y -= CROUCH_DROP;
        }
        outTranslation.set(offset);

        Quaternionf rotation = new Quaternionf(yaw).mul(limbTurn);
        if (turn != null && turn.length == 3) {
            rotation.rotateXYZ((float) Math.toRadians(turn[0]), (float) Math.toRadians(turn[1]),
                    (float) Math.toRadians(turn[2]));
        }
        return rotation.rotateY(PI).normalize();
    }

    // ---- float maths, spelled the way Mojang spells it --------------------

    static final float PI = (float) Math.PI;

    private static float rad(float degrees) {
        return degrees * (PI / 180f);
    }

    private static float sin(float v) {
        return (float) Math.sin(v);
    }

    private static float cos(float v) {
        return (float) Math.cos(v);
    }

    private static float sqrt(float v) {
        return (float) Math.sqrt(v);
    }
}
