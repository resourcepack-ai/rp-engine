package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalPacksTest {

    @TempDir
    Path dir;

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void aFolderAndAZipOfAFolderAreBothFound() throws IOException {
        Path plugins = dir.resolve("plugins");
        write(plugins.resolve("ModelEngine/resource pack/pack.mcmeta"), "{\"pack\":{\"pack_format\":46}}");
        write(plugins.resolve("ModelEngine/resource pack/assets/modelengine/models/a.json"), "{}");
        Path zip = plugins.resolve("Other/build.zip");
        Files.createDirectories(zip.getParent());
        try (OutputStream raw = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(raw)) {
            out.putNextEntry(new ZipEntry("Pack/assets/other/textures/b.png"));
            out.write(new byte[]{1, 2, 3});
            out.closeEntry();
        }

        ExternalPacks.Resolved resolved = ExternalPacks.resolve(
                List.of("ModelEngine/resource pack", "Other/build.zip", "Nothing/here"),
                plugins, dir.resolve("cache"));

        assertEquals(2, resolved.packs().size(), resolved.toString());
        assertEquals(plugins.resolve("ModelEngine/resource pack").normalize(),
                resolved.packs().get("_merge.resource_pack"));
        assertTrue(Files.isRegularFile(resolved.packs().get("_merge.build").resolve("assets/other/textures/b.png")),
                "a zip of a folder is read from the folder inside it");
        assertEquals(1, resolved.problems().size());
        assertTrue(resolved.problems().get(0).startsWith("Nothing/here"));
    }

    @Test
    void aMergedPackIsBuiltInAndTheContentFolderWinsAClash() throws IOException {
        Path content = dir.resolve("content");
        write(content.resolve("mine/pack.mcmeta"), "{\"pack\":{\"pack_format\":46}}");
        write(content.resolve("mine/assets/minecraft/textures/block/stone.png"), "MINE");
        Path theirs = dir.resolve("theirs");
        write(theirs.resolve("assets/minecraft/textures/block/stone.png"), "THEIRS");
        write(theirs.resolve("assets/theirs/textures/only.png"), "ONLY");

        java.util.Map<String, Path> merged = java.util.Map.of("_merge.theirs", theirs);
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED, merged);
        BuiltPack pack = new PackBuilder().merging(merged).build(content, dir.resolve("out"), loaded).packs().get(0);

        try (ZipFile zip = new ZipFile(pack.file().toFile())) {
            assertEquals("ONLY", new String(zip.getInputStream(zip.getEntry("assets/theirs/textures/only.png"))
                    .readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("MINE", new String(zip.getInputStream(zip.getEntry("assets/minecraft/textures/block/stone.png"))
                    .readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
