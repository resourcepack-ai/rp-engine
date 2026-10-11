package ai.resourcepack.engine.core.armor3d;

import ai.resourcepack.engine.api.Diagnostic;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a 3D armour set out of a Blockbench project.
 *
 * <p>The file is the one MythicArmors reads and the one Studio's armour export
 * writes: cubes in groups named after the body's nine bones (see
 * {@link Armor3dBaker#BONES}), in the wearer's frame — feet on y = 0, the
 * front facing +Z, the wearer's right at -X, 16 units to a block, the
 * proportions of Blockbench's player template. Nothing is re-centred, because
 * a worn part is placed relative to the body and a re-centred model would
 * lose where the body was.
 *
 * <p>Both of Blockbench's save shapes are read: 5.0's, where the outliner is a
 * tree of uuids and each group's data is in {@code groups}, and the older one,
 * where the outliner holds the groups themselves. A plugin's own parser may
 * read only one of them; this one should not be the reason a file fails.
 *
 * <p>Two things the block model format cannot say are kept here as drawn,
 * because a display entity can say them: any cube rotation (three angles,
 * any values), and the rotation of a group, which is folded into each of its
 * cubes. Blockbench composes a cube's three angles Z, then Y, then X; the
 * baker composes X, then Y, then Z, which is the convention of the rest of the
 * engine and of a display's matrix. A single-axis turn is the same number in
 * both and is passed through untouched, so the five legal angles survive
 * exactly.
 */
public final class Armor3dBlockbench {

    private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

    private Armor3dBlockbench() {
    }

    /** What a project holds: the cubes on the body, and the images they are painted from. */
    public record Source(List<Cube> cubes, List<byte[]> textures) {
    }

    /**
     * One cube, in the wearer's frame.
     *
     * @param rotation degrees about x, y and z, composed in that order about
     *                 {@code origin}, or null for an unturned cube
     * @param origin   where it turns, or null when it does not
     * @param faces    by block-model face name, in a fixed order
     */
    public record Cube(String bone, Armor3dSet.Piece piece, double[] from, double[] to,
                       double[] rotation, double[] origin, Map<String, Face> faces) {
    }

    /**
     * One face.
     *
     * @param uv       {x0, y0, x1, y1} in the texture's own UV space
     * @param texture  which of {@link Source#textures()} it is painted from
     * @param uvWidth  the width of that UV space, which is what maps it to 0-16
     * @param uvHeight its height
     * @param rotation the face's UV rotation, 0, 90, 180 or 270
     */
    public record Face(double[] uv, int texture, double uvWidth, double uvHeight, int rotation) {
    }

    /**
     * Reads {@code bytes}.
     *
     * @return the set's cubes, or empty with an error said, when this is not a
     *         Blockbench project or its art cannot be read
     */
    public static Optional<Source> read(byte[] bytes, String origin, String where, List<Diagnostic> diagnostics) {
        JsonObject project;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                diagnostics.add(Diagnostic.error(origin, where, "The model is not a Blockbench project."));
                return Optional.empty();
            }
            project = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            diagnostics.add(Diagnostic.error(origin, where, "The model could not be read as a Blockbench project."));
            return Optional.empty();
        }

        double resolutionWidth = 16;
        double resolutionHeight = 16;
        JsonObject resolution = object(project, "resolution");
        if (resolution != null) {
            resolutionWidth = number(resolution.get("width"), 16);
            resolutionHeight = number(resolution.get("height"), 16);
        }

        // The images first, because a face that names one that is not there
        // is the model's problem and is said once, here.
        JsonArray rawTextures = array(project, "textures");
        List<byte[]> textures = new ArrayList<>();
        for (int i = 0; i < rawTextures.size(); i++) {
            Optional<byte[]> png = decode(rawTextures.get(i));
            if (png.isEmpty()) {
                String name = rawTextures.get(i).isJsonObject() ? string(rawTextures.get(i).getAsJsonObject(), "name") : null;
                diagnostics.add(Diagnostic.error(origin, where, "Texture " + (name == null ? "#" + i : name)
                        + " is not embedded in the model. In Blockbench, right-click it and choose to keep it "
                        + "inside the project, then save again."));
                return Optional.empty();
            }
            textures.add(png.get());
        }
        if (textures.isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where, "The model has no texture, so the armour would be "
                    + "drawn as the missing-texture check."));
            return Optional.empty();
        }

        // Which bone, and which group turns, each cube is under.
        Map<String, JsonObject> groupData = new HashMap<>();
        for (JsonElement raw : array(project, "groups")) {
            if (raw.isJsonObject()) {
                String uuid = string(raw.getAsJsonObject(), "uuid");
                if (uuid != null) {
                    groupData.put(uuid, raw.getAsJsonObject());
                }
            }
        }
        Map<String, Placement> placementOf = new HashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        walk(array(project, "outliner"), groupData, null, List.of(), placementOf, unknown);

        List<Cube> cubes = new ArrayList<>();
        int loose = 0;
        for (JsonElement raw : array(project, "elements")) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject element = raw.getAsJsonObject();
            String type = string(element, "type");
            if (type != null && !type.equals("cube")) {
                continue;
            }
            JsonElement export = element.get("export");
            if (export != null && export.isJsonPrimitive() && export.getAsJsonPrimitive().isBoolean()
                    && !export.getAsBoolean()) {
                continue;
            }
            double[] from = vec3(element, "from");
            double[] to = vec3(element, "to");
            if (from == null || to == null) {
                continue;
            }
            Placement placement = placementOf.get(string(element, "uuid"));
            if (placement == null || placement.bone == null) {
                loose++;
                continue;
            }
            double inflate = number(element.get("inflate"), 0);
            if (inflate != 0) {
                for (int i = 0; i < 3; i++) {
                    from[i] -= inflate;
                    to[i] += inflate;
                }
            }
            double[] cubeOrigin = vec3(element, "origin");
            double[] rotation = fromBlockbench(vec3(element, "rotation"));
            Turned turned = new Turned(from, to, rotation, cubeOrigin);
            // Innermost group first: that is the order the turns apply in.
            for (int i = placement.turns.size() - 1; i >= 0; i--) {
                turned = turned.turnedBy(placement.turns.get(i));
            }

            Map<String, Face> faces = new LinkedHashMap<>();
            JsonObject rawFaces = object(element, "faces");
            for (String face : FACES) {
                JsonObject rawFace = object(rawFaces, face);
                if (rawFace == null) {
                    continue;
                }
                JsonElement textureRef = rawFace.get("texture");
                if (textureRef == null || textureRef.isJsonNull()
                        || (textureRef.isJsonPrimitive() && textureRef.getAsJsonPrimitive().isBoolean())) {
                    continue;
                }
                int slot = slotOf(textureRef, rawTextures);
                JsonObject texture = rawTextures.get(slot).isJsonObject() ? rawTextures.get(slot).getAsJsonObject() : null;
                double uvWidth = number(texture == null ? null : texture.get("uv_width"), resolutionWidth);
                double uvHeight = number(texture == null ? null : texture.get("uv_height"), resolutionHeight);
                JsonArray uvRaw = array(rawFace, "uv");
                double[] uv = uvRaw.size() >= 4
                        ? new double[]{number(uvRaw.get(0), 0), number(uvRaw.get(1), 0),
                                number(uvRaw.get(2), 0), number(uvRaw.get(3), 0)}
                        : new double[]{0, 0, uvWidth, uvHeight};
                int faceRotation = (int) number(rawFace.get("rotation"), 0);
                if (faceRotation != 90 && faceRotation != 180 && faceRotation != 270) {
                    faceRotation = 0;
                }
                faces.put(face, new Face(uv, slot, uvWidth, uvHeight, faceRotation));
            }
            Armor3dSet.Piece piece = Armor3dBaker.BONES.get(placement.bone).piece();
            cubes.add(new Cube(placement.bone, piece, turned.from, turned.to,
                    turned.rotation, turned.rotation == null ? null : turned.origin, faces));
        }

        for (String name : unknown) {
            diagnostics.add(Diagnostic.warning(origin, where, "The group " + name + " is not one of the bones "
                    + Armor3dBaker.BONES.keySet() + ". Its cubes ride the bone it is inside, if any."));
        }
        if (loose > 0) {
            diagnostics.add(Diagnostic.warning(origin, where, loose + (loose == 1 ? " cube is" : " cubes are")
                    + " not inside any bone group, so nothing wears " + (loose == 1 ? "it" : "them")
                    + ". Put each in head, body, right_arm and so on."));
        }
        return Optional.of(new Source(List.copyOf(cubes), List.copyOf(textures)));
    }

    // ---- the outliner ------------------------------------------------------

    /** Where a cube is: the bone it rides, and the group turns above it, outermost first. */
    private record Placement(String bone, List<Turn> turns) {
    }

    /** A group's rotation: a matrix about a point. */
    private record Turn(double[][] matrix, double[] pivot) {
    }

    private static void walk(JsonArray nodes, Map<String, JsonObject> groupData, String bone, List<Turn> turns,
                             Map<String, Placement> placementOf, Set<String> unknown) {
        for (JsonElement raw : nodes) {
            if (raw.isJsonPrimitive()) {
                placementOf.put(raw.getAsString(), new Placement(bone, turns));
                continue;
            }
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject node = raw.getAsJsonObject();
            String uuid = string(node, "uuid");
            JsonObject data = uuid == null ? null : groupData.get(uuid);
            JsonObject group = data != null ? data : node;
            String name = string(group, "name");
            String mine = bone;
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (Armor3dBaker.BONES.containsKey(lower)) {
                    mine = lower;
                } else {
                    unknown.add(name);
                }
            }
            List<Turn> inner = turns;
            double[] rotation = vec3(group, "rotation");
            if (rotation != null && (rotation[0] != 0 || rotation[1] != 0 || rotation[2] != 0)) {
                double[] pivot = vec3(group, "origin");
                inner = new ArrayList<>(turns);
                inner.add(new Turn(zyx(rotation), pivot == null ? new double[3] : pivot));
                inner = List.copyOf(inner);
            }
            walk(array(node, "children"), groupData, mine, inner, placementOf, unknown);
        }
    }

    // ---- turns -------------------------------------------------------------

    /** A cube's box and its own turn, which a group's turn moves and composes onto. */
    private record Turned(double[] from, double[] to, double[] rotation, double[] origin) {

        /**
         * The same cube, turned by a group about the group's pivot.
         *
         * <p>p' = G(R(p − o) + o − P) + P, which is GR about o' = G(o − P) + P
         * once the box is slid by o' − o — Studio's {@code poseCube}.
         */
        Turned turnedBy(Turn turn) {
            double[] centre = {(from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2};
            double[] o = origin != null ? origin : centre;
            double[] relative = {o[0] - turn.pivot[0], o[1] - turn.pivot[1], o[2] - turn.pivot[2]};
            double[] moved = apply(turn.matrix, relative);
            double[] oNew = {moved[0] + turn.pivot[0], moved[1] + turn.pivot[1], moved[2] + turn.pivot[2]};
            double[] d = {oNew[0] - o[0], oNew[1] - o[1], oNew[2] - o[2]};
            double[][] own = rotation == null ? identity() : xyz(rotation);
            double[] angles = Armor3dBaker.r4(eulerXyz(multiply(turn.matrix, own)));
            return new Turned(
                    new double[]{from[0] + d[0], from[1] + d[1], from[2] + d[2]},
                    new double[]{to[0] + d[0], to[1] + d[1], to[2] + d[2]},
                    isZero(angles) ? null : angles, oNew);
        }
    }

    /**
     * Blockbench's angles (composed Z, then Y, then X) as the same turn in the
     * engine's order (X, then Y, then Z). A one-axis turn is the same number
     * in both, and is returned as it is so the legal angles stay exact.
     */
    static double[] fromBlockbench(double[] zyxDegrees) {
        if (zyxDegrees == null) {
            return null;
        }
        int nonZero = 0;
        for (double v : zyxDegrees) {
            if (Math.abs(v) > 1e-9) {
                nonZero++;
            }
        }
        if (nonZero == 0) {
            return null;
        }
        if (nonZero == 1) {
            return zyxDegrees.clone();
        }
        double[] angles = Armor3dBaker.r4(eulerXyz(zyx(zyxDegrees)));
        return isZero(angles) ? null : angles;
    }

    private static boolean isZero(double[] angles) {
        return angles[0] == 0 && angles[1] == 0 && angles[2] == 0;
    }

    /** Rz·Ry·Rx, three.js's matrix for an Euler in ZYX order. */
    static double[][] zyx(double[] degrees) {
        return multiply(rz(degrees[2]), multiply(ry(degrees[1]), rx(degrees[0])));
    }

    /** Rx·Ry·Rz, three.js's matrix for an Euler in XYZ order. */
    static double[][] xyz(double[] degrees) {
        return multiply(rx(degrees[0]), multiply(ry(degrees[1]), rz(degrees[2])));
    }

    /** A rotation matrix back to XYZ Euler degrees: three.js's {@code setFromRotationMatrix}. */
    static double[] eulerXyz(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, m[0][2])));
        double x;
        double z;
        if (Math.abs(m[0][2]) < 0.9999999) {
            x = Math.atan2(-m[1][2], m[2][2]);
            z = Math.atan2(-m[0][1], m[0][0]);
        } else {
            x = Math.atan2(m[2][1], m[1][1]);
            z = 0;
        }
        return new double[]{Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z)};
    }

    private static double[][] rx(double degrees) {
        double c = Math.cos(Math.toRadians(degrees));
        double s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    private static double[][] ry(double degrees) {
        double c = Math.cos(Math.toRadians(degrees));
        double s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    private static double[][] rz(double degrees) {
        double c = Math.cos(Math.toRadians(degrees));
        double s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    }

    private static double[][] identity() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return out;
    }

    private static double[] apply(double[][] m, double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    // ---- textures ----------------------------------------------------------

    /**
     * Which texture a face is painted from. Blockbench has written three
     * things here over the years: the texture's uuid, its {@code id}, and an
     * index. A number is tried as an {@code id} first, because that is what
     * an older save meant by it, and as an index after.
     */
    private static int slotOf(JsonElement reference, JsonArray textures) {
        String wanted = reference.getAsString();
        for (int i = 0; i < textures.size(); i++) {
            if (!textures.get(i).isJsonObject()) {
                continue;
            }
            JsonObject texture = textures.get(i).getAsJsonObject();
            if (wanted.equals(string(texture, "uuid")) || wanted.equals(string(texture, "id"))) {
                return i;
            }
        }
        if (reference.isJsonPrimitive() && reference.getAsJsonPrimitive().isNumber()) {
            int index = reference.getAsInt();
            if (index >= 0 && index < textures.size()) {
                return index;
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
            return Optional.of(Base64.getDecoder().decode(source.substring(comma + 1).trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ---- json, gently ------------------------------------------------------

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

    private static double number(JsonElement value, double fallback) {
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        try {
            return primitive.isBoolean() ? fallback : primitive.getAsDouble();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double[] vec3(JsonObject object, String key) {
        JsonArray value = array(object, key);
        if (value.size() < 3) {
            return null;
        }
        return new double[]{number(value.get(0), 0), number(value.get(1), 0), number(value.get(2), 0)};
    }
}
