package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleSessionsTest {

    private final UUID player = UUID.randomUUID();
    private BundleSessions sessions;

    @BeforeEach
    void setUp() {
        sessions = new BundleSessions();
    }

    private static BuiltPack pack(String bundle, String sha1) {
        return BuiltPack.of(bundle, Path.of(bundle + ".zip"), sha1, 1, 1);
    }

    private static List<String> bundles(List<BuiltPack> packs) {
        return packs.stream().map(BuiltPack::bundle).toList();
    }

    private static List<String> heldBundles(List<BundleSessions.Held> held) {
        return held.stream().map(BundleSessions.Held::bundle).toList();
    }

    @Test
    void aPlayerHoldingNothingIsSentEverything() {
        BundleSessions.Delta delta = sessions.plan(player, List.of(pack("base", "a"), pack("lobby", "b")));

        assertEquals(List.of("base", "lobby"), bundles(delta.add()));
        assertTrue(delta.remove().isEmpty());
    }

    @Test
    void holdingTheRightThingCostsNothing() {
        List<BuiltPack> packs = List.of(pack("base", "a"), pack("lobby", "b"));
        sessions.applied(player, packs);

        assertTrue(sessions.plan(player, packs).isEmpty(), "the common case must be free");
    }

    @Test
    void addingOnTopSendsOnlyTheNewOne() {
        sessions.applied(player, List.of(pack("base", "a")));

        BundleSessions.Delta delta = sessions.plan(player, List.of(pack("base", "a"), pack("event", "c")));

        assertEquals(List.of("event"), bundles(delta.add()));
        assertTrue(delta.remove().isEmpty(), "the base is already in the right place");
    }

    @Test
    void swappingTheTopKeepsTheBase() {
        sessions.applied(player, List.of(pack("base", "a"), pack("lobby", "b")));

        BundleSessions.Delta delta = sessions.plan(player, List.of(pack("base", "a"), pack("dungeon", "d")));

        assertEquals(List.of("lobby"), heldBundles(delta.remove()));
        assertEquals(List.of("dungeon"), bundles(delta.add()));
    }

    @Test
    void changingTheBaseResendsEverythingAboveIt() {
        sessions.applied(player, List.of(pack("base", "a"), pack("lobby", "b")));

        // Stack position decides who overrides whom and there is no way to
        // insert into the middle of a client's stack, so everything above the
        // first difference has to go.
        BundleSessions.Delta delta = sessions.plan(player, List.of(pack("other", "z"), pack("lobby", "b")));

        assertEquals(List.of("lobby", "base"), heldBundles(delta.remove()), "deepest first");
        assertEquals(List.of("other", "lobby"), bundles(delta.add()));
    }

    @Test
    void aRebuiltBundleIsResent() {
        sessions.applied(player, List.of(pack("lobby", "old")));

        BundleSessions.Delta delta = sessions.plan(player, List.of(pack("lobby", "new")));

        // Same bundle, new build. The client caches by hash and would keep
        // showing the old one.
        assertEquals(List.of("lobby"), heldBundles(delta.remove()));
        assertEquals(List.of("lobby"), bundles(delta.add()));
    }

    @Test
    void reorderingIsAResend() {
        sessions.applied(player, List.of(pack("a", "1"), pack("b", "2")));

        BundleSessions.Delta delta = sessions.plan(player, List.of(pack("b", "2"), pack("a", "1")));

        assertEquals(List.of("b", "a"), heldBundles(delta.remove()));
        assertEquals(List.of("b", "a"), bundles(delta.add()));
    }

    @Test
    void wantingNothingRemovesEverything() {
        sessions.applied(player, List.of(pack("base", "a"), pack("lobby", "b")));

        BundleSessions.Delta delta = sessions.plan(player, List.of());

        assertEquals(List.of("lobby", "base"), heldBundles(delta.remove()));
        assertTrue(delta.add().isEmpty());
    }

    @Test
    void planningRecordsNothing() {
        // A push can be declined, or the player can leave mid-swap. Believing
        // in a pack nobody has is worse than planning it twice.
        sessions.plan(player, List.of(pack("lobby", "b")));

        assertTrue(sessions.held(player).isEmpty());
        assertEquals(0, sessions.size());
    }

    @Test
    void appliedRecordsTheStackInOrder() {
        sessions.applied(player, List.of(pack("base", "a"), pack("lobby", "b")));

        assertEquals(List.of("base", "lobby"), heldBundles(sessions.held(player)));
        assertTrue(sessions.holds(player, "lobby"));
        assertFalse(sessions.holds(player, "dungeon"));
    }

    @Test
    void aRemovalNamesTheStablePackUuid() {
        sessions.applied(player, List.of(pack("lobby", "b")));

        assertEquals(BuiltPack.uuidFor("lobby"), sessions.held(player).get(0).uuid());
    }

    @Test
    void forgettingAPlayerDropsTheirStack() {
        sessions.applied(player, List.of(pack("lobby", "b")));

        sessions.forget(player);

        assertTrue(sessions.held(player).isEmpty());
        assertEquals(0, sessions.size());
        // A returning player is believed to hold nothing, which is what their
        // client actually does after a disconnect.
        assertEquals(List.of("lobby"), bundles(sessions.plan(player, List.of(pack("lobby", "b"))).add()));
    }

    @Test
    void applyingNothingForgets() {
        sessions.applied(player, List.of(pack("lobby", "b")));
        sessions.applied(player, List.of());

        assertEquals(0, sessions.size());
    }

    @Test
    void playersDoNotSeeEachOthersStacks() {
        UUID other = UUID.randomUUID();
        sessions.applied(player, List.of(pack("lobby", "b")));

        assertTrue(sessions.held(other).isEmpty());
        assertEquals(1, sessions.size());
    }

    @Test
    void nullArgumentsAnswerEmpty() {
        assertTrue(sessions.held(null).isEmpty());
        assertFalse(sessions.holds(null, "lobby"));
        assertTrue(sessions.plan(null, null).isEmpty());
        sessions.applied(null, List.of(pack("lobby", "b")));
        sessions.forget(null);
        assertEquals(0, sessions.size());
    }
}
