package ai.resourcepack.engine.core.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackSquashTest {

    @Test
    void theOptionsPutPathsAndTopLevelKeysAboveEveryTableAndOverlaysFirst() {
        String user = String.join("\n",
                "# mine",
                "pack_directory = 'somewhere else'",
                "size_increasing_zip_obfuscation = true",
                "",
                "['**/*.ogg']",
                "transcode_ogg = false",
                "");
        String out = PackSquash.options(Path.of("in"), Path.of("out.zip"),
                new TreeSet<>(Set.of("assets", "rpe_shader_v97", "rpai_armor3d_26_1")), user);

        assertFalse(out.contains("somewhere else"), "the owner's pack_directory is replaced by ours");
        assertTrue(out.startsWith("pack_directory = '"), out);
        int obfuscation = out.indexOf("size_increasing_zip_obfuscation = true");
        int firstTable = out.indexOf("\n[");
        assertTrue(obfuscation > 0 && obfuscation < firstTable, "top-level keys stay above every table");
        assertTrue(out.contains("['rpe_shader_v97/**']\nforce_include = true"), out);
        assertTrue(out.contains("['rpai_armor3d_26_1/**']\nforce_include = true"), out);
        assertFalse(out.contains("['assets/**']"), "assets is PackSquash's to optimise");
        assertTrue(out.indexOf("['rpe_shader_v97/**']") < out.indexOf("['**/*.ogg']"), "overlays before the owner's");
    }

    @Test
    void aPathWithAQuoteIsABasicString() {
        assertEquals("'C:\\packs\\a'", PackSquash.literal("C:\\packs\\a"));
        assertEquals("\"C:\\\\it's\"", PackSquash.literal("C:\\it's"));
    }

    @Test
    void skippedLinesAreRead() {
        assertEquals("rpe_shader_v97/assets/minecraft/shaders/core/rendertype_text.fsh",
                PackSquash.skippedPath("* rpe_shader_v97/assets/minecraft/shaders/core/rendertype_text.fsh: Skipped"));
        assertNull(PackSquash.skippedPath("> assets/a/textures/b.png: Optimized"));
    }

    @Test
    void sourceFilesAreNoLossButAnythingElseIs() {
        List<String> lost = PackSquash.lost(List.of(
                "assets/christmas/models/cap.bbmodel",
                "assets/christmas/sounds/train.yml",
                "rpai_armor3d_26_1/assets/christmas/equipment/armor3d/elf_boots.json"));
        assertEquals(List.of("rpai_armor3d_26_1/assets/christmas/equipment/armor3d/elf_boots.json"), lost);
    }

    @Test
    void theDefaultOptionsProtectTheShaderTexturesAndSetNoPaths() {
        assertTrue(PackSquash.DEFAULT_OPTIONS.contains("['**/textures/font/**/*.png']"));
        assertTrue(PackSquash.DEFAULT_OPTIONS.contains("['**/armor3d/**/*.png']"));
        assertFalse(PackSquash.DEFAULT_OPTIONS.contains("\npack_directory"));
        assertFalse(PackSquash.DEFAULT_OPTIONS.contains("['**/*.png']"),
                "a catch-all png table would overlap the protective ones");
    }
}
