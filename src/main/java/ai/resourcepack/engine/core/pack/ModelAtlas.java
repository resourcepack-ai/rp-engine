package ai.resourcepack.engine.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds every texture a model in the bundle draws, and no atlas would stitch,
 * to the blocks atlas.
 *
 * <p>Since 1.19.3 a model's texture is only drawn if an atlas lists it, and
 * the game's own lists are folders: {@code textures/block/} and
 * {@code textures/item/} (1.21.11 moved {@code item/} into an items atlas of
 * its own). A pack of ours keeps its art in {@code textures/item/}, so it
 * never needed this. An imported one keeps it wherever its plugin let it,
 * {@code textures/<set>/} in a marketplace ItemsAdder pack, and every face of
 * every model in it drew as the missing texture. ItemsAdder, Nexo and
 * CraftEngine all write an atlas entry for that; this is ours.
 *
 * <p>It is {@code blocks.json} on every version: it is the only atlas before
 * 1.21.11, and from then item models look a texture up in it first and the
 * items atlas second. An atlas file from a pack adds to the game's rather
 * than replacing it, so one entry per texture is all it takes, and one a
 * pack already shipped is kept and added to.
 */
final class ModelAtlas {

    static final String BLOCKS = "assets/minecraft/atlases/blocks.json";

    private static final Pattern MODEL = Pattern.compile("assets/([^/]+)/models/.+\\.json");

    /** Folders the game's own atlases already stitch, on some version or all. */
    private static final List<String> STITCHED = List.of("block/", "item/");

    private ModelAtlas() {
    }

    /**
     * Writes the entries {@code zip} needs, if any.
     *
     * @return how many textures were added
     */
    static int complete(DeterministicZip zip) {
        Set<String> needed = new LinkedHashSet<>();
        for (String path : new ArrayList<>(zip.paths())) {
            Matcher model = MODEL.matcher(path);
            if (model.matches()) {
                collect(zip, zip.get(path), model.group(1), needed);
            }
        }
        if (needed.isEmpty()) {
            return 0;
        }
        JsonObject atlas = existing(zip.get(BLOCKS));
        JsonArray sources = atlas.getAsJsonArray("sources");
        Set<String> listed = new LinkedHashSet<>();
        for (JsonElement source : sources) {
            if (source.isJsonObject() && source.getAsJsonObject().has("resource")) {
                listed.add(location(source.getAsJsonObject().get("resource").getAsString(), "minecraft"));
            }
        }
        int added = 0;
        for (String texture : needed) {
            if (listed.add(texture)) {
                JsonObject single = new JsonObject();
                single.addProperty("type", "single");
                single.addProperty("resource", texture);
                sources.add(single);
                added++;
            }
        }
        if (added > 0) {
            zip.add(BLOCKS, atlas.toString().getBytes(StandardCharsets.UTF_8));
        }
        return added;
    }

    /** The textures one model names that are shipped here and stitched by nothing. */
    private static void collect(DeterministicZip zip, byte[] bytes, String modelNamespace, Set<String> into) {
        JsonObject model;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return;
            }
            model = parsed.getAsJsonObject();
        } catch (RuntimeException notJson) {
            return;
        }
        if (!model.has("textures") || !model.get("textures").isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> slot : model.getAsJsonObject("textures").entrySet()) {
            if (!slot.getValue().isJsonPrimitive()) {
                continue;
            }
            String reference = slot.getValue().getAsString();
            if (reference.isEmpty() || reference.startsWith("#")) {
                continue;
            }
            // A bare path in a model means minecraft:, whichever namespace the
            // model is in; that is the game's rule.
            String texture = location(reference, "minecraft");
            int colon = texture.indexOf(':');
            String path = texture.substring(colon + 1);
            if (STITCHED.stream().anyMatch(path::startsWith)) {
                continue;
            }
            // Only art this pack ships: one it does not is either the game's
            // own, in an atlas already, or missing, and an entry fixes neither.
            if (zip.has("assets/" + texture.substring(0, colon) + "/textures/" + path + ".png")) {
                into.add(texture);
            }
        }
    }

    private static String location(String reference, String defaultNamespace) {
        return reference.indexOf(':') > 0 ? reference : defaultNamespace + ":" + reference;
    }

    /** The atlas a pack already shipped, or an empty one; never one without a sources list. */
    private static JsonObject existing(byte[] bytes) {
        if (bytes != null) {
            try {
                JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("sources")
                        && parsed.getAsJsonObject().get("sources").isJsonArray()) {
                    return parsed.getAsJsonObject();
                }
            } catch (RuntimeException notJson) {
                // Replaced below: a file the game cannot read stitches nothing anyway.
            }
        }
        JsonObject atlas = new JsonObject();
        atlas.add("sources", new JsonArray());
        return atlas;
    }
}
