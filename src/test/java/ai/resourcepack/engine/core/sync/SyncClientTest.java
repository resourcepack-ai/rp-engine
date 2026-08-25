package ai.resourcepack.engine.core.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frame parsing, which is the whole of what can be tested without a socket and
 * also the whole of what tends to be wrong.
 */
class SyncClientTest {

    private final List<String> applied = new ArrayList<>();
    private final List<String> given = new ArrayList<>();

    private SyncClient client() {
        return new SyncClient("wss://example.invalid/connect", Logger.getLogger("test"),
                (code, payload) -> applied.add(code + "|" + payload),
                (code, command) -> given.add(code + "|" + command));
    }

    @Test
    void readsAnApply() {
        client().handle("APPLY 48213097 https://example.com/pack.zip");

        assertEquals(List.of("48213097|https://example.com/pack.zip"), applied);
    }

    @Test
    void aUrlPayloadKeepsItsSpaces() {
        // The protocol splits on the first two spaces ONLY. Studio sends two
        // space-joined urls when a pack has animated models, and splitting on
        // every space truncates the first one — which fails as a push that
        // appears to succeed.
        client().handle("APPLY 48213097 https://example.com/pack.zip https://example.com/rigs.json");

        assertEquals(List.of("48213097|https://example.com/pack.zip https://example.com/rigs.json"), applied);
    }

    @Test
    void aGiveCommandKeepsItsSpaces() {
        client().handle("GIVE 48213097 /give @p paper[minecraft:custom_model_data={strings:[\"foo\"]}]");

        assertEquals(List.of("48213097|/give @p paper[minecraft:custom_model_data={strings:[\"foo\"]}]"), given);
    }

    @Test
    void aUuidRefWorksTheSameAsACode() {
        // The two shapes do not collide, which is why the protocol carries both
        // without a second set of message types.
        client().handle("APPLY 069a79f4a3df4229adc07f26f6c2ec3a https://example.com/pack.zip");

        assertTrue(applied.get(0).startsWith("069a79f4"));
    }

    @Test
    void anythingElseIsIgnoredRatherThanLogged() {
        SyncClient client = client();

        // An unknown frame is how a protocol grows. Complaining about one would
        // mean this plugin has to be updated before the far end can be.
        client.handle("SOMETHING_NEW 48213097 payload");
        client.handle("APPLIED 48213097");
        client.handle("");
        client.handle(null);
        client.handle("APPLY");
        client.handle("APPLY 48213097");

        assertTrue(applied.isEmpty());
        assertTrue(given.isEmpty());
    }

    @Test
    void aFrameTypeIsMatchedRegardlessOfCase() {
        client().handle("apply 48213097 https://example.com/pack.zip");

        assertEquals(1, applied.size());
    }

    @Test
    void nothingIsClaimedUntilSomethingClaimsIt() {
        SyncClient client = client();

        assertEquals(null, client.claimant("48213097"));
        // The far end is unreachable, so linking fails rather than pretending.
        assertTrue(!client.link("48213097", "Notch"));
        assertEquals(null, client.claimant("48213097"));
    }
}
