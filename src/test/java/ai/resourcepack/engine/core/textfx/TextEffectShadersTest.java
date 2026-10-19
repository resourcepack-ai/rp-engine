package ai.resourcepack.engine.core.textfx;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's text effect shader, held to Studio's.
 *
 * <p>{@code expected.json} is written by Studio's {@code gen-rpe-text-effects}
 * script: for each test pack, the SHA-256 of every shader file Studio's own
 * linker ships for it, per version group. A core shader that differs is not
 * "close enough"; it is a file nobody compiled, and a text shader that does not
 * compile drops the whole pack. So the same bytes is the test. Re-derive the
 * hashes from Studio, never from this.
 */
class TextEffectShadersTest {

    private static final class Expected {
        List<Case> cases;
    }

    private static final class Case {
        String label;
        List<EffectJson> effects;
        Map<String, String> sha256;
    }

    private static final class EffectJson {
        String name;
        int index;
        String glsl;
        int[] pad;
    }

    @Test
    void everyGroupIsStudiosShaderByteForByte() throws IOException {
        Expected expected;
        try (InputStream in = getClass().getResourceAsStream("/text-effects/expected.json")) {
            expected = new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Expected.class);
        }
        TextEffectShaders shaders = TextEffectShaders.get();
        int checked = 0;
        for (Case c : expected.cases) {
            List<TextEffectShaders.Effect> effects = new ArrayList<>();
            for (EffectJson e : c.effects) {
                effects.add(new TextEffectShaders.Effect(e.name, e.index, e.glsl, e.pad));
            }
            for (TextEffectShaders.Group group : shaders.groups()) {
                for (Map.Entry<String, String> file : shaders.files(group, effects).entrySet()) {
                    String key = group.minFormat() + "/" + file.getKey();
                    assertEquals(c.sha256.get(key), sha256(file.getValue()), c.label + ": " + key);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "only " + checked + " files were compared");
    }

    @Test
    void namesAreRenamedOnlyAsWholeWords() {
        List<String> library = TextEffectShaders.get().libraryNames();
        String glsl = "float over2(float x) { return x; }\nvec4 effect(Fx f) { return over(vec4(f.p, 0.0, 1.0), vec4(over2(1.0))); }";
        String renamed = TextEffectGlsl.namespaceEffect(glsl, "rtx3_", library);
        assertTrue(renamed.contains("float rtx3_over2(float x)"), renamed);
        assertTrue(renamed.contains("vec4 rtx3_effect(Fx f)"), renamed);
        // A library call is not the effect's own name, and a member is never a name.
        assertTrue(renamed.contains(" over(vec4(f.p"), renamed);
        String prefixed = TextEffectGlsl.prefixLibrary(renamed, library);
        assertTrue(prefixed.contains("rtx_over(vec4(f.p"), prefixed);
        assertTrue(prefixed.contains("rtx3_over2(1.0)"), prefixed);
        assertTrue(prefixed.contains("rtx_Fx f"), prefixed);
    }

    @Test
    void theRulesRefuseWhatWouldHangOrReachPastTheEffect() {
        TextEffectShaders shaders = TextEffectShaders.get();
        assertTrue(shaders.problems("vec4 effect(Fx f) { return vec4(1.0); }").isEmpty());
        assertFalse(shaders.problems("vec4 effect(Fx f) { while (true) {} return vec4(1.0); }").isEmpty());
        assertFalse(shaders.problems("uniform float x; vec4 effect(Fx f) { return vec4(x); }").isEmpty());
        assertFalse(shaders.problems("vec4 effect(Fx f) { for (int i = 0; i < 1000; i++) {} return vec4(1.0); }").isEmpty());
        assertFalse(shaders.problems("vec4 effect(Fx f) { for (int i = 0; i < n; i++) {} return vec4(1.0); }").isEmpty());
        assertFalse(shaders.problems("vec4 shade(Fx f) { return vec4(1.0); }").isEmpty());
        // In a comment, a forbidden word is only a word.
        assertTrue(shaders.problems("// no uniform here\nvec4 effect(Fx f) { return vec4(1.0); }").isEmpty());
    }

    @Test
    void everyPresetPassesTheRules() {
        TextEffectShaders shaders = TextEffectShaders.get();
        for (TextEffectPresets.Preset preset : TextEffectPresets.get().all()) {
            assertEquals(List.of(), shaders.problems(preset.glsl()), preset.key());
        }
    }

    @Test
    void colorsAreStudios() {
        TextEffectShaders shaders = TextEffectShaders.get();
        assertEquals("#fc04f8", shaders.color(0));
        assertEquals("#fc80f8", shaders.color(31));
        assertEquals(32, shaders.maxEffects());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
