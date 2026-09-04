package ai.resourcepack.engine.core.edit;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.VehicleEmitter;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.core.item.BbModel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning {@code /rp edit <kind> <id>} into something to send.
 *
 * <p>Everything is read off disk here rather than out of the registry, and
 * that is the decision this class is really about. The registry holds what the
 * loader made of a file — defaults filled in, ids validated, a
 * {@code .bbmodel} already converted — and what has to go back is the
 * <em>file</em>. So the item and the vehicle come from the registry (they are
 * how an author names the thing they want to edit) and every byte comes from
 * the folder.
 *
 * <p>Free of Bukkit, so the whole of this is testable against a temp
 * directory, which matters here more than usual: getting a path wrong means
 * writing over the wrong file on somebody's server.
 */
final class EditTargets {

    /**
     * Where a texture the author creates in the editor is written.
     *
     * <p>Beside the item's own art, which is the only place a new texture for
     * that item sensibly goes. Studio derives the reference from this, so the
     * two spellings of "where item art lives" have to be the same one.
     */
    private static final String NEW_TEXTURE_DIR = "assets/textures/item";

    /**
     * Where a Blockbench project's embedded art is named on the wire.
     *
     * <p>A project keeps its textures <em>inside itself</em>, so they have no
     * file of their own and the round trip has nowhere to put a path. This is
     * that nowhere, spelled so it is obviously not a real folder: studio
     * treats a path as an opaque string, sends it back unchanged, and
     * {@link EditApply} puts anything under this prefix back into the project
     * rather than onto the disk.
     */
    static final String PROJECT_TEXTURE_DIR = ".project-textures";

    private EditTargets() {
    }

    /** The item's own sprite — {@code assets/textures/item/ruby.png}. */
    static EditTarget texture(Path contentRoot, ItemInfo item, String client, String version)
            throws EditException {
        Path packDir = packDir(contentRoot, item.id());
        String namespace = item.id().namespace();

        String relative = textureFile(packDir, item.texture());
        if (relative == null) {
            throw new EditException("No texture at assets/textures/" + item.texture()
                    + ".png. That is what " + item.id() + " is drawn with.");
        }

        EditWire.Open request = base("texture", item.id(), client, version);
        EditWire.Texture texture = new EditWire.Texture();
        texture.ref = namespace + ":" + item.texture();
        texture.path = relative;
        texture.png = base64(packDir.resolve(relative));
        texture.primary = Boolean.TRUE;
        request.textures = List.of(texture);

        Map<String, String> refPaths = new LinkedHashMap<>();
        refPaths.put(texture.ref, relative);
        return new EditTarget(item.id(), packDir, namespace, request, null, refPaths, null, null, null);
    }

    /** The 3D model an item wears, and every texture of ours it names. */
    static EditTarget model(Path contentRoot, ItemInfo item, String client, String version)
            throws EditException {
        EditWire.Open request = base("model", item.id(), client, version);
        Path packDir = packDir(contentRoot, item.id());
        EditTarget target = geometry(packDir, item, request);
        if (target == null) {
            throw new EditException(item.id() + " is a flat sprite, not a 3D model. "
                    + "Try /rp edit texture " + item.id() + " instead.");
        }
        return target;
    }

    /**
     * A vehicle: its own settings, plus the model it wears so the seats can be
     * placed against real bodywork.
     *
     * <p>The geometry is read-only in that editor, which is why a vehicle whose
     * model cannot be found is still worth opening — the seat coordinates are
     * the point, and an author would rather move them blind than not at all.
     * Everything else refuses when its file is missing; this one warns.
     */
    static EditTarget vehicle(Path contentRoot, VehicleInfo vehicle, ItemInfo model,
                              String client, String version) throws EditException {
        Path packDir = packDir(contentRoot, vehicle.id());
        EditWire.Open request = base("vehicle", vehicle.id(), client, version);
        request.vehicle = wire(vehicle);

        Path file = vehicleFile(packDir, vehicle.id().path());
        if (file == null) {
            throw new EditException("Could not find " + vehicle.id().path()
                    + " in any file under " + packDir.getFileName() + "/vehicles/.");
        }

        EditTarget geometry = model == null ? null : geometry(packDir, model, request);
        return new EditTarget(vehicle.id(), packDir, vehicle.id().namespace(), request,
                geometry == null ? null : geometry.project,
                geometry == null ? Map.of() : geometry.refPaths,
                file, vehicle.id().path(), vehicle.model().map(ContentId::toString).orElse(null));
    }

    // ---- the shared half: a model and the art it names ----------------------

    /**
     * Fills in {@code request}'s model and textures from the item's own files.
     *
     * @return the target, or null when the item has no 3D model at all
     */
    private static EditTarget geometry(Path packDir, ItemInfo item, EditWire.Open request)
            throws EditException {
        String name = item.model().orElse(null);
        if (name == null) {
            return null;
        }
        String namespace = item.id().namespace();
        Map<String, String> refPaths = new LinkedHashMap<>();

        String projectPath = source(packDir, "models/" + name + ".bbmodel");
        if (projectPath != null) {
            byte[] project = read(packDir.resolve(projectPath));
            BbModel.Converted converted = BbModel.convert(project, namespace, name)
                    .orElseThrow(() -> new EditException(projectPath
                            + " has no cube geometry in it. A mesh cannot become a Minecraft "
                            + "model; convert it to cubes in Blockbench first."));

            EditWire.Model model = new EditWire.Model();
            model.path = projectPath;
            model.format = "bbmodel";
            model.json = converted.model();
            request.model = model;

            // A project's art lives inside it, so each texture is named under
            // the virtual folder above rather than a real one. The slot name
            // is what BbModel calls it, which is what the model's own refs
            // already say.
            List<EditWire.Texture> textures = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : converted.textures().entrySet()) {
                EditWire.Texture texture = new EditWire.Texture();
                texture.ref = namespace + ":item/" + entry.getKey();
                texture.path = PROJECT_TEXTURE_DIR + "/" + entry.getKey() + ".png";
                texture.png = Base64.getEncoder().encodeToString(entry.getValue());
                textures.add(texture);
                refPaths.put(texture.ref, texture.path);
            }
            request.textures = textures;
            request.texturePrefix = PROJECT_TEXTURE_DIR;
            return new EditTarget(item.id(), packDir, namespace, request, project, refPaths, null, null, null);
        }

        String jsonPath = source(packDir, "models/" + name + ".json");
        if (jsonPath == null) {
            throw new EditException("No model at assets/models/" + name + ".bbmodel or "
                    + "assets/models/" + name + ".json, which is what " + item.id() + " wears.");
        }
        JsonObject json = parse(read(packDir.resolve(jsonPath)), jsonPath);

        EditWire.Model model = new EditWire.Model();
        model.path = jsonPath;
        model.format = "json";
        model.json = json;
        request.model = model;

        List<EditWire.Texture> textures = new ArrayList<>();
        JsonElement rawTextures = json.get("textures");
        if (rawTextures != null && rawTextures.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : rawTextures.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String ref = entry.getValue().getAsString();
                if (refPaths.containsKey(ref)) {
                    continue;
                }
                String relative = textureFile(packDir, ourTexturePath(namespace, ref));
                if (relative == null) {
                    // A vanilla texture, another pack's, or one this pack has
                    // not got. All three are the editor's problem to draw (it
                    // resolves vanilla itself and shows the rest as missing),
                    // and none of them is a file this session may write.
                    continue;
                }
                EditWire.Texture texture = new EditWire.Texture();
                texture.ref = ref;
                texture.path = relative;
                texture.png = base64(packDir.resolve(relative));
                textures.add(texture);
                refPaths.put(ref, relative);
            }
        }
        request.textures = textures;
        request.texturePrefix = NEW_TEXTURE_DIR;
        return new EditTarget(item.id(), packDir, namespace, request, null, refPaths, null, null, null);
    }

    // ---- naming -------------------------------------------------------------

    /**
     * The path inside this pack a texture reference names, or null if it names
     * somebody else's.
     *
     * <p>A bare reference is rewritten into the pack's own namespace by the
     * loader, so it is ours. One carrying our namespace is ours. Anything else
     * — {@code minecraft:block/stone}, another pack's id — is not, and the
     * distinction matters because this is what decides which files a session
     * may read and later overwrite.
     */
    static String ourTexturePath(String namespace, String ref) {
        if (ref == null || ref.isEmpty() || ref.startsWith("#")) {
            return null;
        }
        int colon = ref.indexOf(':');
        if (colon < 0) {
            return ref;
        }
        return ref.substring(0, colon).equals(namespace) ? ref.substring(colon + 1) : null;
    }

    /**
     * Where a texture reference's file goes, for one the author has just
     * created in the editor.
     *
     * <p>Derived rather than sent, because the far end derived it the same way
     * from the prefix this class gave it. The two spellings have to agree or a
     * new texture is written where nothing looks for it.
     */
    static String newTexturePath(String namespace, String ref) {
        String path = ourTexturePath(namespace, ref);
        return path == null ? null : "assets/textures/" + path + ".png";
    }

    // ---- the vehicle's own settings ----------------------------------------

    /**
     * A parsed vehicle, in the shape the wire carries.
     *
     * <p>Package-private rather than private so the round trip can be tested:
     * this is the outward half of a conversion whose inward half is
     * {@link VehicleYaml}, and the property worth pinning is that writing what
     * this produces and parsing it again gives the same vehicle.
     */
    static EditWire.Vehicle wire(VehicleInfo info) {
        EditWire.Vehicle out = new EditWire.Vehicle();
        out.name = info.name().orElse("");
        out.medium = info.medium().name().toLowerCase(java.util.Locale.ROOT);
        out.weight = info.weight();
        out.speed = info.speed();
        out.acceleration = info.acceleration();
        out.turnSpeed = info.turnSpeed();
        out.hitboxWidth = info.hitbox().width();
        out.hitboxHeight = info.hitbox().height();
        out.hitboxLength = info.hitbox().length();

        List<EditWire.Seat> seats = new ArrayList<>();
        for (VehicleSeat seat : info.seats()) {
            EditWire.Seat out2 = new EditWire.Seat();
            out2.role = seat.role().key();
            out2.pose = seat.pose().key();
            out2.x = seat.x();
            out2.y = seat.y();
            out2.z = seat.z();
            out2.yaw = seat.yaw();
            out2.name = seat.name().orElse("");
            out2.animations = states(seat.animations());
            seats.add(out2);
        }
        out.seats = seats;
        out.animations = states(info.animations());

        List<EditWire.Emitter> emitters = new ArrayList<>();
        for (VehicleEmitter emitter : info.emitters()) {
            EditWire.Emitter out2 = new EditWire.Emitter();
            out2.effect = emitter.effect();
            List<String> names = new ArrayList<>();
            for (VehicleState state : emitter.states()) {
                names.add(state.key());
            }
            out2.states = names;
            out2.x = emitter.x();
            out2.y = emitter.y();
            out2.z = emitter.z();
            out2.count = emitter.count();
            out2.spread = emitter.spread();
            out2.speed = emitter.speed();
            out2.interval = emitter.interval();
            out2.enabled = emitter.enabled();
            out2.size = emitter.size();
            emitter.color().ifPresent(color -> out2.color = color);
            emitters.add(out2);
        }
        out.particles = emitters;
        return out;
    }

    private static Map<String, String> states(Map<VehicleState, String> source) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<VehicleState, String> entry : source.entrySet()) {
            out.put(entry.getKey().key(), entry.getValue());
        }
        return out;
    }

    // ---- files --------------------------------------------------------------

    /**
     * The file under {@code vehicles/} holding a top-level key.
     *
     * <p>Found by looking rather than remembered, because the loader keeps a
     * definition's origin only for the length of one load and this may run
     * hours later. Looking is also what makes the answer right after somebody
     * has moved an entry between files, which is a thing people do.
     */
    static Path vehicleFile(Path packDir, String key) {
        Path folder = packDir.resolve("vehicles");
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            List<Path> candidates = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .forEach(candidates::add);
            for (Path candidate : candidates) {
                if (YamlBlocks.find(Files.readString(candidate, StandardCharsets.UTF_8), key) != null) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * A source file under {@code assets/}, or at the pack root.
     *
     * <p>Ours is the documented layout and is tried first; an ItemsAdder pack
     * keeps {@code models/} and {@code textures/} beside its configs, and a
     * pack copied straight out of one should not need its folders moved before
     * this works. The same order {@code ItemAssets} reads them in.
     */
    private static String source(Path packDir, String path) {
        String ours = "assets/" + path;
        if (Files.isRegularFile(packDir.resolve(ours))) {
            return ours;
        }
        return Files.isRegularFile(packDir.resolve(path)) ? path : null;
    }

    /** {@code assets/textures/<path>.png}, wherever it actually is. */
    private static String textureFile(Path packDir, String texturePath) {
        return texturePath == null ? null : source(packDir, "textures/" + texturePath + ".png");
    }

    private static Path packDir(Path contentRoot, ContentId id) throws EditException {
        Path packDir = contentRoot.resolve(id.namespace());
        if (!Files.isDirectory(packDir)) {
            throw new EditException(id.namespace() + " is not a folder in your content directory. "
                    + "Only content you wrote by hand can be edited this way.");
        }
        return packDir;
    }

    private static EditWire.Open base(String kind, ContentId id, String client, String version) {
        EditWire.Open request = new EditWire.Open();
        request.client = client;
        request.kind = kind;
        request.label = id.toString();
        request.minecraftVersion = version;
        return request;
    }

    private static byte[] read(Path path) throws EditException {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new EditException("Could not read " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private static String base64(Path path) throws EditException {
        return Base64.getEncoder().encodeToString(read(path));
    }

    private static JsonObject parse(byte[] bytes, String path) throws EditException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new EditException(path + " is not a model file.");
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new EditException(path + " is not valid JSON.");
        }
    }
}
