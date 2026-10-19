package ai.resourcepack.engine.core.vfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds the three ports in {@code core/vfx/} to Studio's own answers, which
 * Studio's {@code gen-rpe-vfx} script writes into {@code src/test/resources/vfx/}:
 * the cleaner over untidy recipes, the player over every preset (and one recipe
 * that reaches what the presets do not), and the painter over every sprite at
 * every level in three palettes. A difference here is an effect that looks one
 * way in Studio and another in game, with nothing anywhere to say so.
 */
class VfxPortTest {

    private static JsonObject read(String name) throws IOException {
        try (InputStream in = VfxPortTest.class.getResourceAsStream("/vfx/" + name)) {
            if (in == null) {
                throw new IOException("Missing test resource vfx/" + name + ": run studio's gen-rpe-vfx.mjs");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void cleansAsStudioDoes() throws IOException {
        for (JsonElement c : read("clean.json").getAsJsonArray("cases")) {
            JsonObject one = c.getAsJsonObject();
            JsonObject ours = VfxRecipe.clean(one.get("raw")).toJson();
            assertEquals(one.get("clean"), ours, "cleaning " + one.get("raw"));
        }
    }

    @Test
    void presetsSurviveCleaningUnchanged() {
        for (VfxData.Preset preset : VfxData.get().presets()) {
            VfxRecipe recipe = VfxRecipe.clean(preset.recipe());
            assertEquals(preset.recipe(), recipe.toJson(), preset.key() + " differs at " + diff("", preset.recipe(), recipe.toJson()));
            assertTrue(recipe.problems().isEmpty(), preset.key() + ": " + recipe.problems());
        }
    }

    /** Where two JSON values first differ, for a failure message a person can act on. */
    static String diff(String at, JsonElement a, JsonElement b) {
        if (a == null || b == null) {
            return at + ": " + a + " vs " + b;
        }
        if (a.isJsonObject() && b.isJsonObject()) {
            for (String k : a.getAsJsonObject().keySet()) {
                if (!a.getAsJsonObject().get(k).equals(b.getAsJsonObject().get(k))) {
                    return diff(at + "." + k, a.getAsJsonObject().get(k), b.getAsJsonObject().get(k));
                }
            }
            for (String k : b.getAsJsonObject().keySet()) {
                if (!a.getAsJsonObject().has(k)) {
                    return at + "." + k + ": absent vs " + b.getAsJsonObject().get(k);
                }
            }
        }
        if (a.isJsonArray() && b.isJsonArray() && a.getAsJsonArray().size() == b.getAsJsonArray().size()) {
            for (int i = 0; i < a.getAsJsonArray().size(); i++) {
                if (!a.getAsJsonArray().get(i).equals(b.getAsJsonArray().get(i))) {
                    return diff(at + "[" + i + "]", a.getAsJsonArray().get(i), b.getAsJsonArray().get(i));
                }
            }
        }
        return at + ": " + a + " vs " + b;
    }

    private static double r4(double n) {
        return Math.round(n * 1e4) / 1e4;
    }

    @Test
    void playsAsStudioDoes() throws IOException {
        for (JsonElement el : read("frames.json").getAsJsonArray("effects")) {
            JsonObject effect = el.getAsJsonObject();
            String key = effect.get("key").getAsString();
            VfxRecipe recipe = VfxRecipe.clean(effect.get("recipe"));
            assertEquals(effect.get("recipe"), recipe.toJson(), key + " recipe differs at " + diff("", effect.get("recipe"), recipe.toJson()));
            VfxPlayer player = new VfxPlayer(recipe);
            List<VfxPlayer.Frame> frames = new ArrayList<>();
            while (!player.done()) {
                frames.add(player.step());
            }
            assertEquals(effect.get("ticks").getAsInt(), frames.size(), key + " ticks");
            for (JsonElement fe : effect.getAsJsonArray("frames")) {
                JsonObject expected = fe.getAsJsonObject();
                int t = expected.get("t").getAsInt();
                VfxPlayer.Frame frame = frames.get(t);
                JsonArray want = expected.getAsJsonArray("instances");
                assertEquals(want.size(), frame.instances().size(), key + " tick " + t + " instance count");
                for (int i = 0; i < want.size(); i++) {
                    JsonArray w = want.get(i).getAsJsonArray();
                    VfxPlayer.Instance got = frame.instances().get(i);
                    String where = key + " tick " + t + " " + got.id();
                    assertEquals(w.get(0).getAsString(), got.id(), where + " id");
                    assertEquals(w.get(1).getAsString(), got.sprite(), where + " sprite");
                    assertEquals(w.get(2).isJsonNull() ? null : w.get(2).getAsString(), got.palette(), where + " palette");
                    assertEquals(w.get(3).getAsInt(), got.level(), where + " level");
                    assertEquals(w.get(4).getAsInt() == 1, got.billboard(), where + " billboard");
                    double[] nums = new double[10];
                    System.arraycopy(got.pos(), 0, nums, 0, 3);
                    System.arraycopy(got.rot(), 0, nums, 3, 4);
                    System.arraycopy(got.scale(), 0, nums, 7, 3);
                    for (int k = 0; k < 10; k++) {
                        assertEquals(w.get(5 + k).getAsDouble(), r4(nums[k]) + 0.0, 1e-9, where + " value " + k);
                    }
                }
            }
            JsonArray sounds = effect.getAsJsonArray("sounds");
            List<double[]> ours = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (VfxPlayer.Frame f : frames) {
                for (VfxPlayer.SoundEvent s : f.sounds()) {
                    ours.add(new double[] {f.t(), s.volume(), s.pitch(), s.at()[0], s.at()[1], s.at()[2]});
                    names.add(s.sound());
                }
            }
            assertEquals(sounds.size(), ours.size(), key + " sounds");
            for (int i = 0; i < sounds.size(); i++) {
                JsonArray s = sounds.get(i).getAsJsonArray();
                assertEquals(s.get(1).getAsString(), names.get(i), key + " sound " + i);
                assertEquals(s.get(0).getAsInt(), (int) ours.get(i)[0], key + " sound tick " + i);
                for (int k = 0; k < 5; k++) {
                    assertEquals(s.get(2 + k).getAsDouble(), r4(ours.get(i)[1 + k]) + 0.0, 1e-9, key + " sound " + i + " value " + k);
                }
            }
        }
    }

    @Test
    void paintsAsStudioDoes() throws Exception {
        JsonObject file = read("sprites.json");
        JsonObject custom = file.getAsJsonObject("custom");
        VfxRecipe.Palette mine = new VfxRecipe.Palette();
        mine.deep = custom.get("deep").getAsString();
        mine.mid = custom.get("mid").getAsString();
        mine.bright = custom.get("bright").getAsString();
        String customId = VfxPainter.paletteId("mine", Map.of("mine", mine));
        assertEquals(file.get("customId").getAsString(), customId, "custom palette id");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (JsonElement e : file.getAsJsonArray("painted")) {
            JsonArray one = e.getAsJsonArray();
            String sprite = one.get(0).getAsString();
            String palette = one.get(1).isJsonNull() ? null : one.get(1).getAsString();
            int k = one.get(2).getAsInt();
            double[][] stops = palette == null ? VfxData.get().presetStops("sun")
                    : palette.equals("mine") ? VfxPainter.customStops(mine.deep, mine.mid, mine.bright)
                    : VfxData.get().presetStops(palette);
            String hash = HexFormat.of().formatHex(sha.digest(VfxPainter.paint(sprite, stops, k)));
            if (!hash.equals(one.get(3).getAsString())) {
                wrong.add(sprite + "/" + palette + "/" + k);
            }
            checked++;
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " of " + checked + " painted levels differ from Studio's: " + wrong);
    }
}
