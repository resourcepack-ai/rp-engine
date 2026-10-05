package ai.resourcepack.engine.core.dialog;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving items in a dialog: the keys Studio writes, and the inventory's own
 * rule for putting a stack down — the two halves that need no server.
 */
class DialogSlotsTest {

    @Test
    void readsTheKeysStudioWrites() {
        assertEquals(Optional.of(new DialogSlots.Key("inv", 5)), DialogSlots.Key.parse("inv/5"));
        assertEquals(Optional.of(new DialogSlots.Key("ender", 26)), DialogSlots.Key.parse("ender/26"));
        // A bare number is a slot of the inventory.
        assertEquals(Optional.of(new DialogSlots.Key("inv", 12)), DialogSlots.Key.parse("12"));
        assertEquals("inv/5", new DialogSlots.Key("inv", 5).toString());
        assertTrue(DialogSlots.Key.parse("inv/").isEmpty());
        assertTrue(DialogSlots.Key.parse("INV/5").isEmpty());
        assertTrue(DialogSlots.Key.parse("inv/5 ender/3").isEmpty());
        assertTrue(DialogSlots.Key.parse("inv/1000").isEmpty());
        assertTrue(DialogSlots.Key.parse(null).isEmpty());
    }

    @Test
    void knowsTheContainersItFills() {
        for (String name : new String[] {"inv", "ender", "item", "player.vault", "player.bag_2", "api.bank", "api.shop-1"}) {
            assertTrue(DialogSlots.known(name), name);
        }
        for (String name : new String[] {"somebodys", "player.", "api.", "player.Vault", "player.a.b", "api." + "x".repeat(25), "items", null}) {
            assertTrue(!DialogSlots.known(name), String.valueOf(name));
        }
        assertEquals(Optional.of(new DialogSlots.Key("player.vault", 40)), DialogSlots.Key.parse("player.vault/40"));
        assertEquals(Optional.of(new DialogSlots.Key("item", 3)), DialogSlots.Key.parse("item/3"));
    }

    @Test
    void aPluginsContainerNeedsAName() {
        DialogSlots slots = new DialogSlots(null);
        assertTrue(slots.register("bank", viewer -> null));
        assertTrue(!slots.register("Bank", viewer -> null));
        assertTrue(!slots.register("", viewer -> null));
        assertTrue(slots.register("bank", null));
    }

    @Test
    void anEmptySlotTakesTheWholeStack() {
        assertEquals(new DialogSlots.Plan(DialogSlots.Kind.PLACE, 64), DialogSlots.plan(64, 0, false, 64));
    }

    @Test
    void theSameItemTakesWhatFitsAndLeavesTheRest() {
        assertEquals(new DialogSlots.Plan(DialogSlots.Kind.MERGE, 20), DialogSlots.plan(20, 30, true, 64));
        assertEquals(new DialogSlots.Plan(DialogSlots.Kind.MERGE, 4), DialogSlots.plan(20, 60, true, 64));
        // Ender pearls stack to sixteen.
        assertEquals(new DialogSlots.Plan(DialogSlots.Kind.MERGE, 6), DialogSlots.plan(16, 10, true, 16));
    }

    @Test
    void aFullStackOfTheSameItemTakesNothing() {
        assertEquals(DialogSlots.Kind.NONE, DialogSlots.plan(10, 64, true, 64).kind());
        // Swords do not stack: the same sword onto the same sword moves nothing.
        assertEquals(DialogSlots.Kind.NONE, DialogSlots.plan(1, 1, true, 1).kind());
    }

    @Test
    void aDifferentItemSwaps() {
        assertEquals(DialogSlots.Kind.SWAP, DialogSlots.plan(1, 64, false, 64).kind());
        assertEquals(DialogSlots.Kind.SWAP, DialogSlots.plan(64, 1, false, 1).kind());
    }
}
