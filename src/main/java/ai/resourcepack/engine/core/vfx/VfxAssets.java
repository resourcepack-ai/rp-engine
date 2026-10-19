package ai.resourcepack.engine.core.vfx;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the sprites a bundle's VFX draw: painted here from the recipes, never
 * stored, under {@code rpai_vfx:}, and only on a server new enough to play one.
 *
 * <p>A bundle carries the effects of the namespaces in it, and nothing at all
 * when it has none, so a server that never writes {@code vfx/} ships exactly
 * the pack it always did. A file already in the bundle wins, the rule Studio's
 * own pack build follows.
 */
public final class VfxAssets implements PackContributor {

    private final Map<ContentId, VfxEntry> effects;
    private final boolean supported;

    public VfxAssets(Map<ContentId, VfxEntry> effects, boolean supported) {
        this.effects = effects;
        this.supported = supported;
    }

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        if (!supported || effects.isEmpty()) {
            return;
        }
        List<VfxRecipe> mine = new ArrayList<>();
        for (Map.Entry<ContentId, VfxEntry> e : effects.entrySet()) {
            if (bundle.namespaces().contains(e.getKey().namespace())) {
                mine.add(e.getValue().recipe());
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        for (Map.Entry<String, byte[]> file : VfxPack.files(mine).entrySet()) {
            if (!into.has(file.getKey())) {
                into.add(file.getKey(), file.getValue());
            }
        }
    }
}
