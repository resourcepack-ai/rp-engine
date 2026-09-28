package ai.resourcepack.engine.core.armor3d;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pose against the game's own matrix stack.
 *
 * <p>Every check here runs a point through {@code LivingEntityRenderer}'s chain
 * as the client does — turn, flip, scale, drop, the part's own offset and
 * rotation — and through what a display is sent, and asks for the same place
 * in the world. The frames between the two are where this is easy to get
 * wrong, and a sign error shows up as armour on the wrong side of a limb, not
 * as an exception.
 */
class WornPoseTest {

    private static final float EPS = 1e-4f;

    /** Where the client draws a model-space point on part {@code offset/turn}, relative to the feet. */
    private static Vector3f vanilla(float bodyYaw, boolean crouched, float[] offset, float[] turn, Vector3f local) {
        Matrix4f m = new Matrix4f()
                .translate(0, crouched ? -2f / 16f : 0f, 0)
                .rotateY((float) Math.toRadians(180f - bodyYaw))
                .scale(-1f, -1f, 1f)
                .scale(0.9375f)
                .translate(0, -1.501f, 0)
                .translate(offset[0] / 16f, offset[1] / 16f, offset[2] / 16f)
                .rotateZYX(turn[2], turn[1], turn[0]);
        return m.transformPosition(new Vector3f(local).div(16f));
    }

    /** Where a display at the feet draws item-local point {@code u} for this part. */
    private static Vector3f ours(WornPose.Pose pose, WornPose.Limb limb, float[] anchor, float[] partTurn,
                                 float bodyYaw, boolean crouched, Vector3f u) {
        Vector3f t = new Vector3f();
        Quaternionf q = WornPose.place(pose, limb, anchor, partTurn, bodyYaw, crouched, t);
        // ItemDisplayRenderer's own half-turn, then the transformation.
        Vector3f p = new Quaternionf().rotateY((float) Math.PI).transform(new Vector3f(u));
        q.transform(p);
        return p.mul(WornPose.PLAYER_SCALE).add(t);
    }

    private static void assertClose(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPS, "x of " + actual + " vs " + expected);
        assertEquals(expected.y, actual.y, EPS, "y of " + actual + " vs " + expected);
        assertEquals(expected.z, actual.z, EPS, "z of " + actual + " vs " + expected);
    }

    /** A point on a limb, in the wearer's frame, and the same point as the game's part-local model space. */
    private static Vector3f modelLocal(WornPose.Limb limb, Vector3f wearer) {
        Vector3f rest = limb.restPivot();
        return new Vector3f(wearer.x - rest.x, -(wearer.y - rest.y), -(wearer.z - rest.z));
    }

    @Test
    void aStridingLegMatchesTheRenderer() {
        WornPose.State s = new WornPose.State();
        s.walkPosition = 3.7f;
        s.walkSpeed = 0.8f;
        WornPose.Pose pose = WornPose.pose(s);
        float rx = (float) Math.cos(3.7f * 0.6662f) * 1.4f * 0.8f;
        float[] anchor = {-2.5f, 6f, 1.5f};
        Vector3f u = new Vector3f(0.05f, -0.1f, 0.12f);
        Vector3f point = new Vector3f(anchor[0], anchor[1], anchor[2]).add(new Vector3f(u).mul(16f));
        for (float yaw : new float[]{0f, 37f, -120f, 180f}) {
            Vector3f expected = vanilla(yaw, false, new float[]{-1.9f, 12f, 0f},
                    new float[]{rx, 0.005f, 0.005f}, modelLocal(WornPose.Limb.RIGHT_LEG, point));
            assertClose(expected, ours(pose, WornPose.Limb.RIGHT_LEG, anchor, null, yaw, false, u));
        }
    }

    @Test
    void aCrouchingBodyMatchesTheRenderer() {
        WornPose.State s = new WornPose.State();
        s.crouching = true;
        WornPose.Pose pose = WornPose.pose(s);
        float[] anchor = {0f, 20f, 2.5f};
        Vector3f u = new Vector3f(-0.2f, 0.1f, 0.03f);
        Vector3f point = new Vector3f(anchor[0], anchor[1], anchor[2]).add(new Vector3f(u).mul(16f));
        Vector3f expected = vanilla(64f, true, new float[]{0f, 3.2f, 0f}, new float[]{0.5f, 0f, 0f},
                modelLocal(WornPose.Limb.BODY, point));
        assertClose(expected, ours(pose, WornPose.Limb.BODY, anchor, null, 64f, true, u));
    }

    @Test
    void aPartsOwnTurnIsInnermost() {
        WornPose.State s = new WornPose.State();
        WornPose.Pose pose = WornPose.pose(s);
        float[] anchor = {0f, 18f, 3f};
        float[] turn = {20f, -35f, 10f};
        Vector3f u = new Vector3f(0.1f, 0f, 0f);
        // The part turned about its anchor, XYZ as a matrix, then placed.
        Vector3f turned = new Quaternionf().rotateXYZ((float) Math.toRadians(20), (float) Math.toRadians(-35),
                (float) Math.toRadians(10)).transform(new Vector3f(u).mul(16f));
        Vector3f point = new Vector3f(anchor[0], anchor[1], anchor[2]).add(turned);
        Vector3f expected = vanilla(0f, false, new float[]{0f, 0f, 0f}, new float[]{0f, 0f, 0f},
                modelLocal(WornPose.Limb.BODY, point));
        assertClose(expected, ours(pose, WornPose.Limb.BODY, anchor, turn, 0f, false, u));
    }

    @Test
    void theFrontIsWhereTheBodyFaces() {
        WornPose.Pose pose = WornPose.pose(new WornPose.State());
        Vector3f t = new Vector3f();
        // A point a few pixels in front of the chest, body facing west (90).
        WornPose.place(pose, WornPose.Limb.BODY, new float[]{0f, 18f, 6f}, null, 90f, false, t);
        assertTrue(t.x < -0.3f, "facing west puts the front at -X, got " + t);
        assertEquals(0f, t.z, EPS);
    }

    @Test
    void theStrideIsVanillasFilter() {
        float[] walk = new float[2];
        WornPose.walk(walk, 0.2);
        // min(0.2 * 4, 1) = 0.8, chased at 0.4
        assertEquals(0.32f, walk[1], EPS);
        assertEquals(0.32f, walk[0], EPS);
        WornPose.walk(walk, 5);
        assertEquals(0.32f + (1f - 0.32f) * 0.4f, walk[1], EPS);
    }

    @Test
    void theBodyTurnsToWalkAndStaysWithinFiftyOfTheLook() {
        // Walking north (-Z) while looking north: the body swings round to 180.
        float body = 0f;
        for (int i = 0; i < 40; i++) {
            body = WornPose.bodyYaw(body, 180f, 0, -0.2, false);
        }
        assertEquals(180f, Math.abs(WornPose.wrap(body)), 0.5f);
        // Standing and looking 120 away: the neck holds at 50.
        body = WornPose.bodyYaw(0f, 120f, 0, 0, false);
        assertEquals(70f, body, 0.5f);
        // Walking backwards does not turn the body round.
        body = 0f;
        for (int i = 0; i < 40; i++) {
            body = WornPose.bodyYaw(body, 0f, 0, -0.2, false);
        }
        assertEquals(0f, WornPose.wrap(body), 0.5f);
    }

    @Test
    void bonesRideTheRightLimbs() {
        assertEquals(WornPose.Limb.BODY, WornPose.limbOf("waist"));
        assertEquals(WornPose.Limb.LEFT_LEG, WornPose.limbOf("left_foot"));
        assertEquals(WornPose.Limb.RIGHT_ARM, WornPose.limbOf("right_arm"));
        assertEquals(null, WornPose.limbOf("tail"));
    }
}
