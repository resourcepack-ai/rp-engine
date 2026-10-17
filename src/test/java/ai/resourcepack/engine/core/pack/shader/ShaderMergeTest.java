package ai.resourcepack.engine.core.pack.shader;

import ai.resourcepack.engine.core.pack.PackFormats;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge, against the real thing on both sides: the text shaders Studio
 * builds (written into resources by Studio, and regenerated whenever it changes)
 * and the packs BetterHud 2.0.0 and 2.2.0 generate, captured from the plugins
 * running on a server. BetterHud is MIT licensed; see LICENSE-BetterHud beside
 * them. 2.0.0 is the version whose template a user reported the clash with.
 *
 * <p>Every merged file is also written to {@code build/shader-merge/}, with a
 * manifest, so it can be compiled and linked on a real graphics driver against
 * the game's own include files. A merge that reads right and does not compile
 * is the failure that matters, and only a driver can say.
 */
class ShaderMergeTest {

    private static final List<String> OURS = List.of("objects", "world", "heads");
    private static final List<String> THEIRS = List.of("betterhud-2.0.0", "betterhud-2.2.0");

    private static Path resources() throws URISyntaxException {
        return Path.of(ShaderMergeTest.class.getResource("/shader-merge").toURI());
    }

    private static PackSource pack(String side, String name) throws URISyntaxException {
        return PackSource.read(name, resources().resolve(side).resolve(name)).orElseThrow();
    }

    @Test
    void mergesEveryStudioPackIntoEveryBetterHud() throws Exception {
        JsonArray manifest = new JsonArray();
        // The same, as tab-separated lines, for the driver check to read with
        // no JSON library: case, merged dir, ours, theirs, shader name, formats.
        StringBuilder tsv = new StringBuilder();
        Path out = Path.of("build", "shader-merge");
        for (String ours : OURS) {
            for (String theirs : THEIRS) {
                MergedShaders.Plan plan = MergedShaders.plan(List.of(pack("ours", ours)), pack("theirs", theirs));
                assertFalse(plan.isEmpty(), ours + " into " + theirs + " merged nothing: " + plan.refused());
                if (theirs.equals("betterhud-2.2.0")) {
                    // The current BetterHud compiles everywhere, so everything merges.
                    assertTrue(plan.refused().isEmpty(), ours + " into " + theirs + ": " + plan.refused());
                }
                for (MergedShaders.Outcome o : plan.outcomes()) {
                    System.out.println(ours + " + " + theirs + " [" + o.minFormat() + "-" + o.maxFormat() + "] "
                            + o.versions() + ": " + (o.merged() ? "merged " + o.files().keySet() : "REFUSED " + o.refused()));
                }
                for (MergedShaders.Outcome o : plan.merged()) {
                    checkMerged(o, ours, theirs);
                    Path dir = out.resolve(ours + "+" + theirs).resolve("v" + o.minFormat());
                    for (Map.Entry<String, String> f : o.files().entrySet()) {
                        Path file = dir.resolve(f.getKey());
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, f.getValue(), StandardCharsets.UTF_8);
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("case", ours + "+" + theirs);
                    entry.addProperty("dir", dir.toAbsolutePath().toString());
                    entry.addProperty("theirs", resources().resolve("theirs").resolve(theirs).toString());
                    JsonArray formats = new JsonArray();
                    for (PackFormats.Range r : PackFormats.ranges()) {
                        if (r.format() >= o.minFormat() && r.format() <= o.maxFormat()) {
                            formats.add(r.format());
                        }
                    }
                    entry.add("formats", formats);
                    entry.addProperty("name", ShaderMerge.textShaderName(o.minFormat()));
                    manifest.add(entry);
                    tsv.append(ours).append('+').append(theirs).append('\t')
                            .append(dir.toAbsolutePath()).append('\t')
                            .append(resources().resolve("ours").resolve(ours)).append('\t')
                            .append(resources().resolve("theirs").resolve(theirs)).append('\t')
                            .append(ShaderMerge.textShaderName(o.minFormat())).append('\t')
                            .append(String.join(",", formats.asList().stream().map(f -> f.getAsString()).toList()))
                            .append('\n');
                }
            }
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
        Files.writeString(out.resolve("manifest.tsv"), tsv.toString());
    }

    /** What every merged file must be, read off its text. */
    private static void checkMerged(MergedShaders.Outcome o, String ours, String theirs) {
        String name = ShaderMerge.textShaderName(o.minFormat());
        String vsh = o.files().get(ShaderMerge.corePath(name, "vsh"));
        String fsh = o.files().get(ShaderMerge.corePath(name, "fsh"));
        String where = ours + " + " + theirs + " v" + o.minFormat();
        assertTrue(vsh != null && fsh != null, where + ": both stages are written");
        // BetterHud's own vertex code survives, and is the base.
        assertTrue(vsh.contains("HEIGHT_BIT"), where + ": BetterHud's vertex code is kept");
        assertEquals(1, GlslScan.of(vsh).mainCount(), where + ": one vertex main");
        assertEquals(1, GlslScan.of(fsh).mainCount(), where + ": one fragment main");
        assertTrue(vsh.contains(ShaderMerge.MERGED), where + ": the vertex file says it is merged");
        if (!ours.equals("heads")) {
            assertTrue(vsh.contains("rawColor = Color;"), where + ": Studio's prologue is in");
            assertTrue(vsh.contains("void rpe_vertex_main()") && vsh.contains("rpe_vertex_main();"),
                    where + ": BetterHud's main is renamed and called");
            assertTrue(fsh.contains("void rpe_vanilla_main()") && fsh.contains("rpe_vanilla_main();"),
                    where + ": BetterHud's fragment main is renamed and called");
            assertTrue(fsh.contains("const float SIG_R"), where + ": Studio's scene is in");
            assertTrue(ShaderMerge.unlinked(vsh, fsh).isEmpty(), where + ": every varying is written");
        } else {
            assertTrue(vsh.contains("void rpe_head_vertex_main()"), where + ": the head branch wraps BetterHud's main");
            assertFalse(fsh.contains(ShaderMerge.MERGED), where + ": the fragment stage is BetterHud's untouched");
        }
        String json = o.files().get(ShaderMerge.corePath(name, "json"));
        if (json != null) {
            JsonObject program = JsonParser.parseString(json).getAsJsonObject();
            List<String> uniforms = program.getAsJsonArray("uniforms").asList().stream()
                    .map(e -> e.getAsJsonObject().get("name").getAsString()).toList();
            assertTrue(uniforms.contains("ScreenSize"), where + ": BetterHud's uniforms are kept");
            assertEquals(uniforms.size(), uniforms.stream().distinct().count(), where + ": no uniform twice");
        }
    }

    @Test
    void theReportedVersionMergesWhereItsOwnShaderCompiles() throws Exception {
        MergedShaders.Plan plan = MergedShaders.plan(List.of(pack("ours", "objects")), pack("theirs", "betterhud-2.0.0"));
        // BetterHud 2.0.0 numbers its overlays from 0 and its template asks for
        // SHADER_VERSION >= 3 to mean 1.21.6, so its 1.21.6 to 1.21.11 shader
        // takes the pre-1.21.6 branch and declares FogColor, which 1.21.6's
        // fog.glsl declares too. It cannot compile on those versions with or
        // without us, and merging would only replace Studio's working shader
        // with a broken one. So those are refused, with the reason.
        for (int format : new int[] {63, 64, 69, 75}) {
            MergedShaders.Outcome o = plan.outcomes().stream()
                    .filter(x -> x.minFormat() <= format && x.maxFormat() >= format).findFirst().orElseThrow();
            assertFalse(o.merged(), format + " should not merge");
            assertTrue(o.refused().contains("cannot compile") && o.refused().contains("FogColor"), o.refused());
        }
        // Everywhere its shader does compile, it merges: 26.1, and everything up to 1.21.5.
        for (int format : new int[] {13, 22, 46, 55, 84}) {
            assertTrue(plan.merged().stream().anyMatch(o -> o.minFormat() <= format && o.maxFormat() >= format),
                    format + ": " + plan.outcomes());
        }
    }

    @Test
    void addsBackAVanillaDeclarationTheirShaderLacks() throws Exception {
        // A 3D object needs IViewRotMat up to 1.20.4; BetterHud 2.0.0's oldest
        // shader does not declare it, so it comes back from vanilla's line.
        MergedShaders.Plan plan = MergedShaders.plan(List.of(pack("ours", "world")), pack("theirs", "betterhud-2.0.0"));
        MergedShaders.Outcome o = plan.merged().stream().filter(x -> x.minFormat() <= 22 && x.maxFormat() >= 22).findFirst().orElseThrow();
        String vsh = o.files().get(ShaderMerge.corePath("rendertype_text", "vsh"));
        assertTrue(vsh.contains("uniform mat3 IViewRotMat;"), vsh);
        String json = o.files().get(ShaderMerge.corePath("rendertype_text", "json"));
        assertTrue(json.contains("\"IViewRotMat\""), "and the program lists the uniform: " + json);
    }

    @Test
    void refusesAShaderBuiltBeforeMarkers() throws Exception {
        PackSource theirs = pack("theirs", "betterhud-2.2.0");
        String old = "#version 150\nin vec4 Color;\nout vec4 rawColor;\nvoid rpe_vertex_main() {}\nvoid main() {}\n";
        PackSource ours = PackSource.of("old", Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":75}}",
                "assets/minecraft/shaders/core/rendertype_text.vsh", old));
        MergedShaders.Plan plan = MergedShaders.plan(List.of(ours), theirs);
        assertTrue(plan.isEmpty());
        assertTrue(plan.refused().get(0).refused().contains("before shaders could be merged"), plan.refused().toString());
    }

    @Test
    void refusesANameDeclaredDifferently() throws Exception {
        String ours = Files.readString(resources().resolve("ours/objects/assets/minecraft/shaders/core/rendertype_text.vsh"));
        String base = "#version 150\nin vec4 Color;\nout float rawColor;\nvoid main() {gl_Position = vec4(0);}\n";
        ShaderMerge.Refused refused = assertThrows(ShaderMerge.Refused.class,
                () -> ShaderMerge.mergeStage(base, ours, ShaderMerge.Stage.VERTEX, GlslScan.Includes.NONE));
        assertTrue(refused.getMessage().contains("rawColor"), refused.getMessage());
    }

    @Test
    void declaresANameTheyOnlyHaveUnderAConditionUnderTheOtherOne() throws Exception {
        // A full-screen object needs Sampler0 in the vertex stage on every
        // variant; BetterHud 2.2.0's 26.2 shader declares it only outside the
        // GUI. Ours goes in under the opposite condition, so each variant has one.
        String ours = Files.readString(resources().resolve("ours/objects/rpe_shader_v88/assets/minecraft/shaders/core/text.vsh"));
        String base = Files.readString(resources().resolve("theirs/betterhud-2.2.0/betterhud_26_2/assets/minecraft/shaders/core/text.vsh"));
        String merged = ShaderMerge.mergeStage(base, ours, ShaderMerge.Stage.VERTEX, GlslScan.Includes.NONE);
        assertTrue(merged.contains("#if !((!defined(IS_GUI) && !defined(IS_SEE_THROUGH)))\nuniform sampler2D Sampler0;\n#endif"), merged);
    }

    @Test
    void invertsAnElseBranchToo() throws Exception {
        String ours = Files.readString(resources().resolve("ours/objects/rpe_shader_v88/assets/minecraft/shaders/core/text.vsh"));
        String base = "#version 330\nin vec4 Color;in vec2 UV0;\n#ifdef A\nout vec4 x;\n#else\nuniform sampler2D Sampler0;\n#endif\n"
                + "out vec4 vertexColor;void main() {gl_Position = vec4(0);}\n";
        String merged = ShaderMerge.mergeStage(base, ours, ShaderMerge.Stage.VERTEX, GlslScan.Includes.NONE);
        assertTrue(merged.contains("#if !((!(defined(A))))\nuniform sampler2D Sampler0;\n#endif"), merged);
    }

    @Test
    void refusesAShaderThatCannotCompileOnItsOwn() throws Exception {
        String ours = Files.readString(resources().resolve("ours/objects/assets/minecraft/shaders/core/rendertype_text.fsh"));
        String base = "#version 150\n#moj_import <fog.glsl>\nuniform vec4 FogColor;\nout vec4 fragColor;\nvoid main() {}\n";
        GlslScan.Includes fog = file -> file.equals("fog.glsl") ? java.util.Set.of("FogColor") : null;
        ShaderMerge.Refused refused = assertThrows(ShaderMerge.Refused.class,
                () -> ShaderMerge.mergeStage(base, ours, ShaderMerge.Stage.FRAGMENT, fog));
        assertTrue(refused.getMessage().contains("cannot compile"), refused.getMessage());
    }

    @Test
    void refusesTwoMains() throws Exception {
        String ours = Files.readString(resources().resolve("ours/objects/assets/minecraft/shaders/core/rendertype_text.fsh"));
        String base = "#version 150\nout vec4 fragColor;\nvoid main() {}\n#ifdef A\nvoid main() {}\n#endif\n";
        assertThrows(ShaderMerge.Refused.class, () -> ShaderMerge.mergeStage(base, ours, ShaderMerge.Stage.FRAGMENT, GlslScan.Includes.NONE));
    }

    @Test
    void neverMergesIntoAMergedFile() throws Exception {
        String ours = Files.readString(resources().resolve("ours/objects/assets/minecraft/shaders/core/rendertype_text.vsh"));
        String base = Files.readString(resources().resolve("theirs/betterhud-2.0.0/betterhud_1_21_6/assets/minecraft/shaders/core/rendertype_text.vsh"));
        String once = ShaderMerge.mergeStage(base, ours, ShaderMerge.Stage.VERTEX, GlslScan.Includes.NONE);
        assertThrows(ShaderMerge.Refused.class, () -> ShaderMerge.mergeStage(once, ours, ShaderMerge.Stage.VERTEX, GlslScan.Includes.NONE));
    }

    @Test
    void theProgramKeepsBothSidesUniforms() throws Exception {
        String theirs = "{\"samplers\":[{\"name\":\"Sampler0\"}],\"uniforms\":[{\"name\":\"ScreenSize\",\"type\":\"float\",\"count\":2,\"values\":[1,1]}]}";
        String ours = "{\"samplers\":[{\"name\":\"Sampler0\"},{\"name\":\"Sampler2\"}],\"uniforms\":[{\"name\":\"GameTime\",\"type\":\"float\",\"count\":1,\"values\":[0]},{\"name\":\"ScreenSize\",\"type\":\"float\",\"count\":2,\"values\":[1,1]}]}";
        JsonObject merged = JsonParser.parseString(ShaderMerge.mergeProgram(theirs, ours)).getAsJsonObject();
        assertEquals(2, merged.getAsJsonArray("samplers").size());
        assertEquals(2, merged.getAsJsonArray("uniforms").size());
    }

    @Test
    void writesAPackOfOverlaysOnly() throws Exception {
        MergedShaders.Plan plan = MergedShaders.plan(List.of(pack("ours", "objects")), pack("theirs", "betterhud-2.0.0"));
        Map<String, byte[]> files = MergedShaders.files(plan, 75, "test");
        assertTrue(files.keySet().stream().allMatch(p -> p.equals("pack.mcmeta") || p.startsWith("rpe_merged_v")), files.keySet().toString());
        JsonObject meta = JsonParser.parseString(new String(files.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(plan.merged().size(), meta.getAsJsonObject("overlays").getAsJsonArray("entries").size());
        // And the client would read each merged file from it on that group's formats.
        PackSource written = PackSource.of("written", decode(files));
        for (MergedShaders.Outcome o : plan.merged()) {
            for (Map.Entry<String, String> f : o.files().entrySet()) {
                assertEquals(f.getValue(), written.resolve(f.getKey(), o.minFormat()).orElse(null), o.minFormat() + " " + f.getKey());
            }
        }
    }

    private static Map<String, String> decode(Map<String, byte[]> files) {
        Map<String, String> out = new java.util.TreeMap<>();
        files.forEach((k, v) -> out.put(k, new String(v, StandardCharsets.UTF_8)));
        return out;
    }
}
