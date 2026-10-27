package ai.resourcepack.engine.core.armor3d;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A set read from a {@code .bbmodel}, turned into the models a pack carries
 * and the {@link Armor3dSet} that wears them.
 *
 * <p><strong>A port of Studio's armour pack writer, and it has to stay
 * one.</strong> A set built here and the same set pushed from Studio must put
 * every part in the same place on the body, so the arithmetic below is
 * Studio's line for line: the helmet built to the game's head-item transform,
 * one model per bone for the cubes a block model can hold as drawn, one per
 * group of cubes turned in a way it cannot (carrying the turn on the display,
 * the free-rotation trick), the shrink that keeps a model inside the format's
 * bounds, and an inventory icon per piece.
 *
 * <p><strong>It also does Studio's shader rendering</strong>, for the body
 * pieces: the set is encoded into equipment layers by
 * {@link Armor3dShaderEncoder} (after {@link Armor3dSeparation}), and clients
 * inside one of {@link Armor3dShaders#RANGES} draw it themselves, on their own
 * limbs, with no displays. Everybody else is shown the displays, unless the
 * set turned them off ({@code displays: false}), in which case they see only
 * the helmet. The helmet is the one difference from Studio's shader path: it
 * stays the game's head item on every client rather than wearing an equipment
 * asset too, so it is never missing for anybody, at the price of a free turn
 * being drawn at its nearest legal angle.
 *
 * <p>Where it differs from Studio is only in naming. Studio's pack owns
 * vanilla paper's item definition and dispatches every model string from it;
 * the engine's pack cannot own that file (a pushed pack does), so each model
 * here also gets an item definition of its own, under the set's namespace,
 * and the stacks name it with {@code item_model}. See
 * {@link Armor3dSet#itemModel}.
 *
 * <p>Pure: no Bukkit, no disk. A test can bake a set and read the numbers.
 */
public final class Armor3dBaker {

    /**
     * A bone of the body: the piece it belongs to and where the player's limb
     * it is glued to turns. Pivots are the game's own (the humanoid model's
     * part offsets, in pixels in the wearer's frame); the names are
     * MythicArmors'.
     */
    public record Bone(Armor3dSet.Piece piece, double[] pivot) {
    }

    private static final double[] HEAD = {0, 24, 0};
    private static final double[] BODY = {0, 24, 0};
    private static final double[] RIGHT_ARM = {-5, 22, 0};
    private static final double[] LEFT_ARM = {5, 22, 0};
    private static final double[] RIGHT_LEG = {-1.9, 12, 0};
    private static final double[] LEFT_LEG = {1.9, 12, 0};

    /** The nine bones, in the order Studio lists them. */
    public static final Map<String, Bone> BONES = bones();

    private static Map<String, Bone> bones() {
        Map<String, Bone> map = new LinkedHashMap<>();
        map.put("head", new Bone(Armor3dSet.Piece.HELMET, HEAD));
        map.put("body", new Bone(Armor3dSet.Piece.CHESTPLATE, BODY));
        map.put("right_arm", new Bone(Armor3dSet.Piece.CHESTPLATE, RIGHT_ARM));
        map.put("left_arm", new Bone(Armor3dSet.Piece.CHESTPLATE, LEFT_ARM));
        // The waist rides the body and a foot its leg, as in the game.
        map.put("waist", new Bone(Armor3dSet.Piece.LEGGINGS, BODY));
        map.put("right_leg", new Bone(Armor3dSet.Piece.LEGGINGS, RIGHT_LEG));
        map.put("left_leg", new Bone(Armor3dSet.Piece.LEGGINGS, LEFT_LEG));
        map.put("right_foot", new Bone(Armor3dSet.Piece.BOOTS, RIGHT_LEG));
        map.put("left_foot", new Bone(Armor3dSet.Piece.BOOTS, LEFT_LEG));
        return Collections.unmodifiableMap(map);
    }

    /**
     * Where a helmet's elements go so the game draws them on the head.
     *
     * <p>{@code CustomHeadLayer} puts a head item at the head's centre, turned
     * 180 degrees and scaled by 0.625 with Y and Z flipped, and then applies
     * the model's {@code head} transform. Worked through, a {@code head} of
     * {turned 180 about Y, scale 1.6} draws model pixel (x + 8, y - 20, z + 8)
     * exactly on wearer-frame pixel (x, y, z): one pixel per pixel, standing
     * on the neck at y = 24.
     */
    static final double[] HELMET_SHIFT = {8, -20, 8};
    static final double HELMET_HEAD_SCALE = 1.6;

    /** The only angles a block model element may turn by, on one axis. */
    private static final double[] SNAP_ANGLES = {-45, -22.5, 0, 22.5, 45};

    private static final Gson GSON = new Gson();

    private Armor3dBaker() {
    }

    /**
     * A baked set.
     *
     * @param set           what {@code /rp armor} and the API wear
     * @param files         every file it puts in the base pack, by zip path, sorted
     * @param overlayFiles  what goes into each shader range's overlay besides
     *                      the shaders: the equipment assets, by path relative
     *                      to the overlay directory. Empty when the set could
     *                      not be encoded
     * @param shaderProblem why the set is not drawn by shaders, or null
     */
    public record Baked(Armor3dSet set, String namespace, Map<String, byte[]> files,
                        Map<String, byte[]> overlayFiles, String shaderProblem) {

        public Baked(Armor3dSet set, String namespace, Map<String, byte[]> files) {
            this(set, namespace, files, Map.of(), null);
        }
    }

    /**
     * Bakes one set.
     *
     * @param namespace the pack's namespace, which owns every file written
     * @param key       the set's path within it; the set's id is
     *                  {@code namespace:key}
     * @param name      what players are told it is called, or null for the id
     */
    public static Baked bake(String namespace, String key, String name, Armor3dBlockbench.Source source) {
        return bake(namespace, key, name, source, true);
    }

    /**
     * Bakes one set.
     *
     * @param displays whether viewers whose client does not draw the set get
     *                 display entities for its body pieces. Off, they see the
     *                 helmet and nothing else
     */
    public static Baked bake(String namespace, String key, String name, Armor3dBlockbench.Source source,
                             boolean displays) {
        return bake(namespace, key, name, source, displays, false);
    }

    /**
     * Bakes one set.
     *
     * @param displays     see {@link #bake(String, String, String, Armor3dBlockbench.Source, boolean)}
     * @param shaderHelmet whether the helmet is drawn by the client's shaders
     *                     like the body, rather than by the game as a head
     *                     item. It can then be anything the body can be (any
     *                     turn, mesh faces), and clients outside the shader
     *                     ranges see no helmet at all
     */
    public static Baked bake(String namespace, String key, String name, Armor3dBlockbench.Source source,
                             boolean displays, boolean shaderHelmet) {
        String setId = namespace + ":" + key;
        Map<String, byte[]> files = new TreeMap<>();

        // The textures, one per image the project embedded.
        List<String> textureRefs = new ArrayList<>();
        int count = source.textures().size();
        for (int i = 0; i < count; i++) {
            String file = "item/armor3d/" + key + (count == 1 ? "" : "_" + i);
            textureRefs.add(namespace + ":" + file);
            files.put("assets/" + namespace + "/textures/" + file + ".png", source.textures().get(i));
        }
        JsonObject textures = new JsonObject();
        for (int i = 0; i < textureRefs.size(); i++) {
            textures.addProperty(String.valueOf(i), textureRefs.get(i));
        }
        textures.addProperty("particle", textureRefs.get(0));

        Map<String, JsonObject> models = new LinkedHashMap<>();
        Map<Armor3dSet.Piece, Armor3dSet.Worn> pieces = new EnumMap<>(Armor3dSet.Piece.class);

        for (Armor3dSet.Piece piece : Armor3dSet.Piece.values()) {
            List<Armor3dBlockbench.Cube> own = new ArrayList<>();
            for (Armor3dBlockbench.Cube cube : source.cubes()) {
                if (cube.piece() == piece && BONES.containsKey(cube.bone())) {
                    own.add(cube);
                }
            }
            boolean meshed = false;
            for (Armor3dBlockbench.Quad quad : source.quads()) {
                meshed |= quad.piece() == piece && BONES.containsKey(quad.bone());
            }
            if (own.isEmpty() && !meshed) {
                continue;
            }

            if (piece == Armor3dSet.Piece.HELMET) {
                // Drawn by the game on the head, so every cube is in this one
                // model and a free turn gets its nearest legal angle.
                double[] anchor = sub(new double[]{8, 8, 8}, HELMET_SHIFT);
                List<Element> elements = new ArrayList<>();
                for (Armor3dBlockbench.Cube cube : own) {
                    elements.add(element(cube, anchor, Turn.APPROXIMATE));
                }
                double k = fitBounds(elements);
                // A shader-drawn helmet is the equipment layer; the item is
                // its icon only, scaled to nothing on the head in case a
                // client draws the head item beside the layer.
                double head = shaderHelmet ? 0 : HELMET_HEAD_SCALE / k;
                JsonObject display = elements.isEmpty() ? new JsonObject() : itemDisplay(elements);
                JsonObject headTransform = new JsonObject();
                headTransform.add("rotation", array(0, 180, 0));
                headTransform.add("translation", array(0, 0, 0));
                headTransform.add("scale", array(r4(head), r4(head), r4(head)));
                display.add("head", headTransform);
                models.put(piece.wire(), model(textures, elements, display));
                // No displays: the game draws it, on every client.
                pieces.put(piece, new Armor3dSet.Worn(piece, modelString(setId, piece.wire()), List.of(), null));
                continue;
            }

            // The icon: the whole piece standing, in one model, for an inventory.
            List<Element> icon = new ArrayList<>();
            for (Armor3dBlockbench.Cube cube : own) {
                icon.add(element(cube, new double[]{0, 12, 0}, Turn.APPROXIMATE));
            }
            fitBounds(icon);
            models.put(piece.wire(), model(textures, icon, icon.isEmpty() ? null : itemDisplay(icon)));

            // The displays: per bone, one model of everything the format can
            // hold as it is, and one per group of cubes turned in a way it
            // cannot.
            List<Armor3dSet.Part> parts = new ArrayList<>();
            Set<String> bones = new LinkedHashSet<>();
            for (Armor3dBlockbench.Cube cube : own) {
                bones.add(cube.bone());
            }
            for (String bone : bones) {
                List<Armor3dBlockbench.Cube> square = new ArrayList<>();
                Map<String, List<Armor3dBlockbench.Cube>> turned = new LinkedHashMap<>();
                for (Armor3dBlockbench.Cube cube : own) {
                    if (!cube.bone().equals(bone)) {
                        continue;
                    }
                    if (isLegal(cube.rotation())) {
                        square.add(cube);
                    } else {
                        turned.computeIfAbsent(key(cube.rotation()) + "|" + key(cube.origin()),
                                k -> new ArrayList<>()).add(cube);
                    }
                }
                double[] pivot = BONES.get(bone).pivot();
                if (!square.isEmpty()) {
                    String model = piece.wire() + "." + bone;
                    List<Element> elements = new ArrayList<>();
                    for (Armor3dBlockbench.Cube cube : square) {
                        elements.add(element(cube, pivot, Turn.KEEP));
                    }
                    double k = fitBounds(elements);
                    if (displays) {
                        models.put(model, model(textures, elements, null));
                    }
                    parts.add(new Armor3dSet.Part(bone, modelString(setId, model), floats(pivot), null,
                            k != 1 ? (float) r4(k) : 1f));
                }
                int n = 0;
                for (List<Armor3dBlockbench.Cube> group : turned.values()) {
                    double[] origin = group.get(0).origin();
                    String model = piece.wire() + "." + bone + "." + n++;
                    List<Element> elements = new ArrayList<>();
                    for (Armor3dBlockbench.Cube cube : group) {
                        elements.add(element(cube, origin, Turn.SQUARE));
                    }
                    double k = fitBounds(elements);
                    if (displays) {
                        models.put(model, model(textures, elements, null));
                    }
                    parts.add(new Armor3dSet.Part(bone, modelString(setId, model), floats(r4(origin)),
                            floats(r4(group.get(0).rotation())), k != 1 ? (float) r4(k) : 1f));
                }
            }
            pieces.put(piece, new Armor3dSet.Worn(piece, modelString(setId, piece.wire()),
                    displays ? List.copyOf(parts) : List.of(), null));
        }

        // The shader-drawn set. One that cannot be encoded is still a set: it
        // is shown by displays (or, with them off, by its helmet), and the
        // reason is said when the folder is read.
        Map<String, byte[]> overlayFiles = Map.of();
        String shaderProblem = null;
        List<int[]> shaderProtocols = List.of();
        try {
            Armor3dShaderEncoder.Encoded encoded = encodeShaderSet(namespace, key, source, shaderHelmet);
            files.putAll(encoded.baseFiles());
            overlayFiles = encoded.overlayFiles();
            shaderProtocols = Armor3dShaders.protocols();
            for (Map.Entry<Armor3dSet.Piece, String> asset : encoded.equipment().entrySet()) {
                Armor3dSet.Worn worn = pieces.get(asset.getKey());
                if (worn != null && (shaderHelmet || asset.getKey() != Armor3dSet.Piece.HELMET)) {
                    pieces.put(asset.getKey(), new Armor3dSet.Worn(worn.piece(), worn.item(), worn.parts(),
                            asset.getValue()));
                }
            }
        } catch (IllegalArgumentException e) {
            shaderProblem = e.getMessage();
        }

        for (Map.Entry<String, JsonObject> model : models.entrySet()) {
            String path = "item/armor3d/" + key + "/" + model.getKey();
            files.put("assets/" + namespace + "/models/" + path + ".json", json(model.getValue()));
            // The item definition the stacks name with item_model; see the class note.
            JsonObject definition = new JsonObject();
            JsonObject inner = new JsonObject();
            inner.addProperty("type", "minecraft:model");
            inner.addProperty("model", namespace + ":" + path);
            definition.add("model", inner);
            files.put("assets/" + namespace + "/items/armor3d/" + key + "/" + model.getKey() + ".json",
                    json(definition));
        }

        Armor3dSet set = new Armor3dSet(setId, name, pieces, shaderProtocols,
                namespace + ":armor3d/" + key + "/");
        return new Baked(set, namespace, Collections.unmodifiableMap(files),
                Collections.unmodifiableMap(new TreeMap<>(overlayFiles)), shaderProblem);
    }

    // ---- the shader-drawn set ------------------------------------------------

    /**
     * The body pieces as equipment layers: every image the project embedded
     * stacked into one atlas, the cubes separated as Studio separates a set,
     * the helmet left out (it is the game's head item, see the class note),
     * and the rest encoded.
     *
     * @throws IllegalArgumentException when they cannot be, with why
     */
    static Armor3dShaderEncoder.Encoded encodeShaderSet(String namespace, String key,
                                                        Armor3dBlockbench.Source source) {
        return encodeShaderSet(namespace, key, source, false);
    }

    /**
     * @param shaderHelmet whether the helmet is encoded too, rather than left
     *                     to the game as a head item
     */
    static Armor3dShaderEncoder.Encoded encodeShaderSet(String namespace, String key,
                                                        Armor3dBlockbench.Source source, boolean shaderHelmet) {
        // The atlas: each image under the last, at least 23 wide (the
        // encoder's header needs the room; the padding is never sampled).
        List<BufferedImage> images = new ArrayList<>();
        int width = 23;
        int height = 0;
        for (byte[] png : source.textures()) {
            BufferedImage image;
            try {
                image = ImageIO.read(new ByteArrayInputStream(png));
            } catch (IOException | RuntimeException e) {
                image = null;
            }
            if (image == null) {
                throw new IllegalArgumentException("one of its images could not be read");
            }
            images.add(image);
            width = Math.max(width, image.getWidth());
            height += image.getHeight();
        }
        if (height == 0) {
            throw new IllegalArgumentException("it has no images");
        }
        byte[] rgba = new byte[width * height * 4];
        int[] top = new int[images.size()];
        int y0 = 0;
        for (int t = 0; t < images.size(); t++) {
            BufferedImage image = images.get(t);
            top[t] = y0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int i = ((y0 + y) * width + x) * 4;
                    rgba[i] = (byte) (argb >> 16);
                    rgba[i + 1] = (byte) (argb >> 8);
                    rgba[i + 2] = (byte) argb;
                    rgba[i + 3] = (byte) (argb >>> 24);
                }
            }
            y0 += image.getHeight();
        }

        // Every cube on the body, separated together as Studio separates a
        // whole set, so a body piece lands where it would from Studio.
        List<Armor3dBlockbench.Cube> cubes = new ArrayList<>();
        List<Armor3dSeparation.Part> separated = new ArrayList<>();
        for (Armor3dBlockbench.Cube cube : source.cubes()) {
            if (!BONES.containsKey(cube.bone())) {
                continue;
            }
            cubes.add(cube);
            separated.add(new Armor3dSeparation.Part(cube.from().clone(), cube.to().clone(), cube.rotation(),
                    cube.origin(), cube.faces().keySet()));
        }
        Armor3dSeparation.separate(separated);

        List<Armor3dShaderEncoder.Part> parts = new ArrayList<>();
        for (int i = 0; i < cubes.size(); i++) {
            Armor3dBlockbench.Cube cube = cubes.get(i);
            if (cube.piece() == Armor3dSet.Piece.HELMET && !shaderHelmet) {
                continue;
            }
            Map<String, Armor3dShaderEncoder.FaceArt> faces = new LinkedHashMap<>();
            for (Map.Entry<String, Armor3dBlockbench.Face> entry : cube.faces().entrySet()) {
                Armor3dBlockbench.Face face = entry.getValue();
                if (face.texture() < 0 || face.texture() >= images.size()) {
                    continue;
                }
                BufferedImage image = images.get(face.texture());
                double sx = image.getWidth() / face.uvWidth();
                double sy = image.getHeight() / face.uvHeight();
                double oy = top[face.texture()];
                faces.put(entry.getKey(), new Armor3dShaderEncoder.FaceArt(new double[]{
                        face.uv()[0] * sx, oy + face.uv()[1] * sy, face.uv()[2] * sx, oy + face.uv()[3] * sy},
                        face.rotation()));
            }
            parts.add(new Armor3dShaderEncoder.Part(cube.bone(), separated.get(i).from(), separated.get(i).to(),
                    cube.rotation(), cube.origin(), faces));
        }
        // Mesh faces, as they are: the author keeps them out of one another's
        // planes, since the separation pass only knows boxes.
        List<Armor3dShaderEncoder.Quad> quads = new ArrayList<>();
        for (Armor3dBlockbench.Quad quad : source.quads()) {
            if (!BONES.containsKey(quad.bone()) || (quad.piece() == Armor3dSet.Piece.HELMET && !shaderHelmet)
                    || quad.texture() < 0 || quad.texture() >= images.size()) {
                continue;
            }
            BufferedImage image = images.get(quad.texture());
            double sx = image.getWidth() / quad.uvWidth();
            double sy = image.getHeight() / quad.uvHeight();
            double oy = top[quad.texture()];
            double[][] uvs = new double[4][];
            for (int i = 0; i < 4; i++) {
                uvs[i] = new double[]{quad.uvs()[i][0] * sx, oy + quad.uvs()[i][1] * sy};
            }
            quads.add(new Armor3dShaderEncoder.Quad(quad.bone(), quad.corners(), uvs));
        }
        if (parts.isEmpty() && quads.isEmpty()) {
            throw new IllegalArgumentException("it has nothing below the neck, which is all the shaders draw");
        }
        return Armor3dShaderEncoder.encode(namespace, "armor3d/" + key, parts, quads, width, height, rgba);
    }

    /** The string a model is dispatched by: {@code armor3d:<set>/<name>}. */
    static String modelString(String setId, String name) {
        return Armor3dItems.MODEL_PREFIX + setId + "/" + name;
    }

    // ---- the geometry ------------------------------------------------------

    /** What happens to a cube's own turn: kept (a legal one), dropped (a display carries it), or approximated. */
    enum Turn { KEEP, SQUARE, APPROXIMATE }

    /** A block model element, in progress. */
    static final class Element {
        double[] from;
        double[] to;
        /** Null, or {origin, axis, angle} as a legal element rotation. */
        double[] rotationOrigin;
        String axis;
        double angle;
        final Map<String, Armor3dBlockbench.Face> faces;

        Element(double[] from, double[] to, Map<String, Armor3dBlockbench.Face> faces) {
            this.from = from;
            this.to = to;
            this.faces = faces;
        }
    }

    /**
     * A cube as an element of a model whose (8, 8, 8) is {@code anchor}.
     */
    static Element element(Armor3dBlockbench.Cube cube, double[] anchor, Turn turn) {
        double[] shift = sub(new double[]{8, 8, 8}, anchor);
        Element el = new Element(add(cube.from(), shift), add(cube.to(), shift), cube.faces());
        double[] rotation = cube.rotation();
        if (turn != Turn.SQUARE && rotation != null && cube.origin() != null
                && (rotation[0] != 0 || rotation[1] != 0 || rotation[2] != 0)) {
            Legal legal = legalRotationFrom(cube.origin(), rotation);
            if (legal != null) {
                el.rotationOrigin = add(legal.origin, shift);
                el.axis = legal.axis;
                el.angle = legal.angle;
            }
        }
        return el;
    }

    /** Whether a turn is one the block model format can say as it is. */
    static boolean isLegal(double[] turn) {
        if (turn == null || (turn[0] == 0 && turn[1] == 0 && turn[2] == 0)) {
            return true;
        }
        int nonZero = 0;
        double value = 0;
        for (double v : turn) {
            if (v != 0) {
                nonZero++;
                value = v;
            }
        }
        return nonZero == 1 && (value == 22.5 || value == -22.5 || value == 45 || value == -45);
    }

    private record Legal(double[] origin, String axis, double angle) {
    }

    /**
     * The closest thing a block model can say to a free rotation: its largest
     * angle, on that angle's axis, snapped to one of the legal values. Null
     * when the snap lands on zero. Studio's {@code legalRotationFrom}.
     */
    private static Legal legalRotationFrom(double[] origin, double[] angles) {
        String[] axes = {"x", "y", "z"};
        String axis = "x";
        double best = 0;
        for (int i = 0; i < 3; i++) {
            double angle = normalizeDegrees(angles[i]);
            if (Math.abs(angle) > Math.abs(best)) {
                best = angle;
                axis = axes[i];
            }
        }
        double angle = SNAP_ANGLES[0];
        for (double candidate : SNAP_ANGLES) {
            if (Math.abs(candidate - best) < Math.abs(angle - best)) {
                angle = candidate;
            }
        }
        if (angle == 0) {
            return null;
        }
        return new Legal(origin.clone(), axis, angle);
    }

    /** Degrees folded into (-180, 180], to two decimals. */
    private static double normalizeDegrees(double deg) {
        double wrapped = ((((deg + 180) % 360) + 360) % 360) - 180;
        return Math.round((wrapped == -180 ? 180 : wrapped) * 100) / 100.0;
    }

    /**
     * Shrinks elements about (8, 8, 8) until they sit inside the format's
     * bounds, [-16, 32].
     *
     * @return the factor applied, 1 when nothing moved
     */
    static double fitBounds(List<Element> elements) {
        double reach = 0;
        for (Element el : elements) {
            for (double v : el.from) {
                reach = Math.max(reach, Math.abs(v - 8));
            }
            for (double v : el.to) {
                reach = Math.max(reach, Math.abs(v - 8));
            }
        }
        if (reach <= 24) {
            return 1;
        }
        double k = 24 / reach;
        for (Element el : elements) {
            el.from = squeeze(el.from, k);
            el.to = squeeze(el.to, k);
            if (el.rotationOrigin != null) {
                el.rotationOrigin = squeeze(el.rotationOrigin, k);
            }
        }
        return k;
    }

    private static double[] squeeze(double[] v, double k) {
        return new double[]{r4(8 + (v[0] - 8) * k), r4(8 + (v[1] - 8) * k), r4(8 + (v[2] - 8) * k)};
    }

    // ---- how an item is held and shown --------------------------------------

    /** {@code ItemTransform}'s rotation, XYZ in degrees, as a matrix applied to a vector: Z first. */
    static double[] rotate(double[] v, double[] deg) {
        double x = Math.toRadians(deg[0]);
        double y = Math.toRadians(deg[1]);
        double z = Math.toRadians(deg[2]);
        double a = v[0];
        double b = v[1];
        double c = v[2];
        double a1 = a * Math.cos(z) - b * Math.sin(z);
        double b1 = a * Math.sin(z) + b * Math.cos(z);
        double a2 = a1 * Math.cos(y) + c * Math.sin(y);
        double c1 = -a1 * Math.sin(y) + c * Math.cos(y);
        double b2 = b1 * Math.cos(x) - c1 * Math.sin(x);
        double c2 = b1 * Math.sin(x) + c1 * Math.cos(x);
        return new double[]{a2, b2, c2};
    }

    /**
     * A display transform that shows the model's bounding box centred and
     * {@code fill} pixels across. The translation undoes the centre after the
     * rotation and scale, because that is the order the game applies them in.
     */
    static JsonObject framed(List<Element> elements, double[] rotation, double fill, double[] nudge) {
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Element el : elements) {
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], Math.min(el.from[i], el.to[i]));
                hi[i] = Math.max(hi[i], Math.max(el.from[i], el.to[i]));
            }
        }
        double extent = Math.max(Math.max(hi[0] - lo[0], hi[1] - lo[1]), Math.max(hi[2] - lo[2], 1));
        double scale = Math.min(4, fill / extent);
        double[] centre = {(lo[0] + hi[0]) / 2 - 8, (lo[1] + hi[1]) / 2 - 8, (lo[2] + hi[2]) / 2 - 8};
        double[] moved = rotate(new double[]{centre[0] * scale, centre[1] * scale, centre[2] * scale}, rotation);
        JsonObject out = new JsonObject();
        out.add("rotation", array(rotation));
        double[] translation = new double[3];
        for (int i = 0; i < 3; i++) {
            translation[i] = r4(Math.max(-80, Math.min(80, -moved[i] + nudge[i])));
        }
        out.add("translation", array(translation));
        double s = r4(scale);
        out.add("scale", array(s, s, s));
        return out;
    }

    /** How a piece looks in an inventory, on the ground, in a frame and in a hand. */
    static JsonObject itemDisplay(List<Element> elements) {
        double[] none = {0, 0, 0};
        JsonObject display = new JsonObject();
        display.add("gui", framed(elements, new double[]{15, 30, 0}, 14.5, none));
        display.add("ground", framed(elements, new double[]{0, 0, 0}, 7, none));
        display.add("fixed", framed(elements, new double[]{0, 180, 0}, 12, none));
        display.add("thirdperson_righthand", framed(elements, new double[]{0, 0, 0}, 7, new double[]{0, 2, 1}));
        display.add("thirdperson_lefthand", framed(elements, new double[]{0, 0, 0}, 7, new double[]{0, 2, 1}));
        display.add("firstperson_righthand", framed(elements, new double[]{0, -20, 0}, 8, new double[]{1, 3, 0}));
        display.add("firstperson_lefthand", framed(elements, new double[]{0, 20, 0}, 8, new double[]{-1, 3, 0}));
        return display;
    }

    // ---- writing -----------------------------------------------------------

    private static JsonObject model(JsonObject textures, List<Element> elements, JsonObject display) {
        JsonObject model = new JsonObject();
        model.add("textures", textures.deepCopy());
        JsonArray out = new JsonArray();
        for (Element el : elements) {
            JsonObject element = new JsonObject();
            element.add("from", array(r4(el.from)));
            element.add("to", array(r4(el.to)));
            if (el.rotationOrigin != null) {
                JsonObject rotation = new JsonObject();
                rotation.add("origin", array(r4(el.rotationOrigin)));
                rotation.addProperty("axis", el.axis);
                rotation.add("angle", number(el.angle));
                element.add("rotation", rotation);
            }
            JsonObject faces = new JsonObject();
            for (Map.Entry<String, Armor3dBlockbench.Face> entry : el.faces.entrySet()) {
                Armor3dBlockbench.Face face = entry.getValue();
                JsonObject written = new JsonObject();
                written.add("uv", array(
                        r4(face.uv()[0] * 16 / face.uvWidth()), r4(face.uv()[1] * 16 / face.uvHeight()),
                        r4(face.uv()[2] * 16 / face.uvWidth()), r4(face.uv()[3] * 16 / face.uvHeight())));
                written.addProperty("texture", "#" + face.texture());
                if (face.rotation() != 0) {
                    written.addProperty("rotation", face.rotation());
                }
                faces.add(entry.getKey(), written);
            }
            element.add("faces", faces);
            out.add(element);
        }
        model.add("elements", out);
        if (display != null) {
            model.add("display", display);
        }
        return model;
    }

    private static byte[] json(JsonObject object) {
        return GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonArray array(double... values) {
        JsonArray out = new JsonArray();
        for (double value : values) {
            out.add(number(value));
        }
        return out;
    }

    /** A whole number written as one, and never a negative zero. */
    private static com.google.gson.JsonPrimitive number(double value) {
        if (value == 0) {
            return new com.google.gson.JsonPrimitive(0);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e9) {
            return new com.google.gson.JsonPrimitive((long) value);
        }
        return new com.google.gson.JsonPrimitive(value);
    }

    // ---- arithmetic ---------------------------------------------------------

    /** Four decimals, Studio's {@code r4}: JavaScript's {@code Math.round} rounds half up, as Java's does. */
    static double r4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    static double[] r4(double[] v) {
        return new double[]{r4(v[0]), r4(v[1]), r4(v[2])};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] floats(double[] v) {
        return new float[]{(float) v[0], (float) v[1], (float) v[2]};
    }

    private static String key(double[] v) {
        return v == null ? "" : v[0] + "," + v[1] + "," + v[2];
    }
}
