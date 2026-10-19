package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code text-effects/} format in FORMAT.md, asserted. */
class TextEffectDefinitionsTest {

    @TempDir
    Path root;

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private TextEffectDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl()).load(root, ContentSource.AUTHORED);
        return TextEffectDefinitions.parse(loaded, name -> {
            try {
                return Files.readString(root.resolve(name));
            } catch (IOException e) {
                return null;
            }
        });
    }

    private static String problems(TextEffectDefinitions.Result result) {
        return result.diagnostics().stream().map(Diagnostic::message).collect(Collectors.joining(" | "));
    }

    @Test
    void aPresetCodeOrAFileEachMakeOne() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/text-effects/effects.yml", String.join("\n",
                "fire:",
                "  preset: blaze",
                "wobble:",
                "  name: Wobbly",
                "  pad: [0, 3, 0, 3]",
                "  glsl: |",
                "    vec4 effect(Fx f) {",
                "        return vec4(vec3(1.0), G(f.p - vec2(0.0, sin(f.t * 4.0))));",
                "    }",
                "shimmer:",
                "  file: mypack/text-effects/shimmer.glsl",
                ""));
        write("mypack/text-effects/shimmer.glsl", "vec4 effect(Fx f) { return vec4(hsv(f.t, 1.0, 1.0), G(f.p)); }\n");
        TextEffectDefinitions.Result result = parse();
        assertTrue(result.diagnostics().isEmpty(), problems(result));
        assertEquals(3, result.effects().size());

        TextEffectDefinitions.Authored fire = result.effects().get(ContentId.parse("mypack:fire").orElseThrow());
        assertEquals("blaze", fire.preset().orElseThrow());
        assertEquals(TextEffectPresets.get().find("blaze").orElseThrow().glsl(), fire.glsl());

        TextEffectDefinitions.Authored wobble = result.effects().get(ContentId.parse("mypack:wobble").orElseThrow());
        assertEquals("Wobbly", wobble.name());
        assertArrayEquals(new int[]{0, 3, 0, 3}, wobble.pad());
        assertFalse(wobble.preset().isPresent());

        assertTrue(result.effects().containsKey(ContentId.parse("mypack:shimmer").orElseThrow()));
    }

    @Test
    void codeThatBreaksTheRulesIsLeftOutAndSaysWhy() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/text-effects/bad.yml", String.join("\n",
                "spin:",
                "  glsl: \"uniform float t; vec4 effect(Fx f) { return vec4(t); }\"",
                "nothing:",
                "  name: Nothing",
                "typo:",
                "  preset: blaaaze",
                ""));
        TextEffectDefinitions.Result result = parse();
        assertTrue(result.effects().isEmpty());
        String said = problems(result);
        assertTrue(said.contains("No uniforms"), said);
        assertTrue(said.contains("needs preset:"), said);
        assertTrue(said.contains("blaaaze"), said);
    }

    @Test
    void aPadOutsideTheLimitIsClampedAndSaid() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/text-effects/pad.yml", "big:\n  preset: blaze\n  pad: [0, 99, -2, 1.5]\n");
        TextEffectDefinitions.Result result = parse();
        int max = TextEffectShaders.get().maxPad();
        assertArrayEquals(new int[]{0, max, 0, 2}, result.effects().get(ContentId.parse("mypack:big").orElseThrow()).pad());
        assertEquals(3, result.diagnostics().size(), problems(result));
    }
}
