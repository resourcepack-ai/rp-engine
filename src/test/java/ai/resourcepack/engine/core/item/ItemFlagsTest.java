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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code flags:}, vanilla's item flags by Bukkit's names. */
class ItemFlagsTest {

    @TempDir
    Path content;

    @Test
    void flagsAreReadInEitherCaseAndOnlyOnce() throws IOException {
        Path pack = content.resolve("mypack");
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("pack.yml"), "name: mine\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("items/swords.yml"), """
                sword:
                  material: IRON_SWORD
                  flags: [hide_enchants, HIDE_ATTRIBUTES, hide-enchants]
                plain:
                  material: STICK
                """, StandardCharsets.UTF_8);

        var items = ItemDefinitions.parse(
                new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED)).items();
        ItemInfo sword = items.get(ContentId.parse("mypack:sword").orElseThrow());
        assertEquals(List.of("HIDE_ENCHANTS", "HIDE_ATTRIBUTES"), sword.stats().flags());
        assertTrue(items.get(ContentId.parse("mypack:plain").orElseThrow()).stats().isEmpty());
    }
}
