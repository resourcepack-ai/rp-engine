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
 * <p>A set the client's own shaders can draw also puts its equipment assets
 * in each shader range's overlay, with the shaders and their licence, and
 * declares the overlay. The layer textures go in the base pack: nothing names
 * them outside an overlay, so they are harmless there and written once. The
 * equipment assets must NOT be in the base pack, or a client with no shader
 * port would draw the encoded layers as ordinary armour, which is noise on the
 * body. Studio's pack is laid out the same way.
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
        boolean shaded = false;
        for (Armor3dBaker.Baked baked : sets.baked().values()) {
            if (!bundle.namespaces().contains(baked.namespace())) {
                continue;
            }
            for (Map.Entry<String, byte[]> file : baked.files().entrySet()) {
                into.add(file.getKey(), file.getValue());
            }
            for (Armor3dShaders.Range range : Armor3dShaders.RANGES) {
                for (Map.Entry<String, byte[]> file : baked.overlayFiles().entrySet()) {
                    into.add(range.directory() + "/" + file.getKey(), file.getValue());
                }
            }
            shaded |= !baked.overlayFiles().isEmpty();
            String model = sets.models().get(baked.set().id());
            if (model != null) {
                // Where the build copied it: assets/ is routed into the pack's
                // namespace, and the root models/ and blueprints/ of the other
                // plugins' layouts are copied as though they were under it.
                String within = model.startsWith("assets/") ? model.substring("assets/".length()) : model;
                into.drop("assets/" + baked.namespace() + "/" + within);
            }
        }
        if (shaded) {
            for (Armor3dShaders.Range range : Armor3dShaders.RANGES) {
                for (Map.Entry<String, byte[]> file : Armor3dShaders.files(range).entrySet()) {
                    into.add(range.directory() + "/" + file.getKey(), file.getValue());
                }
                into.overlay(range.directory(), range.minFormat(), range.maxFormat());
            }
        }
    }
}
