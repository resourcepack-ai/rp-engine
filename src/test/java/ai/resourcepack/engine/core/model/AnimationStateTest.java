package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.AnimationSettings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(1d, RigAnimator.speedOf(animation(named("wave", ""))), 0.0001);
        assertEquals(1d, RigAnimator.speedOf(animation(named("wave", ",\"speed\":0"))), 0.0001);
        assertEquals(1d, RigAnimator.speedOf(animation(named("wave", ",\"speed\":-2"))), 0.0001);
        assertEquals(1d, RigAnimator.speedOf(null), 0.0001);
    }

    @Test
    void speedScalesHowFarThroughTheAnimationTimeHasGot() {
        RigStore.Animation half = animation(named("wave", ",\"speed\":0.5"));
        RigStore.Animation twice = animation(named("wave", ",\"speed\":2"));

        assertEquals(0.5, RigAnimator.animationTime(half, 1), 0.0001);
        assertEquals(2, RigAnimator.animationTime(twice, 1), 0.0001);
    }

    @Test
    void aFastOneShotStopsAtItsEndRatherThanRunningPastIt() {
        RigStore.Animation twice = animation(named("wave", ",\"speed\":2"));

        // Length 2, double speed: over at one second of real time, and it
        // must clamp there rather than sampling off the end of the curve.
        assertEquals(2, RigAnimator.animationTime(twice, 5), 0.0001);
    }

    // ---- what happens at the end -----------------------------------------

    @Test
    void aHeldAnimationStopsOnItsLastFrameAndStaysThere() {
        // A door that stays open. Without this every one sprang shut the
        // moment it finished opening, because a one-shot that ran out went
        // back to rest.
        RigStore.Rig rig = rig(named("open", ",\"mode\":\"hold\""));

        assertEquals(0, RigAnimator.playbackAnimationIndex(rig, 0, 99),
                "long past its length, and still the pose on screen");
        assertEquals(2, RigAnimator.animationTime(rig.animations.get(0), 99), 0.0001,
                "clamped to the last frame");
    }

    @Test
    void aOnceAnimationGoesBackToRest() {
        RigStore.Rig rig = rig(named("wave", ",\"mode\":\"once\""));

        assertEquals(0, RigAnimator.playbackAnimationIndex(rig, 0, 1), "still running");
        assertEquals(-1, RigAnimator.playbackAnimationIndex(rig, 0, 99), "and then nothing");
    }

    @Test
    void aModeOverridesTheOlderBooleanRatherThanArguingWithIt() {
        // The boolean is what a plugin older than modes reads. Both are
        // written, so the two have to agree — but where they do not, the mode
        // is the more specific answer.
        RigStore.Rig rig = rig("{\"name\":\"spin\",\"length\":2,\"loop\":false,"
                + "\"mode\":\"loop\",\"triggers\":[{\"type\":\"loop\"}]}");

        assertEquals(0, RigAnimator.playbackAnimationIndex(rig, 0, 99));
    }

    @Test
    void aManifestWithNoModeBehavesExactlyAsItDidBefore() {
        RigStore.Rig looping = rig("{\"name\":\"spin\",\"length\":2,\"loop\":true,"
                + "\"triggers\":[{\"type\":\"loop\"}]}");
        RigStore.Rig once = rig(named("wave", ""));

        assertEquals(0, RigAnimator.playbackAnimationIndex(looping, 0, 99));
        assertEquals(-1, RigAnimator.playbackAnimationIndex(once, 0, 99));
    }

    // ---- which one wins --------------------------------------------------

    @Test
    void theHighestPriorityClaimantOfATriggerWins() {
        RigStore.Rig rig = rig(named("nudge", ""), named("slam", ",\"priority\":10"));

        assertEquals(1, RigAnimator.findAnimationIndex(rig, RigAnimator.TRIGGER_RIGHT_CLICK));
    }

    @Test
    void equalPrioritiesFallBackToRigOrder() {
        // Which is exactly what this did before priority existed, since every
        // animation then had the same one.
        RigStore.Rig rig = rig(named("first", ""), named("second", ""));

        assertEquals(0, RigAnimator.findAnimationIndex(rig, RigAnimator.TRIGGER_RIGHT_CLICK));
    }

    @Test
    void aNamedChoiceStillBeatsPriority() {
        // A placement that was given a choice is a decision somebody made
        // about that one piece, and priority is a default about the model.
        RigStore.Rig rig = rig(named("nudge", ""), named("slam", ",\"priority\":10"));

        assertEquals(0, RigAnimator.findAnimationIndex(rig, RigAnimator.TRIGGER_RIGHT_CLICK, "nudge"));
    }

    // ---- the crossfade ---------------------------------------------------

    private static Transformation at(float x, float degrees) {
        return new Transformation(
                new Vector3f(x, 0, 0),
                new Quaternionf().rotateY((float) Math.toRadians(degrees)),
                new Vector3f(1, 1, 1),
                new Quaternionf());
    }

    @Test
    void aBlendEndsWhereItWasGoing() {
        Transformation mixed = RigAnimator.mix(at(0, 0), at(4, 90), 1f);

        assertEquals(4f, mixed.getTranslation().x, 0.0001);
        assertEquals(at(4, 90).getLeftRotation().y, mixed.getLeftRotation().y, 0.0001);
    }

    @Test
    void aBlendStartsWhereItWas() {
        Transformation mixed = RigAnimator.mix(at(0, 0), at(4, 90), 0f);

        assertEquals(0f, mixed.getTranslation().x, 0.0001);
    }

    @Test
    void positionMovesLinearlyThroughTheBlend() {
        assertEquals(2f, RigAnimator.mix(at(0, 0), at(4, 90), 0.5f).getTranslation().x, 0.0001);
    }

    @Test
    void rotationIsSlerpedSoALimbDoesNotShrinkHalfwayThrough() {
        // The reason this is not a component-wise average: averaging two
        // quaternions gives something SHORTER than a unit quaternion, and a
        // non-unit rotation scales what it rotates. Halfway through a 180
        // degree turn the error is at its worst, and a lerp would visibly
        // collapse the part and spring it back out.
        Transformation mixed = RigAnimator.mix(at(0, 0), at(0, 180), 0.5f);
        Quaternionf rotation = mixed.getLeftRotation();
        float length = (float) Math.sqrt(rotation.x * rotation.x + rotation.y * rotation.y
                + rotation.z * rotation.z + rotation.w * rotation.w);

        assertEquals(1f, length, 0.0001, "still a rotation");
    }

    @Test
    void anAmountOutsideZeroToOneIsClamped() {
        assertEquals(0f, RigAnimator.mix(at(0, 0), at(4, 0), -1f).getTranslation().x, 0.0001);
        assertEquals(4f, RigAnimator.mix(at(0, 0), at(4, 0), 2f).getTranslation().x, 0.0001);
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
