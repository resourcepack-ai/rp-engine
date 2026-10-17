package ai.resourcepack.engine.core.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderClashesTest {

    @TempDir
    Path root;

    @Test
    void namesAShaderInTheBaseOrAnOverlay() {
        assertEquals("rendertype_text", ShaderClashes.shaderName("assets/minecraft/shaders/core/rendertype_text.vsh"));
        assertEquals("rendertype_text", ShaderClashes.shaderName("rpai_head_v69/assets/minecraft/shaders/core/rendertype_text.fsh"));
        assertEquals("gui", ShaderClashes.shaderName("rpai_dialog_v63/assets/minecraft/shaders/core/gui.json"));
        assertNull(ShaderClashes.shaderName("assets/minecraft/shaders/include/fog.glsl"));
        assertNull(ShaderClashes.shaderName("assets/mypack/shaders/core/rendertype_text.vsh"));
    }

    @Test
    void readsTwentySixsTextAsTheSameShader() {
        assertEquals("rendertype_text", ShaderClashes.shaderName("assets/minecraft/shaders/core/text.vsh"));
    }

    @Test
    void findsLooseFilesAndZips() throws IOException {
        Path plugins = root.resolve("plugins");
        Path build = plugins.resolve("BetterHud/build/assets/minecraft/shaders/core");
        Files.createDirectories(build);
        Files.writeString(build.resolve("rendertype_text.vsh"), "void main() {}");
        zip(plugins.resolve("Other/pack/pack.zip"),
                "assets/minecraft/shaders/core/position_tex_color.fsh",
                "assets/other/textures/item/ruby.png");

        ShaderClashes.Found hud = ShaderClashes.scan(plugins.resolve("BetterHud"), plugins);
        assertEquals(Set.of("rendertype_text"), hud.shaders());
        assertEquals("BetterHud/build", hud.where());

        ShaderClashes.Found other = ShaderClashes.scan(plugins.resolve("Other"), plugins);
        assertEquals(Set.of("position_tex_color"), other.shaders());
        assertEquals("Other/pack/pack.zip", other.where());
    }

    @Test
    void aFolderWithNoPackFindsNothing() throws IOException {
        Path plugins = root.resolve("plugins");
        Files.createDirectories(plugins.resolve("Plain"));
        Files.writeString(plugins.resolve("Plain/config.yml"), "a: 1");
        ShaderClashes.Found found = ShaderClashes.scan(plugins.resolve("Plain"), plugins);
        assertTrue(found.shaders().isEmpty());
        assertNull(found.where());
        assertTrue(ShaderClashes.scan(plugins.resolve("Missing"), plugins).shaders().isEmpty());
    }

    @Test
    void aBrokenZipIsNotAnError() throws IOException {
        Path bad = root.resolve("bad.zip");
        Files.writeString(bad, "not a zip");
        assertTrue(ShaderClashes.inZip(bad).isEmpty());
    }

    @Test
    void reportsOnlyWhatBothSidesReplace() {
        List<ShaderClashes.Clash> clashes = ShaderClashes.find(
                Map.of("Studio's pack", Set.of("rendertype_text", "gui"),
                        "the bundle default", Set.of("rendertype_text")),
                Map.of("BetterHud", Set.of("rendertype_text"),
                        "3DEngine", Set.of("item", "entity")));
        assertEquals(1, clashes.size());
        ShaderClashes.Clash clash = clashes.get(0);
        assertEquals("rendertype_text", clash.shader());
        assertEquals(List.of("Studio's pack", "the bundle default"), clash.ours());
        assertEquals(List.of("BetterHud"), clash.theirs());
        assertTrue(clash.describe().contains("BetterHud"));
    }

    @Test
    void nothingOnOneSideIsNoClash() {
        assertTrue(ShaderClashes.find(Map.of(), Map.of("BetterHud", Set.of("rendertype_text"))).isEmpty());
        assertTrue(ShaderClashes.find(Map.of("ours", Set.of("rendertype_text")), Map.of()).isEmpty());
    }

    private static void zip(Path file, String... entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("x".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
