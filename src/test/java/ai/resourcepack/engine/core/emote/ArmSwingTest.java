package ai.resourcepack.engine.core.emote;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The swing curve, and the one property the whole feature rests on.
 *
 * <p>The thing worth testing here is not the shape — that is Mojang's and is
 * copied — but the TIMING: that the frame sent on the tick the click arrived is
 * already a swing, rather than the zero the curve starts at. That is the
 * difference between a swing that reads as instant and one that reads as a tick
 * late, and it is invisible in every screenshot.
 */
class ArmSwingTest {

    @Test
    void theFrameSentOnTheClickTickIsAlreadyASwing() {
        // <b>This is the pin that matters.</b> The event arrives partway
        // through tick T and poses immediately; if that pose sampled the curve
        // at 0 it would be identical to what is already on screen, and the
        // first movement would land a tick later. Sampling one step in means
        // the packet that goes out on the click's own tick is a swung arm.
        long armed = 100L;
        assertEquals(1.0 / ArmSwing.SWING_TICKS, ArmSwing.progress(armed, armed), 1e-9);
        assertTrue(ArmSwing.pitch(ArmSwing.progress(armed, armed)) > 0f,
            "the arm has to have moved on the very tick the click arrived");
    }

    @Test
    void theArmIsAlmostFullyUpByTheFirstFrame() {
        // Mojang's easing is 1-(1-p)^4, which is why a swing reads as a snap:
        // by one tick in the arm has done nearly all of its travel and the
        // remaining five ticks are the return. If this ever drops a long way
        // below peak, the curve has been changed into a sweep and the swing
        // will feel soft however early it is sent.
        float first = ArmSwing.pitch(ArmSwing.progress(100L, 100L));
        float peak = 0f;
        for (int i = 0; i <= 100; i++) peak = Math.max(peak, ArmSwing.pitch(i / 100.0));
        assertTrue(first > peak * 0.9f,
            "first frame " + first + " should be most of the peak " + peak);
    }

    @Test
    void aSwingRunsForSixTicksAndThenStops() {
        long armed = 500L;
        for (long t = armed; t < armed + ArmSwing.SWING_TICKS; t++) {
            assertTrue(ArmSwing.running(armed, t), "tick " + (t - armed) + " is still swinging");
        }
        assertFalse(ArmSwing.running(armed, armed + ArmSwing.SWING_TICKS),
            "and the seventh tick is over — six ticks, not seven");
    }

    @Test
    void anUnarmedSessionIsNeverSwinging() {
        // The resting value must not read as a swing that started an
        // unimaginably long time ago and is somehow still going. It did:
        // `now - Long.MIN_VALUE` overflows back to a negative, so the progress
        // test answered yes for every session that had never swung.
        assertFalse(ArmSwing.running(ArmSwing.NOT_SWINGING, 0L));
        assertFalse(ArmSwing.running(ArmSwing.NOT_SWINGING, 1_000_000L));
        assertFalse(ArmSwing.running(ArmSwing.NOT_SWINGING, Long.MAX_VALUE));
    }

    @Test
    void aSwingArmedInTheFutureIsNotRunningYet() {
        // A world whose game time went backwards — a restore from a backup, or
        // a plugin setting it — must not leave every wearer's arm stuck up.
        assertFalse(ArmSwing.running(100L, 99L));
    }

    @Test
    void theCurveStartsAndEndsAtRest() {
        assertEquals(0f, ArmSwing.pitch(0.0), 1e-6, "nothing before it starts");
        assertEquals(0f, ArmSwing.pitch(1.0), 1e-5, "and back down by the end");
        assertEquals(0f, ArmSwing.roll(0.0, false), 1e-6);
        assertEquals(0f, ArmSwing.roll(1.0, false), 1e-5);
    }

    @Test
    void nothingIsAppliedOutsideTheSwing() {
        assertEquals(0f, ArmSwing.pitch(1.5), 0f, "a finished swing contributes nothing");
        assertEquals(0f, ArmSwing.pitch(-0.5), 0f);
    }

    @Test
    void theOffHandTiltsTheOtherWay() {
        // A turn that takes the right arm away from the body takes the left one
        // into it, so the roll mirrors. The pitch does not: both arms swing
        // forward.
        double mid = 0.5;
        assertEquals(-ArmSwing.roll(mid, false), ArmSwing.roll(mid, true), 1e-6);
        assertTrue(ArmSwing.roll(mid, false) != 0f, "and it is actually doing something");
    }

    @Test
    void theSwingCarriesTheHandForward() {
        // The direction check, stated where a sign slip would be caught: the
        // rig faces -z, so a hand that swings must end up in FRONT of where it
        // rested. Composed the way pose() composes it — the swing on the arm
        // bone, the hand socket on top — so this is the real chain rather than
        // an assertion about a rotation in isolation.
        float[] shoulder = {12f, 30f, 8f}; // studio's rightArm pivot, model px

        Matrix4f rest = new Matrix4f();
        HeldItem.applyTo(rest, false, false);
        float restZ = rest.getTranslation(new Vector3f()).z;

        Matrix4f swung = new Matrix4f();
        ArmSwing.applyTo(swung, shoulder, 0.5, false);
        HeldItem.applyTo(swung, false, false);
        float swungZ = swung.getTranslation(new Vector3f()).z;

        assertTrue(swungZ < restZ,
            "a swung hand should be forward (-z) of a resting one: " + swungZ + " vs " + restZ);
    }

    @Test
    void theSwingTurnsAboutTheShoulderRatherThanMovingIt() {
        // Same translate-rotate-translate-back contract RigMath.applyStep has.
        // A rotation not taken about the joint slides the whole arm out of the
        // shoulder, which looks like the limb detaching rather than swinging.
        float[] shoulder = {12f, 30f, 8f};
        Matrix4f m = new Matrix4f();
        ArmSwing.applyTo(m, shoulder, 0.5, false);

        Vector3f pivot = new Vector3f((12f - 8f) / 16f, (30f - 8f) / 16f, (8f - 8f) / 16f);
        Vector3f moved = m.transformPosition(new Vector3f(pivot));
        assertEquals(pivot.x, moved.x, 1e-5, "the joint itself must not move");
        assertEquals(pivot.y, moved.y, 1e-5);
        assertEquals(pivot.z, moved.z, 1e-5);
    }

    @Test
    void aBonelessPivotIsIgnoredRatherThanThrowing() {
        // Same tolerance applyStep has for a manifest that is missing one: a
        // pack with a malformed bone should lose its swing, not its emote.
        Matrix4f m = new Matrix4f();
        ArmSwing.applyTo(m, null, 0.5, false);
        assertEquals(new Matrix4f(), m);
        ArmSwing.applyTo(m, new float[] {1f, 2f}, 0.5, false);
        assertEquals(new Matrix4f(), m);
    }
}
