package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.BuiltPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rigs written as a resource pack of their own. */
class RigPackTest {

    private static final String STEVE = RigBaker.DEFAULT_KEY;
    private static final String ALEX = "0123456789abcdef0123456789abcdef";

    private static RigAssets rigs(RigBaker.Skin... skins) {
        return new RigAssets(RigBaker.bake(List.of(skins)));
    }

    private static RigBaker.Skin steve() {
        return new RigBaker.Skin(STEVE, new byte[] {1, 2, 3}, RigGeometry.WIDE);
    }

    private static RigBaker.Skin alex() {
        return new RigBaker.Skin(ALEX, new byte[] {4}, RigGeometry.SLIM);
    }

    @Test
    void theSameSkinsWriteTheSameBytes(@TempDir Path dir) throws Exception {
        BuiltPack one = rigs(steve(), alex()).writePack(dir.resolve("a.zip"), 46, "rigs");
        BuiltPack two = rigs(steve(), alex()).writePack(dir.resolve("b.zip"), 46, "rigs");

        assertEquals(one.sha1(), two.sha1(), "an unchanged server must rebuild to an unchanged hash");
        assertEquals(RigAssets.BUNDLE, one.bundle());
    }

    @Test
    void aNewSkinChangesOnlyTheRigsPack(@TempDir Path dir) throws Exception {
        BuiltPack before = rigs(steve()).writePack(dir.resolve("a.zip"), 46, "rigs");
        BuiltPack after = rigs(steve(), alex()).writePack(dir.resolve("b.zip"), 46, "rigs");

        assertNotEquals(before.sha1(), after.sha1());
    }

    @Test
    void itIsAWholePack(@TempDir Path dir) throws Exception {
        RigAssets rigs = rigs(steve(), alex());
        BuiltPack pack = rigs.writePack(dir.resolve("rigs.zip"), 46, "RP Engine - emote rigs");

        try (ZipFile zip = new ZipFile(pack.file().toFile())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            for (String path : rigs.files().keySet()) {
                assertNotNull(zip.getEntry(path), path);
            }
            assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("assets/rpengine/items/rig__")),
                    "the shared bone definitions ride in the rigs pack");
        }
    }

    @Test
    void nothingBakedIsNoPack(@TempDir Path dir) throws Exception {
        assertNull(new RigAssets(new RigBaker.Baked()).writePack(dir.resolve("none.zip"), 46, "rigs"));
    }
}
