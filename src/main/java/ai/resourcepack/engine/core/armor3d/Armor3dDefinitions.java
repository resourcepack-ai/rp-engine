package ai.resourcepack.engine.core.armor3d;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.item.ModelSources;
import ai.resourcepack.engine.core.pack.PackFiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code armor3d/} out of a content folder.
 *
 * <pre>{@code
 * santa:
 *   name: "Santa Suit"   # optional; the id when absent
 *   model: armor_santa   # assets/models/armor_santa.bbmodel; the id's path when absent
 * }</pre>
 *
 * <p>A set cannot be understood without its model: which pieces it has, and
 * where a turned part's display stands, are in the cubes. So the model is read
 * and baked HERE, once, and the build writes what this produced rather than
 * reading the file a second time and perhaps coming to a different answer.
 * The same reason an icon's GIF is read at parse time.
 */
public final class Armor3dDefinitions {

    private static final Set<String> KEYS = Set.of("name", "model");

    private Armor3dDefinitions() {
    }

    /**
     * @param sets   by id ({@code namespace:path}), in load order
     * @param baked  the same sets with the files each puts in the pack
     * @param models for each set, the {@code .bbmodel} it consumed, as a path
     *               relative to its pack folder, when the build may drop the
     *               copy the pack's assets would otherwise ship
     */
    public record Result(Map<String, Armor3dSet> sets, Map<String, Armor3dBaker.Baked> baked,
                         Map<String, String> models, List<Diagnostic> diagnostics) {

        public static Result empty() {
            return new Result(Map.of(), Map.of(), Map.of(), List.of());
        }
    }

    public static Result parse(LoadReport loaded, PackFiles files) {
        if (loaded == null) {
            return Result.empty();
        }
        Map<String, Armor3dSet> sets = new LinkedHashMap<>();
        Map<String, Armor3dBaker.Baked> baked = new LinkedHashMap<>();
        Map<String, String> models = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ContentDefinition definition : loaded.definitions(ContentKind.ARMOR3D)) {
            DefinitionNode body = definition.body();
            String origin = definition.origin();
            String namespace = definition.id().namespace();
            String key = definition.id().path();
            for (String written : body.keys()) {
                if (!KEYS.contains(written)) {
                    diagnostics.add(Diagnostic.warning(origin, key, written + " is not a 3D armour setting, "
                            + "so it was ignored. A set takes name and model."));
                }
            }
            String model = body.string("model").orElse(key);
            Optional<ModelSources.Found> found = ModelSources.find(namespace, model, ".bbmodel",
                    path -> files.read(namespace, path));
            if (found.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, key, "No model at "
                        + ModelSources.describe(namespace, model, ".bbmodel")
                        + ". A 3D armour set is a Blockbench project with cubes in groups named after the bones."));
                continue;
            }
            Optional<Armor3dBlockbench.Source> source = Armor3dBlockbench.read(found.get().bytes(), origin, key,
                    diagnostics);
            if (source.isEmpty()) {
                continue;
            }
            Armor3dBaker.Baked set = Armor3dBaker.bake(namespace, key, body.string("name").orElse(null),
                    source.get());
            if (set.set().pieces().isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, key, "The model has no cubes on any bone, so the set "
                        + "has no pieces. Name its groups " + Armor3dBaker.BONES.keySet() + "."));
                continue;
            }
            sets.put(set.set().id(), set.set());
            baked.put(set.set().id(), set);
            if (found.get().consumable()) {
                models.put(set.set().id(), found.get().path());
            }
        }
        return new Result(java.util.Collections.unmodifiableMap(sets),
                java.util.Collections.unmodifiableMap(baked), java.util.Collections.unmodifiableMap(models),
                List.copyOf(diagnostics));
    }
}
