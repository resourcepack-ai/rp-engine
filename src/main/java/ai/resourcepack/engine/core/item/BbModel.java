package ai.resourcepack.engine.core.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a Blockbench project file into a Minecraft model.
 *
 * <p>A port of studio's {@code Studio's .bbmodel reader}, and it exists for one reason:
 * a `.bbmodel` is what Blockbench <em>saves</em>, and a Java model is what it
 * has to be told to <em>export</em>. Somebody who forgets that step gets a
 * build error rather than a model, every time, for ever. Reading the save file
 * directly removes the step.
 *
 * <p><strong>Scope, deliberately the same as studio's:</strong> cubic
 * elements, per-face UVs scaled from the project's texture resolution into
 * Minecraft's 0–16 space, and single-axis rotations snapped to the angles
 * Minecraft actually allows. Meshes are dropped. Animations are read by
 * nothing here yet and pass through untouched rather than being discarded, so
 * whoever wires up rigs finds them where Blockbench left them.
 *
 * <p>Textures come out too. Blockbench embeds them as data URIs in the save
 * file, so a `.bbmodel` is a whole model in one file — which is the other half
 * of why importing it is worth doing.
 */
public final class BbModel {

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    /** The only rotations a Minecraft element may have, on one axis at a time. */
    private static final float[] ALLOWED_ANGLES = {-45f, -22.5f, 0f, 22.5f, 45f};

    private BbModel() {
    }

    /** A converted project: the model, and the images it needs beside it. */
    public static final class Converted {

        private final JsonObject model;
        private final Map<String, byte[]> textures;

        Converted(JsonObject model, Map<String, byte[]> textures) {
            this.model = model;
            this.textures = textures;
        }

        /** The model, with its texture slots pointing at {@link #textures()}. */
        public JsonObject model() {
            return model;
        }

        /**
         * The images the project had embedded, keyed by the name they should
         * be written under within the pack's {@code textures/} folder.
         */
        public Map<String, byte[]> textures() {
            return textures;
        }
    }

    /**
     * Converts {@code source}.
     *
     * @param namespace the pack the model is going into, so texture references
     *                  resolve inside it
     * @param name      the model's own name, used to name its textures, so two
     *                  projects that both called a texture "skin" do not
     *                  collide in one pack
     * @return the model, or empty if this is not a Blockbench project with
     *         convertible geometry in it
     */
    public static Optional<Converted> convert(byte[] source, String namespace, String name) {
        if (source == null || namespace == null || name == null) {
            return Optional.empty();
        }
        JsonObject project;
        try {
            JsonElement parsed = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            project = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }

        List<JsonObject> cubes = cubesOf(project);
        if (cubes.isEmpty()) {
            return Optional.empty();
        }

        JsonArray rawTextures = array(project, "textures");
        Map<String, byte[]> images = new LinkedHashMap<>();
        List<String> slotNames = new ArrayList<>();
        for (int i = 0; i < rawTextures.size(); i++) {
            String file = name + (rawTextures.size() == 1 ? "" : "_" + i);
            slotNames.add(file);
            decode(rawTextures.get(i)).ifPresent(png -> images.put(file, png));
        }

        int resolutionWidth = 16;
        int resolutionHeight = 16;
        JsonObject resolution = object(project, "resolution");
        if (resolution != null) {
            resolutionWidth = intOf(resolution, "width", 16);
            resolutionHeight = intOf(resolution, "height", 16);
        }

        JsonArray elements = new JsonArray();
        for (JsonObject cube : cubes) {
            elements.add(element(cube, rawTextures, resolutionWidth, resolutionHeight));
        }

        JsonObject textures = new JsonObject();
        for (int i = 0; i < slotNames.size(); i++) {
            textures.addProperty(String.valueOf(i), namespace + ":item/" + slotNames.get(i));
        }
        if (!slotNames.isEmpty()) {
            // Particles come off the first texture, which is what Blockbench
            // does and what a model with no explicit particle slot needs.
            textures.addProperty("particle", namespace + ":item/" + slotNames.get(0));
        }

        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        // Carried rather than dropped: nothing here reads them, and the rig
        // work will want them exactly where Blockbench left them.
        JsonElement animations = project.get("animations");
        if (animations != null && animations.isJsonArray() && !animations.getAsJsonArray().isEmpty()) {
            model.add("animations", animations);
        }
        return Optional.of(new Converted(model, Map.copyOf(images)));
    }

    /** Whether {@code source} looks like a Blockbench project at all. */
    public static boolean looksLikeProject(byte[] source) {
        return convertible(source);
    }

    private static boolean convertible(byte[] source) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            return parsed.isJsonObject() && !cubesOf(parsed.getAsJsonObject()).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The elements that can become Minecraft cubes.
     *
     * <p>A mesh has no {@code from}/{@code to} and cannot be a block model
     * element at all, so it is dropped here rather than producing something
     * malformed further down.
     */
    private static List<JsonObject> cubesOf(JsonObject project) {
        List<JsonObject> cubes = new ArrayList<>();
        for (JsonElement raw : array(project, "elements")) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject element = raw.getAsJsonObject();
            String type = string(element, "type");
            if (type != null && !type.equals("cube")) {
                continue;
            }
            if (vec3(element, "from") != null && vec3(element, "to") != null) {
                cubes.add(element);
            }
        }
        return cubes;
    }

    private static JsonObject element(JsonObject cube, JsonArray textures, int resWidth, int resHeight) {
        JsonObject out = new JsonObject();
        out.add("from", numbers(vec3(cube, "from")));
        out.add("to", numbers(vec3(cube, "to")));

        String name = string(cube, "name");
        if (name != null) {
            out.addProperty("name", name);
        }

        JsonObject rotation = rotation(cube);
        if (rotation != null) {
            out.add("rotation", rotation);
        }

        JsonObject faces = new JsonObject();
        JsonObject rawFaces = object(cube, "faces");
        if (rawFaces != null) {
            for (String face : FACES) {
                JsonObject raw = object(rawFaces, face);
                if (raw == null || raw.get("texture") == null || raw.get("texture").isJsonNull()) {
                    continue;
                }
                faces.add(face, face(raw, textures, resWidth, resHeight));
            }
        }
        out.add("faces", faces);
        return out;
    }

    private static JsonObject face(JsonObject raw, JsonArray textures, int resWidth, int resHeight) {
        int slot = slotOf(raw.get("texture"), textures);
        int uvWidth = resWidth;
        int uvHeight = resHeight;
        if (slot < textures.size() && textures.get(slot).isJsonObject()) {
            JsonObject texture = textures.get(slot).getAsJsonObject();
            uvWidth = intOf(texture, "uv_width", resWidth);
            uvHeight = intOf(texture, "uv_height", resHeight);
        }

        // Blockbench UVs are in texture-resolution space and Minecraft wants
        // 0-16. Two decimal places, because the difference beyond that is
        // invisible and the digits are not.
        JsonArray uvRaw = array(raw, "uv");
        float[] uv = uvRaw.size() >= 4
                ? new float[]{uvRaw.get(0).getAsFloat(), uvRaw.get(1).getAsFloat(),
                        uvRaw.get(2).getAsFloat(), uvRaw.get(3).getAsFloat()}
                : new float[]{0f, 0f, uvWidth, uvHeight};

        JsonArray scaled = new JsonArray();
        scaled.add(round(uv[0] * 16f / uvWidth));
        scaled.add(round(uv[1] * 16f / uvHeight));
        scaled.add(round(uv[2] * 16f / uvWidth));
        scaled.add(round(uv[3] * 16f / uvHeight));

        JsonObject out = new JsonObject();
        out.add("uv", scaled);
        out.addProperty("texture", "#" + slot);
        int faceRotation = intOf(raw, "rotation", 0);
        if (faceRotation == 90 || faceRotation == 180 || faceRotation == 270) {
            out.addProperty("rotation", faceRotation);
        }
        return out;
    }

    /**
     * Blockbench's rotation, reduced to what Minecraft allows.
     *
     * <p>One axis, at one of five angles. Blockbench lets an author rotate on
     * all three by any amount, so the dominant axis is kept and the angle
     * snapped. That loses information and there is nowhere to put it: the
     * format simply cannot express the rest.
     */
    private static JsonObject rotation(JsonObject cube) {
        float[] origin = vec3(cube, "origin");
        String axis = null;
        float angle = 0f;

        JsonElement raw = cube.get("rotation");
        if (raw != null && raw.isJsonArray() && raw.getAsJsonArray().size() >= 3) {
            String[] axes = {"x", "y", "z"};
            for (int i = 0; i < 3; i++) {
                float candidate = raw.getAsJsonArray().get(i).getAsFloat();
                if (Math.abs(candidate) > Math.abs(angle)) {
                    angle = candidate;
                    axis = axes[i];
                }
            }
        } else if (raw != null && raw.isJsonObject()) {
            JsonObject object = raw.getAsJsonObject();
            String declared = string(object, "axis");
            if (declared != null && (declared.equals("x") || declared.equals("y") || declared.equals("z"))) {
                axis = declared;
                angle = intOf(object, "angle", 0);
                float[] declaredOrigin = vec3(object, "origin");
                if (declaredOrigin != null) {
                    origin = declaredOrigin;
                }
            }
        }

        if (axis == null || angle == 0f) {
            return null;
        }
        float snapped = ALLOWED_ANGLES[0];
        for (float allowed : ALLOWED_ANGLES) {
            if (Math.abs(allowed - angle) < Math.abs(snapped - angle)) {
                snapped = allowed;
            }
        }
        if (snapped == 0f) {
            return null;
        }

        JsonObject out = new JsonObject();
        out.add("origin", numbers(origin == null ? new float[]{8f, 8f, 8f} : origin));
        out.addProperty("axis", axis);
        out.addProperty("angle", snapped);
        return out;
    }

    /** A face's texture reference: a slot index, or a uuid naming one. */
    private static int slotOf(JsonElement reference, JsonArray textures) {
        if (reference.isJsonPrimitive() && reference.getAsJsonPrimitive().isNumber()) {
            int index = reference.getAsInt();
            return index >= 0 && index < textures.size() ? index : 0;
        }
        if (reference.isJsonPrimitive()) {
            String id = reference.getAsString();
            for (int i = 0; i < textures.size(); i++) {
                if (!textures.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject texture = textures.get(i).getAsJsonObject();
                if (id.equals(string(texture, "uuid")) || id.equals(string(texture, "id"))) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** The PNG behind a {@code data:image/png;base64,...} source. */
    private static Optional<byte[]> decode(JsonElement texture) {
        if (!texture.isJsonObject()) {
            return Optional.empty();
        }
        String source = string(texture.getAsJsonObject(), "source");
        if (source == null) {
            return Optional.empty();
        }
        int comma = source.indexOf(',');
        if (!source.startsWith("data:image/") || comma < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Base64.getDecoder().decode(source.substring(comma + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ---- json, gently --------------------------------------------------

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static int intOf(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float[] vec3(JsonObject object, String key) {
        JsonArray value = array(object, key);
        if (value.size() < 3) {
            return null;
        }
        try {
            return new float[]{value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat()};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonArray numbers(float[] values) {
        JsonArray out = new JsonArray();
        for (float value : values) {
            out.add(round(value));
        }
        return out;
    }

    private static float round(float value) {
        return Math.round(value * 100f) / 100f;
    }
}
