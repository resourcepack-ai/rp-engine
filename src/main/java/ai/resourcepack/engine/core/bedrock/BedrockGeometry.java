package ai.resourcepack.engine.core.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A Java block model, as a Bedrock client entity: geometry, one baked texture,
 * and native keyframe animations.
 *
 * <p><strong>A port of Studio's Bedrock geometry builder, and it has to stay
 * one.</strong> Studio converts the models it pushes; this converts the ones a
 * server owner authored, and a chair from each must come out the same way up.
 * The coordinate rules are Blockbench's Bedrock codec, which both editions'
 * modellers trust:
 * <ul>
 *   <li>Bedrock space is feet-centred: x and z shift by -8, then x mirrors.</li>
 *   <li>A cube's origin is {@code [8 - to.x, from.y, from.z - 8]}.</li>
 *   <li>Rotations negate x and y; z keeps its sign. A free rotation is first
 *       re-expressed in Bedrock's Z-Y-X order.</li>
 *   <li>Up and down faces flip both UV axes.</li>
 *   <li>Animation positions negate x; rotations negate x and y.</li>
 * </ul>
 *
 * <p>Pure: no Bukkit, no Geyser, no IO beyond the texture lookup it is handed.
 */
public final class BedrockGeometry {

    private BedrockGeometry() {
    }

    /** Everything one model becomes. {@code animations} is null when it has none. */
    public record Converted(JsonObject geometry, JsonObject animations, JsonObject entity, BufferedImage atlas) {
    }

    private record Rect(int x, int y, int w, int h) {
    }

    private record Bone(String name, String parent, float[] pivot, List<Integer> elements) {
    }

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    /**
     * @param identifier the entity identifier the bridge spawns it as
     * @param key        a Bedrock-safe name for this model, used in the
     *                   geometry, texture and animation ids
     * @param textures   a resolved texture ref ({@code ns:block/stone}) to its
     *                   image, or null when the pack does not ship it
     * @return empty when the model has no cubes
     */
    public static java.util.Optional<Converted> convert(String identifier, String key, JsonObject model,
                                                        Function<String, BufferedImage> textures) {
        JsonArray elements = model.has("elements") && model.get("elements").isJsonArray()
                ? model.getAsJsonArray("elements") : new JsonArray();
        if (elements.isEmpty()) return java.util.Optional.empty();

        // --- Atlas -----------------------------------------------------------
        List<String> refs = new ArrayList<>();
        for (JsonElement e : elements) {
            JsonObject faces = obj(e.getAsJsonObject(), "faces");
            if (faces == null) continue;
            for (String face : FACES) {
                JsonObject f = obj(faces, face);
                if (f == null || !f.has("texture")) continue;
                String ref = resolve(model, f.get("texture").getAsString());
                if (ref != null && !refs.contains(ref)) refs.add(ref);
            }
        }
        if (refs.isEmpty()) return java.util.Optional.empty();
        List<BufferedImage> tiles = new ArrayList<>();
        int tile = 16;
        for (String ref : refs) {
            BufferedImage image = textures.apply(ref);
            if (image == null) image = missing();
            // Animated textures are vertical strips; frame 0 is the top square.
            if (image.getHeight() > image.getWidth()) image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
            tiles.add(image);
            tile = Math.max(tile, image.getWidth());
        }
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(refs.size())));
        int rows = Math.max(1, (int) Math.ceil(refs.size() / (double) cols));
        BufferedImage atlas = new BufferedImage(cols * tile, rows * tile, BufferedImage.TYPE_INT_ARGB);
        Map<String, Rect> rects = new LinkedHashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            int cx = (i % cols) * tile;
            int cy = (i / cols) * tile;
            BufferedImage src = tiles.get(i);
            for (int y = 0; y < tile; y++) {
                int sy = Math.min(src.getHeight() - 1, y * src.getHeight() / tile);
                for (int x = 0; x < tile; x++) {
                    int sx = Math.min(src.getWidth() - 1, x * src.getWidth() / tile);
                    atlas.setRGB(cx + x, cy + y, src.getRGB(sx, sy));
                }
            }
            rects.put(refs.get(i), new Rect(cx, cy, tile, tile));
        }

        // --- Bones -----------------------------------------------------------
        Set<String> animated = animatedTargets(model);
        List<Bone> bones = new ArrayList<>();
        Map<String, String> boneByTarget = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        Set<Integer> claimed = new HashSet<>();
        JsonArray groups = model.has("groups") && model.get("groups").isJsonArray()
                ? model.getAsJsonArray("groups") : new JsonArray();
        String[] groupNames = new String[groups.size()];
        for (int gi = 0; gi < groups.size(); gi++) {
            JsonObject group = groups.get(gi).getAsJsonObject();
            String groupBone = unique(used, sanitize(group.has("name") ? group.get("name").getAsString() : "group"));
            groupNames[gi] = groupBone;
            boneByTarget.put("g:" + gi, groupBone);
            List<Integer> own = new ArrayList<>();
            JsonArray children = group.has("children") ? group.getAsJsonArray("children") : new JsonArray();
            for (JsonElement c : children) {
                int i = c.getAsInt();
                if (i < 0 || i >= elements.size() || !claimed.add(i)) continue;
                if (animated.contains(String.valueOf(i))) {
                    JsonObject el = elements.get(i).getAsJsonObject();
                    String cubeBone = unique(used, sanitize(el.has("name") ? el.get("name").getAsString() : "cube_" + i));
                    boneByTarget.put(String.valueOf(i), cubeBone);
                    bones.add(new Bone(cubeBone, groupBone, animPivot(el), List.of(i)));
                } else {
                    own.add(i);
                }
            }
            int parent = group.has("parent") ? group.get("parent").getAsInt() : -1;
            String parentName = parent >= 0 && parent != gi && parent < gi ? groupNames[parent] : null;
            bones.add(new Bone(groupBone, parentName, vec(group, "origin", 8, 0, 8), own));
        }
        List<Integer> loose = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (claimed.contains(i)) continue;
            if (animated.contains(String.valueOf(i))) {
                JsonObject el = elements.get(i).getAsJsonObject();
                String cubeBone = unique(used, sanitize(el.has("name") ? el.get("name").getAsString() : "cube_" + i));
                boneByTarget.put(String.valueOf(i), cubeBone);
                bones.add(new Bone(cubeBone, null, animPivot(el), List.of(i)));
            } else {
                loose.add(i);
            }
        }
        if (!loose.isEmpty() || bones.isEmpty()) bones.add(new Bone(unique(used, "root"), null, new float[] {8, 0, 8}, loose));

        // --- Geometry --------------------------------------------------------
        boolean uvRotation = false;
        JsonArray boneJson = new JsonArray();
        for (Bone bone : bones) {
            JsonObject b = new JsonObject();
            b.addProperty("name", bone.name());
            if (bone.parent() != null) b.addProperty("parent", bone.parent());
            b.add("pivot", point(bone.pivot()));
            if (!bone.elements().isEmpty()) {
                JsonArray cubes = new JsonArray();
                for (int i : bone.elements()) {
                    JsonObject cube = cube(model, elements.get(i).getAsJsonObject(), rects);
                    if (cube.has("uv_rotation_used")) {
                        uvRotation = true;
                        cube.remove("uv_rotation_used");
                    }
                    cubes.add(cube);
                }
                b.add("cubes", cubes);
            }
            boneJson.add(b);
        }
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.rpe." + key);
        description.addProperty("texture_width", atlas.getWidth());
        description.addProperty("texture_height", atlas.getHeight());
        description.addProperty("visible_bounds_width", 3);
        description.addProperty("visible_bounds_height", 3);
        JsonArray offset = new JsonArray();
        offset.add(0);
        offset.add(0.5);
        offset.add(0);
        description.add("visible_bounds_offset", offset);
        JsonObject geo = new JsonObject();
        geo.add("description", description);
        geo.add("bones", boneJson);
        JsonArray list = new JsonArray();
        list.add(geo);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("format_version", uvRotation ? "1.21.0" : "1.16.0");
        geometry.add("minecraft:geometry", list);

        // --- Animations ------------------------------------------------------
        JsonObject animationsOut = new JsonObject();
        Map<String, String> ids = new LinkedHashMap<>();
        List<String> loops = new ArrayList<>();
        JsonArray animations = model.has("animations") && model.get("animations").isJsonArray()
                ? model.getAsJsonArray("animations") : new JsonArray();
        for (JsonElement a : animations) {
            JsonObject animation = a.getAsJsonObject();
            String name = animation.has("name") ? animation.get("name").getAsString() : "animation";
            JsonObject animators = obj(animation, "animators");
            if (animators == null) continue;
            JsonObject bonesOut = new JsonObject();
            for (Map.Entry<String, JsonElement> target : animators.entrySet()) {
                String bone = boneByTarget.get(target.getKey());
                if (bone == null || !target.getValue().isJsonObject()) continue;
                JsonObject channels = new JsonObject();
                for (String channel : new String[] {"rotation", "position", "scale"}) {
                    JsonObject animator = target.getValue().getAsJsonObject();
                    if (!animator.has(channel) || !animator.get(channel).isJsonArray()) continue;
                    JsonArray frames = animator.getAsJsonArray(channel);
                    if (frames.isEmpty()) continue;
                    channels.add(channel, channel(channel, frames));
                }
                if (channels.size() > 0) bonesOut.add(bone, channels);
            }
            if (bonesOut.size() == 0) continue;
            String id = animationId(key, name);
            ids.put(name, id);
            boolean loop = animation.has("loop") && animation.get("loop").getAsBoolean();
            if (animation.has("triggers") && animation.get("triggers").isJsonArray()) {
                for (JsonElement t : animation.getAsJsonArray("triggers")) {
                    if (t.isJsonObject() && t.getAsJsonObject().has("type")
                            && "loop".equals(t.getAsJsonObject().get("type").getAsString())) loop = true;
                }
            }
            JsonObject out = new JsonObject();
            if (loop) {
                out.addProperty("loop", true);
                loops.add(id);
            }
            float length = animation.has("length") ? animation.get("length").getAsFloat() : 1f;
            out.addProperty("animation_length", round(Math.max(0.05f, length)));
            out.add("bones", bonesOut);
            animationsOut.add(id, out);
        }
        JsonObject animationJson = null;
        if (animationsOut.size() > 0) {
            animationJson = new JsonObject();
            animationJson.addProperty("format_version", "1.8.0");
            animationJson.add("animations", animationsOut);
        }

        // --- Client entity -----------------------------------------------------
        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", identifier);
        JsonObject materials = new JsonObject();
        String renderType = model.has("render_type") ? model.get("render_type").getAsString() : "";
        materials.addProperty("default", renderType.contains("translucent") ? "entity_alphablend" : "entity_alphatest");
        desc.add("materials", materials);
        JsonObject tex = new JsonObject();
        tex.addProperty("default", "textures/entity/rpe_" + key);
        desc.add("textures", tex);
        JsonObject geoRef = new JsonObject();
        geoRef.addProperty("default", "geometry.rpe." + key);
        desc.add("geometry", geoRef);
        if (!ids.isEmpty()) {
            JsonObject shortNames = new JsonObject();
            JsonArray animate = new JsonArray();
            for (Map.Entry<String, String> entry : ids.entrySet()) {
                String shortName = sanitize(entry.getKey());
                shortNames.addProperty(shortName, entry.getValue());
                if (loops.contains(entry.getValue())) animate.add(shortName);
            }
            desc.add("animations", shortNames);
            if (!animate.isEmpty()) {
                JsonObject scripts = new JsonObject();
                scripts.add("animate", animate);
                desc.add("scripts", scripts);
            }
        }
        JsonArray controllers = new JsonArray();
        controllers.add("controller.render.default");
        desc.add("render_controllers", controllers);
        JsonObject body = new JsonObject();
        body.add("description", desc);
        JsonObject entity = new JsonObject();
        entity.addProperty("format_version", "1.10.0");
        entity.add("minecraft:client_entity", body);

        return java.util.Optional.of(new Converted(geometry, animationJson, entity, atlas));
    }

    /** The id an animation is registered under; the bridge plays it by this. */
    public static String animationId(String key, String animation) {
        return "animation.rpe." + key + "." + sanitize(animation);
    }

    /** Studio's name sanitiser, which both ends of a Bedrock pack must agree on. */
    public static String sanitize(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "_")
                .replaceAll("[^a-z0-9_]", "")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    /**
     * An x-then-y-then-z turn (the free rotation's order) re-expressed in
     * Bedrock's z-then-y-then-x. Degrees in and out. The same arithmetic as
     * Studio's {@code eulerXyzToZyx}.
     */
    static float[] eulerXyzToZyx(float[] degrees) {
        double a = Math.toRadians(degrees[0]);
        double b = Math.toRadians(degrees[1]);
        double c = Math.toRadians(degrees[2]);
        double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cc = Math.cos(c), sc = Math.sin(c);
        double m00 = cb * cc;
        double m10 = ca * sc + sa * sb * cc;
        double m20 = sa * sc - ca * sb * cc;
        double m21 = sa * cc + ca * sb * sc;
        double m22 = ca * cb;
        double y = Math.asin(Math.max(-1, Math.min(1, -m20)));
        double x = Math.atan2(m21, m22);
        double z = Math.atan2(m10, m00);
        return new float[] {(float) Math.toDegrees(x), (float) Math.toDegrees(y), (float) Math.toDegrees(z)};
    }

    // --- Pieces ---------------------------------------------------------------

    private static JsonObject cube(JsonObject model, JsonObject el, Map<String, Rect> rects) {
        float[] from = vec(el, "from", 0, 0, 0);
        float[] to = vec(el, "to", 16, 16, 16);
        JsonObject cube = new JsonObject();
        cube.add("origin", arr(8 - to[0], from[1], from[2] - 8));
        cube.add("size", arr(to[0] - from[0], to[1] - from[1], to[2] - from[2]));

        JsonObject free = obj(el, "free_rotation");
        JsonObject rotation = obj(el, "rotation");
        if (free != null && free.has("angles") && !identity(vec(free, "angles", 0, 0, 0))) {
            cube.add("pivot", point(vec(free, "origin", 8, 8, 8)));
            float[] zyx = eulerXyzToZyx(vec(free, "angles", 0, 0, 0));
            cube.add("rotation", arr(-zyx[0], -zyx[1], zyx[2]));
        } else if (rotation != null && rotation.has("angle") && rotation.get("angle").getAsFloat() != 0) {
            float angle = rotation.get("angle").getAsFloat();
            String axis = rotation.has("axis") ? rotation.get("axis").getAsString() : "y";
            cube.add("pivot", point(vec(rotation, "origin", 8, 8, 8)));
            cube.add("rotation", switch (axis) {
                case "x" -> arr(-angle, 0, 0);
                case "z" -> arr(0, 0, angle);
                default -> arr(0, -angle, 0);
            });
        }

        JsonObject uv = new JsonObject();
        JsonObject faces = obj(el, "faces");
        if (faces != null) {
            for (String faceName : FACES) {
                JsonObject face = obj(faces, faceName);
                if (face == null || !face.has("texture")) continue;
                String ref = resolve(model, face.get("texture").getAsString());
                Rect rect = ref == null ? null : rects.get(ref);
                if (rect == null) continue;
                float[] f = face.has("uv") && face.get("uv").isJsonArray() && face.getAsJsonArray("uv").size() == 4
                        ? new float[] {
                            face.getAsJsonArray("uv").get(0).getAsFloat(), face.getAsJsonArray("uv").get(1).getAsFloat(),
                            face.getAsJsonArray("uv").get(2).getAsFloat(), face.getAsJsonArray("uv").get(3).getAsFloat()}
                        : defaultUv(faceName, from, to);
                float u1 = rect.x() + f[0] * rect.w() / 16f;
                float v1 = rect.y() + f[1] * rect.h() / 16f;
                float u2 = rect.x() + f[2] * rect.w() / 16f;
                float v2 = rect.y() + f[3] * rect.h() / 16f;
                float du = u2 - u1;
                float dv = v2 - v1;
                if (faceName.equals("up") || faceName.equals("down")) {
                    u1 += du;
                    v1 += dv;
                    du = -du;
                    dv = -dv;
                }
                JsonObject entry = new JsonObject();
                entry.add("uv", arr2(u1, v1));
                entry.add("uv_size", arr2(du, dv));
                if (face.has("rotation") && face.get("rotation").getAsInt() != 0) {
                    entry.addProperty("uv_rotation", face.get("rotation").getAsInt());
                    cube.addProperty("uv_rotation_used", true);
                }
                uv.add(faceName, entry);
            }
        }
        cube.add("uv", uv);
        return cube;
    }

    /** Java's own default face UVs, for older models that leave them out. */
    private static float[] defaultUv(String face, float[] from, float[] to) {
        return switch (face) {
            case "down" -> new float[] {from[0], 16 - to[2], to[0], 16 - from[2]};
            case "up" -> new float[] {from[0], from[2], to[0], to[2]};
            case "north" -> new float[] {16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case "south" -> new float[] {from[0], 16 - to[1], to[0], 16 - from[1]};
            case "west" -> new float[] {from[2], 16 - to[1], to[2], 16 - from[1]};
            default -> new float[] {16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
        };
    }

    private static JsonObject channel(String channel, JsonArray frames) {
        JsonObject out = new JsonObject();
        JsonObject previous = null;
        for (JsonElement e : frames) {
            JsonObject frame = e.getAsJsonObject();
            float[] value = flip(channel, vec(frame, "value", 0, 0, 0));
            String time = timecode(frame.has("time") ? frame.get("time").getAsFloat() : 0f);
            String interpolation = frame.has("interpolation") ? frame.get("interpolation").getAsString() : "linear";
            String previousInterpolation = previous != null && previous.has("interpolation")
                    ? previous.get("interpolation").getAsString() : "";
            if (interpolation.equals("smooth")) {
                JsonObject v = new JsonObject();
                v.add("post", arr(value[0], value[1], value[2]));
                v.addProperty("lerp_mode", "catmullrom");
                out.add(time, v);
            } else if (previousInterpolation.equals("step")) {
                float[] pre = flip(channel, vec(previous, "value", 0, 0, 0));
                JsonObject v = new JsonObject();
                v.add("pre", arr(pre[0], pre[1], pre[2]));
                v.add("post", arr(value[0], value[1], value[2]));
                out.add(time, v);
            } else {
                out.add(time, arr(value[0], value[1], value[2]));
            }
            previous = frame;
        }
        return out;
    }

    private static float[] flip(String channel, float[] v) {
        return switch (channel) {
            case "rotation" -> new float[] {-v[0], -v[1], v[2]};
            case "position" -> new float[] {-v[0], v[1], v[2]};
            default -> v;
        };
    }

    private static String timecode(float t) {
        String s = trim(round(t));
        return s.contains(".") ? s : s + ".0";
    }

    private static Set<String> animatedTargets(JsonObject model) {
        Set<String> keys = new HashSet<>();
        if (!model.has("animations") || !model.get("animations").isJsonArray()) return keys;
        for (JsonElement a : model.getAsJsonArray("animations")) {
            JsonObject animators = a.isJsonObject() ? obj(a.getAsJsonObject(), "animators") : null;
            if (animators == null) continue;
            for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> channel : entry.getValue().getAsJsonObject().entrySet()) {
                    if (channel.getValue().isJsonArray() && !channel.getValue().getAsJsonArray().isEmpty()) {
                        keys.add(entry.getKey());
                    }
                }
            }
        }
        return keys;
    }

    private static float[] animPivot(JsonObject el) {
        JsonObject rotation = obj(el, "rotation");
        if (rotation != null && rotation.has("origin")) return vec(rotation, "origin", 8, 8, 8);
        float[] from = vec(el, "from", 0, 0, 0);
        float[] to = vec(el, "to", 16, 16, 16);
        return new float[] {(from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2};
    }

    /** Follows "#slot" indirection to the texture a face really uses. */
    static String resolve(JsonObject model, String texture) {
        JsonObject map = obj(model, "textures");
        String ref = texture;
        for (int hops = 0; ref != null && hops < 8; hops++) {
            if (!ref.startsWith("#")) return ref.contains(":") ? ref : "minecraft:" + ref;
            String slot = ref.substring(1);
            ref = map != null && map.has(slot) && map.get(slot).isJsonPrimitive() ? map.get(slot).getAsString() : null;
        }
        return null;
    }

    private static boolean identity(float[] angles) {
        for (float a : angles) if (a % 360f != 0f) return false;
        return true;
    }

    private static BufferedImage missing() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) image.setRGB(x, y, (x < 8) == (y < 8) ? 0xFFF800F8 : 0xFF000000);
        }
        return image;
    }

    private static String unique(Set<String> used, String base) {
        String name = base;
        int n = 2;
        while (used.contains(name)) name = base + "_" + n++;
        used.add(name);
        return name;
    }

    private static JsonObject obj(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static float[] vec(JsonObject parent, String key, float dx, float dy, float dz) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) return new float[] {dx, dy, dz};
        JsonArray a = parent.getAsJsonArray(key);
        if (a.size() < 3) return new float[] {dx, dy, dz};
        return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static JsonArray point(float[] p) {
        return arr(8 - p[0], p[1], p[2] - 8);
    }

    private static JsonArray arr(float x, float y, float z) {
        JsonArray a = new JsonArray();
        a.add(round(x));
        a.add(round(y));
        a.add(round(z));
        return a;
    }

    private static JsonArray arr2(float x, float y) {
        JsonArray a = new JsonArray();
        a.add(round(x));
        a.add(round(y));
        return a;
    }

    private static double round(float n) {
        double r = Math.round(n * 10000.0) / 10000.0;
        return r == 0 ? 0 : r;
    }

    private static String trim(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
