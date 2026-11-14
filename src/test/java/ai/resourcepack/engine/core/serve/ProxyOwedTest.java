package ai.resourcepack.engine.core.serve;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A pack is sent through a connection once per time it was offered there, and never unasked. */
class ProxyOwedTest {

    private final UUID player = UUID.randomUUID();

    @Test
    void aPackNobodyWasOfferedIsRefused() {
        ProxyBridge.Owed owed = new ProxyBridge.Owed();
        assertFalse(owed.claim(player, "a"));
        owed.offered(player, List.of("a"));
        assertFalse(owed.claim(player, "b"));
        assertFalse(owed.claim(UUID.randomUUID(), "a"));
    }

    @Test
    void eachOfferedPackIsSentOnce() {
        ProxyBridge.Owed owed = new ProxyBridge.Owed();
        owed.offered(player, List.of("a", "b"));
        assertTrue(owed.claim(player, "a"));
        assertFalse(owed.claim(player, "a"));
        assertTrue(owed.claim(player, "b"));
    }

    @Test
    void aNewStackOffersItsPacksAgain() {
        ProxyBridge.Owed owed = new ProxyBridge.Owed();
        owed.offered(player, List.of("a"));
        assertTrue(owed.claim(player, "a"));
        owed.offered(player, List.of("a"));
        assertTrue(owed.claim(player, "a"));
        assertFalse(owed.claim(player, "a"));
    }
}
