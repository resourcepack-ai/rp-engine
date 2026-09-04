package ai.resourcepack.engine.core.edit;

import ai.resourcepack.engine.core.item.BbModel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writing an edited model back into the Blockbench project it came out of.
 *
 * <p>{@link BbModel} reads a project into a Minecraft model; this is the other
 * direction, and it exists because <strong>a {@code .bbmodel} beats a
 * {@code .json} when both are there</strong> ({@code ItemAssets} tries the
 * project first). So writing the edited model out as JSON beside somebody's
 * project would be a save that changed nothing at all, with no error anywhere
 * — the worst failure this feature could have.
 *
 * <h2>It edits the original rather than building a fresh one</h2>
 *
 * <p>A project holds a great deal this plugin never reads: the meta block,
 * display settings, texture names and folders, mesh elements, the author's own
 * UV layout. Building a new project from the model would silently drop all of
 * it. So the original is the starting point and only what the editor can
 * actually change is replaced — the elements, the outliner, the animations,
 * and the pixels inside each texture.
 *
 * <h2>Nothing is written that does not read back</h2>
 *
 * <p>{@link #write} converts its own output with {@link BbModel} and compares
 * it against the model it was asked to write. A mismatch is an empty answer,
 * not a file. That check is the reason this class is safe to have at all: the
 * conversion has a dozen places to be subtly wrong — a UV scale, a rotation
 * axis, a bone index — and every one of them would land on somebody's own
 * project file. The reader is right here, so the round trip can be tried
 * before anything is committed to disk.
 */
final class BbWriter {

    /**
     * How much two numbers may differ and still be the same number.
     *
     * <p>{@link BbModel} rounds to two decimal places on the way out, and UVs
     * go through a scale and back. The check is for a sign, an axis or an index
     * being wrong, not for the last digit.
     */
    private static final double EPSILON = 0.02;

    private BbWriter() {
    }

    /**
     * The project, updated.
     *
     * @param original the project file as it is on disk
     * @param model    the edited Minecraft model, in {@link BbModel}'s own
     *                 output shape
     * @param textures the PNG for each texture slot, in slot order; an entry
     *                 may be null for a slot whose art did not come back, and
     *                 that slot keeps whatever the project already had
     * @param namespace the pack's namespace, for the verification pass
     * @param name      the model's name, likewise
     * @return the bytes to write, or empty when the result would not read back
     *         as the model it was given
     */
    static Optional<byte[]> write(byte[] original, JsonObject rawModel, List<byte[]> textures,
                                  String namespace, String name) {
        JsonObject project = parse(original);
        if (project == null || rawModel == null) {
            return Optional.empty();
        }
        // Bones first, because everything after this reads their order.
        JsonObject model = preorder(rawModel, array(rawModel, "elements").size());

        List<String> slots = slotKeys(model);
        JsonObject resolution = object(project, "resolution");
        int resolutionWidth = intOf(resolution, "width", 16);
        int resolutionHeight = intOf(resolution, "height", 16);

        JsonArray oldTextures = array(project, "textures");
        JsonArray newTextures = new JsonArray();
        for (int slot = 0; slot < slots.size(); slot++) {
            JsonObject texture = slot < oldTextures.size() && oldTextures.get(slot).isJsonObject()
                    ? oldTextures.get(slot).getAsJsonObject().deepCopy()
                    : freshTexture(name, slot, resolutionWidth, resolutionHeight);
            byte[] png = slot < textures.size() ? textures.get(slot) : null;
            if (png != null) {
                texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
                // A project that pointed at a file on the author's machine now
                // carries the pixels instead. The path stays as a note of
                // where it came from; Blockbench prefers the embedded source.
                texture.addProperty("saved", false);
            }
            newTextures.add(texture);
        }
        project.add("textures", newTextures);

        JsonArray elements = new JsonArray();
        JsonArray sourceElements = array(model, "elements");
        List<String> cubeUuids = new ArrayList<>();
        for (int i = 0; i < sourceElements.size(); i++) {
            if (!sourceElements.get(i).isJsonObject()) {
                continue;
            }
            String uuid = uuid("cube", i);
            cubeUuids.add(uuid);
            elements.add(cube(sourceElements.get(i).getAsJsonObject(), uuid, slots,
                    newTextures, resolutionWidth, resolutionHeight));
        }
        project.add("elements", elements);

        JsonArray groups = array(model, "groups");
        Outliner outliner = outliner(groups, cubeUuids);
        project.add("outliner", outliner.tree);
        project.add("animations", animations(array(model, "animations"), outliner.uuids));

        byte[] written = project.toString().getBytes(StandardCharsets.UTF_8);
        return reads(written, model, slots, namespace, name) ? Optional.of(written) : Optional.empty();
    }

    // ---- the pieces ---------------------------------------------------------

    /**
     * The model's texture slots, in the order they become indices.
     *
     * <p>A model's texture map is keyed by strings; a project's is a list. The
     * mapping between them is this order, and it is the one thing both halves
     * of this class and the verification pass have to agree about. Numeric keys
     * first and in numeric order — which is what {@link BbModel} writes — and
     * anything else after them in the order it was given, which is where a key
     * the editor invented ends up.
     *
     * <p>{@code particle} is not a slot. It is a pointer at one, and the game
     * gives it no geometry.
     */
    private static List<String> slotKeys(JsonObject model) {
        JsonObject textures = object(model, "textures");
        List<String> numeric = new ArrayList<>();
        List<String> named = new ArrayList<>();
        if (textures != null) {
            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                if ("particle".equals(entry.getKey())) {
                    continue;
                }
                if (entry.getKey().matches("\\d+")) {
                    numeric.add(entry.getKey());
                } else {
                    named.add(entry.getKey());
                }
            }
        }
        numeric.sort((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        numeric.addAll(named);
        return numeric;
    }

    private static JsonObject freshTexture(String name, int slot, int width, int height) {
        JsonObject texture = new JsonObject();
        texture.addProperty("id", String.valueOf(slot));
        texture.addProperty("name", name + "_" + slot + ".png");
        texture.addProperty("folder", "block");
        texture.addProperty("namespace", "");
        texture.addProperty("uuid", uuid("texture", slot));
        texture.addProperty("particle", false);
        texture.addProperty("render_mode", "default");
        texture.addProperty("uv_width", width);
        texture.addProperty("uv_height", height);
        return texture;
    }

    private static JsonObject cube(JsonObject element, String uuid, List<String> slots,
                                   JsonArray textures, int resolutionWidth, int resolutionHeight) {
        JsonObject out = new JsonObject();
        String name = string(element, "name");
        out.addProperty("name", name == null ? "cube" : name);
        out.addProperty("type", "cube");
        out.addProperty("uuid", uuid);
        out.addProperty("visibility", true);
        out.add("from", numbers(vec3(element, "from")));
        out.add("to", numbers(vec3(element, "to")));

        JsonObject rotation = object(element, "rotation");
        float[] origin = rotation == null ? null : vec3(rotation, "origin");
        // BbModel's own fallback, so a cube with no rotation round-trips to the
        // same pivot rather than drifting to wherever this class felt like.
        out.add("origin", numbers(origin == null ? new float[] {8f, 8f, 8f} : origin));
        float angle = rotation == null ? 0f : floatOf(rotation, "angle", 0f);
        if (rotation != null && Math.abs(angle) >= 0.001f) {
            String axis = string(rotation, "axis");
            JsonArray triple = new JsonArray();
            triple.add("x".equals(axis) ? angle : 0f);
            triple.add("y".equals(axis) ? angle : 0f);
            triple.add("z".equals(axis) ? angle : 0f);
            // The ARRAY form, not the object one. Both are read back, and only
            // this one is what Blockbench itself writes and its editor expects.
            out.add("rotation", triple);
        }

        JsonObject faces = new JsonObject();
        JsonObject sourceFaces = object(element, "faces");
        if (sourceFaces != null) {
            for (Map.Entry<String, JsonElement> entry : sourceFaces.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject face = entry.getValue().getAsJsonObject();
                int slot = slotOf(string(face, "texture"), slots);
                if (slot < 0) {
                    continue;
                }
                int uvWidth = resolutionWidth;
                int uvHeight = resolutionHeight;
                if (slot < textures.size() && textures.get(slot).isJsonObject()) {
                    JsonObject texture = textures.get(slot).getAsJsonObject();
                    uvWidth = intOf(texture, "uv_width", resolutionWidth);
                    uvHeight = intOf(texture, "uv_height", resolutionHeight);
                }
                JsonArray uv = array(face, "uv");
                JsonArray scaled = new JsonArray();
                for (int i = 0; i < 4; i++) {
                    float value = i < uv.size() ? uv.get(i).getAsFloat() : (i < 2 ? 0f : 16f);
                    scaled.add(round(value * ((i % 2 == 0 ? uvWidth : uvHeight) / 16f)));
                }
                JsonObject out2 = new JsonObject();
                out2.add("uv", scaled);
                out2.addProperty("texture", slot);
                int faceRotation = intOf(face, "rotation", 0);
                if (faceRotation != 0) {
                    out2.addProperty("rotation", faceRotation);
                }
                faces.add(entry.getKey(), out2);
            }
        }
        // Blockbench expects all six, and a face with a null texture is how it
        // spells "this side is not painted" — which is also what BbModel skips.
        for (String side : new String[] {"north", "east", "south", "west", "up", "down"}) {
            if (!faces.has(side)) {
                JsonObject blank = new JsonObject();
                JsonArray uv = new JsonArray();
                uv.add(0);
                uv.add(0);
                uv.add(0);
                uv.add(0);
                blank.add("uv", uv);
                blank.add("texture", com.google.gson.JsonNull.INSTANCE);
                faces.add(side, blank);
            }
        }
        out.add("faces", faces);
        return out;
    }

    /**
     * The model, with its bones in pre-order.
     *
     * <p><strong>A project's outliner has no index; the order IS the index.</strong>
     * {@link BbModel} numbers bones as it walks the tree, so re-reading what
     * this class writes renumbers them depth-first — and an animator keyed
     * {@code g:3} would then be attached to whichever bone the walk happened to
     * reach third. The flat list this receives is only in that order if nothing
     * has been added to it since it was read: an editor that appends a new bone
     * with an earlier parent produces a perfectly valid list that is not
     * pre-order at all.
     *
     * <p>So it is put into pre-order here, once, with {@code parent}, each
     * group's {@code children} (which name ELEMENTS and are therefore
     * untouched) and every animator key remapped to match. Everything
     * downstream — the outliner, the animations, and the verification pass —
     * then reads one consistent numbering.
     *
     * <p>A model whose list is already pre-order comes back unchanged, which is
     * every model that has just been read out of a project.
     */
    private static JsonObject preorder(JsonObject model, int elementCount) {
        JsonArray groups = array(model, "groups");
        if (groups.isEmpty()) {
            return model;
        }
        List<Integer> order = new ArrayList<>();
        boolean[] placed = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            visit(groups, i, -1, order, placed, 0);
        }
        // A cycle in the parent chain leaves entries unplaced. They are put at
        // the end rather than dropped: a bone that vanished would take its
        // cubes' animation with it, and the verification pass is a better place
        // for a malformed list to be caught than a silent deletion here.
        for (int i = 0; i < groups.size(); i++) {
            if (!placed[i]) {
                placed[i] = true;
                order.add(i);
            }
        }

        int[] renumbered = new int[groups.size()];
        for (int i = 0; i < order.size(); i++) {
            renumbered[order.get(i)] = i;
        }
        JsonObject out = model.deepCopy();
        JsonArray reordered = new JsonArray();
        for (int index : order) {
            JsonObject group = groups.get(index).isJsonObject()
                    ? groups.get(index).getAsJsonObject().deepCopy()
                    : new JsonObject();
            int parent = intOf(group, "parent", -1);
            group.addProperty("parent", parent >= 0 && parent < renumbered.length ? renumbered[parent] : -1);
            // A child index naming an element that is not there any more —
            // a cube deleted in the editor whose bone was not updated —
            // cannot be written and must not be compared against either, or
            // every save of that model is refused with nothing to explain it.
            JsonArray kept = new JsonArray();
            for (JsonElement child : array(group, "children")) {
                int cube = intValue(child, -1);
                if (cube >= 0 && cube < elementCount) {
                    kept.add(cube);
                }
            }
            group.add("children", kept);
            reordered.add(group);
        }
        out.add("groups", reordered);

        JsonArray animations = array(out, "animations");
        for (JsonElement raw : animations) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject animation = raw.getAsJsonObject();
            JsonObject animators = object(animation, "animators");
            if (animators == null) {
                continue;
            }
            JsonObject remapped = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
                Integer bone = boneIndex(entry.getKey());
                if (bone == null || bone < 0 || bone >= renumbered.length) {
                    remapped.add(entry.getKey(), entry.getValue());
                    continue;
                }
                remapped.add("g:" + renumbered[bone], entry.getValue());
            }
            animation.add("animators", remapped);
        }
        return out;
    }

    /** Depth-first from one root, guarding against a parent chain that loops. */
    private static void visit(JsonArray groups, int index, int expectedParent,
                              List<Integer> order, boolean[] placed, int depth) {
        if (index < 0 || index >= groups.size() || placed[index] || depth > groups.size()) {
            return;
        }
        if (!groups.get(index).isJsonObject()) {
            placed[index] = true;
            order.add(index);
            return;
        }
        int parent = intOf(groups.get(index).getAsJsonObject(), "parent", -1);
        if (parent != expectedParent) {
            return;
        }
        placed[index] = true;
        order.add(index);
        for (int child = 0; child < groups.size(); child++) {
            visit(groups, child, index, order, placed, depth + 1);
        }
    }

    /** The tree, and the uuid each bone index got. */
    private static final class Outliner {
        final JsonArray tree = new JsonArray();
        final List<String> uuids = new ArrayList<>();
    }

    /**
     * The bone list, back into Blockbench's nested outliner.
     *
     * <p>The flat list carries a {@code parent} index per entry and is in
     * pre-order — which is what {@link BbModel}'s walk produced and therefore
     * what re-reading this will produce again. Rebuilding it is a matter of
     * appending each node to its parent's children in index order.
     *
     * <p>A cube belonging to no bone is a bare uuid at the root, which is the
     * form Blockbench uses for a loose cube and the form {@code BbModel} reads
     * as "already in the element list, belongs to no bone".
     */
    private static Outliner outliner(JsonArray groups, List<String> cubeUuids) {
        Outliner out = new Outliner();
        List<JsonObject> nodes = new ArrayList<>();
        List<JsonArray> children = new ArrayList<>();
        boolean[] claimed = new boolean[cubeUuids.size()];

        for (int i = 0; i < groups.size(); i++) {
            if (!groups.get(i).isJsonObject()) {
                nodes.add(null);
                children.add(null);
                out.uuids.add(null);
                continue;
            }
            JsonObject group = groups.get(i).getAsJsonObject();
            String uuid = uuid("bone", i);
            out.uuids.add(uuid);

            JsonObject node = new JsonObject();
            String name = string(group, "name");
            node.addProperty("name", name == null ? "bone" : name);
            float[] origin = vec3(group, "origin");
            node.add("origin", numbers(origin == null ? new float[] {8f, 0f, 8f} : origin));
            node.addProperty("uuid", uuid);
            node.addProperty("export", true);
            node.addProperty("isOpen", true);
            node.addProperty("visibility", true);

            JsonArray childArray = new JsonArray();
            for (JsonElement child : array(group, "children")) {
                int index = child.isJsonPrimitive() ? intValue(child, -1) : -1;
                if (index >= 0 && index < cubeUuids.size()) {
                    childArray.add(cubeUuids.get(index));
                    claimed[index] = true;
                }
            }
            node.add("children", childArray);
            nodes.add(node);
            children.add(childArray);
        }

        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == null) {
                continue;
            }
            int parent = intOf(groups.get(i).getAsJsonObject(), "parent", -1);
            // A parent that is not strictly earlier cannot be one: the list is
            // pre-order, and honouring a forward reference would build a cycle
            // or reorder the indices an animator is keyed on. Treated as a root
            // instead, and the verification pass is what notices if that was
            // the wrong call.
            if (parent >= 0 && parent < i && children.get(parent) != null) {
                children.get(parent).add(nodes.get(i));
            } else {
                out.tree.add(nodes.get(i));
            }
        }
        for (int i = 0; i < cubeUuids.size(); i++) {
            if (!claimed[i]) {
                out.tree.add(cubeUuids.get(i));
            }
        }
        return out;
    }

    /** The animations, back into the project's own shape. */
    private static JsonArray animations(JsonArray source, List<String> boneUuids) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < source.size(); i++) {
            if (!source.get(i).isJsonObject()) {
                continue;
            }
            JsonObject animation = source.get(i).getAsJsonObject();
            JsonObject written = new JsonObject();
            String name = string(animation, "name");
            written.addProperty("uuid", uuid("anim", i));
            written.addProperty("name", name == null ? "animation" : name);
            // BbModel reads `loop` as the three-way string when it is one and
            // as the old boolean when it is not, so writing the string is both
            // current and readable by everything.
            String mode = string(animation, "mode");
            written.addProperty("loop", mode == null
                    ? (bool(animation, "loop") ? "loop" : "once")
                    : mode);
            written.addProperty("override", false);
            written.addProperty("length", floatOf(animation, "length", 1f));
            written.addProperty("snapping", 24);

            JsonObject animators = new JsonObject();
            JsonObject sourceAnimators = object(animation, "animators");
            if (sourceAnimators != null) {
                int keyframe = 0;
                for (Map.Entry<String, JsonElement> entry : sourceAnimators.entrySet()) {
                    Integer bone = boneIndex(entry.getKey());
                    if (bone == null || bone < 0 || bone >= boneUuids.size()
                            || boneUuids.get(bone) == null || !entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonArray frames = new JsonArray();
                    for (Map.Entry<String, JsonElement> channel
                            : entry.getValue().getAsJsonObject().entrySet()) {
                        if (!channel.getValue().isJsonArray()) {
                            continue;
                        }
                        for (JsonElement raw : channel.getValue().getAsJsonArray()) {
                            if (!raw.isJsonObject()) {
                                continue;
                            }
                            frames.add(keyframe(channel.getKey(), raw.getAsJsonObject(), keyframe++));
                        }
                    }
                    if (frames.isEmpty()) {
                        continue;
                    }
                    JsonObject animator = new JsonObject();
                    animator.addProperty("name", "bone");
                    animator.addProperty("type", "bone");
                    animator.add("keyframes", frames);
                    animators.add(boneUuids.get(bone), animator);
                }
            }
            written.add("animators", animators);
            out.add(written);
        }
        return out;
    }

    private static JsonObject keyframe(String channel, JsonObject source, int index) {
        JsonObject out = new JsonObject();
        out.addProperty("channel", channel);
        JsonArray value = array(source, "value");
        JsonObject point = new JsonObject();
        point.addProperty("x", value.size() > 0 ? value.get(0).getAsFloat() : 0f);
        point.addProperty("y", value.size() > 1 ? value.get(1).getAsFloat() : 0f);
        point.addProperty("z", value.size() > 2 ? value.get(2).getAsFloat() : 0f);
        JsonArray points = new JsonArray();
        points.add(point);
        out.add("data_points", points);
        out.addProperty("uuid", uuid("kf", index));
        out.addProperty("time", floatOf(source, "time", 0f));
        out.addProperty("color", -1);
        // BbModel maps Blockbench's names onto two: `catmullrom` and `smooth`
        // both mean smooth, `step` means step, and anything else is linear.
        // `catmullrom` is what Blockbench itself writes for a smooth keyframe.
        String interpolation = string(source, "interpolation");
        out.addProperty("interpolation",
                "smooth".equals(interpolation) ? "catmullrom"
                        : "step".equals(interpolation) ? "step" : "linear");
        return out;
    }

    /** {@code g:3} is bone three. Anything else is not a bone at all. */
    private static Integer boneIndex(String key) {
        if (key == null || !key.startsWith("g:")) {
            return null;
        }
        try {
            return Integer.parseInt(key.substring(2));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A face's {@code #key}, as the slot index it names. */
    private static int slotOf(String reference, List<String> slots) {
        if (reference == null) {
            return -1;
        }
        String key = reference.startsWith("#") ? reference.substring(1) : reference;
        return slots.indexOf(key);
    }

    // ---- the check ----------------------------------------------------------

    /**
     * Whether what was written reads back as what was asked for.
     *
     * <p>Compared after both sides are put through {@link #canonical}, which
     * removes the two differences that are expected and mean nothing: the
     * texture map (a project names its slots by index and the model may name
     * them anything, so only the COUNT is comparable) and float noise.
     */
    private static boolean reads(byte[] written, JsonObject model, List<String> slots,
                                 String namespace, String name) {
        Optional<BbModel.Converted> converted = BbModel.convert(written, namespace, name);
        if (converted.isEmpty()) {
            return false;
        }
        return same(canonical(model, slots), canonical(converted.get().model(),
                slotKeys(converted.get().model())));
    }

    /**
     * A model reduced to what both sides can be expected to agree about.
     *
     * <p>Face texture references become their slot INDEX, because the key a
     * model uses is arbitrary and a re-read project always calls slot two
     * {@code #2}. The texture map itself becomes a count for the same reason.
     */
    private static JsonObject canonical(JsonObject model, List<String> slots) {
        JsonObject out = new JsonObject();
        out.addProperty("textures", slots.size());

        JsonArray elements = new JsonArray();
        for (JsonElement raw : array(model, "elements")) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject element = raw.getAsJsonObject().deepCopy();
            // Cosmetic and carried by a straight copy, so comparing it would
            // only ever fail on the fallback this class supplies for a cube
            // that had no name at all.
            element.remove("name");
            // A rotation of zero degrees is no rotation: BbModel drops one on
            // the way out, so an input that carried it would never match.
            JsonObject rotation = object(element, "rotation");
            if (rotation != null && Math.abs(floatOf(rotation, "angle", 0f)) < 0.001f) {
                element.remove("rotation");
            }
            JsonObject faces = object(element, "faces");
            if (faces != null) {
                for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject face = entry.getValue().getAsJsonObject();
                    face.addProperty("texture", slotOf(string(face, "texture"), slots));
                }
            }
            elements.add(element);
        }
        out.add("elements", elements);
        if (model.has("groups")) {
            out.add("groups", model.get("groups"));
        }
        if (model.has("animations")) {
            out.add("animations", model.get("animations"));
        }
        return out;
    }

    /**
     * Deep equality with a tolerance on numbers.
     *
     * <p>A key present on one side and absent on the other is a difference,
     * except where the absent one is the default the reader would supply —
     * which is why {@code triggers} is skipped: it is derived from the loop
     * mode on the way out and never round-trips through a project.
     */
    private static boolean same(JsonElement left, JsonElement right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isJsonObject() && right.isJsonObject()) {
            JsonObject a = left.getAsJsonObject();
            JsonObject b = right.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : a.entrySet()) {
                if (skipped(entry.getKey())) {
                    continue;
                }
                if (!same(entry.getValue(), b.get(entry.getKey()))) {
                    return false;
                }
            }
            for (Map.Entry<String, JsonElement> entry : b.entrySet()) {
                if (skipped(entry.getKey()) || a.has(entry.getKey())) {
                    continue;
                }
                return false;
            }
            return true;
        }
        if (left.isJsonArray() && right.isJsonArray()) {
            JsonArray a = left.getAsJsonArray();
            JsonArray b = right.getAsJsonArray();
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!same(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isJsonPrimitive() && right.isJsonPrimitive()) {
            JsonPrimitive a = left.getAsJsonPrimitive();
            JsonPrimitive b = right.getAsJsonPrimitive();
            if (a.isNumber() && b.isNumber()) {
                return Math.abs(a.getAsDouble() - b.getAsDouble()) <= EPSILON;
            }
            return a.equals(b);
        }
        return left.equals(right);
    }

    /**
     * Fields a project cannot carry and that the reader therefore invents.
     *
     * <p>{@code triggers} is derived from the loop mode — a project has no
     * notion of one — so the model going in has whatever studio stored and the
     * model coming back has the derivation. Comparing them would fail every
     * animated model, which is the case this whole class exists for.
     */
    private static boolean skipped(String key) {
        return "triggers".equals(key);
    }

    // ---- json, gently -------------------------------------------------------

    /**
     * A stable uuid.
     *
     * <p>Derived from the kind and the index rather than random, so writing the
     * same model twice produces the same file. That is worth having for the
     * same reason the pack builder's zips are deterministic: a file that
     * changes when nothing changed is a diff somebody has to read.
     */
    private static String uuid(String kind, int index) {
        long hash = 1125899906842597L;
        for (int i = 0; i < kind.length(); i++) {
            hash = 31 * hash + kind.charAt(i);
        }
        hash = 31 * hash + index;
        String hex = String.format(Locale.ROOT, "%016x%016x", hash, hash * 31 + index);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-4" + hex.substring(13, 16)
                + "-a" + hex.substring(17, 20) + "-" + hex.substring(20, 32);
    }

    private static JsonObject parse(byte[] source) {
        if (source == null) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

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

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static int intOf(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return intValue(value, fallback);
    }

    private static int intValue(JsonElement value, int fallback) {
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float floatOf(JsonObject object, String key, float fallback) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
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
            return new float[] {value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat()};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonArray numbers(float[] values) {
        JsonArray out = new JsonArray();
        for (float value : values == null ? new float[] {0f, 0f, 0f} : values) {
            out.add(round(value));
        }
        return out;
    }

    private static float round(float value) {
        return Math.round(value * 100f) / 100f;
    }

    /** Every slot's PNG, in slot order, from a lookup by texture reference. */
    static List<byte[]> texturesInSlotOrder(JsonObject model, Map<String, byte[]> byRef) {
        List<byte[]> out = new ArrayList<>();
        JsonObject textures = object(model, "textures");
        Map<String, String> refs = new LinkedHashMap<>();
        if (textures != null) {
            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    refs.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        for (String key : slotKeys(model)) {
            out.add(byRef.get(refs.get(key)));
        }
        return out;
    }
}
