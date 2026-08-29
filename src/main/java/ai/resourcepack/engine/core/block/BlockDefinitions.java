package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads block definitions.
 *
 * <p>Free of Bukkit, like every other definition parser here, so the whole of
 * reading a pack is testable without a server.
 */
public final class BlockDefinitions {

    /** What a block may be made of, by the name an author writes. */
    private static final Map<String, BlockInfo.Base> BASES = Map.of(
            "note_block", BlockInfo.Base.NOTE_BLOCK,
            "mushroom_stem", BlockInfo.Base.MUSHROOM_STEM);

    private BlockDefinitions() {
    }

    /** Everything of kind BLOCK in {@code loaded}, parsed. */
    public static Result parse(LoadReport loaded) {
        Map<ContentId, BlockInfo> blocks = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.BLOCK)) {
            parseOne(definition, diagnostics).ifPresent(block -> blocks.put(block.id(), block));
        }
        return new Result(Map.copyOf(blocks), List.copyOf(diagnostics));
    }

    private static Optional<BlockInfo> parseOne(ContentDefinition definition, List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        BlockInfo.Base base = BlockInfo.Base.NOTE_BLOCK;
        Optional<String> declared = body.string("base");
        if (declared.isPresent()) {
            base = BASES.get(declared.get().trim().toLowerCase(Locale.ROOT));
            if (base == null) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "base: " + declared.get() + " is not " + BASES.keySet()
                                + ". Using note_block."));
                base = BlockInfo.Base.NOTE_BLOCK;
            }
        }

        // A block with no model is a block wearing the base block's own
        // texture, which is a note block. Worth a warning rather than an error:
        // the block still works, and somebody mid-way through building a pack
        // should not be stopped by art they have not drawn yet.
        String model = body.string("model").orElse(null);
        if (model == null) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "No model, so this renders as a plain " + base.name().toLowerCase(Locale.ROOT)
                            + ". Add model: <name> for a model under assets/models/."));
        }

        float hardness = 1.5f;
        Optional<String> declaredHardness = body.string("hardness");
        if (declaredHardness.isPresent()) {
            try {
                hardness = Float.parseFloat(declaredHardness.get().trim());
            } catch (NumberFormatException e) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "hardness: " + declaredHardness.get() + " is not a number. Using 1.5."));
            }
            if (hardness < 0) {
                hardness = 0;
            }
        }

        int light = body.integer("light").orElse(0);
        if (light < 0 || light > 15) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "light: " + light + " is outside 0-15 and was clamped."));
            light = Math.max(0, Math.min(15, light));
        }

        ContentId drop = null;
        Optional<String> declaredDrop = body.string("drop");
        if (declaredDrop.isPresent()) {
            drop = ContentId.parse(declaredDrop.get()).orElse(null);
            if (drop == null) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "drop: " + declaredDrop.get() + " is not a namespace:id. "
                                + "It gives back itself."));
            }
        }

        return Optional.of(BlockInfo.of(definition.id(), base, model, hardness,
                body.string("tool").orElse(null), drop, light,
                body.string("sound").orElse(null)));
    }

    /** The blocks, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, BlockInfo> blocks;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, BlockInfo> blocks, List<Diagnostic> diagnostics) {
            this.blocks = blocks;
            this.diagnostics = diagnostics;
        }

        /** Every block that parsed, keyed by id. */
        public Map<ContentId, BlockInfo> blocks() {
            return blocks;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
