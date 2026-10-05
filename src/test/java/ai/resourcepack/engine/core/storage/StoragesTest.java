package ai.resourcepack.engine.core.storage;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one piece of the container service that is arithmetic: how big to open
 * something whose pack has changed size under it.
 */
class StoragesTest {

    @Test
    void anEmptyContainerOpensAtTheSizeThePackAsks() {
        assertEquals(27, Storages.sizeFor(27, new ItemStack[0]));
        assertEquals(27, Storages.sizeFor(27, null));
        assertEquals(27, Storages.sizeFor(27, new ItemStack[54]), "empty slots past the end are not contents");
    }

    @Test
    void aShrunkContainerOpensBigEnoughForWhatIsInIt() {
        // A cabinet made three rows after it was six must not lose rows four
        // to six of every full one, which opening at 27 and saving would do.
        ItemStack[] stored = new ItemStack[54];
        stored[40] = new ItemStack(Material.STONE);

        assertEquals(45, Storages.sizeFor(27, stored));
    }

    @Test
    void aGrownContainerOpensAtItsNewSize() {
        ItemStack[] stored = new ItemStack[27];
        stored[3] = new ItemStack(Material.STONE);

        assertEquals(54, Storages.sizeFor(54, stored));
    }
}
