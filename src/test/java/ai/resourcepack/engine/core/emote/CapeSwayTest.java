package ai.resourcepack.engine.core.emote;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of {@link CapeSway} that can be wrong quietly.
 *
 * <p>Everything here is about DIRECTION rather than about magnitude, because a
 * cape whose numbers are a little off looks like a cape and a cape whose signs
 * are wrong also looks like a cape — it just leans into the body while you run
 * and swings left when you strafe right. Neither shows up as an error, and
 * neither is visible without a cape, a skin, a pushed pack and a client.
 *
 * <p>The magnitudes are Mojang's and are asserted only where they are clamps,
 * which are the part a bad lag vector runs into.
 */
class CapeSwayTest {

    /** The cape's joint, as studio's `capeBonePivot` states it, in model px. */
    private static final float[] PIVOT = {8f, 24f, 10f};

    private static final int LEAN = 0;
    private static final int RISE = 1;
    private static final int SWAY = 2;

    /**
     * Yaw 0 faces +z, so walking forward leaves the cloak at -z behind them.
     * A lag of one block backward is well past the 150-degree clamp — a real
     * one is a few hundredths — so this is asking about the sign and the
     * ceiling together.
     */
    @Test
    void walkingForwardLiftsTheHemAwayFromTheBack() {
        double[] a = CapeSway.angles(0, 0, -0.05, 0f, 0, false);
        assertTrue(a[LEAN] > 0, "expected a lean away from the back, got " + a[LEAN]);
    }

    /** Walking BACKWARD must not pull the cape through the body. */
    @Test
    void walkingBackwardsLeansNothing() {
        double[] a = CapeSway.angles(0, 0, 0.05, 0f, 0, false);
        assertEquals(0.0, a[LEAN], 1e-9);
    }

    @Test
    void theLeanIsClampedAtMojangsCeiling() {
        assertEquals(150.0, CapeSway.angles(0, 0, -10, 0f, 0, false)[LEAN], 1e-9);
    }

    /**
     * Facing +z, the player's right hand is at -x — so a cloak lagging to
     * their right is a lag at -x, and the sway comes out positive.
     */
    @Test
    void aCloakLaggingRightSwaysRight() {
        assertTrue(CapeSway.angles(-0.05, 0, 0, 0f, 0, false)[SWAY] > 0);
        assertTrue(CapeSway.angles(0.05, 0, 0, 0f, 0, false)[SWAY] < 0);
    }

    /** And the same, half a turn round, which is what proves the yaw is used. */
    @Test
    void theAxesFollowTheBody() {
        // Facing -z now, so walking forward leaves the cloak at +z.
        assertTrue(CapeSway.angles(0, 0, 0.05, 180f, 0, false)[LEAN] > 0);
        assertEquals(0.0, CapeSway.angles(0, 0, -0.05, 180f, 0, false)[LEAN], 1e-9);
    }

    @Test
    void fallingLiftsTheHemAndIsClamped() {
        // The cloak is left above them while they fall, so the lag is +y. Ten
        // degrees per block of it, which is a tenth of what the horizontal
        // terms get — so a fall has to be well under way before the ceiling
        // bites, and one block of lag is nowhere near it.
        assertEquals(10.0, CapeSway.angles(0, 1, 0, 0f, 0, false)[RISE], 1e-9);
        assertEquals(32.0, CapeSway.angles(0, 5, 0, 0f, 0, false)[RISE], 1e-9);
        // Rising puts the cloak below them — floored much sooner, Mojang's -6,
        // and the asymmetry is his: a cape blows up, not down.
        assertEquals(-6.0, CapeSway.angles(0, -1, 0, 0f, 0, false)[RISE], 1e-9);
    }

    /** Crouching lifts the hem clear of the body, on the vertical term. */
    @Test
    void crouchingLiftsTheHem() {
        double still = CapeSway.angles(0, 0, 0, 0f, 0, false)[RISE];
        double crouched = CapeSway.angles(0, 0, 0, 0f, 0, true)[RISE];
        assertEquals(25.0, crouched - still, 1e-9);
    }

    /**
     * <b>The derivation this whole file exists for.</b> The cape hangs below
     * its joint and behind the body (+z), and leaning it must take the hem
     * FURTHER back, not forward through the chest. Vanilla states the same
     * motion as a positive rotation, in a space whose y points down and whose
     * cloak has been flipped 180 degrees; lifting that sign rather than
     * re-deriving it puts the cape inside the player.
     */
    @Test
    void leaningTakesTheHemBackwardsNotThroughTheBody() {
        Vector3f hem = hemAfter(90, 0, 0);
        assertTrue(hem.z > 0.1f, "expected the hem behind the body, got z=" + hem.z);
    }

    /** A cape at rest hangs, near enough straight down and just off the back. */
    @Test
    void aStillCapeHangs() {
        Vector3f hem = hemAfter(0, 0, 0);
        assertTrue(hem.y < -0.8f, "expected the hem below the joint, got y=" + hem.y);
        // Six degrees of rest lean over a one-block drop is about 0.1 blocks.
        assertTrue(hem.z > 0 && hem.z < 0.2f, "expected a small rest lean, got z=" + hem.z);
    }

    /** Swaying right takes the hem to +x, which is the rig's right hand. */
    @Test
    void swayingRightTakesTheHemRight() {
        assertTrue(hemAfter(0, 0, 40).x > 0.1f);
        assertTrue(hemAfter(0, 0, -40).x < -0.1f);
    }

    /**
     * Where a point one block below the joint ends up, relative to the joint.
     *
     * <p>Relative, so the pivot's own position drops out and what is left is
     * the rotation — which is the only thing these assertions are about.
     */
    private static Vector3f hemAfter(double lean, double rise, double sway) {
        Matrix4f m = new Matrix4f();
        CapeSway.applyTo(m, PIVOT, lean, rise, sway);
        float px = (PIVOT[0] - 8f) / 16f;
        float py = (PIVOT[1] - 8f) / 16f;
        float pz = (PIVOT[2] - 8f) / 16f;
        Vector3f moved = m.transformPosition(new Vector3f(px, py - 1f, pz));
        return moved.sub(px, py, pz);
    }
}
