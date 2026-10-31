package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.core.animation.RigMath;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which side of a worn model leads, worked through the animator's own
 * arithmetic. A mob that walks tail first is this going wrong, and nothing
 * else would say so.
 */
class BoundFacingTest {

    /**
     * Where a direction in the model ends up in the world, for a part at rest
     * worn by a host facing {@code yaw} with {@code turn} added: the matrix the
     * animator sends, then the half turn the client gives an item display's
     * model (a fact about the client, which is why it is here as a constant).
     */
    private static Vector3f world(Vector3f inModel, float yaw, float turn) {
        Matrix4f sent = new Matrix4f().rotateY((float) Math.toRadians(-(yaw + turn)))
                .mul(RigMath.toItemDisplaySpace(new Matrix4f()));
        Matrix4f drawn = new Matrix4f(sent).rotateY((float) Math.PI);
        return drawn.transformDirection(new Vector3f(inModel));
    }

    /** Minecraft's heading for a yaw: 0 is south (+z), 90 is west (-x). */
    private static Vector3f heading(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vector3f((float) -Math.sin(r), 0, (float) Math.cos(r));
    }

    private static void assertSame(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-5);
        assertEquals(expected.y, actual.y, 1e-5);
        assertEquals(expected.z, actual.z, 1e-5);
    }

    @Test
    void atTurnZeroTheModelsNorthSideLeads() {
        for (float yaw : new float[]{0, 90, 180, -90, 37}) {
            assertSame(heading(yaw), world(new Vector3f(0, 0, BoundModels.FRONT_Z), yaw, 0));
        }
    }

    @Test
    void aHalfTurnPutsTheSouthSideInFrontWhichIsHowStudioDrawsAModel() {
        for (float yaw : new float[]{0, 90, 180, -90, 37}) {
            assertSame(heading(yaw), world(new Vector3f(0, 0, -BoundModels.FRONT_Z), yaw, 180));
        }
    }

    @Test
    void aQuarterTurnPutsTheWestSideInFront() {
        // Studio's mobFrontInModel says its turn 90 puts the front at +x; on
        // the wire that is 270, which must lead with +x here.
        assertSame(heading(0), world(new Vector3f(1, 0, 0), 0, 270));
        assertSame(heading(0), world(new Vector3f(-1, 0, 0), 0, 90));
    }

    @Test
    void aRearingPolarBearIsFollowedAsTheClientGrowsIt() {
        // One step a tick, six to stand fully, as the client counts.
        assertEquals(2, StandingHosts.step(0, true, 2));
        assertEquals(6, StandingHosts.step(5, true, 2));
        assertEquals(4, StandingHosts.step(6, false, 2));
        assertEquals(0, StandingHosts.step(1, false, 2));
        // Fully up, the box (and the passenger point on top of it) has grown
        // by the whole of its height: the two blocks the model leapt.
        assertEquals(2.0f, StandingHosts.rise(2.0, 6), 1e-6);
        assertEquals(1.0f, StandingHosts.rise(2.0, 3), 1e-6);
        assertEquals(0f, StandingHosts.rise(2.0, 0), 1e-6);
    }

    @Test
    void theLiftTakesThePassengerHeightBackOffAndHalfABlockMore() {
        // A ravager's passenger point at scale 0.909, measured on 1.21.11.
        assertEquals(0.5f - 2.057f, RigAnimator.groundLift(2.05681810826063), 1e-6);
        // A display drawn at its host's feet is a model whose centre is at
        // them, so it still comes up by half a block.
        assertEquals(0.5f, RigAnimator.groundLift(0), 1e-6);
    }
}
