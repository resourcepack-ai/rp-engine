package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player who already has a bundle from another plugin's pack: what this
 * server still sends them, and what the build event says changed.
 */
class HeldElsewhereTest {

    private final UUID player = UUID.randomUUID();

    private static BuiltPack pack(String bundle, String sha1) {
        return BuiltPack.of(bundle, Path.of(bundle + ".zip"), sha1, 1, 1);
    }

    private static List<String> bundles(List<BuiltPack> packs) {
        return packs.stream().map(BuiltPack::bundle).toList();
    }

    @Test
    void aMarkedBundleIsLeftOutAndTheRestKeepTheirOrder() {
        HeldElsewhere external = new HeldElsewhere();
        external.mark(player, "main");
        List<BuiltPack> stack = ServerStack.of(pack("main", "a"), pack("rpengine-rigs", "r"), null, pack("studio", "s"));

        assertEquals(List.of("rpengine-rigs", "studio"), bundles(external.without(player, stack)));
        assertEquals(List.of("main", "rpengine-rigs", "studio"),
                bundles(external.without(UUID.randomUUID(), stack)));
    }

    @Test
    void markingAPlayerWhoHoldsOursTakesOursOff() {
        HeldElsewhere external = new HeldElsewhere();
        BundleSessions sessions = new BundleSessions();
        List<BuiltPack> stack = ServerStack.of(pack("main", "a"), pack("rpengine-rigs", "r"), null, null);
        sessions.applied(player, stack);

        external.mark(player, "main");
        BundleSessions.Delta delta = sessions.plan(player, external.without(player, stack));

        assertTrue(delta.remove().stream().anyMatch(held -> held.bundle().equals("main")));
        assertEquals(List.of("rpengine-rigs"), bundles(delta.add()));
    }

    @Test
    void marksAreSaidOnceAndForgottenOnQuit() {
        HeldElsewhere external = new HeldElsewhere();
        assertTrue(external.mark(player, "main"));
        assertFalse(external.mark(player, "main"));
        assertTrue(external.marked(player, "main"));
        assertTrue(external.unmark(player, "main"));
        assertFalse(external.unmark(player, "main"));

        external.mark(player, "main");
        external.forget(player);
        assertFalse(external.marked(player, "main"));
    }

    @Test
    void theBuildEventNamesOnlyWhatChanged() {
        List<BuiltPack> sent = new ArrayList<>(List.of(pack("main", "a"), pack("rpengine-rigs", "r")));
        PacksImpl packs = new PacksImpl(new PacksImpl.Host() {
            @Override
            public List<BuiltPack> sent() {
                return List.copyOf(sent);
            }

            @Override
            public List<BuiltPack> readable() {
                return List.of(pack("main", "unsquashed"));
            }

            @Override
            public String defaultBundle() {
                return "main";
            }

            @Override
            public void marksChanged(UUID player) {
            }
        }, new HeldElsewhere());

        assertEquals(Set.of("main", "rpengine-rigs"), packs.rebuilt().changed());
        assertEquals(Set.of(), packs.rebuilt().changed());

        sent.set(1, pack("rpengine-rigs", "r2"));
        assertEquals(Set.of("rpengine-rigs"), packs.rebuilt().changed());

        sent.remove(1);
        assertEquals(Set.of("rpengine-rigs"), packs.rebuilt().changed());

        assertEquals("unsquashed", packs.readable("main").orElseThrow().sha1());
        assertEquals("a", packs.get("main").orElseThrow().sha1());
        assertTrue(packs.readable("nothing").isEmpty());
    }

    @Test
    void changedComparesHashesBothWays() {
        assertEquals(Set.of("b", "c"), PacksImpl.changed(Map.of("a", "1", "b", "1"), Map.of("a", "1", "c", "1")));
    }
}
