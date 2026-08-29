package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.item.Geometry;
import ai.resourcepack.engine.core.item.BbModel;
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
                if (block.base() != base || !bundle.namespaces().contains(block.id().namespace())) {
                    continue;
                }
                Optional<Integer> number = states.existing(block);
                if (number.isEmpty()) {
                    continue;
                }
                String namespace = block.id().namespace();
                String path = block.id().path();
                String modelName = namespace + ":block/" + path;
                // Every instrument that means this block, all pointing at one
                // model — the game changes the instrument on its own and that
                // must not change what a player sees.
                for (String state : BlockStates.statesFor(base, number.get())) {
                    models.put(state, modelName);
                }
                writeModel(block, namespace, path, into);
            }
            if (!models.isEmpty()) {
                into.add(blockstatePath(base), blockstates(base, models));
            }
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
    private void writeModel(BlockInfo block, String namespace, String path, Contribution into) {
        String target = "assets/" + namespace + "/models/block/" + path + ".json";
        if (block.model().isEmpty()) {
            // No art: the base block's own texture, so it is visible and
            // obviously unfinished rather than invisible.
            into.add(target, ("{\"parent\":\"minecraft:block/"
                    + block.base().name().toLowerCase(Locale.ROOT) + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }

        String name = block.model();
        Optional<byte[]> project = source(into, namespace, "models/" + name + ".bbmodel");
        if (project.isPresent()) {
            Optional<BbModel.Converted> converted = BbModel.convert(project.get(), namespace, name);
            if (converted.isPresent()) {
                into.add(target, converted.get().model().toString().getBytes(StandardCharsets.UTF_8));
                converted.get().textures().forEach((file, png) ->
                        into.add("assets/" + namespace + "/textures/item/" + file + ".png", png));
                into.drop("assets/" + namespace + "/models/" + name + ".bbmodel");
                return;
            }
        }

        Optional<byte[]> exported = source(into, namespace, "models/" + name + ".json");
        if (exported.isEmpty()) {
            into.error(namespace + "/blocks", block.id().path(),
                    "No model at assets/models/" + name + ".bbmodel or .json. "
                            + "The block is placeable and renders as a plain "
                            + block.base().name().toLowerCase(Locale.ROOT) + ".");
            into.add(target, ("{\"parent\":\"minecraft:block/"
                    + block.base().name().toLowerCase(Locale.ROOT) + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        Optional<Geometry.Model> model = Geometry.read(exported.get(), namespace);
        if (model.isPresent()) {
            into.add(target, model.get().json());
            into.drop("assets/" + namespace + "/models/" + name + ".json");
        }
    }

    /** Ours first, then an ItemsAdder pack's root. Same rule as items. */
    private static Optional<byte[]> source(Contribution into, String namespace, String path) {
        Optional<byte[]> ours = into.source(namespace, "assets/" + path);
        return ours.isPresent() ? ours : into.source(namespace, path);
    }

    private static String blockstatePath(BlockInfo.Base base) {
        return "assets/minecraft/blockstates/" + base.name().toLowerCase(Locale.ROOT) + ".json";
    }

    /** Every state of a base, ours pointed at our models and the rest at vanilla's. */
    private static byte[] blockstates(BlockInfo.Base base, Map<String, String> models) {
        String vanilla = "minecraft:block/" + base.name().toLowerCase(Locale.ROOT);
        StringBuilder json = new StringBuilder("{\n  \"variants\": {\n");
        List<String> every = BlockStates.everyState(base);
        for (int i = 0; i < every.size(); i++) {
            String state = every.get(i);
            json.append("    \"").append(state).append("\": { \"model\": \"")
                    .append(models.getOrDefault(state, vanilla)).append("\" }");
            json.append(i == every.size() - 1 ? "\n" : ",\n");
        }
        json.append("  }\n}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
