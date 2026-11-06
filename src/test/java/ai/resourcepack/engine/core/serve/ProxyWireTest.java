package ai.resourcepack.engine.core.serve;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Holds {@link ProxyBridge}'s messages to the recorded ones in
 * {@code proxy-wire/messages.txt}. RPE Proxy keeps its own code for the same
 * format and its test reads the same file, so the two agree byte for byte as
 * long as both pass: what this side writes (a stack, a chunk) must be those
 * bytes, and what the proxy writes (a need, a status) must read back here.
 */
class ProxyWireTest {

    private static final UUID ONE = new UUID(1, 2);
    private static final UUID TWO = new UUID(3, 4);
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    private static final String C = "c".repeat(40);

    private static Map<String, byte[]> recorded() throws IOException {
        Map<String, byte[]> messages = new LinkedHashMap<>();
        try (InputStream in = ProxyWireTest.class.getResourceAsStream("/proxy-wire/messages.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ", 2);
                messages.put(parts[0], HexFormat.of().parseHex(parts[1]));
            }
        }
        return messages;
    }

    @Test
    void writesTheRecordedBytes() throws IOException {
        Map<String, byte[]> recorded = recorded();
        assertEquals(ProxyBridge.VERSION, recorded.get("stack")[0], "the file records this version");
        assertArrayEquals(recorded.get("stack"), ProxyBridge.stackMessage("Please accept the server's pack", true, List.of(
                new ProxyBridge.Entry(ONE, A, B, "https://example.com/a.zip", 1234),
                new ProxyBridge.Entry(TWO, C, C, null, 5678))));
        assertArrayEquals(recorded.get("chunk"), ProxyBridge.chunkMessage(C, 1, 3, new byte[] {1, 2, 3}));
    }

    @Test
    void readsTheRecordedBytes() throws IOException {
        Map<String, byte[]> recorded = recorded();
        assertEquals(ProxyBridge.NEED, recorded.get("need")[1]);
        assertEquals(C, ProxyBridge.readNeed(recorded.get("need")));
        assertEquals(ProxyBridge.STATUS, recorded.get("status")[1]);
        assertEquals(new ProxyBridge.Status(ONE, "SUCCESSFULLY_LOADED"), ProxyBridge.readStatus(recorded.get("status")));
    }
}
