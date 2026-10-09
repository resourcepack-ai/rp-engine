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
}
