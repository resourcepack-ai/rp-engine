package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteTrigger;
import ai.resourcepack.engine.api.MergeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emote manifest, ported from {@code the previous engine}'s
 * {@code origin/groups}.
 *
 * <p>Its shape is produced by studio's {@code Studio's emote manifest} and the
 * two have to stay in step, so these tests are written against the JSON rather
 * than against the objects: a field renamed on either side should fail here
 * rather than in game.
 */
class EmoteStoreTest {

    /** The nil UUID studio bakes the shared default rig under. */
    private static final String DEFAULT_PLAYER = "00000000000000000000000000000000";

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path folder;

    private EmoteStore store;

    @BeforeEach
    void setUp() {
        store = new EmoteStore(folder.toFile());
    }

    private static String manifest(String extra) {
        return "{"
                + "\"packId\": \"pack-1\","
                + "\"bones\": [{\"key\": \"head\", \"pivot\": [8, 24, 8]},"
                + "            {\"key\": \"right_arm\", \"pivot\": [12, 22, 8]}],"
                + "\"emotes\": {"
                + "  \"wave\": {\"name\": \"Wave\", \"length\": 1.5, \"loop\": false,"
                + "             \"animators\": {\"rightArm\": {\"rotation\": ["
                + "                {\"time\": 0, \"value\": [0,0,0]},"
                + "                {\"time\": 1, \"value\": [0,0,45]}]}}}"
                + "},"
                + "\"players\": {\"" + DEFAULT_PLAYER + "\": {\"item\": \"steve\", \"variant\": \"wide\"}}"
                + extra
                + "}";
    }

    @Test
    void mergesAManifestAndReportsWhatCameIn() {
        MergeResult result = store.updateFromJson(manifest(""));

        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        assertEquals("pack-1", result.packId());
        assertNotNull(store.find("wave"));
    }

    @Test
    void anEmoteIsFoundByIdOrByName() {
        store.updateFromJson(manifest(""));

        // Ids are slugs and names are free text, and somebody typing what they
        // see in the panel is typing the name.
        assertNotNull(store.find("wave"));
        assertNotNull(store.find("Wave"));
        assertNotNull(store.find("WAVE"));
        assertNull(store.find("nope"));
    }

    @Test
    void somebodyWithNoRigOfTheirOwnGetsTheSharedDefault() {
        store.updateFromJson(manifest(""));

        // Falling back rather than refusing is the whole reason the default is
        // baked: somebody who joined after the last push would otherwise be
        // told to go and re-sync, which is a poor answer to a person standing
        // in game who wants to wave.
        UUID stranger = UUID.randomUUID();
        assertNull(store.ownRigFor(stranger));
        assertNotNull(store.rigFor(stranger));
        assertTrue(store.hasAnyRig());
    }

    @Test
    void aManifestThatIsNotJsonFailsWithAReasonRatherThanThrowing() {
        MergeResult result = store.updateFromJson("not json at all");

        assertFalse(result.ok());
        assertNotNull(result.error());
    }

    @Test
    void anEmptyManifestFailsTheSameWay() {
        assertFalse(store.updateFromJson("").ok());
        assertFalse(store.updateFromJson(null).ok());
    }

    // ---- stances -------------------------------------------------------

    @Test
    void anEmoteWithNoTriggersIsAMomentRatherThanAStance() {
        store.updateFromJson(manifest(""));

        assertTrue(EmoteStore.triggersOf(store.find("wave")).isEmpty());
    }

    @Test
    void triggersAreReadOffTheManifestByWireName() {
        store.updateFromJson(manifest(",\"x\": 0").replace(
                "\"loop\": false,", "\"loop\": true, \"triggers\": [\"idle\", \"walk\"],"));

        assertEquals(EnumSet.of(EmoteTrigger.IDLE, EmoteTrigger.WALK),
                EmoteStore.triggersOf(store.find("wave")));
    }

    @Test
    void aTriggerThisJarHasNeverHeardOfIsDroppedRatherThanFailingThePack() {
        store.updateFromJson(manifest("").replace(
                "\"loop\": false,", "\"loop\": true, \"triggers\": [\"idle\", \"moonwalk\"],"));

        // A newer studio may name a state this build cannot detect. Refusing
        // the whole pack over one word would cost every other emote in it.
        assertEquals(EnumSet.of(EmoteTrigger.IDLE), EmoteStore.triggersOf(store.find("wave")));
    }

    @Test
    void aPackBuiltBeforeTheCrouchSplitStillCoversBothCrouchingStates() {
        store.updateFromJson(manifest("").replace(
                "\"loop\": false,", "\"loop\": true, \"triggers\": [\"sneak\"],"));

        // Nothing ever resolves TO sneak, so without covers() a set that
        // plainly handles a crouch-walk would report that it does not.
        assertEquals(EnumSet.of(EmoteTrigger.SNEAK_IDLE, EmoteTrigger.SNEAK_MOVE),
                EmoteStore.statesOf(EmoteStore.triggersOf(store.find("wave"))));
    }

    // ---- per-bone models -----------------------------------------------

    @Test
    void anArmPicksTheWidthThePlayerIsActuallyWearing() {
        store.updateFromJson(manifest("").replace(
                "\"variant\": \"wide\"", "\"variant\": \"wide\", \"arms\": [\"slim\", \"wide\"]"));

        EmoteStore.PlayerRig rig = store.rigFor(UUID.fromString(
                "00000000-0000-0000-0000-000000000000"));

        // Studio cannot see the slim/wide flag when it bakes, so the pack
        // carries both pairs and the choice is made here off the live profile.
        assertEquals("steve__slim__rightarm", EmoteStore.boneItemId(rig, "rightArm", "slim"));
        assertEquals("steve__wide__rightarm", EmoteStore.boneItemId(rig, "rightArm", "wide"));
    }

    @Test
    void aBoneThatIsNotAnArmIgnoresTheWidthEntirely() {
        store.updateFromJson(manifest("").replace(
                "\"variant\": \"wide\"", "\"variant\": \"wide\", \"arms\": [\"slim\", \"wide\"]"));

        EmoteStore.PlayerRig rig = store.rigFor(UUID.fromString(
                "00000000-0000-0000-0000-000000000000"));

        // Only the arms are baked under a qualified name, so qualifying any
        // other bone would name a model that is not there.
        assertEquals("steve__head", EmoteStore.boneItemId(rig, "head", "slim"));
    }

    @Test
    void aPackFromBeforeBothArmsWereBakedUsesTheUnqualifiedModel() {
        store.updateFromJson(manifest(""));

        EmoteStore.PlayerRig rig = store.rigFor(UUID.fromString(
                "00000000-0000-0000-0000-000000000000"));

        // A model that exists, rather than one that does not.
        assertEquals("steve__rightarm", EmoteStore.boneItemId(rig, "rightArm", "slim"));
    }

    // ---- persistence ---------------------------------------------------

    @Test
    void aMergedManifestSurvivesARestart() {
        store.updateFromJson(manifest(""));
        store.save(LOG);

        EmoteStore reopened = new EmoteStore(folder.toFile());
        reopened.load(LOG);

        // Emotes outlive the socket that delivered them: a server restarted
        // overnight should not need a fresh push before anybody can wave.
        assertNotNull(reopened.find("wave"));
        assertTrue(reopened.hasAnyRig());
    }

    @Test
    void retiringAPackTakesItsEmotesWithIt() {
        store.updateFromJson(manifest(""));

        store.retire("pack-1");

        assertNull(store.find("wave"));
        assertFalse(store.packIds().contains("pack-1"));
    }

    @Test
    void loadingWithNoFileIsNotAProblem() {
        store.load(LOG);

        assertFalse(store.hasAnyRig());
    }
}
