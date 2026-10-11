package ai.resourcepack.engine.core.armor3d;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.util.Map;

/**
 * Writes the authored 3D armour sets into the bundles their packs are in.
 *
 * <p>Everything was baked when the folder was read ({@link Armor3dDefinitions}),
 * so this only copies: the models, the item definitions that name them and
 * the textures out of the {@code .bbmodel}. The {@code .bbmodel} itself is
 * then dropped from the pack, because the client cannot read one and every
 * player would download it for nothing.
 *
 * <p>Nothing is written on a server older than 1.21.4: a set cannot be worn
 * there, and its files would be a download with nothing to show.
 */
public final class Armor3dAssets implements PackContributor {

    private final Armor3dDefinitions.Result sets;
    private final boolean enabled;

    public Armor3dAssets(Armor3dDefinitions.Result sets, boolean enabled) {
        this.sets = sets;
        this.enabled = enabled;
    }

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        if (!enabled) {
            return;
        }
        for (Armor3dBaker.Baked baked : sets.baked().values()) {
            if (!bundle.namespaces().contains(baked.namespace())) {
                continue;
            }
            for (Map.Entry<String, byte[]> file : baked.files().entrySet()) {
                into.add(file.getKey(), file.getValue());
            }
            String model = sets.models().get(baked.set().id());
            if (model != null) {
                // Where the build copied it: assets/ is routed into the pack's
                // namespace, and the root models/ and blueprints/ of the other
                // plugins' layouts are copied as though they were under it.
                String within = model.startsWith("assets/") ? model.substring("assets/".length()) : model;
                into.drop("assets/" + baked.namespace() + "/" + within);
            }
        }
    }
}
