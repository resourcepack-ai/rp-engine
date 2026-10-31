package ai.resourcepack.engine.core.cape;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.util.Map;

/**
 * Writes each content folder cape's model and item definition into the
 * bundle its pack is in. The texture needs nothing: it is under the pack's
 * {@code assets/textures/} and the build copies it like any other.
 *
 * <p>Nothing is written on a server older than 1.21.4, where a cape cannot be
 * drawn (its display names its art with an {@code item_model}).
 */
public final class CapeAssets implements PackContributor {

    private final CapeDefinitions.Result capes;
    private final boolean enabled;

    public CapeAssets(CapeDefinitions.Result capes, boolean enabled) {
        this.capes = capes;
        this.enabled = enabled;
    }

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        if (!enabled) {
            return;
        }
        for (Map.Entry<String, Map<String, byte[]>> namespace : capes.files().entrySet()) {
            if (!bundle.namespaces().contains(namespace.getKey())) {
                continue;
            }
            for (Map.Entry<String, byte[]> file : namespace.getValue().entrySet()) {
                into.add(file.getKey(), file.getValue());
            }
        }
    }
}
