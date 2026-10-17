package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emote rigs as a pack of their own, on top of the server's bundle: what
 * a player is sent, and what a rigs-only rebuild costs them.
 */
class ServerStackTest {

    private final UUID player = UUID.randomUUID();

    private static BuiltPack pack(String bundle, String sha1) {
        return BuiltPack.of(bundle, Path.of(bundle + ".zip"), sha1, 1, 1);
    }

    private static List<String> bundles(List<BuiltPack> packs) {
        return packs.stream().map(BuiltPack::bundle).toList();
    }

    @Test
    void theRigsSitOnTheBundleAndAPlayersOwnRigOnTheRigs() {
        List<BuiltPack> stack = ServerStack.of(pack("main", "a"), pack("rpengine-rigs", "r"),
                pack("rig-me", "m"), pack("studio", "s"));

        assertEquals(List.of("main", "rpengine-rigs", "rig-me", "studio"), bundles(stack));
    }

    @Test
    void theMergedShadersGoOnTopOfEverythingEvenAPush() {
        List<BuiltPack> stack = ServerStack.withShaders(ServerStack.of(pack("main", "a"), null, null, pack("studio", "s")),
                pack("rpengine-shaders-1234", "x"));
        assertEquals(List.of("main", "studio", "rpengine-shaders-1234"), bundles(stack));
        assertEquals(List.of(), ServerStack.withShaders(List.of(), pack("rpengine-shaders-1234", "x")),
                "and never alone: there is nothing of ours for them to sit on");
    }

    @Test
    void rigsInsideTheBundleAreNotAPack() {
        assertEquals(List.of("main"), bundles(ServerStack.of(pack("main", "a"), null, null, null)));
    }

    @Test
    void nothingOfTheServersGoesOutWithoutItsBundle() {
        List<BuiltPack> stack = ServerStack.of(null, pack("rpengine-rigs", "r"), pack("rig-me", "m"),
                pack("studio", "s"));

        assertEquals(List.of("studio"), bundles(stack), "the rigs are made to sit on the bundle");
    }

    @Test
    void aRigsOnlyRebuildSendsOnlyTheRigs() {
        BundleSessions sessions = new BundleSessions();
        BuiltPack main = pack("main", "a");
        sessions.applied(player, ServerStack.of(main, pack("rpengine-rigs", "r1"), null, null));

        BundleSessions.Delta delta = sessions.plan(player,
                ServerStack.of(main, pack("rpengine-rigs", "r2"), null, null));

        assertEquals(List.of("rpengine-rigs"), bundles(delta.add()), "the bundle is kept, cached");
        assertEquals("r2", delta.add().get(0).sha1());
        assertEquals(1, delta.remove().size());
        assertEquals("rpengine-rigs", delta.remove().get(0).bundle());
    }

    @Test
    void aRebuildThatHasAPlayersOwnRigDropsItAndKeepsTheBundle() {
        BundleSessions sessions = new BundleSessions();
        BuiltPack main = pack("main", "a");
        BuiltPack push = pack("studio", "s");
        sessions.applied(player, ServerStack.of(main, pack("rpengine-rigs", "r1"), pack("rig-me", "m"), push));

        BundleSessions.Delta delta = sessions.plan(player,
                ServerStack.of(main, pack("rpengine-rigs", "r2"), null, push));

        assertEquals(List.of("rpengine-rigs", "studio"), bundles(delta.add()));
        assertEquals(List.of("studio", "rig-me", "rpengine-rigs"),
                delta.remove().stream().map(BundleSessions.Held::bundle).toList());
        assertTrue(delta.remove().stream().noneMatch(held -> held.bundle().equals("main")));
    }

    @Test
    void unchangedRigsCostNothing() {
        BundleSessions sessions = new BundleSessions();
        List<BuiltPack> stack = ServerStack.of(pack("main", "a"), pack("rpengine-rigs", "r"), null, null);
        sessions.applied(player, stack);

        assertTrue(sessions.plan(player,
                ServerStack.of(pack("main", "a"), pack("rpengine-rigs", "r"), null, null)).isEmpty());
    }
}
