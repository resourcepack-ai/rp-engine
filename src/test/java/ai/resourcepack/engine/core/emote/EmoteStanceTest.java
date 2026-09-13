package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteTrigger;
import ai.resourcepack.engine.api.Keyframe;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stance: an emote somebody wears while they carry on playing.
 *
 * <p>Two rules decide everything a stance does, and both fail quietly rather
 * than loudly, which is why they are here rather than left to a live server.
 *
 * <p><b>The state a body is in has to resolve to exactly one answer.</b> A
 * timeline has one clock, so "sneaking and walking at once" has no meaning —
 * and getting the precedence wrong does not throw, it plays the wrong
 * animation while somebody crouch-walks and looks like the emote was authored
 * badly.
 *
 * <p><b>An unknown trigger name has to step DOWN to an ordinary emote.</b> A
 * newer studio can name a state this jar cannot detect; the alternative to
 * dropping it is a stance whose condition can never hold, which in game is a
 * rig standing frozen inside its owner with nothing to explain it.
 *
 * <p>Nothing here touches a running server: the two functions under test were
 * split free of {@code Player} exactly so they could be reached, the same shape
 * {@code rigToWorld} and {@code applyPropStep} already have.
 */
class EmoteStanceTest {

    /** Comfortably past STANCE_MOVING_STEP — a crouch is ~0.065 per tick. */
    private static final double A_STEP = 0.09;

    @Test
    void sneakingBeatsEverythingElse() {
        // A crouch-walk reads as crouching to everybody watching it, and a
        // sprint cannot happen while sneaking — but the client's sprint flag
        // has been seen set on the tick sneak begins, so the order matters.
        assertEquals(EmoteTrigger.SNEAK_IDLE, EmoteStance.stanceState(true, false, false));
        assertEquals(EmoteTrigger.SNEAK_MOVE, EmoteStance.stanceState(true, false, true));
        assertEquals(EmoteTrigger.SNEAK_MOVE, EmoteStance.stanceState(true, true, true));
    }

    @Test
    void crouchingResolvesToOneOfTwoStatesAndNeverTheUmbrella() {
        // Standing still and crouch-walking are two different things a body
        // does, exactly as standing and walking are — a set that could only
        // name "sneaking" had to pick which of them its one emote suited.
        //
        // Nothing ever resolves to SNEAK itself: it is what an older pack
        // NAMES, and a state meaning "either of two things" cannot drive a
        // clock with room for one.
        for (int bits = 0; bits < 8; bits++) {
            EmoteTrigger state = EmoteStance.stanceState(
                true, (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0);
            assertTrue(state != EmoteTrigger.SNEAK, "resolved the umbrella: " + state);
        }
    }

    @Test
    void aPackThatNamedTheUmbrellaStillPlaysInBothCrouchingStates() {
        // The whole of backwards compatibility for the split. A stance
        // authored for `sneak` names one state this jar never resolves, so
        // without the fallback its clock would stop the moment somebody
        // crouched — a rig frozen at frame zero, in the state it was made for.
        Set<EmoteTrigger> umbrella = Collections.singleton(EmoteTrigger.SNEAK);
        assertTrue(EmoteStance.plays(umbrella, EmoteTrigger.SNEAK_IDLE));
        assertTrue(EmoteStance.plays(umbrella, EmoteTrigger.SNEAK_MOVE));
        assertFalse(EmoteStance.plays(umbrella, EmoteTrigger.WALK));

        // And the other direction is NOT symmetric: a pack that named one of
        // the two halves means that half, so crouching the other way is not
        // covered by it.
        Set<EmoteTrigger> half = Collections.singleton(EmoteTrigger.SNEAK_IDLE);
        assertTrue(EmoteStance.plays(half, EmoteTrigger.SNEAK_IDLE));
        assertFalse(EmoteStance.plays(half, EmoteTrigger.SNEAK_MOVE));
        assertFalse(EmoteStance.plays(half, EmoteTrigger.SNEAK));
    }

    @Test
    void sprintingIsOnlySprintingWhileActuallyMoving() {
        // The sprint flag stays set for a moment after somebody stops, so a
        // stance authored for "standing still" must not lose to a stale flag.
        assertEquals(EmoteTrigger.IDLE, EmoteStance.stanceState(false, true, false));
        assertEquals(EmoteTrigger.SPRINT, EmoteStance.stanceState(false, true, true));
    }

    @Test
    void movingWithoutSprintingIsAWalk() {
        assertEquals(EmoteTrigger.WALK, EmoteStance.stanceState(false, false, true));
        assertEquals(EmoteTrigger.IDLE, EmoteStance.stanceState(false, false, false));
    }

    @Test
    void standingStillJittersBelowTheThreshold() {
        // A standing player's position moves by hundredths as the client
        // reconciles. Read as walking, that flickers the animation on and off
        // under somebody who has not moved — the symptom this bound exists for.
        Location spot = new Location(null, 100, 64, 100);
        assertFalse(EmoteStance.movedHorizontally(spot, spot.clone()));
        assertFalse(EmoteStance.movedHorizontally(spot, spot.clone().add(0.004, 0, 0.004)));
        assertTrue(EmoteStance.movedHorizontally(spot, spot.clone().add(A_STEP, 0, 0)));
    }

    @Test
    void fallingIsNotWalking() {
        // Horizontal only: a stance authored for idle keeps playing while its
        // wearer drops down a shaft rather than switching to a walk cycle in
        // mid-air.
        Location spot = new Location(null, 100, 64, 100);
        assertFalse(EmoteStance.movedHorizontally(spot, spot.clone().add(0, -12, 0)));
    }

    @Test
    void thereIsNoGaitOnTheFirstPass() {
        // `previous` is null until a stance has ticked once, and a stance is
        // put on standing still — so the absent answer has to be "not moving"
        // rather than an exception on the first tick of every stance.
        assertFalse(EmoteStance.movedHorizontally(null, new Location(null, 0, 64, 0)));
    }

    @Test
    void theRigIsPlacedWhereTheWearerIsAboutToBe() {
        // A rig built from the position we were handed is drawn where its
        // wearer was two ticks and one ping ago. Leading it by the step they
        // just took is what cancels that, and the arithmetic is here because
        // the symptom - a rig sliding into view in front of somebody walking
        // backwards - is the sort of thing nobody traces back to a multiply.
        Location from = new Location(null, 100, 64, 100);
        // A sprint is about 0.28 blocks a tick.
        Location to = from.clone().add(0.28, 0, 0);

        // Smoothed, so one pass is half way to the answer rather than at it.
        Vector first = EmoteStance.leadFor(new Vector(), from, to, 2.0);
        assertEquals(0.28, first.getX(), 1e-9);
        // And it converges on the full two ticks of travel as the wearer keeps
        // going, rather than sitting at half of it.
        Vector settled = first;
        for (int pass = 0; pass < 8; pass++) settled = EmoteStance.leadFor(settled, from, to, 2.0);
        assertEquals(0.56, settled.getX(), 0.01);
    }

    @Test
    void aStoppedWearerSLeadActuallyReachesZero() {
        // The bug this pins cost every wearer eleven teleports a tick for
        // the rest of their emote. An easing that halves never arrives, so
        // the rig's target differed from where it already stood on every pass
        // for ever — "has it moved" answered yes for ever, and the skip that
        // keeps a standing rig silent never fired again once anybody had taken
        // one step. It reads in game as the whole thing being laggy, and
        // nothing about it looks wrong in a screenshot.
        Location spot = new Location(null, 0, 64, 0);
        Vector lead = new Vector(0.56, 0, 0);
        for (int pass = 0; pass < 20; pass++) lead = EmoteStance.leadFor(lead, spot, spot.clone(), 2.0);
        assertEquals(0.0, lead.getX(), 0.0, "a standing wearer's lead has to BE zero, not approach it");
        assertEquals(0.0, lead.lengthSquared(), 0.0);
    }

    @Test
    void aStoppedWearerIsWalkedBackRatherThanSnapped() {
        // The cost of leading: a body that stops was not going where it was
        // going. Unwound over a few passes, because setting it to zero outright
        // trades the lag this fixes for a jolt in the other direction.
        Location spot = new Location(null, 0, 64, 0);
        Vector lead = new Vector(0.56, 0, 0);
        double previous = lead.getX();
        for (int pass = 0; pass < 6; pass++) {
            lead = EmoteStance.leadFor(lead, spot, spot.clone(), 2.0);
            assertTrue(lead.getX() < previous, "the lead has to keep shrinking");
            assertTrue(lead.getX() >= 0, "and never swing past the wearer");
            previous = lead.getX();
        }
        assertEquals(0, lead.getX(), 0.02);
    }

    @Test
    void aTeleportIsNotAStride() {
        // A knockback, a warp or a lag spike arrives as one enormous "step".
        // Reckoning from it would fling the rig across the room, so the lead is
        // capped - the direction is still right and the next pass re-reads the
        // truth anyway.
        Location from = new Location(null, 0, 64, 0);
        Location far = from.clone().add(400, 0, 300);
        Vector lead = new Vector();
        for (int pass = 0; pass < 12; pass++) lead = EmoteStance.leadFor(lead, from, far, 5.0);
        assertTrue(lead.length() <= 0.66, "lead ran away: " + lead.length());
    }

    @Test
    void fallingLeadsNowhere() {
        // Vertical is never led: the client is already simulating gravity, and
        // a rig led upward would leave the ground before its wearer did.
        Location from = new Location(null, 0, 64, 0);
        Vector lead = EmoteStance.leadFor(new Vector(), from, from.clone().add(0, -3, 0), 2.0);
        assertEquals(0, lead.getX(), 1e-9);
        assertEquals(0, lead.getY(), 1e-9);
        assertEquals(0, lead.getZ(), 1e-9);
    }

    @Test
    void thereIsNoLeadWithoutTwoPositionsInOneWorld() {
        // The first pass of every stance, and the pass after a portal. Both
        // decay toward nothing rather than reckoning off a position that means
        // something else.
        Location spot = new Location(null, 0, 64, 0);
        assertEquals(0, EmoteStance.leadFor(new Vector(), null, spot, 2.0).length(), 1e-9);
        assertEquals(0, EmoteStance.leadFor(new Vector(), spot, null, 2.0).length(), 1e-9);
    }

    @Test
    void anEmoteWithNoTriggersIsAnOrdinaryOne() {
        // Absent and empty mean the same thing, and that thing is every emote
        // authored before stances existed.
        assertTrue(EmoteStore.triggersOf(emote((java.util.List<String>) null)).isEmpty());
        assertTrue(EmoteStore.triggersOf(emote(Collections.<String>emptyList())).isEmpty());
        assertTrue(EmoteStore.triggersOf(null).isEmpty());
    }

    @Test
    void everyWireNameResolves() {
        // The names studio writes. A rename on either side silently turns every
        // stance in every pack into an ordinary emote, which is exactly the
        // class of failure nobody would trace back to a string.
        //
        // The list is DERIVED from the enum rather than written out, so adding
        // a state cannot leave this test passing while it checks four of five.
        // It is still a real check: `triggersOf` resolves through
        // `EmoteTrigger.of`, so a wire name that stopped matching its own
        // constant fails here.
        java.util.List<String> wireNames = new java.util.ArrayList<>();
        for (EmoteTrigger trigger : EmoteTrigger.values()) wireNames.add(trigger.wireName());
        Set<EmoteTrigger> all = EmoteStore.triggersOf(emote(wireNames));
        assertEquals(EmoteTrigger.values().length, all.size());
        for (EmoteTrigger trigger : EmoteTrigger.values()) {
            assertTrue(all.contains(trigger), trigger + " should resolve from '" + trigger.wireName() + "'");
        }
    }

    @Test
    void anEmoteIsDESCRIBEDByTheStatesItPlaysIn() {
        // What `Emotes.info` reports, and it is not the same question playback
        // asks. A stance that named the old single crouching state plays in
        // both of the two it became, so a caller asking whether it covers a
        // crouch-walk has to be told yes — the name in the manifest is
        // vocabulary, and the states are the world.
        Set<EmoteTrigger> named = EmoteStore.triggersOf(emote(Collections.singletonList("sneak")));
        assertEquals(Collections.singleton(EmoteTrigger.SNEAK), named);

        Set<EmoteTrigger> states = EmoteStore.statesOf(named);
        assertTrue(states.contains(EmoteTrigger.SNEAK_IDLE));
        assertTrue(states.contains(EmoteTrigger.SNEAK_MOVE));
        assertFalse(states.contains(EmoteTrigger.SNEAK));

        // Everything else describes itself, so a caller walks one list.
        assertEquals(
            Collections.singleton(EmoteTrigger.WALK),
            EmoteStore.statesOf(Collections.singleton(EmoteTrigger.WALK)));
        assertTrue(EmoteStore.statesOf(Collections.<EmoteTrigger>emptySet()).isEmpty());
    }

    @Test
    void aNameThisJarCannotDetectIsDroppedRatherThanHeld() {
        assertEquals(
            Collections.singleton(EmoteTrigger.WALK),
            EmoteStore.triggersOf(emote(Arrays.asList("walk", "swimming", "elytra"))));
    }

    @Test
    void droppingEveryNameStepsDownToAnOrdinaryEmote() {
        // The alternative is a stance whose condition never holds: a rig
        // standing frozen inside its owner, for ever, with nothing on screen
        // saying the jar is simply older than the pack.
        assertTrue(EmoteStore.triggersOf(emote(Arrays.asList("swimming", "elytra"))).isEmpty());
    }

    // ---- the gait clock --------------------------------------------------
    //
    // A walk cycle is paced by the STRIDE rather than by the second, so these
    // fix the two things that would be invisible until somebody watched a
    // player on ice: that the ordinary pace is exactly the authored rate, and
    // that a stopped wearer's legs really stop.

    @Test
    void walkingAtWalkingPacePlaysACycleAtTheRateItWasDrawnAt() {
        // The whole of what makes this link safe to switch on for every set
        // that already exists: on flat ground it changes nothing at all.
        assertEquals(1.0,
            EmoteStance.gaitRate(EmoteTrigger.WALK, EmoteStance.WALK_SPEED), 1e-9);
        assertEquals(1.0,
            EmoteStance.gaitRate(EmoteTrigger.SPRINT, EmoteStance.SPRINT_SPEED), 1e-9);
        assertEquals(1.0,
            EmoteStance.gaitRate(EmoteTrigger.SNEAK_MOVE, EmoteStance.SNEAK_SPEED), 1e-9);
    }

    @Test
    void goingTwiceAsFastMovesTheLegsTwiceAsFast() {
        assertEquals(2.0,
            EmoteStance.gaitRate(EmoteTrigger.WALK, EmoteStance.WALK_SPEED * 2), 1e-9);
        assertEquals(0.5,
            EmoteStance.gaitRate(EmoteTrigger.WALK, EmoteStance.WALK_SPEED / 2), 1e-9);
    }

    @Test
    void thereIsACeilingOnHowFastLegsMayGo() {
        // Beyond it a cycle is a blur, and whoever is going that fast is being
        // carried rather than walking.
        assertEquals(EmoteStance.MAX_GAIT_RATE,
            EmoteStance.gaitRate(EmoteTrigger.SPRINT, EmoteStance.SPRINT_SPEED * 40), 1e-9);
    }

    @Test
    void aStateThatIsNotAGaitIgnoresHowFastAnybodyIsGoing() {
        // An idle breathes at its own rate and a body in the air is on
        // gravity's clock. Neither has a stride to be in step with.
        for (EmoteTrigger state : new EmoteTrigger[] {
                EmoteTrigger.IDLE, EmoteTrigger.SNEAK_IDLE, EmoteTrigger.JUMP }) {
            assertEquals(1.0, EmoteStance.gaitRate(state, 0), 1e-9);
            assertEquals(1.0, EmoteStance.gaitRate(state, 5), 1e-9);
        }
    }

    @Test
    void aGaitWhoseWearerIsNotMovingDoesNotPlay() {
        // Not "plays slowly": stopped. The legs hold where they are and the
        // ease into the idle member is what covers the rest.
        assertEquals(0.0, EmoteStance.gaitRate(EmoteTrigger.WALK, 0), 1e-9);
        assertEquals(0.0, EmoteStance.gaitRate(EmoteTrigger.WALK, -1), 1e-9);
    }

    @Test
    void aStoppedWearerSGaitActuallyReachesZero() {
        // LEAD_DEAD_ZONE's lesson, one field over: a playhead creeping forward
        // for ever is every bone re-sent to every viewer for ever.
        double gait = EmoteStance.WALK_SPEED;
        for (int pass = 0; pass < 60; pass++) {
            gait = EmoteStance.chaseGait(gait, 0);
        }
        assertEquals(0.0, gait, 0.0);
    }

    @Test
    void aGapBetweenMovementPacketsIsNotAStop() {
        // Two ticks of travel and a tick of nothing is what a walk over a real
        // connection looks like from the server. Read literally it is a walk
        // cycle stopping several times a second.
        double gait = 0;
        for (int pass = 0; pass < 30; pass++) {
            gait = EmoteStance.chaseGait(gait, pass % 3 == 2 ? 0 : EmoteStance.WALK_SPEED * 1.5);
        }
        // Still plainly walking, rather than flickering between a stride and a
        // standstill.
        assertTrue(gait > EmoteStance.WALK_SPEED * 0.6, "gait collapsed to " + gait);
    }

    @Test
    void aTeleportIsNotAStrideForTheGaitEither() {
        // Capped rather than refused, exactly as the lead is: the body really
        // did move, and the filter must not spread a blink across the next
        // half second of leg movement.
        assertEquals(EmoteStance.chaseGait(0, EmoteStance.MAX_GAIT_STEP),
            EmoteStance.chaseGait(0, 500), 1e-9);
    }

    @Test
    void stridesAreMeasuredFlatAndNeverAcrossWorlds() {
        Location from = new Location(null, 0, 0, 0);
        assertEquals(0.3, EmoteStance.stepBetween(from, new Location(null, 0.3, 0, 0)), 1e-9);
        // Falling down a shaft is not a stride.
        assertEquals(0.0, EmoteStance.stepBetween(from, new Location(null, 0, -4, 0)), 1e-9);
        assertEquals(0.0, EmoteStance.stepBetween(null, from), 1e-9);
    }

    // ---- joining a cycle where the body already is -------------------------

    @Test
    void aCycleIsJoinedAtThePointNearestThePoseOnScreen() {
        // A movement set is several cycles of ONE body. Started from the top,
        // breaking into a run asks a pair of mid-stride legs for frame zero —
        // a whole stride to cross in five ticks, every time anybody speeds up.
        EmoteStore.Emote run = swingCycle(90f);
        // The body is showing the left leg forward, which this cycle passes
        // through a quarter of the way in.
        float[][] shown = pose(90f);
        assertEquals(0.25, EmoteStance.nearestPhase(run, legs(), shown), 1e-6);

        // And the other way, so this is the match rather than a fixed answer.
        assertEquals(0.75, EmoteStance.nearestPhase(run, legs(), pose(-90f)), 1e-6);
    }

    @Test
    void aCycleThatDoesNotLoopStartsAtItsOwnBeginning() {
        // A jump pose joined in the middle is a jump pose that ends early, and
        // it is held for as long as somebody is in the air.
        EmoteStore.Emote once = swingCycle(90f);
        once.loop = false;
        assertEquals(0.0, EmoteStance.nearestPhase(once, legs(), pose(90f)), 1e-9);
    }

    @Test
    void onlyOneGaitSucceedingAnotherIsJoinedMidWay() {
        // The three gaits are stride cycles of one body and have no beginning
        // between them.
        for (EmoteTrigger from : GAITS) {
            for (EmoteTrigger to : GAITS) {
                assertTrue(EmoteStance.joinsInPhase(from, to), from + " -> " + to);
            }
        }
    }

    @Test
    void aNarrativeMemberIsNeverJoinedMidWay() {
        // <b>This is the jumping bug.</b> A jump launches, tucks and lands; an
        // idle settles. Joined at the frame that happens to resemble the walk
        // you arrived from, the launch never plays and the jump reads as simply
        // not working. Whether it LOOPS is a different question and does not
        // protect it — the set this was found on had a three-second looping
        // idle and its jump was the only narrative member that was safe.
        for (EmoteTrigger other : new EmoteTrigger[] {
                EmoteTrigger.IDLE, EmoteTrigger.SNEAK_IDLE, EmoteTrigger.JUMP }) {
            for (EmoteTrigger gait : GAITS) {
                assertFalse(EmoteStance.joinsInPhase(gait, other), gait + " -> " + other);
                assertFalse(EmoteStance.joinsInPhase(other, gait), other + " -> " + gait);
            }
            assertFalse(EmoteStance.joinsInPhase(other, other), other + " -> " + other);
        }
    }

    @Test
    void theFirstMemberOfAllStartsAtItsBeginning() {
        // Nothing is being left on the pass a set is put on, and `null` is what
        // that reads as. A set whose first state is a walk should start walking
        // from the top rather than from wherever an uninitialised pose matched.
        for (EmoteTrigger gait : GAITS) {
            assertFalse(EmoteStance.joinsInPhase(null, gait), "null -> " + gait);
        }
    }

    private static final EmoteTrigger[] GAITS = {
        EmoteTrigger.WALK, EmoteTrigger.SPRINT, EmoteTrigger.SNEAK_MOVE,
    };

    @Test
    void thereIsNothingToJoinWithoutAnAnimationOrAPose() {
        assertEquals(0.0, EmoteStance.nearestPhase(null, legs(), pose(90f)), 1e-9);
        assertEquals(0.0, EmoteStance.nearestPhase(swingCycle(90f), legs(), null), 1e-9);
        assertEquals(0.0,
            EmoteStance.nearestPhase(swingCycle(90f), Collections.emptyList(), pose(90f)), 1e-9);
    }

    /** One bone, so a phase can be reasoned about by hand. */
    private static java.util.List<EmoteStore.Bone> legs() {
        EmoteStore.Bone leg = new EmoteStore.Bone();
        leg.key = "leftLeg";
        leg.pivot = new float[] {8f, 12f, 8f};
        return Collections.singletonList(leg);
    }

    /** The nine values one bone is composed from, at {@code pitch} degrees. */
    private static float[][] pose(float pitch) {
        return new float[][] {{pitch, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f}, null};
    }

    /**
     * A one-second loop swinging that bone through +swing, 0, -swing and back,
     * so the quarter points are the extremes.
     */
    private static EmoteStore.Emote swingCycle(float swing) {
        EmoteStore.Emote emote = new EmoteStore.Emote();
        emote.name = "cycle";
        emote.length = 1.0;
        emote.loop = true;
        emote.animators = Collections.singletonMap("leftLeg",
            Collections.singletonMap("rotation", Arrays.asList(
                new Keyframe(0.0, new float[] {0f, 0f, 0f}, "linear"),
                new Keyframe(0.25, new float[] {swing, 0f, 0f}, "linear"),
                new Keyframe(0.5, new float[] {0f, 0f, 0f}, "linear"),
                new Keyframe(0.75, new float[] {-swing, 0f, 0f}, "linear"),
                new Keyframe(1.0, new float[] {0f, 0f, 0f}, "linear"))));
        return emote;
    }

    private static EmoteStore.Emote emote(java.util.List<String> triggers) {
        EmoteStore.Emote emote = new EmoteStore.Emote();
        emote.name = "test";
        emote.length = 1.0;
        emote.triggers = triggers;
        return emote;
    }
}
