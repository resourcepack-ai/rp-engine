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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code armor3d:}, an ordinary item wearing a piece of a 3D armour set. */
class ItemArmor3dTest {

    @TempDir
    Path content;

    @Test
    void anItemNamesItsSetAndNeedsASlotToSayWhichPiece() throws IOException {
        Path pack = content.resolve("mypack");
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("pack.yml"), "name: mine\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("items/armour.yml"), """
                helmet:
                  material: NETHERITE_HELMET
                  armor: head
                  armor3d: void
                  attributes:
                    - armor: 5
                loose:
                  material: PAPER
                  armor3d: void
                plain:
                  material: STICK
                """, StandardCharsets.UTF_8);

        var parsed = ItemDefinitions.parse(
                new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED));
        Map<ContentId, ItemInfo> items = parsed.items();
        ItemInfo helmet = items.get(ContentId.parse("mypack:helmet").orElseThrow());
        assertEquals(Optional.of("void"), helmet.armor3d());
        assertEquals(1, helmet.stats().modifiers().size(), "it keeps its own attributes");
        assertEquals(Optional.empty(), items.get(ContentId.parse("mypack:loose").orElseThrow()).armor3d());
        assertTrue(parsed.diagnostics().stream().anyMatch(d -> d.message().contains("needs armor:")));
        assertEquals(Optional.empty(), items.get(ContentId.parse("mypack:plain").orElseThrow()).armor3d());
    }
}
