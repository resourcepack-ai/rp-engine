package ai.resourcepack.engine.core.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writing an edit back onto somebody's server.
 *
 * <p>This is the only class in the plugin that changes a file a human wrote,
 * so it is the one worth being suspicious of. Three rules, and all three are
 * refusals rather than best guesses:
 *
 * <ul>
 *   <li><strong>Nothing lands outside the pack folder.</strong> Every path is
 *       re-checked here even though the far end checked it too — it arrived
 *       over HTTP from a host named in a config file, and the far end's check
 *       is not this end's guarantee. See {@link EditPaths}.</li>
 *   <li><strong>Only the kinds of file a session is about.</strong> The model
 *       it was opened on, and PNGs under a textures folder. A pull that named
 *       {@code pack.yml} or a {@code .jar} is refused whole, because the only
 *       way to send one is for something to be badly wrong.</li>
 *   <li><strong>Nothing is deleted that was not sent.</strong> A texture the
 *       author deleted in the editor is removed, and the check is that this
 *       session brought that exact file in. Everything else stays.</li>
 * </ul>
 *
 * <p>Writes go to a scratch file beside the target and are moved into place,
 * so a crash or a full disk cannot leave half a model where a whole one was.
 */
final class EditApply {

    /** What happened, for the line printed back into chat. */
    static final class Applied {
        final List<String> written = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();

        int touched() {
            return written.size() + deleted.size();
        }
    }

    private EditApply() {
    }

    static Applied apply(EditTarget target, EditWire.Pull pull) throws EditException {
        Map<String, byte[]> files = decode(pull);
        Applied applied = new Applied();

        if (target.request.model != null) {
            model(target, files, applied);
        }
        if (target.vehicleFile != null && pull.vehicle != null) {
            vehicle(target, pull.vehicle, applied);
        }
        textures(target, files, applied);
        removals(target, pull, applied);

        if (applied.touched() == 0) {
            throw new EditException("The editor sent nothing this session may write.");
        }
        return applied;
    }

    // ---- the model ----------------------------------------------------------

    private static void model(EditTarget target, Map<String, byte[]> files, Applied applied)
            throws EditException {
        String path = target.request.model.path;
        byte[] raw = files.remove(path);
        if (raw == null) {
            // A vehicle session's geometry is read-only, so a pull with no
            // model in it is the ordinary case there rather than a failure.
            if ("vehicle".equals(target.request.kind)) {
                return;
            }
            throw new EditException("The editor sent no model back.");
        }
        JsonObject model = json(raw, path);

        if (!target.isProject()) {
            write(target, path, raw, applied);
            return;
        }

        // A Blockbench project: the geometry goes back inside the file it came
        // from, and so does the art. Its textures have no files of their own,
        // which is why nothing under the virtual folder is ever written to disk.
        Map<String, byte[]> byRef = new LinkedHashMap<>();
        JsonObject textures = object(model, "textures");
        if (textures != null) {
            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String ref = entry.getValue().getAsString();
                byte[] png = files.remove(pathFor(target, ref));
                if (png != null) {
                    byRef.put(ref, png);
                }
            }
        }

        String name = baseName(path);
        Optional<byte[]> project = BbWriter.write(target.project, model,
                BbWriter.texturesInSlotOrder(model, byRef), target.namespace, name);
        if (project.isEmpty()) {
            throw new EditException("This model could not be written back into "
                    + name + ".bbmodel, so nothing was changed. Export it from Blockbench as "
                    + "assets/models/" + name + ".json and try again.");
        }
        write(target, path, project.get(), applied);
    }

    // ---- the vehicle --------------------------------------------------------

    private static void vehicle(EditTarget target, EditWire.Vehicle vehicle, Applied applied)
            throws EditException {
        String text;
        try {
            text = Files.readString(target.vehicleFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EditException("Could not read " + target.vehicleFile.getFileName()
                    + ": " + e.getMessage());
        }
        String block = VehicleYaml.write(target.vehicleKey, target.vehicleModel, vehicle);
        String updated = YamlBlocks.replace(text, target.vehicleKey, block);
        if (updated == null) {
            // The entry has been renamed or moved since the session opened.
            // Refused rather than appended: adding it back would leave two
            // vehicles where the author meant one.
            throw new EditException(target.vehicleKey + " is no longer in "
                    + target.vehicleFile.getFileName() + ", so it was left alone.");
        }
        writeAbsolute(target.vehicleFile, updated.getBytes(StandardCharsets.UTF_8));
        applied.written.add(target.packDir.relativize(target.vehicleFile).toString().replace('\\', '/'));
    }

    // ---- the art ------------------------------------------------------------

    private static void textures(EditTarget target, Map<String, byte[]> files, Applied applied)
            throws EditException {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            if (path.startsWith(EditTargets.PROJECT_TEXTURE_DIR + "/")) {
                // Art for a project that was not written — a texture the author
                // added to a model whose write-back failed, or a stray. It has
                // no file of its own by definition, so there is nowhere to put
                // it and dropping it is the whole of the right answer.
                continue;
            }
            if (!isTexture(path)) {
                throw new EditException("The editor sent a file this session may not write: " + path);
            }
            write(target, path, entry.getValue(), applied);
        }
    }

    /**
     * Whether a path is art this session may write.
     *
     * <p>Both layouts, because both are read: ours puts everything under
     * {@code assets/}, and an ItemsAdder pack keeps {@code textures/} at its
     * root. Anything else with a {@code .png} on the end is still refused —
     * the point is not the extension, it is the folder.
     */
    private static boolean isTexture(String path) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                && (path.startsWith("assets/textures/") || path.startsWith("textures/"));
    }

    private static void removals(EditTarget target, EditWire.Pull pull, Applied applied) {
        if (pull.removed == null) {
            return;
        }
        for (String path : pull.removed) {
            // Only a file this session brought in. A pull naming anything else
            // is either a bug at the far end or somebody in the middle, and
            // either way the answer is to leave the file alone.
            if (!target.refPaths.containsValue(path)) {
                continue;
            }
            if (path.startsWith(EditTargets.PROJECT_TEXTURE_DIR + "/")) {
                continue;
            }
            Path file = EditPaths.resolve(target.packDir, path);
            if (file == null) {
                continue;
            }
            try {
                if (Files.deleteIfExists(file)) {
                    applied.deleted.add(path);
                }
            } catch (IOException ignored) {
                // A file that would not go is a file that is still there, which
                // is the safe half of this operation. Nothing to report.
            }
        }
    }

    // ---- files --------------------------------------------------------------

    private static void write(EditTarget target, String path, byte[] bytes, Applied applied)
            throws EditException {
        Path file = EditPaths.resolve(target.packDir, path);
        if (file == null) {
            throw new EditException("The editor sent a path this plugin will not write: " + path);
        }
        writeAbsolute(file, bytes);
        applied.written.add(path);
    }

    /**
     * Written beside the target and moved into place.
     *
     * <p>The same reasoning {@code StudioPush} gives for downloading a pack to
     * a scratch file first: a write that dies halfway must not leave a
     * truncated model where a working one was, and on a content folder that is
     * somebody's own work rather than a cache.
     */
    private static void writeAbsolute(Path file, byte[] bytes) throws EditException {
        Path scratch = file.resolveSibling(file.getFileName() + "." + System.nanoTime() + ".part");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(scratch, bytes);
            try {
                Files.move(scratch, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some Windows volumes and every network share. Still better
                // than writing in place: the window is a move rather than the
                // length of the write.
                Files.move(scratch, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new EditException("Could not write " + file.getFileName() + ": " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(scratch);
            } catch (IOException ignored) {
                // A stray .part is untidy and harmless.
            }
        }
    }

    private static Map<String, byte[]> decode(EditWire.Pull pull) throws EditException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (EditWire.File file : pull.files) {
            if (file == null || file.path == null || file.content == null) {
                continue;
            }
            if (!EditPaths.safe(file.path)) {
                throw new EditException("The editor sent a path this plugin will not write: " + file.path);
            }
            try {
                files.put(file.path, "base64".equals(file.encoding)
                        ? Base64.getDecoder().decode(file.content)
                        : file.content.getBytes(StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new EditException("The editor sent " + file.path + " in a form this plugin cannot read.");
            }
        }
        return files;
    }

    /**
     * Where a texture reference's file was named on the wire.
     *
     * <p>One this session sent has a remembered path. One the author created in
     * the editor does not, and its path is derived the same way the far end
     * derived it from the prefix this plugin gave it — the two spellings have
     * to agree or a new texture is looked for where nothing put it.
     */
    private static String pathFor(EditTarget target, String ref) {
        String known = target.refPaths.get(ref);
        if (known != null) {
            return known;
        }
        int colon = ref.indexOf(':');
        String rest = colon < 0 ? ref : ref.substring(colon + 1);
        return rest.startsWith(EditTargets.PROJECT_TEXTURE_DIR + "/")
                ? rest + ".png"
                : "assets/textures/" + rest + ".png";
    }

    private static String baseName(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    private static JsonObject json(byte[] bytes, String path) throws EditException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new EditException("The editor's " + path + " is not a model.");
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new EditException("The editor's " + path + " is not valid JSON.");
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
}
