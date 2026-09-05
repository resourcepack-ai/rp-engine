package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.core.animation.RigMath;
import ai.resourcepack.engine.api.AnimationSettings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How an animation plays, rather than what it is.
 *
 * <p>Speed, what happens at the end, which of two wins, and the crossfade
 * between them. All of it is arithmetic over the manifest, so all of it is
 * testable without a server — which matters here more than usual, because the
 * failure mode of every one of these is "it looks slightly wrong in game" and
 * nobody can bisect that.
 */
class AnimationStateTest {

    /** An animation as it arrives in a manifest, so gson fills it as it would. */
    private static RigStore.Animation animation(String json) {
        return new com.google.gson.Gson().fromJson(json, RigStore.Animation.class);
    }

    private static RigStore.Rig rig(String... animations) {
        return new com.google.gson.Gson().fromJson(
                "{\"parts\":[],\"animations\":[" + String.join(",", animations) + "]}", RigStore.Rig.class);
    }

    private static String named(String name, String extra) {
        return "{\"name\":\"" + name + "\",\"length\":2,\"loop\":false,"
                + "\"triggers\":[{\"type\":\"right_click\"}]" + extra + "}";
    }

    // ---- speed -----------------------------------------------------------

    @Test
    void anAbsentSpeedIsTheSpeedItWasAuthoredAt() {
        // Every manifest written before this has no speed at all, so absent
        // has to mean 1 rather than 0 — a rig frozen on its first frame is a
        // much worse answer than one that ignores a setting.
        assertEquals(1d, RigAnimations.speedOf(animation(named("wave", ""))), 0.0001);
        assertEquals(1d, RigAnimations.speedOf(animation(named("wave", ",\"speed\":0"))), 0.0001);
        assertEquals(1d, RigAnimations.speedOf(animation(named("wave", ",\"speed\":-2"))), 0.0001);
        assertEquals(1d, RigAnimations.speedOf(null), 0.0001);
    }

    @Test
    void speedScalesHowFarThroughTheAnimationTimeHasGot() {
        RigStore.Animation half = animation(named("wave", ",\"speed\":0.5"));
        RigStore.Animation twice = animation(named("wave", ",\"speed\":2"));

        assertEquals(0.5, RigAnimations.animationTime(half, 1), 0.0001);
        assertEquals(2, RigAnimations.animationTime(twice, 1), 0.0001);
    }



    @Test
    void aFastOneShotStopsAtItsEndRatherThanRunningPastIt() {
        RigStore.Animation twice = animation(named("wave", ",\"speed\":2"));

        // Length 2, double speed: over at one second of real time, and it
        // must clamp there rather than sampling off the end of the curve.
        assertEquals(2, RigAnimations.animationTime(twice, 5), 0.0001);
    }

    // ---- what happens at the end -----------------------------------------

    @Test
    void aHeldAnimationStopsOnItsLastFrameAndStaysThere() {
        // A door that stays open. Without this every one sprang shut the
        // moment it finished opening, because a one-shot that ran out went
        // back to rest.
        RigStore.Rig rig = rig(named("open", ",\"mode\":\"hold\""));

        assertEquals(0, RigAnimations.playbackAnimationIndex(rig, 0, 99),
                "long past its length, and still the pose on screen");
        assertEquals(2, RigAnimations.animationTime(rig.animations.get(0), 99), 0.0001,
                "clamped to the last frame");
    }

    @Test
    void aOnceAnimationGoesBackToRest() {
        RigStore.Rig rig = rig(named("wave", ",\"mode\":\"once\""));

        assertEquals(0, RigAnimations.playbackAnimationIndex(rig, 0, 1), "still running");
        assertEquals(-1, RigAnimations.playbackAnimationIndex(rig, 0, 99), "and then nothing");
    }

    @Test
    void aModeOverridesTheOlderBooleanRatherThanArguingWithIt() {
        // The boolean is what a plugin older than modes reads. Both are
        // written, so the two have to agree — but where they do not, the mode
        // is the more specific answer.
        RigStore.Rig rig = rig("{\"name\":\"spin\",\"length\":2,\"loop\":false,"
                + "\"mode\":\"loop\",\"triggers\":[{\"type\":\"loop\"}]}");

        assertEquals(0, RigAnimations.playbackAnimationIndex(rig, 0, 99));
    }

    @Test
    void aManifestWithNoModeBehavesExactlyAsItDidBefore() {
        RigStore.Rig looping = rig("{\"name\":\"spin\",\"length\":2,\"loop\":true,"
                + "\"triggers\":[{\"type\":\"loop\"}]}");
        RigStore.Rig once = rig(named("wave", ""));

        assertEquals(0, RigAnimations.playbackAnimationIndex(looping, 0, 99));
        assertEquals(-1, RigAnimations.playbackAnimationIndex(once, 0, 99));
    }

    // ---- a driven rig has no resting loop --------------------------------

    /**
     * <strong>A rig somebody else drives falls back to NOTHING.</strong>
     *
     * <p>A rig standing in the world resting on its own {@code loop}-triggered
     * animation is what makes a windmill turn unattended, and that is right for
     * a statue. It is wrong for a vehicle, where playback belongs to the
     * vehicle: "this state animates nothing" has to be expressible, and it is
     * not if the model's own loop reclaims the rig the moment nothing is asked
     * for.
     *
     * <p>Found the hard way. A kayak's rowing cycle is exactly the kind of
     * animation an author marks as a loop, so removing its idle mapping changed
     * nothing on screen and the boat kept rowing while moored.
     */
    @Test
    void aDrivenRigDoesNotFallBackToTheModelsOwnLoop() {
        RigStore.Rig rowing = rig("{\"name\":\"row\",\"length\":2,\"loop\":true,"
                + "\"triggers\":[{\"type\":\"loop\"}]}");

        // Standing in the world: nothing active, so its own loop takes over.
        assertEquals(0, RigAnimations.playbackAnimationIndex(rowing, null, 0, null, true));
        // Driven: nothing active means nothing plays.
        assertEquals(-1, RigAnimations.playbackAnimationIndex(rowing, null, 0, null, false));
    }

    /**
     * What it is TOLD to play still loops, driven or not — the flag is about
     * the fallback, not about honouring a loop that was actually asked for.
     */
    @Test
    void aDrivenRigStillLoopsWhatItWasAskedFor() {
        RigStore.Rig rowing = rig("{\"name\":\"row\",\"length\":2,\"loop\":true,"
                + "\"triggers\":[{\"type\":\"loop\"}]}");
        assertEquals(0, RigAnimations.playbackAnimationIndex(rowing, 0, 99, null, false),
                "asked for and looping, so it keeps going");
    }

    /**
     * And a one-shot on a driven rig runs out rather than being replaced by a
     * resting loop — which is what lets a vehicle notice it has stopped and
     * ask for it again. See Vehicles.animate.
     */
    @Test
    void aDrivenOneShotRunsOutRatherThanRestingOnALoop() {
        RigStore.Rig mixed = rig(
                "{\"name\":\"row\",\"length\":2,\"loop\":false,\"mode\":\"once\","
                        + "\"triggers\":[{\"type\":\"place\"}]}",
                "{\"name\":\"bob\",\"length\":2,\"loop\":true,"
                        + "\"triggers\":[{\"type\":\"loop\"}]}");

        assertEquals(1, RigAnimations.playbackAnimationIndex(mixed, 0, 99, null, true),
                "a placed rig settles onto its idle loop");
        assertEquals(-1, RigAnimations.playbackAnimationIndex(mixed, 0, 99, null, false),
                "a driven one reports that it has finished");
    }

    // ---- which one wins --------------------------------------------------

    @Test
    void theHighestPriorityClaimantOfATriggerWins() {
        RigStore.Rig rig = rig(named("nudge", ""), named("slam", ",\"priority\":10"));

        assertEquals(1, RigAnimations.findAnimationIndex(rig, RigAnimations.TRIGGER_RIGHT_CLICK));
    }

    @Test
    void equalPrioritiesFallBackToRigOrder() {
        // Which is exactly what this did before priority existed, since every
        // animation then had the same one.
        RigStore.Rig rig = rig(named("first", ""), named("second", ""));

        assertEquals(0, RigAnimations.findAnimationIndex(rig, RigAnimations.TRIGGER_RIGHT_CLICK));
    }

    @Test
    void aNamedChoiceStillBeatsPriority() {
        // A placement that was given a choice is a decision somebody made
        // about that one piece, and priority is a default about the model.
        RigStore.Rig rig = rig(named("nudge", ""), named("slam", ",\"priority\":10"));

        assertEquals(0, RigAnimations.findAnimationIndex(rig, RigAnimations.TRIGGER_RIGHT_CLICK, "nudge"));
    }

    // ---- the crossfade ---------------------------------------------------
    //
    // A fade works on a step's VALUES — degrees, px, scale — and composes
    // them about the pivot, never on a Transformation. The last test in this
    // group is the reason: a Transformation-space mix, and the client's own
    // interpolation which tweens the same decomposition, took a wheel off its
    // axle on every change of state.

    /** A step at {@code degrees} about X, {@code px} along Y. */
    private static float[] step(float degrees, float px) {
        return new float[] {degrees, 0f, 0f, 0f, px, 0f, 1f, 1f, 1f};
    }

    @Test
    void aFadeEndsWhereItWasGoing() {
        float[] mixed = RigMath.lerpStep(step(0, 0), step(90, 4), 1f);

        assertEquals(90f, mixed[0], 0.0001);
        assertEquals(4f, mixed[4], 0.0001);
    }

    @Test
    void aFadeStartsWhereItWas() {
        float[] mixed = RigMath.lerpStep(step(30, 2), step(90, 4), 0f);

        assertEquals(30f, mixed[0], 0.0001);
        assertEquals(2f, mixed[4], 0.0001);
    }

    @Test
    void everythingMovesLinearlyThroughTheFade() {
        float[] mixed = RigMath.lerpStep(step(0, 0), step(90, 4), 0.5f);

        assertEquals(45f, mixed[0], 0.0001);
        assertEquals(2f, mixed[4], 0.0001);
    }

    @Test
    void restIsWhatNullMeansAtEitherEnd() {
        float[] out = RigMath.lerpStep(step(90, 4), null, 0.5f);
        assertEquals(45f, out[0], 0.0001);
        assertEquals(2f, out[4], 0.0001);
        assertEquals(1f, out[6], 0.0001, "scale rests at 1, not 0");

        float[] in = RigMath.lerpStep(null, step(90, 4), 0.25f);
        assertEquals(22.5f, in[0], 0.0001);
    }

    @Test
    void aRotationGoesTheShortWayRound() {
        // A wheel at 350 asked for 10 turns twenty degrees on, not three
        // hundred and forty back — on a spinning wheel the difference between
        // a fade nobody sees and a wheel that reverses.
        float[] mixed = RigMath.lerpStep(step(350, 0), step(10, 0), 0.5f);

        assertEquals(360f, mixed[0], 0.0001);
        assertEquals(20f, RigMath.turnBetween(new float[][] {step(350, 0)}, new float[][] {step(10, 0)}, 1), 0.0001);
    }

    @Test
    void aFadingWheelStaysOnItsAxle() {
        // THE property, and the one a Transformation-space mix does not have:
        // every pose of the fade is composed about the pivot, so the pivot
        // never moves. Tweened the other way — translation and rotation
        // separately, which is what the client does between two sends — a
        // wheel a block out from the entity leaves its axle by up to that
        // whole block halfway through a half turn.
        float[] pivot = {24f, 8f, 8f}; // a block out along X
        for (float amount : new float[] {0f, 0.25f, 0.5f, 0.75f, 1f}) {
            float[] values = RigMath.lerpStep(new float[] {0f, 170f, 0f, 0f, 0f, 0f, 1f, 1f, 1f}, null, amount);
            Matrix4f m = new Matrix4f();
            RigMath.composeStep(m, pivot, values);
            Vector3f centre = m.transformPosition(new Vector3f(1f, 0f, 0f));
            assertEquals(1f, centre.x, 0.0001, "at " + amount);
            assertEquals(0f, centre.y, 0.0001, "at " + amount);
            assertEquals(0f, centre.z, 0.0001, "at " + amount);
        }
    }

    @Test
    void aFadeIsAsLongAsItsBiggestTurnNeeds() {
        // No send may turn a bone more than the step, so a half turn is four
        // sends of 45 and a nudge is one: an ordinary frame.
        float[][] from = {step(170, 0)};
        assertEquals(8, RigMath.fadeTicks(from, null, 1, 2, 45f));
        assertEquals(2, RigMath.fadeTicks(from, new float[][] {step(160, 9)}, 1, 2, 45f));
        assertEquals(2, RigMath.fadeTicks(null, null, 1, 2, 45f), "rest to rest is still one send");
    }

    @Test
    void anAmountOutsideZeroToOneIsClamped() {
        assertEquals(0f, RigMath.lerpStep(step(0, 0), step(90, 4), -1f)[0], 0.0001);
        assertEquals(90f, RigMath.lerpStep(step(0, 0), step(90, 4), 2f)[0], 0.0001);
    }

    // ---- joining a cycle in phase ----------------------------------------

    private static RigStore.Animation cycle(String name, double length, String track) {
        return animation("{\"name\":\"" + name + "\",\"length\":" + length + ",\"mode\":\"loop\","
                + "\"animators\":{\"wheel\":{\"rotation\":[" + track + "]}}}");
    }

    private static String key(double time, float x) {
        return "{\"time\":" + time + ",\"value\":[" + x + ",0,0]}";
    }

    @Test
    void aWheelJoinsTheNextCycleAtTheAngleItIsAlreadyAt() {
        // moving runs 0 to 360 over two seconds; turning, a different length,
        // runs the same way over one. A wheel a fifth of the way round joins
        // turning a fifth of the way round, not at 0.
        RigStore.Animation moving = cycle("moving", 2, key(0, 0) + "," + key(2, 360));
        RigStore.Animation turning = cycle("turning", 1, key(0, 0) + "," + key(1, 360));

        assertEquals(0.2, RigAnimations.nearestPhase(moving, 0.4, turning), 0.03);
    }

    @Test
    void aWheelJoinsAReverseCycleWhereItReadsTheSameAngle() {
        // reversing runs 360 down to 0, so 72 degrees is four fifths of the
        // way through it. The wheel keeps its angle and changes direction,
        // which is what a car does.
        RigStore.Animation moving = cycle("moving", 2, key(0, 0) + "," + key(2, 360));
        RigStore.Animation reversing = cycle("reversing", 1, key(0, 360) + "," + key(1, 0));

        assertEquals(0.8, RigAnimations.nearestPhase(moving, 0.4, reversing), 0.03);
    }

    @Test
    void anAngleIsMatchedUpToWholeTurns() {
        // 540 and 180 are the same angle; the cost has to say so, or a cycle
        // authored as two turns could never be joined from one authored as one.
        RigStore.Animation fast = cycle("fast", 1, key(0, 0) + "," + key(1, 720));
        RigStore.Animation slow = cycle("slow", 1, key(0, 0) + "," + key(1, 360));

        // fast at 0.75 s reads 540, which is 180; slow reads 180 at 0.5 s.
        assertEquals(0.5, RigAnimations.nearestPhase(fast, 0.75, slow), 0.03);
    }

    @Test
    void aCycleThatRotatesNothingIsJoinedAtTheTop() {
        RigStore.Animation moving = cycle("moving", 2, key(0, 0) + "," + key(2, 360));
        RigStore.Animation bob = animation("{\"name\":\"idle\",\"length\":1,\"mode\":\"loop\","
                + "\"animators\":{\"body\":{\"position\":[" + key(0, 0) + "," + key(1, 2) + "]}}}");

        assertEquals(0, RigAnimations.nearestPhase(moving, 0.4, bob), 0.0001);
        assertEquals(0, RigAnimations.nearestPhase(null, 0, moving), 0.0001, "from rest is frame 0");
    }

    @Test
    void theJoinIsToWhatIsOnScreenNotToWhatTheOldCycleSamples() {
        // A wheel HELD at 200 degrees through an idle that never touched it
        // rejoins moving at 200, not at the 0 idle would have read for it.
        RigStore.Animation idle = animation("{\"name\":\"idle\",\"length\":1,\"mode\":\"loop\","
                + "\"animators\":{\"body\":{\"position\":[" + key(0, 0) + "," + key(1, 2) + "]}}}");
        RigStore.Animation moving = cycle("moving", 2, key(0, 0) + "," + key(2, 360));
        Map<String, float[]> shown = Map.of("wheel", new float[] {200f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f});

        assertEquals(200.0 / 360 * 2, RigAnimations.nearestPhase(shown, idle, 0.3, moving), 0.03);
        assertEquals(0, RigAnimations.nearestPhase(Map.of(), idle, 0.3, moving), 0.0001, "nothing shown: as before");
    }

    // ---- a spinning bone nothing drives keeps its angle --------------------

    @Test
    void aTrackOfZerosDrivesNothingAndAFullTurnSpins() {
        RigStore.Animation moving = cycle("moving", 2, key(0, 0) + "," + key(2, 360));
        RigStore.Animation still = cycle("idle", 1, key(0, 0) + "," + key(1, 0));
        RigStore.Animation steer = cycle("turning", 1, key(0, 20) + "," + key(1, 20));
        RigStore.Animation wobble = cycle("bump", 1, key(0, 0) + "," + key(0.5, 90) + "," + key(1, 0));

        assertTrue(RigAnimations.drivesRotation(moving, "wheel"));
        assertTrue(RigAnimations.spins(moving, "wheel"));
        assertFalse(RigAnimations.drivesRotation(still, "wheel"), "zeros are a bone left alone");
        assertTrue(RigAnimations.drivesRotation(steer, "wheel"), "a held angle is still a driven one");
        assertFalse(RigAnimations.spins(steer, "wheel"));
        assertFalse(RigAnimations.spins(wobble, "wheel"), "there and back is not a turn");
        assertFalse(RigAnimations.drivesRotation(moving, "body"), "no track at all");
        assertFalse(RigAnimations.drivesRotation(null, "wheel"));
        assertFalse(RigAnimations.spins(null, "wheel"));
    }

    @Test
    void aHeldRotationRidesOverWhateverTheNewPoseSays() {
        float[][] held = {new float[] {200f, 0f, 0f}};

        float[][] out = RigMath.holdRotations(new float[][] {step(0, 4)}, held, 1);
        assertEquals(200f, out[0][0], 0.0001);
        assertEquals(4f, out[0][4], 0.0001, "position is the new pose's");

        float[][] rest = RigMath.holdRotations(null, held, 1);
        assertEquals(200f, rest[0][0], 0.0001, "held through rest too");
        assertEquals(1f, rest[0][6], 0.0001);

        assertNull(RigMath.holdRotations(null, null, 1), "nothing held, nothing made");
        assertEquals(0f, RigMath.turnBetween(new float[][] {step(200, 0)}, rest, 1), 0.0001,
                "a held wheel gives the fade nothing to turn");
    }

    // ---- the neck --------------------------------------------------------

    @Test
    void anAngleIsWrappedIntoTheShortWayRound() {
        // A mob turning from 179 to -179 has moved two degrees, not 358. Left
        // unwrapped, the clamp below reads that as a hard limit hit and snaps
        // the head to the far side.
        assertEquals(2f, HeadLook.wrap(362f), 0.0001);
        assertEquals(-2f, HeadLook.wrap(358f), 0.0001);
        assertEquals(-179f, HeadLook.wrap(181f), 0.0001);
        assertEquals(0f, HeadLook.wrap(720f), 0.0001);
    }

    @Test
    void aNeckIsClampedRatherThanLettingAHeadGoOnBackwards() {
        assertEquals(75f, HeadLook.clamp(200f, 75f), 0.0001);
        assertEquals(-75f, HeadLook.clamp(-200f, 75f), 0.0001);
        assertEquals(30f, HeadLook.clamp(30f, 75f), 0.0001);
    }

    @Test
    void aPartOnAManifestWithNoBehaviourHasNone() {
        // Every rig pushed before behaviours existed, which is all of them.
        RigStore.Part part = new com.google.gson.Gson().fromJson(
                "{\"item\":\"mypack:golem__part0\",\"program\":[]}", RigStore.Part.class);

        assertEquals(ai.resourcepack.engine.api.BoneBehaviour.NONE, HeadLook.behaviourOf(part));
        assertEquals(ai.resourcepack.engine.api.BoneBehaviour.NONE, HeadLook.behaviourOf(null));
    }

    @Test
    void aBehaviourOnTheManifestIsReadBackByName() {
        RigStore.Part part = new com.google.gson.Gson().fromJson(
                "{\"item\":\"a:b__part0\",\"program\":[],\"behaviour\":\"hitbox_oriented\"}",
                RigStore.Part.class);

        assertEquals(ai.resourcepack.engine.api.BoneBehaviour.HITBOX_ORIENTED,
                HeadLook.behaviourOf(part));
    }

    // ---- layers ----------------------------------------------------------

    @Test
    void anAnimationIsOnTheBaseLayerUnlessItSaysOtherwise() {
        // Every rig pushed before layers existed, which is all of them: the
        // absent field reads as 0, and 0 is the ordinary path.
        assertEquals(0, animation(named("wave", "")).layer);
        assertEquals(2, animation(named("wave", ",\"layer\":2")).layer);
    }

    @Test
    void aLayeredSettingReachesTheManifest() {
        JsonObject model = JsonParser.parseString("{\"elements\":[{\"from\":[0,0,0],\"to\":[4,4,4],\"faces\":{}}],"
                + "\"groups\":[{\"name\":\"arm\",\"origin\":[8,6,8],\"parent\":-1,\"children\":[0]}],"
                + "\"animations\":[{\"name\":\"wave\",\"length\":1,\"loop\":false,"
                + "\"triggers\":[{\"type\":\"right_click\"}],\"animators\":{\"g:0\":{\"rotation\":["
                + "{\"time\":0,\"value\":[0,0,0]},{\"time\":1,\"value\":[0,90,0]}]}}}]}").getAsJsonObject();
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem", model).orElseThrow();

        ModelRigs.apply(rig, Map.of("wave",
                AnimationSettings.of(null, 0, 0, 0, 3)));

        assertEquals(3, rig.animations().get(0).getAsJsonObject().get("layer").getAsInt());
    }

    @Test
    void layerZeroIsNotWrittenBecauseItIsTheDefault() {
        JsonObject model = JsonParser.parseString("{\"elements\":[{\"from\":[0,0,0],\"to\":[4,4,4],\"faces\":{}}],"
                + "\"groups\":[{\"name\":\"arm\",\"origin\":[8,6,8],\"parent\":-1,\"children\":[0]}],"
                + "\"animations\":[{\"name\":\"wave\",\"length\":1,\"loop\":false,"
                + "\"triggers\":[{\"type\":\"right_click\"}],\"animators\":{\"g:0\":{\"rotation\":["
                + "{\"time\":0,\"value\":[0,0,0]},{\"time\":1,\"value\":[0,90,0]}]}}}]}").getAsJsonObject();
        ModelRigs.Rig rig = ModelRigs.compute("mypack:golem", model).orElseThrow();

        ModelRigs.apply(rig, Map.of("wave", AnimationSettings.of(null, 0, 0, 0, 0)));

        assertFalse(rig.animations().get(0).getAsJsonObject().has("layer"));
    }

    // ---- weights and bone masks ------------------------------------------

    private static RigStore.Part part(String json) {
        return new com.google.gson.Gson().fromJson(json, RigStore.Part.class);
    }

    @Test
    void anAnimationWithNoWeightAppliesAtFullStrength() {
        // Every manifest written before weights, which is all of them.
        assertEquals(1f, RigAnimations.weightOf(animation(named("wave", ""))), 0.0001);
        assertEquals(1f, RigAnimations.weightOf(animation(named("wave", ",\"weight\":0"))), 0.0001);
        assertEquals(1f, RigAnimations.weightOf(null), 0.0001);
        assertEquals(0.5f, RigAnimations.weightOf(animation(named("wave", ",\"weight\":0.5"))), 0.0001);
    }

    @Test
    void aWeightIsNeverMoreThanFullStrength() {
        assertEquals(1f, RigAnimations.weightOf(animation(named("wave", ",\"weight\":4"))), 0.0001);
    }

    @Test
    void anAnimationWithNoMaskMovesEveryPart() {
        RigStore.Animation all = animation(named("wave", ""));

        assertTrue(RigAnimations.moves(all, part("{\"item\":\"a:b__part0\"}")));
        assertTrue(RigAnimations.moves(all, null));
    }

    @Test
    void aMaskMovesOnlyTheBonesItNames() {
        RigStore.Animation upper = animation(named("wave", ",\"bones\":[\"torso\"]"));

        assertTrue(RigAnimations.moves(upper,
                part("{\"item\":\"a:b__part0\",\"bone\":\"torso\",\"bones\":[\"root\",\"torso\"]}")));
        assertFalse(RigAnimations.moves(upper,
                part("{\"item\":\"a:b__part1\",\"bone\":\"leg\",\"bones\":[\"root\",\"leg\"]}")));
    }

    @Test
    void aMaskReachesEverythingHangingOffWhatItNames() {
        // The whole reason a part carries its lineage: masking a torso has to
        // move the arms inside it, and an arm knows its own name and nothing
        // else.
        RigStore.Animation upper = animation(named("wave", ",\"bones\":[\"torso\"]"));

        assertTrue(RigAnimations.moves(upper,
                part("{\"item\":\"a:b__part2\",\"bone\":\"arm\",\"bones\":[\"root\",\"torso\",\"arm\"]}")));
    }

    @Test
    void aMaskedAnimationLeavesOutWhatItCannotIdentify() {
        // A loose cube, or a part from a manifest older than lineages. A mask
        // is an author saying "only these", and "and also anything I cannot
        // place" is the opposite of that.
        RigStore.Animation upper = animation(named("wave", ",\"bones\":[\"torso\"]"));

        assertFalse(RigAnimations.moves(upper, part("{\"item\":\"a:b__part9\"}")));
    }

    @Test
    void aMaskDoesNotCareAboutCase() {
        RigStore.Animation upper = animation(named("wave", ",\"bones\":[\"Torso\"]"));

        assertTrue(RigAnimations.moves(upper,
                part("{\"item\":\"a:b__part0\",\"bone\":\"torso\",\"bones\":[\"torso\"]}")));
    }

    // ---- an author's settings reaching the manifest ----------------------

    @Test
    void settingsAreWrittenOntoTheAnimationTheyName() {
        JsonObject model = JsonParser.parseString("{\"elements\":[{\"from\":[0,0,0],\"to\":[4,4,4],\"faces\":{}}],"
                + "\"groups\":[{\"name\":\"arm\",\"origin\":[8,6,8],\"parent\":-1,\"children\":[0]}],"
                + "\"animations\":[{\"name\":\"open\",\"length\":1,\"loop\":true,"
                + "\"triggers\":[{\"type\":\"loop\"}],\"animators\":{\"g:0\":{\"rotation\":["
                + "{\"time\":0,\"value\":[0,0,0]},{\"time\":1,\"value\":[0,90,0]}]}}}]}").getAsJsonObject();
        ModelRigs.Rig rig = ModelRigs.compute("mypack:door", model).orElseThrow();

        List<String> unmatched = ModelRigs.apply(rig, Map.of(
                "open", AnimationSettings.of(AnimationSettings.Mode.HOLD, 0.5, 7, 0.25)));

        assertTrue(unmatched.isEmpty());
        JsonObject animation = rig.animations().get(0).getAsJsonObject();
        assertEquals("hold", animation.get("mode").getAsString());
        assertEquals(0.5, animation.get("speed").getAsDouble(), 0.0001);
        assertEquals(7, animation.get("priority").getAsInt());
        assertEquals(0.25, animation.get("blend").getAsDouble(), 0.0001);
        // Written for a plugin older than modes, and it has to keep agreeing.
        assertFalse(animation.get("loop").getAsBoolean());
    }

    @Test
    void aSettingForAnAnimationThatIsNotThereIsReportedRatherThanIgnored() {
        JsonObject model = JsonParser.parseString("{\"elements\":[{\"from\":[0,0,0],\"to\":[4,4,4],\"faces\":{}}],"
                + "\"groups\":[{\"name\":\"arm\",\"origin\":[8,6,8],\"parent\":-1,\"children\":[0]}],"
                + "\"animations\":[{\"name\":\"open\",\"length\":1,\"loop\":true,"
                + "\"triggers\":[{\"type\":\"loop\"}],\"animators\":{\"g:0\":{\"rotation\":["
                + "{\"time\":0,\"value\":[0,0,0]},{\"time\":1,\"value\":[0,90,0]}]}}}]}").getAsJsonObject();
        ModelRigs.Rig rig = ModelRigs.compute("mypack:door", model).orElseThrow();

        // The names live in a .bbmodel the definition parser never opened, so
        // this is the only place that can notice a typo at all.
        assertEquals(List.of("clsoe"), ModelRigs.apply(rig, Map.of(
                "clsoe", AnimationSettings.of(null, 0, 0, 0))));
    }
}
