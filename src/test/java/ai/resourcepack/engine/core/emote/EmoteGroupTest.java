package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteTrigger;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A movement group: one emote per state, worn as a single thing.
 *
 * <p>Everything here is about what happens when the manifest and this jar
 * disagree, because that is the failure mode a group has and an emote does not.
 * A group is INDIRECT — it names emotes rather than carrying keyframes — so
 * every one of its parts is a reference that can dangle, and each one dangles
 * silently: a part naming a state this version cannot detect is a condition
 * that never holds, and a part naming an emote that is not here is a rig
 * frozen at frame zero. Both read in game as the feature being broken.
 *
 * <p>Reachable without a server for the same reason {@code EmoteStanceTest} is:
 * {@code updateFromJson} takes a string and {@code partsOf} is arithmetic over
 * two maps. Nothing here spawns anything.
 */
class EmoteGroupTest {

    /** A store holding one manifest, with no file behind it. */
    private static EmoteStore storeOf(String json) {
        EmoteStore store = new EmoteStore(new File("build/tmp/emote-group-test"));
        store.updateFromJson(json);
        return store;
    }

    /** An emote with one keyframe, which is enough to be a real member. */
    private static String emote(String id, String name) {
        return "\"" + id + "\":{\"name\":\"" + name + "\",\"length\":1,\"loop\":true,"
            + "\"animators\":{\"head\":{\"rotation\":[{\"time\":0,\"value\":[0,0,0]}]}}}";
    }

    private static String manifest(String emotes, String groups) {
        return "{\"packId\":\"p\",\"bones\":[],\"emotes\":{" + emotes + "},\"groups\":{" + groups
            + "},\"players\":{}}";
    }

    @Test
    void aGroupIsFoundByNameAndById() {
        EmoteStore store = storeOf(manifest(
            emote("walk-cycle", "Walk cycle"),
            "\"heavy-armour\":{\"name\":\"Heavy armour\",\"parts\":{\"walk\":\"walk-cycle\"}}"));

        // The four ways a player might type it, matched exactly as an emote is
        // — id, name, and either of those in the wrong case.
        assertNotNull(store.findGroup("heavy-armour"));
        assertNotNull(store.findGroup("Heavy armour"));
        assertNotNull(store.findGroup("HEAVY-ARMOUR"));
        assertNotNull(store.findGroup("heavy armour"));
        assertNull(store.findGroup("nothing"));
    }

    @Test
    void aGroupAndAnEmoteShareOneNameSpace() {
        // Studio allocates both out of one manifest, so a name is one or the
        // other and never both. This is what lets `/emote <name>` ask the group
        // map first without having to decide anything.
        EmoteStore store = storeOf(manifest(
            emote("wave", "Wave"),
            "\"heavy-armour\":{\"name\":\"Heavy armour\",\"parts\":{}}"));

        assertNotNull(store.find("wave"));
        assertNull(store.findGroup("wave"));
        assertNotNull(store.findGroup("heavy-armour"));
        assertNull(store.find("heavy-armour"));
    }

    @Test
    void namesListsEmotesAndGroupsTogether() {
        // `/emote` completes off this list. A group a player has to be told
        // about out of band is a group nobody wears.
        EmoteStore store = storeOf(manifest(
            emote("wave", "Wave"),
            "\"heavy-armour\":{\"name\":\"Heavy armour\",\"parts\":{}}"));

        assertEquals(java.util.Arrays.asList("Heavy armour", "Wave"), store.names());
    }

    @Test
    void everyPartResolvesToTheEmoteItNames() {
        EmoteStore store = storeOf(manifest(
            emote("walk-cycle", "Walk cycle") + "," + emote("run-cycle", "Run cycle"),
            "\"set\":{\"name\":\"Set\",\"parts\":{\"walk\":\"walk-cycle\",\"sprint\":\"run-cycle\"}}"));

        Map<EmoteTrigger, EmoteStore.Emote> parts = store.partsOf(store.findGroup("set"));
        assertEquals(2, parts.size());
        assertSame(store.find("walk-cycle"), parts.get(EmoteTrigger.WALK));
        assertSame(store.find("run-cycle"), parts.get(EmoteTrigger.SPRINT));
        // The states it left alone are ABSENT rather than null — that absence
        // is the answer "the player's own animation", and the director reads it
        // as one. A null would be a second spelling of the same thing.
        assertNull(parts.get(EmoteTrigger.IDLE));
        assertNull(parts.get(EmoteTrigger.JUMP));
    }

    @Test
    void aPartNamingAnEmoteThatIsNotHereIsDropped() {
        // Studio's manifest builder already drops such a part, so reaching this
        // is a hand-edited file — and the honest answer is that the state falls
        // back to the player's own body rather than to a rig posed at frame
        // zero for ever.
        EmoteStore store = storeOf(manifest(
            emote("walk-cycle", "Walk cycle"),
            "\"set\":{\"name\":\"Set\",\"parts\":{\"walk\":\"walk-cycle\",\"sprint\":\"deleted\"}}"));

        Map<EmoteTrigger, EmoteStore.Emote> parts = store.partsOf(store.findGroup("set"));
        assertEquals(1, parts.size());
        assertNull(parts.get(EmoteTrigger.SPRINT));
    }

    @Test
    void aStateThisJarCannotDetectIsDropped() {
        // The same step down `triggersOf` takes for a stance: a newer studio
        // can name a state this version has never heard of, and a condition
        // that can never hold is worse than a state the group does not cover.
        EmoteStore store = storeOf(manifest(
            emote("walk-cycle", "Walk cycle"),
            "\"set\":{\"name\":\"Set\",\"parts\":{\"walk\":\"walk-cycle\",\"swimming\":\"walk-cycle\"}}"));

        assertEquals(1, store.partsOf(store.findGroup("set")).size());
    }

    @Test
    void aGroupWithNoUsablePartsResolvesToNothing() {
        // Which is what `startGroup` refuses on: a group in which nothing is
        // ever worn would put somebody in an emote where nothing happens, with
        // no way to tell that from the feature being broken.
        EmoteStore store = storeOf(manifest(
            emote("walk-cycle", "Walk cycle"),
            "\"empty\":{\"name\":\"Empty\",\"parts\":{}},"
                + "\"dangling\":{\"name\":\"Dangling\",\"parts\":{\"walk\":\"gone\"}}"));

        assertTrue(store.partsOf(store.findGroup("empty")).isEmpty());
        assertTrue(store.partsOf(store.findGroup("dangling")).isEmpty());
        assertTrue(store.partsOf(null).isEmpty());
    }

    @Test
    void aManifestWithNoGroupsIsAManifestWithNoGroups() {
        // Every push made before groups existed. Absent must read as none
        // rather than as a parse failure that takes the emotes down with it.
        EmoteStore store = storeOf("{\"packId\":\"p\",\"bones\":[],\"emotes\":{" + emote("wave", "Wave")
            + "},\"players\":{}}");

        assertNotNull(store.find("wave"));
        assertNull(store.findGroup("wave"));
        assertEquals(java.util.Collections.singletonList("Wave"), store.names());
    }

    @Test
    void aPushReplacesThePackSGroupsRatherThanMergingThem() {
        // Same contract the emotes have, and load-bearing for the same reason:
        // a group deleted in the panel has to stop existing here, and nothing
        // else will ever say that it went.
        EmoteStore store = storeOf(manifest(
            emote("walk-cycle", "Walk cycle"),
            "\"gone\":{\"name\":\"Gone\",\"parts\":{\"walk\":\"walk-cycle\"}}"));
        assertNotNull(store.findGroup("gone"));

        store.updateFromJson(manifest(
            emote("walk-cycle", "Walk cycle"),
            "\"kept\":{\"name\":\"Kept\",\"parts\":{\"walk\":\"walk-cycle\"}}"));

        assertNull(store.findGroup("gone"));
        assertNotNull(store.findGroup("kept"));
    }

    @Test
    void beingOffTheGroundWinsOverEverything() {
        // A group answers every state explicitly, so the air is a state of its
        // own — that is what makes "keep the default for jumping" expressible
        // at all. Sneaking and sprinting both lose to it: you can do either in
        // mid-air, and what a viewer sees is somebody jumping.
        assertEquals(EmoteTrigger.JUMP, EmoteStance.stanceState(false, false, false, true));
        assertEquals(EmoteTrigger.JUMP, EmoteStance.stanceState(true, false, true, true));
        assertEquals(EmoteTrigger.JUMP, EmoteStance.stanceState(false, true, true, true));
    }

    @Test
    void aSetThatNamedTheOldSneakWearsItInBothCrouchingStates() {
        // Every set built before crouching was split names `sneak`, and this
        // jar never resolves that state — so without the fallback the rig would
        // be put away the moment its wearer crouched, which is indistinguishable
        // from the set having no crouch at all.
        EmoteStore store = storeOf(manifest(
            emote("crouch", "Crouch"),
            "\"set\":{\"name\":\"Set\",\"parts\":{\"sneak\":\"crouch\"}}"));
        Map<EmoteTrigger, EmoteStore.Emote> parts = store.partsOf(store.findGroup("set"));

        assertSame(store.find("crouch"), EmoteStance.memberFor(parts, EmoteTrigger.SNEAK_IDLE));
        assertSame(store.find("crouch"), EmoteStance.memberFor(parts, EmoteTrigger.SNEAK_MOVE));
        assertNull(EmoteStance.memberFor(parts, EmoteTrigger.WALK));
    }

    @Test
    void aSetThatNamesBothCrouchingStatesGetsBothOfThem() {
        // The point of the split: a crouched idle and a crouch-walk are two
        // animations, and the exact name always wins over the umbrella.
        EmoteStore store = storeOf(manifest(
            emote("crouch", "Crouch") + "," + emote("creep", "Creep") + "," + emote("old", "Old"),
            "\"set\":{\"name\":\"Set\",\"parts\":{\"sneak_idle\":\"crouch\",\"sneak_move\":\"creep\","
                + "\"sneak\":\"old\"}}"));
        Map<EmoteTrigger, EmoteStore.Emote> parts = store.partsOf(store.findGroup("set"));

        assertSame(store.find("crouch"), EmoteStance.memberFor(parts, EmoteTrigger.SNEAK_IDLE));
        assertSame(store.find("creep"), EmoteStance.memberFor(parts, EmoteTrigger.SNEAK_MOVE));
    }

    @Test
    void theFourGroundStatesAreUnchangedWhenNobodyIsInTheAir() {
        // The overload every stance authored before JUMP existed resolves
        // through. If this ever stops matching the five-argument form with
        // `airborne` false, every one of those stances changes behaviour.
        for (int bits = 0; bits < 8; bits++) {
            boolean sneak = (bits & 1) != 0;
            boolean sprint = (bits & 2) != 0;
            boolean moving = (bits & 4) != 0;
            assertEquals(
                EmoteStance.stanceState(sneak, sprint, moving),
                EmoteStance.stanceState(sneak, sprint, moving, false));
        }
    }
}
