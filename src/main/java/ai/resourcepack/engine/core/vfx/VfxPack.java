package ai.resourcepack.engine.core.vfx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a pack carries for its VFX: every sprite they draw, painted once per
 * palette, as item textures, block models and 1.21.4 item model definitions
 * that pick a level by custom_model_data float 0. A port of Studio's
 * {@code lib/vfx/pack.ts}, so a content folder's effect and a pushed one name
 * their sprites the same way ({@code rpai_vfx:glow_sun}) and two packs that
 * both draw a sun glow agree on it.
 *
 * <p>The recipes are not in the zip: a resource pack has no way to say "play
 * this", so the timeline stays on the server that plays it.
 */
public final class VfxPack {

    /** The namespace every VFX file lives under. */
    public static final String NAMESPACE = "rpai_vfx";

    private VfxPack() {
    }

    /** The item model a sprite is shown through: {@code rpai_vfx:glow_sun}. */
    static String itemModel(String sprite, String paletteId) {
        return NAMESPACE + ":" + VfxPainter.spriteId(sprite, paletteId);
    }

    /** Every file the pack needs for these effects, deduplicated across them, by zip path. */
    public static Map<String, byte[]> files(Collection<VfxRecipe> recipes) {
        VfxData data = VfxData.get();
        Map<String, Object[]> wanted = new TreeMap<>();
        for (VfxRecipe recipe : recipes) {
            for (String[] used : recipe.spritesUsed()) {
                String pid = used[1] != null ? VfxPainter.paletteId(used[1], recipe.palettes) : null;
                String id = VfxPainter.spriteId(used[0], pid);
                wanted.putIfAbsent(id, new Object[] {used[0], used[1], recipe});
            }
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, Object[]> e : wanted.entrySet()) {
            String id = e.getKey();
            String sprite = (String) e.getValue()[0];
            String palette = (String) e.getValue()[1];
            VfxRecipe recipe = (VfxRecipe) e.getValue()[2];
            VfxData.Sprite info = data.spriteOf(sprite);
            double[][] stops = VfxPainter.paletteStops(palette, recipe.palettes);
            for (int k = 0; k < info.levels(); k++) {
                String tex = id + "_" + k;
                files.put("assets/" + NAMESPACE + "/textures/item/" + tex + ".png",
                        png(info.w(), info.h() * info.frames(), VfxPainter.paint(sprite, stops, k)));
                if (info.frames() > 1) {
                    files.put("assets/" + NAMESPACE + "/textures/item/" + tex + ".png.mcmeta",
                            "{\"animation\":{\"frametime\":2,\"interpolate\":false}}".getBytes(StandardCharsets.UTF_8));
                }
                JsonObject model = new JsonObject();
                JsonObject textures = new JsonObject();
                textures.addProperty("t", NAMESPACE + ":item/" + tex);
                textures.addProperty("particle", NAMESPACE + ":item/" + tex);
                model.add("textures", textures);
                JsonArray elements = new JsonArray();
                for (JsonElement el : data.model(info.model())) {
                    JsonObject copy = el.getAsJsonObject().deepCopy();
                    if (!info.shade()) {
                        copy.addProperty("shade", false);
                    }
                    elements.add(copy);
                }
                model.add("elements", elements);
                files.put("assets/" + NAMESPACE + "/models/item/" + tex + ".json", model.toString().getBytes(StandardCharsets.UTF_8));
            }
            JsonObject definition = new JsonObject();
            if (info.levels() == 1) {
                definition.add("model", levelModel(id, 0));
            } else {
                JsonObject dispatch = new JsonObject();
                dispatch.addProperty("type", "minecraft:range_dispatch");
                dispatch.addProperty("property", "minecraft:custom_model_data");
                dispatch.addProperty("index", 0);
                JsonArray entries = new JsonArray();
                for (int i = 0; i < info.levels() - 1; i++) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("threshold", i + 1);
                    entry.add("model", levelModel(id, i + 1));
                    entries.add(entry);
                }
                dispatch.add("entries", entries);
                dispatch.add("fallback", levelModel(id, 0));
                definition.add("model", dispatch);
            }
            files.put("assets/" + NAMESPACE + "/items/" + id + ".json", definition.toString().getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    private static JsonObject levelModel(String id, int k) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "minecraft:model");
        m.addProperty("model", NAMESPACE + ":item/" + id + "_" + k);
        return m;
    }

    static byte[] png(int width, int height, byte[] rgba) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = ((rgba[i * 4 + 3] & 255) << 24) | ((rgba[i * 4] & 255) << 16)
                    | ((rgba[i * 4 + 1] & 255) << 8) | (rgba[i * 4 + 2] & 255);
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
