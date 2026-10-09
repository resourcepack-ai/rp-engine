package ai.resourcepack.engine.core.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a pushed URL may point at, and how much of it is read. The URLs arrive
 * over the sync socket, so they are held to the web and to a size.
 */
class StudioPushTest {

    @TempDir
    Path dir;

    @Test
    void httpsIsFetched() {
        assertTrue(StudioPush.acceptableUrl("https://packs.example.com/a.zip?sig=1"));
        assertTrue(StudioPush.acceptableUrl("HTTPS://packs.example.com/a.zip"));
    }

    @Test
    void plainHttpOnlyToThisMachine() {
        assertTrue(StudioPush.acceptableUrl("http://localhost:3000/a.zip"));
        assertTrue(StudioPush.acceptableUrl("http://127.0.0.1:8787/a.json"));
        assertFalse(StudioPush.acceptableUrl("http://packs.example.com/a.zip"));
        assertFalse(StudioPush.acceptableUrl("http://192.168.1.10/a.zip"));
    }

    @Test
    void anythingElseIsNot() {
        assertFalse(StudioPush.acceptableUrl("file:///etc/passwd"));
        assertFalse(StudioPush.acceptableUrl("file:/C:/server/ops.json"));
        assertFalse(StudioPush.acceptableUrl("jar:https://example.com/a.jar!/x"));
        assertFalse(StudioPush.acceptableUrl("ftp://example.com/a.zip"));
        assertFalse(StudioPush.acceptableUrl("https:///no-host"));
        assertFalse(StudioPush.acceptableUrl("not a url"));
        assertFalse(StudioPush.acceptableUrl(null));
    }

    @Test
    void aRefusedUrlIsNeitherDownloadedNorRead() {
        assertEquals("bad-payload", StudioPush.fetch("file:///etc/passwd", dir).reason());
        assertTrue(StudioPush.fetchText("file:///etc/passwd").isEmpty());
    }

    @Test
    void aReadStopsAtItsCap() throws IOException {
        byte[] ten = new byte[10];
        assertArrayEquals(ten, StudioPush.readCapped(new ByteArrayInputStream(ten), 10));
        assertThrows(IOException.class, () -> StudioPush.readCapped(new ByteArrayInputStream(new byte[11]), 10));
    }
}
