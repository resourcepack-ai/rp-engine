package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Keyframe;
import ai.resourcepack.engine.api.MergeResult;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory store of animation rigs, keyed by model id. The panel ships a
 * manifest alongside each pack push; entries merge in per model id and
 * persist to plugins/&lt;plugin&gt;/rigs.json, so placed rigs keep animating
 * after a restart without waiting for the next push.
 *
 * JSON shape is produced by Studio's rig builder.
 */
public final class RigStore {

    static final class Step {
        String target;
        float[] pivot;
    }

    static final class Part {
        String item;
        List<Step> program;
        /** The bone this part came from. Absent on a manifest older than this. */
        String bone;
        /** Its {@link ai.resourcepack.engine.api.BoneBehaviour}, lowercased. Absent means none. */
        String behaviour;
        /** The bone's own pivot in model px, for a behaviour that needs a place. */
        float[] pivot;
        /** Its widest side in blocks, measured at build time. 0 means unknown. */
        float size;
    }


    static final class Animation {
        String name;
        double length;
        boolean loop;
        /**
         * What happens at the end: "loop", "hold" (stay on the last frame) or
         * "once" (back to rest). Null on a manifest older than this, where
         * {@link #loop} is the whole answer.
         */
        String mode;
        /** Playback rate. 0 or absent is 1, which is what an old manifest has. */
        double speed;
        /**
         * Which animation wins when two could play. Higher wins; equal falls
         * back to rig order, which is what every manifest before this had.
         */
        int priority;
        /** Seconds to ease in and out of this animation. 0 is a hard cut. */
        double blend;
        /**
         * Which layer it plays on. 0 is the base and is what everything
         * written before layers existed is.
         *
         * <p>One animation at a time per layer; layers above the base compose
         * ON TOP of it, which is what lets a wave play over a walk cycle
         * instead of replacing it.
         */
        int layer;
        List<Trigger> triggers;
        // animator target ("3" or "g:0") -> channel ("rotation"/"position"/"scale") -> keyframes.
        Map<String, Map<String, List<Keyframe>>> animators;
    }

    static final class Trigger {
        // "loop" | "right_click" | "left_click" | "range" | "place".
        String type;
        // Spherical player-entry radius in blocks; meaningful for range only.
        double distance;
    }

    static final class Rig {
        List<Part> parts;
        List<Animation> animations;
    }

    private static final class Manifest {
        // Absent on manifests from a panel older than per-pack replacement,
        // which is why the no-packId path below still merges the old way.
        String packId;
        Map<String, Rig> models;
        // modelId -> packId, so ownership survives a restart. Written by
        // save() only; a manifest off the wire never sets it.
        Map<String, String> packs;
    }

    private final Gson gson = new Gson();
    private final Map<String, Rig> byModel = new ConcurrentHashMap<>();
    // modelId -> the pack that last supplied it, so a push can retire a model
    // without touching another pack's rigs.
    private final Map<String, String> packOfModel = new ConcurrentHashMap<>();
    private final File file;

    public RigStore(File dataFolder) {
        this.file = new File(dataFolder, "rigs.json");
    }

    Rig get(String modelId) {
        return modelId == null ? null : byModel.get(modelId);
    }

    /**
     * Applies a manifest ({"packId": "...", "models": {...}}) to the store.
     * Safe to call from any thread.
     *
     * It REPLACES everything the same pack supplied before, so a model that
     * lost its rig is dropped. A plain per-key merge left the old rig behind,
     * and placement branches on "is there a rig for this id", so the stale
     * entry spawned the old animated rig instead of the current model.
     *
     * Scoped to the pack: one server can hold pairings for several packs, and
     * a push for one must not drop another's rigs. A manifest with no packId
     * (older panel) can't be attributed, so it retires nothing.
     */
    public MergeResult updateFromJson(String json) {
        Manifest manifest;
        try {
            manifest = gson.fromJson(json, Manifest.class);
        } catch (RuntimeException e) {
            return MergeResult.failed("rig manifest wasn't readable: " + e.getMessage());
        }
        if (manifest == null || manifest.models == null) {
            return MergeResult.failed("rig manifest carried no models");
        }

        if (manifest.packId != null && !manifest.packId.isEmpty()) {
            // Retire this pack's previous models before taking the new set,
            // so an emptied manifest genuinely empties the pack.
            packOfModel.entrySet().removeIf(entry -> {
                if (!manifest.packId.equals(entry.getValue())) return false;
                byModel.remove(entry.getKey());
                return true;
            });
        }

        for (Map.Entry<String, Rig> entry : manifest.models.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                byModel.put(entry.getKey(), entry.getValue());
                if (manifest.packId != null && !manifest.packId.isEmpty()) {
                    packOfModel.put(entry.getKey(), manifest.packId);
                }
            }
        }

        // Restored from disk: load() replays a saved manifest through here,
        // and that carries ownership in `packs` rather than a single packId.
        if (manifest.packs != null) {
            for (Map.Entry<String, String> entry : manifest.packs.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && byModel.containsKey(entry.getKey())) {
                    packOfModel.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return MergeResult.ok(manifest.packId, manifest.models.size());
    }

    /** Every model id held, in no particular order. */
    public Set<String> modelIds() {
        return Set.copyOf(byModel.keySet());
    }

    /** Every pack that has supplied a rig held right now. */
    public Set<String> packIds() {
        return Set.copyOf(packOfModel.values());
    }

    /**
     * Drops everything one pack supplied.
     *
     * <p>Rigs already standing in the world are not touched: they are entities,
     * and they keep the pose they were last put in. What stops is this server
     * knowing how to animate that model, which is the same state as never
     * having been sent it.
     */
    public void retire(String packId) {
        if (packId == null || packId.isEmpty()) return;
        packOfModel.entrySet().removeIf(entry -> {
            if (!packId.equals(entry.getValue())) return false;
            byModel.remove(entry.getKey());
            return true;
        });
    }

    public void save(Logger logger) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Couldn't create " + parent + " to persist rigs");
                return;
            }
            Manifest manifest = new Manifest();
            manifest.models = new HashMap<>(byModel);
            manifest.packs = new HashMap<>(packOfModel);
            Files.write(file.toPath(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warning("Couldn't persist rigs.json: " + e.getMessage());
        }
    }

    public void load(Logger logger) {
        if (!file.exists()) return;
        try {
            Manifest saved = gson.fromJson(
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), Manifest.class);
            if (saved == null || saved.models == null) return;

            // Only rigs whose owning pack is known are restored. An entry with
            // no owner can never be retired (a push replaces the pushing
            // pack's set, and an unattributed entry belongs to none), so it
            // would outlive every sync and every restart. Covers both a file
            // from before ownership existed and one saved by a build that had
            // loaded such a file. The next push restores what's current.
            Map<String, String> owners = saved.packs == null ? new HashMap<>() : saved.packs;
            int dropped = 0;
            for (Map.Entry<String, Rig> entry : saved.models.entrySet()) {
                String owner = entry.getKey() == null ? null : owners.get(entry.getKey());
                if (owner == null || entry.getValue() == null) {
                    dropped++;
                    continue;
                }
                byModel.put(entry.getKey(), entry.getValue());
                packOfModel.put(entry.getKey(), owner);
            }

            logger.info("Loaded " + byModel.size() + " animation rig(s) from rigs.json");
            if (dropped > 0) {
                logger.info(
                    "Dropped " + dropped + " rig(s) with no owning pack — sync from the panel to restore them");
            }
        } catch (IOException | RuntimeException e) {
            logger.warning("Couldn't load rigs.json: " + e.getMessage());
        }
    }
}
