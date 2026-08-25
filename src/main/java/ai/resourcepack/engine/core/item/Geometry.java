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

    /**
     * How much room a model actually takes up, in blocks.
     *
     * <p>Read off the geometry rather than asked for, because the person who
     * modelled something already decided how big it is and making them say it
     * again in YAML is how the two end up disagreeing. A hitbox that is
     * smaller than what you can see means most of a statue cannot be punched
     * and the part that can is buried inside it.
     */
    public static final class Bounds {

        private final float width;
        private final float height;

        Bounds(float width, float height) {
            this.width = width;
            this.height = height;
        }

        /** The wider of its two horizontal extents, in blocks. */
        public float width() {
            return width;
        }

        /** Its vertical extent, in blocks. */
        public float height() {
            return height;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    /** What came out, the texture ids it wants, and how big it is. */
    public static final class Model {

        private final byte[] json;
        private final List<String> textures;
        private final Bounds bounds;

        Model(byte[] json, List<String> textures, Bounds bounds) {
            this.json = json;
            this.textures = textures;
            this.bounds = bounds;
        }

        /** How much room it takes up. */
        public Bounds bounds() {
            return bounds;
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
                List.copyOf(referenced.stream().distinct().sorted().toList()),
                boundsOf(root)));
    }

    /**
     * Measures the elements.
     *
     * <p>Minecraft's model space is 16 units to a block and runs from -16 to
     * 32, so a model may legally be three blocks across and three tall. A
     * character model built to the top of that space is two blocks of height
     * above the ground, which is why placed model cannot assume one.
     */
    private static Bounds boundsOf(JsonObject root) {
        JsonElement elements = root.get("elements");
        if (elements == null || !elements.isJsonArray()) {
            return new Bounds(1f, 1f);
        }
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (JsonElement element : elements.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject cube = element.getAsJsonObject();
            for (String key : new String[]{"from", "to"}) {
                JsonElement corner = cube.get(key);
                if (corner == null || !corner.isJsonArray() || corner.getAsJsonArray().size() < 3) {
                    continue;
                }
                for (int axis = 0; axis < 3; axis++) {
                    float value = corner.getAsJsonArray().get(axis).getAsFloat();
                    lo[axis] = Math.min(lo[axis], value);
                    hi[axis] = Math.max(hi[axis], value);
                    any = true;
                }
            }
        }
        if (!any) {
            return new Bounds(1f, 1f);
        }
        // The wider of the two horizontal extents: an Interaction hitbox is a
        // square column, so the narrow axis has to give.
        float width = Math.max(hi[0] - lo[0], hi[2] - lo[2]) / 16f;
        float height = (hi[1] - lo[1]) / 16f;
        return new Bounds(Math.max(0.1f, width), Math.max(0.1f, height));
    }

    /** Where a texture id lands inside a built pack. */
    public static String zipPathOf(String textureId) {
        int colon = textureId.indexOf(':');
        String namespace = textureId.substring(0, colon);
        String path = textureId.substring(colon + 1);
        return "assets/" + namespace + "/textures/" + path + ".png";
    }
}
