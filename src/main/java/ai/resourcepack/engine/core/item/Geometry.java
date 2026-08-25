package ai.resourcepack.engine.core.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Takes a model file an author exported from Blockbench and makes it fit the
 * pack it is being built into.
 *
 * <p>The awkward part is textures. Blockbench writes them as whatever the
 * author had loaded, typically a bare path like {@code item/sword} or one
 * carrying somebody else's namespace. Neither resolves once the file is inside
 * our pack, and a texture that does not resolve is the purple-and-black square
 * with nothing to trace it to. So a bare path is rewritten into the pack's own
 * namespace, and an explicit one is left exactly as written — an author who
 * typed {@code minecraft:item/stick} meant it.
 *
 * <p>Only textures are touched. Everything else in the file — elements, uvs,
 * display transforms, {@code gui_light}, whatever Blockbench emitted — goes
 * through unread, because a converter that thinks it understands the whole
 * format has to change every time Mojang adds a field.
 */
public final class Geometry {

    /** Written with sorted keys and no spaces so the same input is the same bytes. */
    private static final Gson GSON = new GsonBuilder().create();

    private Geometry() {
    }

    /** What came out, and the texture ids it wants. */
    public static final class Model {

        private final byte[] json;
        private final List<String> textures;

        Model(byte[] json, List<String> textures) {
            this.json = json;
            this.textures = textures;
        }

        /** The rewritten model file. */
        public byte[] json() {
            return json;
        }

        /**
         * Every texture it references, as {@code namespace:path}, sorted.
         *
         * <p>So the builder can say which one is missing rather than leaving
         * somebody to work it out from a black and purple cube.
         */
        public List<String> textures() {
            return textures;
        }
    }

    /**
     * Rewrites {@code source} for {@code namespace}.
     *
     * @return the model, or empty if the file is not a JSON object
     */
    public static Optional<Model> read(byte[] source, String namespace) {
        if (source == null || namespace == null) {
            return Optional.empty();
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }

        List<String> referenced = new ArrayList<>();
        JsonElement textures = root.get("textures");
        if (textures != null && textures.isJsonObject()) {
            JsonObject rewritten = new JsonObject();
            // Sorted, so two builds of the same file are the same bytes.
            Map<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : textures.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                String value = entry.getValue().isJsonPrimitive()
                        ? entry.getValue().getAsString()
                        : "";
                // A leading # is a reference to another slot in the same file,
                // not a texture path. Rewriting one would break the link.
                if (value.isEmpty() || value.startsWith("#")) {
                    rewritten.add(entry.getKey(), entry.getValue());
                    continue;
                }
                String resolved = value.indexOf(':') >= 0 ? value : namespace + ':' + value;
                rewritten.addProperty(entry.getKey(), resolved);
                referenced.add(resolved);
            }
            root.add("textures", rewritten);
        }

        // A model with no parent and no elements renders as nothing. Left
        // alone rather than repaired: guessing a parent for somebody else's
        // file is how a converter starts lying about what it was given.
        return Optional.of(new Model(
                GSON.toJson(root).getBytes(StandardCharsets.UTF_8),
                List.copyOf(referenced.stream().distinct().sorted().toList())));
    }

    /** Where a texture id lands inside a built pack. */
    public static String zipPathOf(String textureId) {
        int colon = textureId.indexOf(':');
        String namespace = textureId.substring(0, colon);
        String path = textureId.substring(colon + 1);
        return "assets/" + namespace + "/textures/" + path + ".png";
    }
}
