package ai.resourcepack.engine.core.storage;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format byte, and the rule that an unreadable container is never an empty
 * one. A real stack needs a server to serialise, so these use empty slots, which
 * is enough to pin everything about the framing.
 */
class ItemBytesTest {

    @Test
    void theFirstByteSaysWhichCodecWroteTheRest() throws IOException {
        byte[] bytes = ItemBytes.write(new ItemStack[4]);

        assertEquals(ItemBytes.BUKKIT_STREAM, bytes[0]);
    }

    @Test
    void emptySlotsComeBackAsEmptySlotsInTheSamePlaces() throws IOException {
        ItemStack[] back = ItemBytes.read(ItemBytes.write(new ItemStack[27]));

        assertEquals(27, back.length, "a slot an item was put in is the slot it comes back in");
        assertArrayEquals(new ItemStack[27], back);
    }

    @Test
    void nothingStoredIsAnEmptyContainer() throws IOException {
        assertEquals(0, ItemBytes.read(null).length);
        assertEquals(0, ItemBytes.read(new byte[0]).length);
    }

    @Test
    void aFormatThisEngineDoesNotKnowIsRefusedRatherThanReadAsEmpty() throws IOException {
        byte[] written = ItemBytes.write(new ItemStack[3]);
        written[0] = 2;

        // Read as empty, this would be saved back empty the moment it closed.
        IOException refused = assertThrows(IOException.class, () -> ItemBytes.read(written));
        assertTrue(refused.getMessage().contains("format 2"), refused.getMessage());
    }

    @Test
    void bytesThatAreNotAStreamAreRefused() {
        assertThrows(IOException.class, () -> ItemBytes.read(new byte[]{ItemBytes.BUKKIT_STREAM, 1, 2, 3}));
    }

    @Test
    void anythingIsFalseForNothing() {
        assertFalse(ItemBytes.anything(null));
        assertFalse(ItemBytes.anything(new ItemStack[9]));
    }
}
