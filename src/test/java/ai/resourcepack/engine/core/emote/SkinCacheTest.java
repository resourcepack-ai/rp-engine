package ai.resourcepack.engine.core.emote;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a skin is fetched from, and how much of it is read. */
class SkinCacheTest {

    @Test
    void onlyMojangsTextureHostIsFetched() {
        // Mojang writes these as plain http, which is why both are allowed.
        assertTrue(SkinCache.isTextureUrl("http://textures.minecraft.net/texture/abc123"));
        assertTrue(SkinCache.isTextureUrl("https://textures.minecraft.net/texture/abc123"));
        assertFalse(SkinCache.isTextureUrl("http://169.254.169.254/latest/meta-data"));
        assertFalse(SkinCache.isTextureUrl("http://textures.minecraft.net.example.com/x"));
        assertFalse(SkinCache.isTextureUrl("file:///etc/passwd"));
        assertFalse(SkinCache.isTextureUrl("ftp://textures.minecraft.net/x"));
        assertFalse(SkinCache.isTextureUrl("not a url"));
    }

    @Test
    void aSheetPastTheLimitIsRefusedWithoutReadingItAll() throws IOException {
        assertEquals(500, SkinCache.readSheet(new ByteArrayInputStream(new byte[500])).length);
        assertEquals(SkinCache.MAX_SHEET_BYTES,
                SkinCache.readSheet(new ByteArrayInputStream(new byte[SkinCache.MAX_SHEET_BYTES])).length);
        assertNull(SkinCache.readSheet(new ByteArrayInputStream(new byte[SkinCache.MAX_SHEET_BYTES + 1])));
    }

    /**
     * Only recent players are baked, newest first: every skin on disk used to
     * be, and the public test server's rigs pack had grown to 308 rigs.
     */
    @Test
    void onlyRecentPlayersAreBakedNewestFirst(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws IOException {
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> dir.toFile();
                    case "getLogger" -> java.util.logging.Logger.getLogger("SkinCacheTest");
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        java.nio.file.Path skins = java.nio.file.Files.createDirectories(dir.resolve(SkinCache.FOLDER));
        long now = 1_000L * 86_400_000L;
        String[] keys = new String[4];
        long[] ages = {1, 5, 20, 40};
        for (int i = 0; i < keys.length; i++) {
            keys[i] = String.valueOf((char) ('a' + i)).repeat(32);
            java.nio.file.Files.write(skins.resolve(keys[i] + ".png"), new byte[]{1});
            java.nio.file.Path meta = java.nio.file.Files.writeString(skins.resolve(keys[i] + ".txt"), "wide");
            java.nio.file.Files.setLastModifiedTime(meta,
                    java.nio.file.attribute.FileTime.fromMillis(now - ages[i] * 86_400_000L));
        }
        SkinCache cache = new SkinCache(plugin);
        java.util.function.Function<java.util.List<RigBaker.Skin>, java.util.List<String>> named = list ->
                list.stream().map(skin -> skin.key).filter(key -> !key.equals(RigBaker.DEFAULT_KEY)).toList();
        assertEquals(java.util.List.of(keys[0], keys[1], keys[2]), named.apply(cache.snapshot(30, 0, now)));
        assertEquals(java.util.List.of(keys[0], keys[1]), named.apply(cache.snapshot(30, 2, now)));
        assertEquals(4, named.apply(cache.snapshot(0, 0, now)).size());
    }
}
