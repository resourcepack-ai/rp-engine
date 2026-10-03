package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.ContentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pushed content belongs to the players holding that push.
 *
 * <p>It used to be one store for the whole server, replaced by every push. On a
 * server where several people sync, that meant the last person to press Sync
 * decided everybody's content: their always-on shader object went up on every
 * screen that held any Studio pack, and the person who pushed before them
 * found their own dialogs gone.
 */
class PushedPacksTest {

    @TempDir
    File dir;

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();
    private static final ContentId MENU = ContentId.parse("studio:menu").orElseThrow();

    private StudioContent pack(String packId, String dialogTitle) {
        StudioContent content = new StudioContent(dir);
        content.updateFromJson("{\"packId\":\"" + packId + "\",\"dialogs\":[{\"id\":\"menu\",\"name\":\"" + dialogTitle
                + "\",\"json\":{\"type\":\"minecraft:notice\",\"title\":\"" + dialogTitle + "\"}}],"
                + "\"huds\":[{\"id\":\"" + packId + "_hud\",\"title\":\"x\",\"triggers\":[{\"kind\":\"always\"}]}]}");
        return content.snapshot();
    }

    @Test
    void eachPlayerSeesTheirOwnPushOfASharedId() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("alice-pack", "Alice"));
        packs.hold(ALICE, "alice-pack");
        packs.arrived(pack("bob-pack", "Bob"));
        packs.hold(BOB, "bob-pack");

        assertEquals("Alice", packs.contentFor(ALICE, false).orElseThrow().dialogs().get(MENU).name());
        assertEquals("Bob", packs.contentFor(BOB, false).orElseThrow().dialogs().get(MENU).name());
    }

    @Test
    void somebodyElsesPushIsNotYours() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("alice-pack", "Alice"));
        packs.hold(ALICE, "alice-pack");

        // Carol holds no push at all: Alice's always-on HUD is not hers to see.
        assertTrue(packs.contentFor(CAROL, false).isEmpty());
    }

    @Test
    void theNextPushDoesNotTakeYoursAway() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("alice-pack", "Alice"));
        packs.hold(ALICE, "alice-pack");
        packs.arrived(pack("bob-pack", "Bob"));
        packs.hold(BOB, "bob-pack");

        StudioContent all = StudioContent.union(packs.live());
        assertTrue(all.huds().containsKey(ContentId.parse("studio:alice-pack_hud").orElseThrow()));
        assertTrue(all.huds().containsKey(ContentId.parse("studio:bob-pack_hud").orElseThrow()));
    }

    @Test
    void aPushNobodyHoldsAnyMoreIsDroppedUnlessItIsTheLatest() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("alice-pack", "Alice"));
        packs.hold(ALICE, "alice-pack");
        packs.arrived(pack("bob-pack", "Bob"));
        packs.hold(BOB, "bob-pack");

        packs.release(ALICE);
        assertEquals(List.of("bob-pack"), ids(packs.live()));

        // The latest stays even with nobody holding it: it is what the server
        // persists, and what a parked vehicle from it is dressed from.
        packs.release(BOB);
        assertEquals(List.of("bob-pack"), ids(packs.live()));
    }

    @Test
    void aPushWithNoManifestHoldsNothingNameable() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("alice-pack", "Alice"));
        packs.hold(BOB, "");
        assertTrue(packs.contentFor(BOB, false).isEmpty());
    }

    @Test
    void thePublishedPackIsTheOneItSaysItIs() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("alice-pack", "Alice"));
        packs.hold(ALICE, "alice-pack");
        packs.arrived(pack("bob-pack", "Bob"));
        packs.hold(BOB, "bob-pack");
        packs.published("alice-pack");

        assertEquals("Alice", packs.contentFor(CAROL, true).orElseThrow().dialogs().get(MENU).name());
        // And it is kept while it is published, even with Alice gone.
        packs.release(ALICE);
        assertEquals("Alice", packs.contentFor(CAROL, true).orElseThrow().dialogs().get(MENU).name());
    }

    @Test
    void aPublishedPackWeHaveNoManifestForShowsNothing() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("bob-pack", "Bob"));
        packs.published("somebody-elses-pack");
        assertTrue(packs.contentFor(CAROL, true).isEmpty());
    }

    /**
     * A Studio that does not say what it publishes: nothing. Guessing "the last
     * push" is what put one tester's overlay on every screen of a server that
     * publishes some other pack.
     */
    @Test
    void anUnnamedPublishedPackShowsNothingPushed() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("bob-pack", "Bob"));
        packs.published(null);
        assertFalse(packs.contentFor(CAROL, true).isPresent());
        assertFalse(packs.contentFor(CAROL, false).isPresent());
    }

    @Test
    void yourOwnPushWinsOverThePublishedOne() {
        PushedPacks packs = new PushedPacks();
        packs.arrived(pack("alice-pack", "Alice"));
        packs.arrived(pack("bob-pack", "Bob"));
        packs.hold(BOB, "bob-pack");
        packs.published("alice-pack");
        assertEquals("Bob", packs.contentFor(BOB, true).orElseThrow().dialogs().get(MENU).name());
    }

    private static List<String> ids(List<StudioContent> live) {
        return live.stream().map(StudioContent::packId).toList();
    }
}
