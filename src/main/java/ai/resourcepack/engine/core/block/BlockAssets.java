package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.item.Geometry;
import ai.resourcepack.engine.core.item.BbModel;
import ai.resourcepack.engine.core.item.ModelSources;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writes the one blockstate file per base that a bundle's custom blocks share.
 *
 * <p>Same shape as {@link ai.resourcepack.engine.core.font.FontAssets}, and for
 * the same reason: {@code assets/minecraft/blockstates/note_block.json} is a
 * single vanilla file that every pack in a bundle would otherwise want to
 * write. It is generated once, from every namespace's blocks at once, so there
 * is nothing to merge and nothing to collide.
 *
 * <p><strong>Every state is listed, not just the used ones.</strong> A variants
 * map that omits a combination leaves the client with no model for it, and a
 * player placing an ordinary note block would see nothing at all. So the
 * unallocated ones are pointed at vanilla's own model, which is also what makes
 * a plain note block still look like a note block on a server with custom
 * blocks.
 */
public final class BlockAssets implements PackContributor {

    private final BlockStates states;

    public BlockAssets(BlockStates states) {
        this.states = states;
    }

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        Map<ContentId, BlockInfo> blocks = BlockDefinitions.parse(loaded).blocks();
        if (blocks.isEmpty()) {
            return;
        }

        for (BlockInfo.Base base : BlockInfo.Base.values()) {
            Map<String, String> models = new LinkedHashMap<>();
            for (BlockInfo block : blocks.values()) {
                if (block.shape() != BlockInfo.Shape.CUBE || block.base() != base
                        || !bundle.namespaces().contains(block.id().namespace())) {
                    continue;
                }
                Models written = new Models(block, into);
                for (String state : block.states()) {
                    Optional<Integer> number = states.existing(block, state);
                    if (number.isEmpty()) {
                        continue;
                    }
                    BlockInfo.Appearance drawn = block.appearanceOf(state);
                    String variant = variant(written.name(drawn.model()), drawn.x(), drawn.y(), drawn.uvlock());
                    // Every instrument that means this state, all pointing at
                    // one model — the game changes the instrument on its own
                    // and that must not change what a player sees.
                    for (String vanilla : BlockStates.statesFor(base, number.get())) {
                        models.put(vanilla, variant);
                    }
                }
            }
            if (!models.isEmpty()) {
                into.add(blockstatePath(base), blockstates(base, models));
            }
        }

        for (BlockInfo block : blocks.values()) {
            if (block.shape() == BlockInfo.Shape.CUBE || !bundle.namespaces().contains(block.id().namespace())) {
                continue;
            }
            if (block.model().isEmpty() && block.roleModels().isEmpty() && block.appearances().isEmpty()) {
                // Nothing to draw it with: the base block's own look, which is
                // what not writing its blockstate file leaves.
                continue;
            }
            states.existingShaped(block).ifPresent(taken -> into.add(
                    "assets/minecraft/blockstates/" + taken + ".json", shaped(block, new Models(block, into))));
        }
    }

    /**
     * The blockstate file of a vanilla block taken over: the game's own
     * layout for that kind of block, with every part pointing at ours.
     *
     * <p>An appearance written against the base block's own states (which is
     * what an importer writes for somebody else's stair) beats the part the
     * layout names, so a block drawn state by state comes across exactly as it
     * was drawn.
     */
    private static byte[] shaped(BlockInfo block, Models written) {
        StringBuilder json = new StringBuilder("{\n  \"variants\": {\n");
        List<ShapeTemplates.Variant> layout = ShapeTemplates.of(block.shape());
        for (int i = 0; i < layout.size(); i++) {
            ShapeTemplates.Variant row = layout.get(i);
            BlockInfo.Appearance chosen = null;
            int best = -1;
            for (BlockInfo.Appearance appearance : block.appearances()) {
                int score = appearance.matches(row.state());
                if (score > best) {
                    chosen = appearance;
                    best = score;
                }
            }
            String variant;
            if (chosen != null && !chosen.model().isEmpty()) {
                variant = variant(written.name(chosen.model()), chosen.x(), chosen.y(), chosen.uvlock());
            } else {
                String model = block.roleModels().getOrDefault(row.role(), block.model());
                variant = variant(written.name(model), row.x(), row.y(), row.uvlock());
            }
            json.append("    \"").append(row.state()).append("\": ").append(variant)
                    .append(i == layout.size() - 1 ? "\n" : ",\n");
        }
        json.append("  }\n}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One variant of a blockstate file, with the turn left out when there is none. */
    private static String variant(String model, int x, int y, boolean uvlock) {
        StringBuilder out = new StringBuilder("{ \"model\": \"").append(model).append('"');
        if (x != 0) {
            out.append(", \"x\": ").append(x);
        }
        if (y != 0) {
            out.append(", \"y\": ").append(y);
        }
        if (uvlock) {
            out.append(", \"uvlock\": true");
        }
        return out.append(" }").toString();
    }

    /**
     * The models one block is drawn with, each written once.
     *
     * <p>Its own model is {@code <namespace>:block/<id>}, as it always was;
     * every other model a state or a part draws is the next free
     * {@code <id>_2}, {@code <id>_3}, in the order they are first asked for,
     * which is the order the definition lists them.
     */
    private final class Models {

        private final BlockInfo block;
        private final Contribution into;
        private final Map<String, String> names = new LinkedHashMap<>();

        Models(BlockInfo block, Contribution into) {
            this.block = block;
            this.into = into;
        }

        /** The model a reference is written as, writing it the first time. */
        String name(String reference) {
            String key = reference == null ? "" : reference;
            String already = names.get(key);
            if (already != null) {
                return already;
            }
            String namespace = block.id().namespace();
            String file = block.id().path() + (names.isEmpty() ? "" : "_" + (names.size() + 1));
            String name = namespace + ":block/" + file;
            names.put(key, name);
            writeModel(block, key, namespace, file, into);
            return name;
        }
    }

    /**
     * A block's own model, from the same source an item's comes from.
     *
     * <p>Written under {@code models/block/} rather than {@code models/item/}
     * so the two can differ later — a block seen in the world and the same
     * thing held in a hand are not always meant to look identical — without
     * either having to be regenerated.
     */
    private void writeModel(BlockInfo block, String reference, String namespace, String path,
                            Contribution into) {
        String target = "assets/" + namespace + "/models/block/" + path + ".json";
        if (reference.isEmpty()) {
            // No art: the base block's own texture, so it is visible and
            // obviously unfinished rather than invisible.
            into.add(target, ("{\"parent\":\"" + baseModel(block.base()) + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }

        String name = reference;
        String local = name.indexOf(':') > 0 ? name.substring(name.indexOf(':') + 1) : name;
        Optional<ModelSources.Found> project = source(into, namespace, name, ".bbmodel");
        if (project.isPresent()) {
            Optional<BbModel.Converted> converted = BbModel.convert(project.get().bytes(), namespace, local);
            if (converted.isPresent()) {
                into.add(target, converted.get().model().toString().getBytes(StandardCharsets.UTF_8));
                converted.get().textures().forEach((file, png) ->
                        into.add("assets/" + namespace + "/textures/item/" + file + ".png", png));
                if (project.get().consumable()) {
                    into.drop("assets/" + namespace + "/models/" + local + ".bbmodel");
                }
                return;
            }
        }

        Optional<ModelSources.Found> exported = source(into, namespace, name, ".json");
        if (exported.isEmpty()) {
            into.error(namespace + "/blocks", block.id().path(),
                    "No model at " + (ModelSources.isLocation(name)
                            ? ModelSources.describe(namespace, name, ".json")
                            : "assets/models/" + name + ".bbmodel or .json") + ". "
                            + "The block is placeable and renders as a plain "
                            + block.base().name().toLowerCase(Locale.ROOT) + ".");
            into.add(target, ("{\"parent\":\"" + baseModel(block.base()) + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        Optional<Geometry.Model> model = Geometry.read(exported.get().bytes(), exported.get().textureNamespace());
        if (model.isPresent()) {
            into.add(target, model.get().json());
            if (exported.get().consumable()) {
                into.drop("assets/" + namespace + "/models/" + local + ".json");
            }
        }
    }

    /** Ours first, then the other plugins' layouts. Same rule as items; see {@link ModelSources}. */
    private static Optional<ModelSources.Found> source(Contribution into, String namespace, String name,
                                                        String extension) {
        return ModelSources.find(namespace, name, extension, path -> into.source(namespace, path));
    }

    private static String blockstatePath(BlockInfo.Base base) {
        return "assets/minecraft/blockstates/" + base.name().toLowerCase(Locale.ROOT) + ".json";
    }

    /** The model a base block is drawn with when it is not one of ours. */
    private static String baseModel(BlockInfo.Base base) {
        // A tripwire has no model of its own name, only its connected shapes.
        return base == BlockInfo.Base.TRIPWIRE ? "minecraft:block/tripwire_ns"
                : "minecraft:block/" + base.name().toLowerCase(Locale.ROOT);
    }

    /** How vanilla draws the tripwire in {@code state}, which ignores disarmed and powered. */
    private static String vanillaTripwire(String state) {
        return ShapeTemplates.vanillaTripwire(
                state.contains("attached=true"), state.contains("east=true"), state.contains("north=true"),
                state.contains("south=true"), state.contains("west=true"));
    }

    /** Every state of a base, ours pointed at our models and the rest at vanilla's. */
    private static byte[] blockstates(BlockInfo.Base base, Map<String, String> variants) {
        // Written exactly as before blocks could turn - { "model": "..." } -
        // so a pack with no turned blocks builds the same bytes it always did.
        String vanilla = variant(baseModel(base), 0, 0, false);
        StringBuilder json = new StringBuilder("{\n  \"variants\": {\n");
        List<String> every = BlockStates.everyState(base);
        for (int i = 0; i < every.size(); i++) {
            String state = every.get(i);
            String fallback = base == BlockInfo.Base.TRIPWIRE ? vanillaTripwire(state) : vanilla;
            json.append("    \"").append(state).append("\": ").append(variants.getOrDefault(state, fallback));
            json.append(i == every.size() - 1 ? "\n" : ",\n");
        }
        json.append("  }\n}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
