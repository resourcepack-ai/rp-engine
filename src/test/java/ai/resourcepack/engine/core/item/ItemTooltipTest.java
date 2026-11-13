package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code tooltip:}, an item wearing a designed tooltip from a Studio pack. */
class ItemTooltipTest {

    private static final ContentId LEGENDARY = ContentId.parse("studio:legendary").orElseThrow();
    private static final List<String> HIDDEN = List.of("minecraft:attribute_modifiers", "minecraft:enchantments");

    private static ItemTooltip tooltip(boolean glint, int emptyLines) {
        String[] problem = new String[1];
        ItemTooltip made = ItemTooltip.of(LEGENDARY, "Legendary", "rpai:tooltip/0a1b2c3d/legendary",
                "\uE000\uE003", "#FFFFFF", emptyLines, "rpai:clear", HIDDEN, glint, problem);
        assertNotNull(made, problem[0]);
        return made;
    }

    @TempDir
    Path content;

    @Test
    void anItemNamesATooltipByItsStudioId() throws IOException {
        Path pack = content.resolve("mypack");
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("pack.yml"), "name: mine\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("items/swords.yml"), """
                sword:
                  material: IRON_SWORD
                  name: "&6Sword"
                  tooltip: Legendary-Sword
                axe:
                  material: IRON_AXE
                  tooltip: studio:frost
                own:
                  material: STICK
                  tooltip: mypack:frost
                broken:
                  material: STICK
                  tooltip: { id: frost }
                plain:
                  material: STICK
                """, StandardCharsets.UTF_8);

        var parsed = ItemDefinitions.parse(
                new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED));
        Map<ContentId, ItemInfo> items = parsed.items();
        ItemInfo sword = items.get(ContentId.parse("mypack:sword").orElseThrow());
        assertEquals(ContentId.parse("studio:legendary-sword"), sword.tooltip(), "a bare id is a Studio one");
        assertEquals(Optional.of("&6Sword"), sword.name(), "its own name is kept, for a server that cannot show it");
        assertEquals(ContentId.parse("studio:frost"), items.get(ContentId.parse("mypack:axe").orElseThrow()).tooltip());
        assertEquals(Optional.empty(), items.get(ContentId.parse("mypack:own").orElseThrow()).tooltip());
        assertEquals(Optional.empty(), items.get(ContentId.parse("mypack:broken").orElseThrow()).tooltip());
        assertEquals(Optional.empty(), items.get(ContentId.parse("mypack:plain").orElseThrow()).tooltip());
        assertTrue(parsed.diagnostics().stream().anyMatch(d -> d.message().contains("Tooltips come from Studio")));
        assertTrue(parsed.diagnostics().stream().anyMatch(d -> d.message().contains("like tooltip: legendary")));
        assertEquals(2, parsed.diagnostics().size(), parsed.diagnostics().toString());
    }

    @Test
    void theComponentsAreWhatStudiosGiveCommandWrites() {
        // Studio's give command for the same tooltip, between its brackets:
        // item_name="",lore=[{text:"\ue000\ue003",font:"rpai:tooltip/...",
        // color:"#ffffff",italic:false,shadow_color:0},"",""],
        // tooltip_style="rpai:clear",tooltip_display={hidden_components:[...]}
        assertEquals("minecraft:item_name=\"\","
                        + "minecraft:lore=[{text:\"\\ue000\\ue003\",font:\"rpai:tooltip/0a1b2c3d/legendary\","
                        + "color:\"#ffffff\",italic:false,shadow_color:0},\"\",\"\"],"
                        + "minecraft:tooltip_style=\"rpai:clear\","
                        + "minecraft:tooltip_display={hidden_components:[\"minecraft:attribute_modifiers\","
                        + "\"minecraft:enchantments\"]}",
                tooltip(false, 2).components());
        assertTrue(tooltip(true, 0).components().endsWith(",minecraft:enchantment_glint_override=true"));
        assertTrue(tooltip(true, 0).components().contains("shadow_color:0}],"), "no empty lines asked for, none written");
    }

    @Test
    void theItemsOwnComponentsAreKeptExceptTheOnesItReplaces() {
        String own = "[minecraft:custom_data={PublicBukkitValues:{\"rpengine:id\":\"mypack:sword\"}},"
                + "minecraft:custom_name='{\"text\":\"Sword, \\'the\\' best\"}',"
                + "minecraft:lore=[\"a\",\"b, c\"],"
                + "minecraft:enchantment_glint_override=true,"
                + "minecraft:item_model=\"mypack:sword\","
                + "!minecraft:tooltip_display,"
                + "max_damage=500]";
        String plain = tooltip(false, 0).itemString("minecraft:iron_sword", own);
        assertTrue(plain.startsWith("minecraft:iron_sword[minecraft:custom_data={PublicBukkitValues:"
                + "{\"rpengine:id\":\"mypack:sword\"}},minecraft:enchantment_glint_override=true,"
                + "minecraft:item_model=\"mypack:sword\",max_damage=500,minecraft:item_name=\"\""), plain);
        assertFalse(plain.contains("custom_name"), "an own name would be drawn over the picture");
        assertFalse(plain.contains("\"b, c\""), "the tooltip's lore is the only lore");
        assertFalse(plain.contains("!minecraft:tooltip_display"));
        assertTrue(plain.endsWith("]}]"), plain);

        String glinting = tooltip(true, 0).itemString("minecraft:iron_sword", own);
        assertEquals(1, glinting.split("enchantment_glint_override", -1).length - 1,
                "a component named twice is refused by the parser");
        assertEquals("minecraft:paper[" + tooltip(false, 0).components() + "]",
                tooltip(false, 0).itemString("minecraft:paper", "[]"));
    }

    @Test
    void entriesAreSplitAtTheTopLevelOnly() {
        assertEquals(List.of("a=1", "b={c:[2,3],d:\"x,]y\"}", "e='q,\\'r'"),
                ItemTooltip.topLevelEntries("[a=1, b={c:[2,3],d:\"x,]y\"},e='q,\\'r']"));
        assertEquals(List.of(), ItemTooltip.topLevelEntries("[]"));
        assertEquals("minecraft:lore", ItemTooltip.componentKey("lore=[]"));
        assertEquals("minecraft:lore", ItemTooltip.componentKey("!minecraft:lore"));
        assertEquals("other:thing", ItemTooltip.componentKey("other:thing={}"));
    }

    @Test
    void aStringIsEscapedTheWayStudioWritesIt() {
        assertEquals("\"a\\\"b\\\\c\"", ItemTooltip.snbt("a\"b\\c"));
        assertEquals("\"\\ue000\"", ItemTooltip.snbt("\uE000"));
        assertEquals("\"\\U0001f600\"", ItemTooltip.snbt(new String(Character.toChars(0x1F600))));
    }

    @Test
    void aTooltipWithoutItsPartsIsRefusedAndSaysWhy() {
        String[] problem = new String[1];
        assertNull(ItemTooltip.of(LEGENDARY, "L", "not a font", "\uE000", "#ffffff", 0, "rpai:clear",
                HIDDEN, false, problem));
        assertEquals("its font is not a namespace:path", problem[0]);
        assertNull(ItemTooltip.of(LEGENDARY, "L", "rpai:f", "", "#ffffff", 0, "rpai:clear", HIDDEN, false, problem));
        assertNull(ItemTooltip.of(LEGENDARY, "L", "rpai:f", "\uE000", "white", 0, "rpai:clear", HIDDEN, false, problem));
        assertNull(ItemTooltip.of(LEGENDARY, "L", "rpai:f", "\uE000", "#ffffff", 0, null, HIDDEN, false, problem));

        ItemTooltip kept = ItemTooltip.of(LEGENDARY, " ", "rpai:f", "\uE000", "#FDFEFC", 5000, "rpai:clear",
                List.of("minecraft:enchantments", "Not One", "minecraft:enchantments"), false, problem);
        assertNotNull(kept);
        assertEquals("legendary", kept.name(), "no name falls back to the id");
        assertEquals("#fdfefc", kept.color());
        assertEquals(ItemTooltip.MAX_EMPTY_LINES, kept.emptyLines());
        assertEquals(List.of("minecraft:enchantments"), kept.hidden());
    }
}
