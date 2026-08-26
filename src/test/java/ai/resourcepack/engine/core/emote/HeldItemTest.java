package ai.resourcepack.engine.core.emote;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand socket, against studio's own numbers.
 *
 * <p>These are arithmetic tests rather than rendering ones, and that is the
 * point: the failure mode this subsystem keeps hitting is the plugin and the
 * editor computing the same thing two ways and disagreeing by a conversion.
 * What is pinned here is that this file's answer is derivable from
 * {@code the model editor's display frames}'s, so a change to either is visible as a change to
 * the other.
 */
class HeldItemTest {

    /** One block is sixteen px; these read better as px than as fractions. */
    private static final float PX = 1f / 16f;

    @Test
    void theHandIsTenPixelsDownTheArmAndTwoInFrontOfIt() {
        // Studio's thirdPersonSocketPx with the 22.5-degree arm pose removed:
        // y = 22 - cos(0)*10 + sin(0)*2 = 12, z = -(sin(0)*10 + cos(0)*2) = -2.
        // In rig px, and then through the two conversions to block offsets.
        float[] socket = HeldItem.socketPoint(false, false);
        assertEquals((12f - 16f) * PX, socket[1], 1e-6,
            "ten px down from a shoulder at 22 is a hand at 12");
        assertEquals(-2f * PX, socket[2], 1e-6,
            "and two px in front of the arm, which is -z");
    }

    @Test
    void theHandSitsOnTheArmSCentreLineNotOnItsPivot() {
        // Studio: x = 4 + (slim ? 3 : 4) / 2. The pivot is the inner edge of
        // the arm box, so a hand on it holds the sword inside the sleeve.
        assertEquals(6f * PX, HeldItem.socketPoint(false, false)[0], 1e-6,
            "a wide arm is 4 wide, so its centre is 2 out from the pivot at 4");
        assertEquals(5.5f * PX, HeldItem.socketPoint(false, true)[0], 1e-6,
            "a slim arm is 3 wide, so 1.5 out");
    }

    @Test
    void theOffHandIsTheMainHandMirrored() {
        for (boolean slim : new boolean[] {false, true}) {
            float[] main = HeldItem.socketPoint(false, slim);
            float[] off = HeldItem.socketPoint(true, slim);
            assertEquals(-main[0], off[0], 1e-6, "mirrored in x");
            assertEquals(main[1], off[1], 1e-6, "and nowhere else — same height");
            assertEquals(main[2], off[2], 1e-6, "and the same distance forward");
        }
    }

    @Test
    void aSlimHandIsInsideAWideOne() {
        // Not a separate rule, but the consequence worth stating: a narrower
        // arm's centre line is nearer the body, never further from it. A sign
        // slip in the variant term would put a slim player's sword outside
        // their wide-armed neighbour's, which reads as "slim skins hold things
        // wrong" and is easy to mistake for a model problem.
        assertTrue(HeldItem.socketPoint(false, true)[0] < HeldItem.socketPoint(false, false)[0]);
        assertTrue(HeldItem.socketPoint(true, true)[0] > HeldItem.socketPoint(true, false)[0]);
    }

    @Test
    void theSocketIsAPlacementSoAnIdentityChainLeavesItWhereItBelongs() {
        // The reading `applyPropStep` depends on and the authoring format states:
        // the offset is measured from the RIG ORIGIN, not from the joint it
        // names. So composing it onto an unposed rig has to land the hand at
        // its rest point rather than at the shoulder.
        Matrix4f m = new Matrix4f();
        HeldItem.applyTo(m, false, false);
        Vector3f at = m.getTranslation(new Vector3f());
        float[] socket = HeldItem.socketPoint(false, false);
        assertEquals(socket[0], at.x, 1e-6);
        assertEquals(socket[1], at.y, 1e-6);
        assertEquals(socket[2], at.z, 1e-6);
    }

    @Test
    void theHandRidesWhateverTheArmDid() {
        // The whole mechanism in one assertion: a chain that moves the arm a
        // block up has to move the hand a block up too, because the hand is
        // composed onto it rather than placed beside it. This is what makes a
        // sword follow a bent elbow, and what makes it follow a swing.
        Matrix4f chain = new Matrix4f().translate(0f, 1f, 0f);
        Matrix4f m = new Matrix4f(chain);
        HeldItem.applyTo(m, false, false);
        Vector3f at = m.getTranslation(new Vector3f());
        float[] socket = HeldItem.socketPoint(false, false);
        assertEquals(socket[1] + 1f, at.y, 1e-6);
    }

    @Test
    void bothHandsUseTheAttachNamesAPropWouldUse() {
        // Load-bearing: EmoteStore.attachEndBone lowercases its input and looks
        // it up in a map keyed by lowercase names, so a camelCase spelling here
        // would silently miss the forearm and fall back to the upper arm — a
        // sword that stops following the elbow, with nothing to say so.
        assertEquals("rightForearm", EmoteStore.attachEndBone(HeldItem.MAIN_HAND_ATTACH));
        assertEquals("leftForearm", EmoteStore.attachEndBone(HeldItem.OFF_HAND_ATTACH));
    }
}
