package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.util.Collections;
import java.util.Map;

/**
 * Puts the baked emote rigs into every bundle.
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

    /** The rigs baked, by player key, for {@link EmoteStore#setNativeRigs}. */
    public Map<String, EmoteStore.PlayerRig> players() {
        return Collections.unmodifiableMap(baked.players);
    }

    /** How many rigs went in, for the build report. */
    public int count() {
        return baked.players.size();
    }
}
