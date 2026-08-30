package ai.resourcepack.engine.core.item;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyItemModelsTest {

    private static String json(String material, boolean isBlock,
                               LegacyItemModels.Override... overrides) {
        return new String(
                LegacyItemModels.json(material, isBlock, Arrays.asList(overrides)),
                StandardCharsets.UTF_8);
    }

    @Test
    void writesThePredicateTheClientMatchesOn() {
        String written = json("PAPER", false, new LegacyItemModels.Override(4, "mypack:item/ruby"));
        assertTrue(written.contains("\"custom_model_data\": 4"), written);
        assertTrue(written.contains("\"model\": \"mypack:item/ruby\""), written);
    }

    @Test
    void reproducesVanillaForAFlatItem() {
        // The half that matters to everybody who is NOT holding a custom item:
        // this file replaces vanilla's, so plain paper renders from here.
        String written = json("PAPER", false, new LegacyItemModels.Override(1, "mypack:item/a"));
        assertTrue(written.contains("\"parent\": \"minecraft:item/generated\""), written);
        assertTrue(written.contains("\"layer0\": \"minecraft:item/paper\""), written);
    }

    @Test
    void holdsAToolAtAnAngleTheWayVanillaDoes() {
        String written = json("IRON_SWORD", false, new LegacyItemModels.Override(1, "mypack:item/a"));
        assertTrue(written.contains("\"parent\": \"minecraft:item/handheld\""), written);
    }

    @Test
    void handheldIsDecidedBySuffixSoANewToolInheritsIt() {
        for (String tool : List.of("NETHERITE_PICKAXE", "GOLDEN_HOE", "STONE_SHOVEL", "DIAMOND_AXE")) {
            assertTrue(json(tool, false, new LegacyItemModels.Override(1, "x:item/a"))
                    .contains("minecraft:item/handheld"), tool);
        }
        // And a stick is not a tool, whatever it looks like.
        assertTrue(json("STICK", false, new LegacyItemModels.Override(1, "x:item/a"))
                .contains("minecraft:item/generated"));
    }

    @Test
    void aBlocksItemFormParentsItsBlockModel() {
        String written = json("NOTE_BLOCK", true, new LegacyItemModels.Override(1, "mypack:item/a"));
        assertTrue(written.contains("\"parent\": \"minecraft:block/note_block\""), written);
        assertFalse(written.contains("layer0"), "a block's item form has no sprite layer");
    }

    @Test
    void ordersOverridesByNumber() {
        // Load-bearing twice over: the client takes the last predicate that
        // matches, so an unsorted list silently resolves to the wrong model —
        // and the zip hash depends on this file being byte-identical between
        // two builds of the same content.
        String written = json("PAPER", false,
                new LegacyItemModels.Override(9, "mypack:item/c"),
                new LegacyItemModels.Override(2, "mypack:item/a"),
                new LegacyItemModels.Override(5, "mypack:item/b"));
        int first = written.indexOf("mypack:item/a");
        int second = written.indexOf("mypack:item/b");
        int third = written.indexOf("mypack:item/c");
        assertTrue(first < second && second < third, written);
    }

    @Test
    void namesTheItemsWhoseVanillaModelCannotBeReproduced() {
        // Not a refusal, but the author has to be told: this file replaces
        // vanilla's, and a bow's own model carries pull-state predicates that
        // nothing here can reconstruct.
        assertTrue(LegacyItemModels.isAwkward("BOW"));
        assertTrue(LegacyItemModels.isAwkward("COMPASS"));
        assertTrue(LegacyItemModels.isAwkward("crossbow"), "the check is case-insensitive");
        assertFalse(LegacyItemModels.isAwkward("PAPER"));
        assertFalse(LegacyItemModels.isAwkward("IRON_SWORD"));
    }

    @Test
    void writesWhereTheClientLooks() {
        assertTrue(LegacyItemModels.path("PAPER").equals("assets/minecraft/models/item/paper.json"));
    }
}
