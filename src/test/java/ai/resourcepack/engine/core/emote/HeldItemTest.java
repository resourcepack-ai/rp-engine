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
        // The 2px lift on top of that is HAND_LIFT_PX, which is measured in a
        // client rather than derived — see its comment, and the test below.
        float[] socket = HeldItem.socketPoint(false, false);
        assertEquals((12f + 2f - 16f) * PX, socket[1], 1e-6,
            "ten px down from a shoulder at 22, lifted the 2 it reads low by");
        assertEquals(-2f * PX, socket[2], 1e-6,
            "and two px in front of the arm, which is -z");
    }

    @Test
    void theLiftIsALiftAndIsSmall() {
        // Two properties worth holding onto separately from the exact value,
        // since that value is expected to be re-tuned in game: it goes UP (a
        // sign slip here would double the problem it was added to fix), and it
        // stays within a few px (anything larger is not a calibration, it is
        // the hand having come off the arm — look at the rotation instead).
        float pureGeometry = (12f - 16f) * PX;
        float actual = HeldItem.socketPoint(false, false)[1];
        assertTrue(actual > pureGeometry, "the correction has to raise the grip, not lower it");
        assertTrue(actual - pureGeometry <= 4f * PX, "and by a few px, not by a limb's length");
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

    /**
     * <b>Why {@link HeldItem#orient} may not be composed in model space.</b>
     *
     * <p>This is the arithmetic behind an item that hung beside the rig
     * instead of in its hand, and it is pinned here so nobody folds the turn
     * back into {@code applyTo} where it obviously belongs and quietly breaks
     * it again. {@code RigMath.toItemDisplaySpace} conjugates by a half turn
     * about Y to get from model space into the space an ItemDisplay reads its
     * transformation in — and a conjugation does not leave rotations alone. It
     * takes vanilla's in-hand {@code Rx(-90)} to {@code Rx(+90)}, so the item
     * came out of that door pitched a half turn backwards along itself, and
     * its own thirdperson display translation then carried it clear of the
     * grip.
     *
     * <p>The socket survives the same conjugation, which is why the PLACE may
     * still be composed in model space and only the turn moved out: a
     * translation's y is untouched and its x and z are negated, which is
     * exactly what a prop's offset already relies on.
     */
    @Test
    void theDisplaySpaceConjugationFlipsAnOrientationsPitch() {
        Matrix4f inModelSpace = new Matrix4f();
        HeldItem.orient(inModelSpace);
        Matrix4f conjugated = ai.resourcepack.engine.core.animation.RigMath
            .toItemDisplaySpace(inModelSpace);

        // What the turn becomes if it is composed before the conjugation: the
        // opposite quarter-turn about X. Stated against orient()'s own pitch
        // rather than a literal, so this keeps meaning the same thing if the
        // calibrated value moves again — the CLAIM is "the conjugation inverts
        // the pitch", not "the pitch is 90". A half turn about Y, if one is
        // ever configured, would come through untouched; see
        // theHalfTurnAboutYIsUntouchedByTheConjugation.
        Matrix4f flipped = new Matrix4f()
            .rotateX(-pitchOf(inModelSpace));
        for (int i = 0; i < 16; i++) {
            assertEquals(flipped.get(i / 4, i % 4), conjugated.get(i / 4, i % 4), 1e-5);
        }

        // And the point of the whole test: that is NOT the orientation asked
        // for, so applying it in model space is applying a different one.
        // Compared over every entry rather than one — the two differ only in
        // the pitch's sine terms, and both quarter turns share every cosine,
        // so picking a single element is how this assertion passes vacuously.
        double worst = 0;
        for (int i = 0; i < 16; i++) {
            worst = Math.max(worst,
                Math.abs(inModelSpace.get(i / 4, i % 4) - conjugated.get(i / 4, i % 4)));
        }
        assertTrue(worst > 1e-3, "the conjugated turn should differ from the one orient() states");
    }

    /**
     * <b>Vanilla's in-hand pair is taken whole, and the Y half of it is a
     * ROLL rather than a duplicate of anything the client does.</b>
     *
     * <p>This turn has now been got wrong in both directions, so it is pinned
     * from both sides. {@code ItemInHandLayer.renderArmWithItem} turns a held
     * stack by {@code Rx(-90)} and then {@code Ry(180)}, and both belong here.
     *
     * <p>The half turn was once deleted on the theory that an ItemDisplay
     * already carries it — that the client's own 180 about Y had "already been
     * spent" once {@code orient} moved outside {@code toItemDisplaySpace}. That
     * reasoning cannot be right, and {@link
     * #theHalfTurnAboutYIsUntouchedByTheConjugation} is the arithmetic: a half
     * turn about Y COMMUTES with a conjugation by a half turn about Y, so
     * moving {@code orient} across that boundary changed the pitch and left the
     * Y contribution exactly as it was. Whatever the client does, it did the
     * same thing before and after that move, so the move cannot have made this
     * term redundant.
     *
     * <p>What deleting it actually did is in {@link
     * #droppingTheYawRollsTheItemAboutItsOwnLength}: it does not reverse the
     * item, it rolls it 180 degrees about the axis running along it — which is
     * horizontal, and is what "the item is flipped in the horizontal axis" is.
     */
    @Test
    void theTurnIsTheOppositePitchToVanillasBecauseRigSpaceIsYUp() {
        Matrix4f actual = new Matrix4f();
        HeldItem.orient(actual);

        Matrix4f pitchUp = new Matrix4f().rotateX((float) (Math.PI / 2.0));
        for (int i = 0; i < 16; i++) {
            assertEquals(pitchUp.get(i / 4, i % 4), actual.get(i / 4, i % 4), 1e-6,
                "orient() is Rx(+90) — vanilla's pitch with the sign a y-up frame gives it");
        }

        // Stated the other way round as well, so the assertion above cannot be
        // "fixed" by making both sides carry the same wrong turn. Vanilla's
        // pair, lifted verbatim, is the thing this is NOT.
        Matrix4f vanillaVerbatim = new Matrix4f()
            .rotateX((float) (-Math.PI / 2.0))
            .rotateY((float) Math.PI);
        double worst = 0;
        for (int i = 0; i < 16; i++) {
            worst = Math.max(worst,
                Math.abs(vanillaVerbatim.get(i / 4, i % 4) - actual.get(i / 4, i % 4)));
        }
        assertTrue(worst > 1e-3,
            "lifting vanilla's in-hand pair into a y-up frame is the bug this test exists to catch");
    }

    /**
     * <b>The turn is configurable, and comes back to its default.</b>
     *
     * <p>Not a feature — calibration. This turn has been wrong four times and
     * no test can see a rig, so the three {@code emotes.held-item-*} settings
     * exist to make it findable by looking. What IS worth pinning is that they
     * reach {@code orient} at all, since a knob that silently does nothing is
     * worse than no knob: somebody would conclude the turn was not the problem.
     */
    @Test
    void theTurnCanBeCalibratedAndRestored() {
        Matrix4f before = new Matrix4f();
        HeldItem.orient(before);
        try {
            HeldItem.turn(0f, 180f, 0f);
            Matrix4f overridden = new Matrix4f();
            HeldItem.orient(overridden);
            Matrix4f expected = new Matrix4f().rotateY((float) Math.PI);
            for (int i = 0; i < 16; i++) {
                assertEquals(expected.get(i / 4, i % 4), overridden.get(i / 4, i % 4), 1e-6,
                    "a configured turn should reach orient()");
            }
        } finally {
            // Static state: every later test in this JVM reads it. The defaults
            // are restated here rather than captured, so a change to them is a
            // change this test can be made to notice.
            HeldItem.turn(90f, 0f, 0f);
        }
        Matrix4f after = new Matrix4f();
        HeldItem.orient(after);
        for (int i = 0; i < 16; i++) {
            assertEquals(before.get(i / 4, i % 4), after.get(i / 4, i % 4), 1e-6,
                "the default turn should be restored");
        }
    }

    /**
     * <b>A half turn about Y survives {@code toItemDisplaySpace} untouched.</b>
     *
     * <p>The load-bearing fact behind the test above, and the one that makes
     * "the client already does this half turn, so drop ours" unsound: the
     * conjugation is BY a half turn about Y, and a rotation commutes with
     * itself. So this term contributes the same 180 whether {@code orient} is
     * composed inside or outside it, and no amount of moving that call can
     * double or cancel it.
     */
    @Test
    void theHalfTurnAboutYIsUntouchedByTheConjugation() {
        Matrix4f yaw = new Matrix4f().rotateY((float) Math.PI);
        Matrix4f conjugated = ai.resourcepack.engine.core.animation.RigMath
            .toItemDisplaySpace(yaw);
        for (int i = 0; i < 16; i++) {
            assertEquals(yaw.get(i / 4, i % 4), conjugated.get(i / 4, i % 4), 1e-6,
                "Ry(180) commutes with a conjugation by Ry(180)");
        }
    }

    /**
     * <b>What dropping the yaw actually looks like: a roll, not a reversal.</b>
     *
     * <p>The symptom that brought this back was "for others the item is flipped
     * in the horizontal axis by 180", and this is that sentence as arithmetic.
     * Take the difference between vanilla's pair and the pitch alone, in the
     * frame the display reads, and it is a half turn about Z — horizontal, the
     * axis pointing the way the rig faces, which after {@code Rx(-90)} is the
     * axis running along the item itself.
     *
     * <p>So it cannot make a sword point the wrong way, and a report of one
     * facing backwards is not evidence about this term. It flips everything
     * PERPENDICULAR to the item's length: which face is up, which way an
     * asymmetric model reads. That is why it went unnoticed on a rig standing
     * still and why nobody could agree on what it looked like.
     */
    @Test
    void aYawTermIsARollAboutTheItemsLengthAndNeverAReversal() {
        // Stated against explicit matrices rather than orient()'s current
        // value: the claim is about what a yaw term DOES, which is true
        // whatever the calibrated turn happens to be.
        Matrix4f pitchOnly = new Matrix4f().rotateX((float) (Math.PI / 2.0));
        Matrix4f withYaw = new Matrix4f(pitchOnly).rotateY((float) Math.PI);

        Matrix4f delta = new Matrix4f(pitchOnly).invert().mul(withYaw);
        Matrix4f halfTurnAboutTheItemsLength = new Matrix4f().rotateY((float) Math.PI);
        for (int i = 0; i < 16; i++) {
            assertEquals(halfTurnAboutTheItemsLength.get(i / 4, i % 4), delta.get(i / 4, i % 4), 1e-5,
                "a yaw term is a roll about the item's own y, not a reversal");
        }

        // And the reversal it is NOT: the item's length is where it was either
        // way. Pinned because this term was once deleted, and later restored,
        // over a symptom it is incapable of causing.
        org.joml.Vector3f alongTheItem = new org.joml.Vector3f(0f, 1f, 0f);
        org.joml.Vector3f withYawEnd = withYaw.transformPosition(new org.joml.Vector3f(alongTheItem));
        org.joml.Vector3f pitchOnlyEnd = pitchOnly.transformPosition(new org.joml.Vector3f(alongTheItem));
        assertEquals(pitchOnlyEnd.x, withYawEnd.x, 1e-5, "the item points the same way with or without a yaw");
        assertEquals(pitchOnlyEnd.y, withYawEnd.y, 1e-5, "the item points the same way with or without a yaw");
        assertEquals(pitchOnlyEnd.z, withYawEnd.z, 1e-5, "the item points the same way with or without a yaw");
    }

    /**
     * <b>What DOES reverse a blade: a half turn about the display's vertical.</b>
     *
     * <p>The other half of the test above, and the arithmetic behind the
     * current value. "The sword points at their back" is a reversal, so the
     * turn that fixes it is a half turn about the vertical axis applied at the
     * grip. After the pitch, the item's local Z is the vertical one — so that
     * is {@code Rz(180)}, and composing it onto the pair that was there
     * collapses to the pitch alone with its sign flipped:
     * {@code Rx(-90)·Ry(180)·Rz(180) == Rx(+90)}.
     */
    @Test
    void aHalfTurnAboutTheVerticalIsWhatReversesTheItem() {
        Matrix4f oldPair = new Matrix4f()
            .rotateX((float) (-Math.PI / 2.0))
            .rotateY((float) Math.PI);
        Matrix4f reversed = new Matrix4f(oldPair).rotateZ((float) Math.PI);
        Matrix4f pitchUp = new Matrix4f().rotateX((float) (Math.PI / 2.0));
        for (int i = 0; i < 16; i++) {
            assertEquals(pitchUp.get(i / 4, i % 4), reversed.get(i / 4, i % 4), 1e-5,
                "Rx(-90)*Ry(180)*Rz(180) is Rx(+90)");
        }

        // And it really is a reversal: the item's length ends up opposite.
        org.joml.Vector3f along = new org.joml.Vector3f(0f, 1f, 0f);
        org.joml.Vector3f a = oldPair.transformPosition(new org.joml.Vector3f(along));
        org.joml.Vector3f b = reversed.transformPosition(new org.joml.Vector3f(along));
        assertEquals(-a.z, b.z, 1e-5, "the item's length should end up pointing the other way");
    }

    /** orient()'s pitch, read back off the matrix rather than assumed. */
    private static float pitchOf(Matrix4f m) {
        return new org.joml.Vector3f(0f, 1f, 0f)
            .rotate(m.getNormalizedRotation(new org.joml.Quaternionf()))
            .z > 0 ? (float) (Math.PI / 2.0) : (float) (-Math.PI / 2.0);
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
