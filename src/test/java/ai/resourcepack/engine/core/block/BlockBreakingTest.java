package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How long a custom block takes, and what counts as the right tool.
 *
 * <p>The arithmetic rather than the loop: the loop needs a server and a player
 * swinging at something, and is covered by the integration harness. What is
 * worth pinning here is that a pack writing {@code hardness: 1.5} gets
 * something that feels like stone, because that is the only reason anybody
 * writes a number at all.
 */
class BlockBreakingTest {

    private static BlockInfo block(float hardness, String tool) {
        return BlockInfo.of(ContentId.parse("mypack:ore").orElseThrow(),
                BlockInfo.Base.NOTE_BLOCK, "ore", hardness, tool, null, null);
    }

    private static ItemStack held(Material material) {
        return material == null ? null : new ItemStack(material);
    }

    @Test
    void theRightToolIsFasterThanTheWrongOne() {
        BlockInfo ore = block(1.5f, "pickaxe");

        double right = BlockBreaking.secondsToBreak(ore, held(Material.IRON_PICKAXE));
        double wrong = BlockBreaking.secondsToBreak(ore, held(Material.IRON_SHOVEL));

        assertTrue(right < wrong, right + " should be quicker than " + wrong);
    }

    @Test
    void aBetterTierIsFasterAgain() {
        BlockInfo ore = block(3f, "pickaxe");

        assertTrue(BlockBreaking.secondsToBreak(ore, held(Material.DIAMOND_PICKAXE))
                < BlockBreaking.secondsToBreak(ore, held(Material.STONE_PICKAXE)));
    }

    @Test
    void harderTakesLonger() {
        assertTrue(BlockBreaking.secondsToBreak(block(3f, null), held(null))
                > BlockBreaking.secondsToBreak(block(0.5f, null), held(null)));
    }

    @Test
    void zeroHardnessIsInstant() {
        assertEquals(0.05, BlockBreaking.secondsToBreak(block(0f, null), held(null)));
    }

    /** A block that asks for nothing is broken by anything, at tool speed. */
    @Test
    void noToolMeansAnyTool() {
        assertTrue(BlockBreaking.isCorrectTool(block(1f, null), held(Material.AIR)));
        assertTrue(BlockBreaking.isCorrectTool(block(1f, null), held(Material.DIAMOND_HOE)));
    }

    @Test
    void aToolIsMatchedOnItsKindRatherThanItsTier() {
        BlockInfo ore = block(1f, "pickaxe");

        assertTrue(BlockBreaking.isCorrectTool(ore, held(Material.WOODEN_PICKAXE)));
        assertTrue(BlockBreaking.isCorrectTool(ore, held(Material.NETHERITE_PICKAXE)));
        assertFalse(BlockBreaking.isCorrectTool(ore, held(Material.NETHERITE_AXE)));
        assertFalse(BlockBreaking.isCorrectTool(ore, held(null)));
    }

    /**
     * Stone is 1.5 in vanilla and takes about a second and a half with a
     * wooden pickaxe. The numbers here are vanilla's for exactly this reason.
     */
    @Test
    void stoneFeelsLikeStone() {
        double seconds = BlockBreaking.secondsToBreak(block(1.5f, "pickaxe"),
                held(Material.WOODEN_PICKAXE));

        assertTrue(seconds > 0.5 && seconds < 2.0, "stone took " + seconds + "s");
    }
}
