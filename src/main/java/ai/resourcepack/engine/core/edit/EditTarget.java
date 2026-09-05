package ai.resourcepack.engine.core.edit;

import ai.resourcepack.engine.api.ContentId;

import java.nio.file.Path;
import java.util.Map;

/**
 * Everything one edit session needs, resolved off disk before anything is
 * sent.
 *
 * <p>Built by {@link EditTargets}, held by {@link EditSessions} for as long as
 * the session lives, and read by {@link EditApply} when the work comes back.
 * It exists because opening and applying are minutes apart and the second one
 * must not go looking for the files again: a pack reloaded in between could
 * point the same content id at a different model, and the write would land on
 * a file nobody was editing.
 */
final class EditTarget {

    /** The content this session is about, for chat and for the log. */
    final ContentId id;

    /** {@code plugins/RPEngine/content/<namespace>}. */
    final Path packDir;

    /** The pack folder's name, which is its namespace. */
    final String namespace;

    /** What is sent to open the session. */
    final EditWire.Open request;

    /**
     * The Blockbench project this model came out of, or null.
     *
     * <p>Kept because a project is written back by editing the one it came
     * from rather than by building a fresh one: everything Blockbench cares
     * about and this plugin does not read — the display settings, the texture
     * names, the meta block — survives only if the original is the starting
     * point. See {@link BbWriter}.
     */
    final byte[] project;

    /**
     * How each texture reference in the model was named on the wire.
     *
     * <p>The far end round-trips a path without interpreting it, so this is
     * how a returned file is matched back to the slot it belongs in. A
     * reference that is not in here is one the author added in the editor, and
     * its path is derived the same way the far end derived it.
     */
    final Map<String, String> refPaths;

    /** For a vehicle: the YAML file holding its entry. Null otherwise. */
    final Path vehicleFile;

    /** For a vehicle: the top-level key in that file. Null otherwise. */
    final String vehicleKey;

    /**
     * For a vehicle: the {@code model:} line's value.
     *
     * <p>Carried through rather than sent over the wire. The editor has no
     * opinion about which item's model a vehicle wears and cannot change it, so
     * it is not in the payload — and the entry is rewritten whole, which means
     * a value nobody carried is a value that would be dropped.
     */
    final String vehicleModel;

    EditTarget(ContentId id, Path packDir, String namespace, EditWire.Open request,
               byte[] project, Map<String, String> refPaths,
               Path vehicleFile, String vehicleKey, String vehicleModel) {
        this.id = id;
        this.packDir = packDir;
        this.namespace = namespace;
        this.request = request;
        this.project = project;
        this.refPaths = refPaths;
        this.vehicleFile = vehicleFile;
        this.vehicleKey = vehicleKey;
        this.vehicleModel = vehicleModel;
    }

    /** Whether the model this session carries is a Blockbench project. */
    boolean isProject() {
        return request.model != null && "bbmodel".equals(request.model.format);
    }
}
