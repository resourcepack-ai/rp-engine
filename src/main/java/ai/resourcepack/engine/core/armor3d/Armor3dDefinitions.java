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
 *   displays: false      # optional, default true; see below
 *   shader-helmet: true  # optional, default false; see below
 * }</pre>
 *
 * <p>{@code displays} decides what a viewer whose client does not draw the set
 * itself is shown. A client inside one of the shader ranges draws the body
 * pieces on its own limbs; everybody else gets display entities posed onto
 * the body, or, with {@code displays: false}, nothing but the helmet (which the
 * game draws as a head item on every client). For a server that only admits
 * clients the shaders cover, or would rather show less than show it late.
 *
 * <p>{@code shader-helmet} hands the helmet to the shaders as well, so it
 * follows the same rules as the body (any turn, mesh faces) instead of a block
 * model's; the price is that a client outside the shader ranges sees no helmet
 * at all. Mesh faces anywhere are drawn by the shaders only.
 *
 * <p>A set cannot be understood without its model: which pieces it has, and
 * where a turned part's display stands, are in the cubes. So the model is read
 * and baked HERE, once, and the build writes what this produced rather than
 * reading the file a second time and perhaps coming to a different answer.
 * The same reason an icon's GIF is read at parse time.
 */
public final class Armor3dDefinitions {

    private static final Set<String> KEYS = Set.of("name", "model", "displays", "shader-helmet");

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
                            + "so it was ignored. A set takes name, model, displays and shader-helmet."));
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
            boolean displays = body.bool("displays").orElse(true);
            boolean shaderHelmet = body.bool("shader-helmet").orElse(false);
            Armor3dBaker.Baked set = Armor3dBaker.bake(namespace, key, body.string("name").orElse(null),
                    source.get(), displays, shaderHelmet);
            boolean meshHelmet = source.get().quads().stream().anyMatch(q -> q.piece() == Armor3dSet.Piece.HELMET);
            if (meshHelmet && !shaderHelmet) {
                diagnostics.add(Diagnostic.warning(origin, key, "The helmet has mesh faces, which only the "
                        + "client's shaders can draw, but the helmet is the game's head item. Add shader-helmet: true "
                        + "to draw them, or they are left out."));
            }
            if (displays && !source.get().quads().isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, key, "The model has mesh faces, which only the client's "
                        + "shaders draw: viewers shown display entities see the set without them. Use cubes, or "
                        + "displays: false."));
            }
            if (set.set().pieces().isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, key, "The model has no cubes on any bone, so the set "
                        + "has no pieces. Name its groups " + Armor3dBaker.BONES.keySet() + "."));
                continue;
            }
            boolean body3d = set.set().pieces().keySet().stream().anyMatch(p -> p != Armor3dSet.Piece.HELMET);
            if (set.shaderProblem() != null && body3d) {
                diagnostics.add(Diagnostic.warning(origin, key, "This set cannot be drawn by the client's own "
                        + "shaders (" + set.shaderProblem() + "), so "
                        + (displays ? "every viewer is shown display entities instead."
                                : "with displays: false nobody sees more of it than the helmet.")));
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
