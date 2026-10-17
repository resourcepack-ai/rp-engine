package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.core.pack.DeterministicZip;
import ai.resourcepack.engine.core.pack.PackBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * The baked emote rigs: a pack of their own stacked on the server's bundle
 * where packs stack ({@link #writePack}), or files put into every bundle
 * where a player holds one pack at a time ({@link #contribute}).
 *
 * <p>One bake per build, shared by every bundle: the rigs are the same
 * wherever a player is, and a server with three worlds' worth of bundles
 * should not fetch and bake its skins three times. Constructed at build time
 * from a {@link SkinCache} snapshot, contributed once per bundle, and then
 * asked for the rig table the {@link EmoteStore} needs.
 *
 * <p>Switched off entirely — no files, no players — on a server that cannot
 * dispatch an item model by name, which is anything before 1.21.4. A native
 * rig reaches its models through an {@code item_model} component and there
 * is no older way to do that without owning vanilla's paper model file, which
 * a pushed pack already does. Such a server still plays pushed rigs exactly
 * as before; it just has none of its own.
 */
public final class RigAssets implements PackContributor {

    /**
     * The bundle name the rigs are served under when they are a pack of their
     * own, stacked on top of the server's bundle. See {@link #writePack}.
     */
    public static final String BUNDLE = "rpengine-rigs";

    private final RigBaker.Baked baked;

    RigAssets(RigBaker.Baked baked) {
        this.baked = baked;
    }

    /** Bakes every cached skin, or nothing when rigs are off or the server is too old. */
    public static RigAssets bake(SkinCache skins, boolean enabled) {
        if (!enabled || skins == null) {
            return new RigAssets(new RigBaker.Baked());
        }
        RigBaker.Baked baked = RigBaker.bake(skins.snapshot());
        skins.baked(baked.players.keySet());
        return new RigAssets(baked);
    }

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        for (Map.Entry<String, byte[]> file : baked.files.entrySet()) {
            into.add(file.getKey(), file.getValue());
        }
    }

    /** Every file the bake produced: the sheets, the bone models and the shared definitions. */
    public Map<String, byte[]> files() {
        return Collections.unmodifiableMap(baked.files);
    }

    /**
     * Writes the rigs as a resource pack of their own, under {@link #BUNDLE}.
     *
     * <p>Where packs stack (1.20.3+), this is how rigs reach players: a small
     * pack on top of the server's bundle rather than files inside it. A rig is
     * a copy of a player's skin, so every newcomer changes the rigs, and while
     * they were in the bundle every newcomer changed the bundle's SHA-1 and
     * sent everybody the whole thing again. Apart, the bundle stays
     * byte-identical while only the set of skins moves, clients keep it
     * cached, and what is rebuilt and re-sent is a few hundred kilobytes.
     *
     * <p>Deterministic, like the bundles: the same skins write the same bytes.
     *
     * @return the pack, or null when this build baked nothing
     */
    public BuiltPack writePack(Path file, int packFormat, String description) throws IOException {
        if (baked.files.isEmpty()) {
            return null;
        }
        return write(baked.files, BUNDLE, file, packFormat, description);
    }

    /**
     * Zips rig files into a pack of their own, with a {@code pack.mcmeta}.
     * Shared by the rigs pack and a single player's own rig pack.
     */
    public static BuiltPack write(Map<String, byte[]> files, String bundle, Path file, int packFormat,
                                  String description) throws IOException {
        DeterministicZip zip = new DeterministicZip();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            zip.add(entry.getKey(), entry.getValue());
        }
        // The bundle's rule (PackBuilder), so a newer client reads it without a
        // parse warning.
        zip.add("pack.mcmeta", PackBuilder.packMeta(packFormat, description, new com.google.gson.JsonArray())
                .getBytes(StandardCharsets.UTF_8));
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String sha1 = zip.writeTo(file);
        return BuiltPack.of(bundle, file, sha1, Files.size(file), zip.size());
    }

    /** The rigs baked, by player key, for {@link EmoteStore#setNativeRigs}. */
    public Map<String, EmoteStore.PlayerRig> players() {
        return Collections.unmodifiableMap(baked.players);
    }

    /** Whether this build baked a rig for the player with this key. */
    public boolean has(String key) {
        return baked.players.containsKey(key);
    }

    /**
     * What one player needs, stacked on top of THIS build, to see their own
     * rig before a rebuild has put it in - see {@link RigBaker#selfPack}.
     * Null when this build already has them, or bakes nothing at all.
     */
    public Self self(SkinCache skins, java.util.UUID player) {
        if (baked.parts.isEmpty() || skins == null || player == null) {
            return null;
        }
        String key = SkinCache.keyOf(player);
        if (has(key)) {
            return null;
        }
        RigBaker.Part part = RigBaker.part(skins.skin(player));
        if (part == null) {
            return null;
        }
        return new Self(key, part.rig, RigBaker.selfPack(baked, part));
    }

    /** One player's own rig, for the store, and the files that draw it. */
    public static final class Self {
        private final String key;
        private final EmoteStore.PlayerRig rig;
        private final Map<String, byte[]> files;

        Self(String key, EmoteStore.PlayerRig rig, Map<String, byte[]> files) {
            this.key = key;
            this.rig = rig;
            this.files = files;
        }

        public String key() {
            return key;
        }

        public EmoteStore.PlayerRig rig() {
            return rig;
        }

        public Map<String, byte[]> files() {
            return Collections.unmodifiableMap(files);
        }
    }

    /** How many rigs went in, for the build report. */
    public int count() {
        return baked.players.size();
    }
}
