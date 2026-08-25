package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.PackMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns what each pack said about itself into the bundles that get built.
 *
 * <p>Deliberately a plain function over {@link PackMeta}: bundle membership is
 * declared per namespace in {@code pack.yml} rather than in a central list, so
 * that adding a pack to a bundle is one line in the folder you just installed
 * and never an edit to a file two directories away.
 *
 * <p>Everything comes out sorted, both the bundles and the namespaces inside
 * them, because this ordering reaches the zip and therefore the hash.
 */
public final class Bundles {

    private Bundles() {
    }

    /** Groups packs by the bundles they declared. */
    public static List<Bundle> resolve(Collection<PackMeta> packs) {
        Map<String, TreeSet<String>> byBundle = new TreeMap<>();
        if (packs != null) {
            for (PackMeta pack : packs) {
                for (String bundle : pack.bundles()) {
                    byBundle.computeIfAbsent(bundle, key -> new TreeSet<>()).add(pack.namespace());
                }
            }
        }
        List<Bundle> resolved = new ArrayList<>(byBundle.size());
        for (Map.Entry<String, TreeSet<String>> entry : byBundle.entrySet()) {
            resolved.add(Bundle.of(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return List.copyOf(resolved);
    }
}
