package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ModelShape;
import ai.resourcepack.engine.core.item.Geometry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a pushed model is shaped like, read out of the pushed pack itself.
 *
 * <p><strong>The pack is already on this disk, so nothing has to be sent.</strong>
 * A push downloads the zip and keeps it — that is what the server hands to
 * joining players — and the geometry inside it is the same JSON a content
 * folder's models are written in. So the honest answer to "where is this
 * model's art" was sitting in a file the whole time, and the alternative
 * (Studio measuring every model and shipping the boxes beside the pack) would
 * have been a second copy of the same numbers, a much larger manifest on every
 * push, and nothing at all for a pack pushed before the field existed.
 *
 * <p><strong>Read lazily, once per model.</strong> A pack can hold hundreds of
 * models and a server will only ever place a few of them; parsing the lot at
 * push time would put that work on the tick that applies a push, for models
 * nobody puts down. So the zip is opened on the first question about an id and
 * the answer is kept — including the answer "there is no such model", which
 * stops a mis-typed or deleted id being looked up for ever.
 *
 * <p>The cache is dropped whole when a new pack arrives, because a model may
 * have been re-modelled and a shape that outlived its art is worse than no
 * shape at all: it is a wall where nothing is drawn.
 */
public final class StudioModelShapes {

    /**
     * Where Studio puts a model in the pack it builds.
     *
     * <p>Under {@code block/} because every model in a Studio pack is dispatched
     * off a vanilla item's model, and this is the directory that item's
     * {@code select} cases point into. It has to agree with the pack builder;
     * getting it wrong is silent, and shows up as every pushed model having no
     * shape while authored ones are fine.
     */
    private static final String PREFIX = "assets/minecraft/models/block/";

    /** The namespace passed to the reader. Nothing here uses its textures. */
    private static final String NAMESPACE = "minecraft";

    private final Map<String, ModelShape> shapes = new ConcurrentHashMap<>();

    /** The pack to read out of, or null before anything has been pushed. */
    private volatile Path pack;

    /**
     * Adopts a freshly pushed pack.
     *
     * <p>Called with the file the push downloaded. The cache goes with the old
     * pack rather than being merged into the new one — see the class note.
     */
    public void usePack(Path zip) {
        this.pack = zip;
        shapes.clear();
    }

    /**
     * Points at the pack a previous run left on disk, if it is still there.
     *
     * <p>A server that restarts is still serving the last pack it was pushed,
     * and the models standing in its world are still that pack's — so the
     * shapes have to come back without waiting for somebody to press Sync
     * again.
     */
    public void loadExisting(Path zip) {
        if (zip != null && Files.isRegularFile(zip)) {
            usePack(zip);
        }
    }

    /**
     * What {@code modelId} is shaped like, or {@link ModelShape#NONE} when
     * there is no pack, no such model, or nothing readable in it.
     *
     * <p>NONE is a usable answer and not an error: the caller falls back to the
     * placement's own hitbox, which is what every version before this used.
     */
    public ModelShape shapeOf(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return ModelShape.NONE;
        }
        ModelShape known = shapes.get(modelId);
        if (known != null) {
            return known;
        }
        ModelShape read = readFromPack(modelId);
        // <strong>Only a settled answer is cached.</strong> "This pack has no
        // such model" is settled and worth remembering; "the zip would not
        // open" is not — a push replaces the file underneath us, so a read
        // that lands in that window fails once. Caching that would leave the
        // model permanently shapeless with nothing to say why, and the only
        // cure would be another push.
        if (read != null) {
            shapes.put(modelId, read);
            return read;
        }
        return ModelShape.NONE;
    }

    /**
     * The model's boxes, {@link ModelShape#NONE} for "not in this pack", or
     * null for "ask again later".
     */
    private ModelShape readFromPack(String modelId) {
        Path zip = pack;
        // A path traversal cannot come from here — the id is read off an entity
        // this plugin wrote — but the zip lookup is a string concatenation and
        // a defensive check costs nothing.
        if (modelId.indexOf('/') >= 0 || modelId.contains("..")) {
            return ModelShape.NONE;
        }
        if (zip == null) {
            // Nothing has been pushed yet. Not settled: the first push may
            // arrive a second from now and these ids would already be cached.
            return null;
        }
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = file.getEntry(PREFIX + modelId + ".json");
            if (entry == null) {
                // Settled: this pack really does not contain that model, which
                // is the ordinary answer for every authored piece that comes
                // past here.
                return ModelShape.NONE;
            }
            try (InputStream in = file.getInputStream(entry)) {
                return Geometry.read(in.readAllBytes(), NAMESPACE)
                        .map(model -> model.bounds().shape())
                        .orElse(ModelShape.NONE);
            }
        } catch (IOException | RuntimeException e) {
            // The pack is being replaced, has been deleted, or will not open.
            // Falling back to the hitbox is what this did before shapes
            // existed, so there is nothing to report — but it is not an answer
            // to keep.
            return null;
        }
    }
}
