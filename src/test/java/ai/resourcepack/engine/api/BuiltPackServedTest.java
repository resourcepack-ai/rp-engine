package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a pack is served from.
 *
 * <p>This exists because getting it wrong is invisible from the server side:
 * the push succeeds, the plugin logs nothing, and the only symptom is a box in
 * one player's client saying the pack failed to download. RP Engine re-served
 * a pushed Studio pack from its own {@code PackHost}, whose address defaults
 * to {@code http://127.0.0.1:8181} — reachable from a client only when the
 * server happens to be on the same machine.
 */
class BuiltPackServedTest {

    private static final Path FILE = Path.of("output", "studio.zip");
    private static final String SIGNED = "https://blob.example.com/pushes/pack.zip?sig=abc";

    @Test
    void aPackWeBuiltIsServedByUs() {
        BuiltPack ours = BuiltPack.of("default", FILE, "abcdef", 1024, 7);

        // Empty means "ask the host", which is what every content-folder
        // bundle wants: we built it, so we serve it.
        assertEquals("", ours.url());
    }

    @Test
    void aPushedPackKeepsTheAddressItCameFrom() {
        BuiltPack pushed = BuiltPack.served("studio", FILE, "abcdef", 1024, 7, SIGNED);

        assertEquals(SIGNED, pushed.url());
    }

    @Test
    void aPushedPackIsOtherwiseAnOrdinaryPack() {
        BuiltPack pushed = BuiltPack.served("studio", FILE, "abcdef", 1024, 7, SIGNED);
        BuiltPack ours = BuiltPack.of("studio", FILE, "abcdef", 1024, 7);

        // Same bundle, so the same UUID — which is what lets a second push
        // replace the first on a client rather than stacking beside it.
        assertEquals(ours.uuid(), pushed.uuid());
        assertEquals(ours.sha1(), pushed.sha1());
        assertEquals(ours.size(), pushed.size());
    }

    @Test
    void aPushedPackStillKeepsItsFile() {
        BuiltPack pushed = BuiltPack.served("studio", FILE, "abcdef", 1024, 7, SIGNED);

        // The bytes are what the SHA-1 was computed from, and the hash is what
        // lets a client cache the pack instead of fetching it twice.
        assertEquals(FILE, pushed.file());
        assertTrue(!pushed.sha1().isEmpty());
    }

    @Test
    void aNullAddressIsNoAddress() {
        assertEquals("", BuiltPack.served("studio", FILE, "abcdef", 1024, 7, null).url());
    }
}
