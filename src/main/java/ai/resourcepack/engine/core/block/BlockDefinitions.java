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
            "mushroom_stem", BlockInfo.Base.MUSHROOM_STEM,
            "tripwire", BlockInfo.Base.TRIPWIRE,
            "string", BlockInfo.Base.TRIPWIRE,
            "plant", BlockInfo.Base.TRIPWIRE);

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

        BlockInfo.Shape shape = shapeOf(body, origin, where, diagnostics);
        String takes = null;
        BlockInfo.Base base = BlockInfo.Base.NOTE_BLOCK;
        Optional<String> declared = body.string("base");
        if (declared.isPresent()) {
            String named = declared.get().trim().toLowerCase(Locale.ROOT);
            base = BASES.get(named);
            if (base == null && shape == BlockInfo.Shape.CUBE && shapeNamedBy(named) != null) {
                // base: minecraft:spruce_stairs says the shape as well as the block.
                shape = shapeNamedBy(named);
            }
            if (base == null && shape != BlockInfo.Shape.CUBE) {
                takes = named;
                base = BlockInfo.Base.NOTE_BLOCK;
            } else if (base == null) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "base: " + declared.get() + " is not " + BASES.keySet()
                                + ", nor a stair, slab, door, trapdoor, grate or bulb to take over. Using note_block."));
                base = BlockInfo.Base.NOTE_BLOCK;
            }
        }

        // A block with no model is a block wearing the base block's own
        // texture, which is a note block. Worth a warning rather than an error:
        // the block still works, and somebody mid-way through building a pack
        // should not be stopped by art they have not drawn yet.
        String model = ai.resourcepack.engine.core.item.ItemDefinitions.model(body);
        if (model == null) {
            // A crop or a random block names its looks rather than a model; the
            // first is what its item shows.
            for (String listed : List.of("stages", "random")) {
                Object raw = body.raw(listed);
                if (model == null && raw instanceof List && !((List<?>) raw).isEmpty()) {
                    Object first = ((List<?>) raw).get(0);
                    if (first instanceof Map && ((Map<?, ?>) first).containsKey("model")) {
                        first = ((Map<?, ?>) first).get("model");
                    }
                    model = first instanceof Map
                            ? new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(first)
                            : String.valueOf(first);
                }
            }
        }
        Map<String, String> roles = Map.of();
        if (shape != BlockInfo.Shape.CUBE) {
            roles = roleModels(shape, body, model, origin, where, diagnostics);
            if (model == null && !roles.isEmpty()) {
                // The item shows the shape's first part: a straight stair, a
                // bottom slab, a closed door's lower half.
                model = roles.get(shape.roles().get(0));
            }
        }
        if (model == null && shape != BlockInfo.Shape.CUBE) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "No model and no texture, so this " + shape.name().toLowerCase(Locale.ROOT)
                            + " draws as the vanilla block it takes over. Add texture: <name> for a picture under "
                            + "assets/textures/."));
        } else if (model == null && body.raw("stages") == null && body.raw("appearances") == null
                && body.raw("random") == null) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "No model, so this renders as a plain " + base.name().toLowerCase(Locale.ROOT)
                            + ". Add model: <name> for a model under assets/models/."));
        }

        // A plant breaks at a touch unless it says otherwise; anything solid is
        // stone-like, which is what somebody writing an ore expects.
        float hardness = base == BlockInfo.Base.TRIPWIRE ? 0f : 1.5f;
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

        // Refused rather than clamped or ignored: light comes from a block's
        // type and no state changes it, so there is no version of this that
        // works. Saying where it does work is the useful half of the message.
        if (body.raw("light") != null && shape == BlockInfo.Shape.BULB) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "light: a bulb gives off its base block's own light when switched on (15, 12, 8 or 4, "
                            + "whichever bulb it is handed), so the number was not used."));
        } else if (body.raw("light") != null) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "light: a custom block cannot give off light - that belongs to the block's "
                            + "type, not its state. A placed model can: put it on an item's "
                            + "place: block instead."));
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

        BlockInfo block = BlockInfo.of(definition.id(), base, model, hardness,
                        body.string("tool").orElse(null), drop,
                        body.string("sound").orElse(null))
                .withItemText(body.string("name").orElse(null), body.strings("lore"))
                .withActions(actions(body, definition, diagnostics));
        if (shape != BlockInfo.Shape.CUBE) {
            block = block.withShape(shape, roles, takes);
        }
        block = states(block, body, origin, where, diagnostics);
        block = behaviour(block, body, origin, where, diagnostics);
        Optional<ai.resourcepack.engine.api.StorageSpec> storage =
                ai.resourcepack.engine.core.storage.StorageDefinitions.parse(body, origin, where, diagnostics);
        if (storage.isPresent()) {
            block = block.withStorage(storage.get());
        }
        Optional<String> itemTexture = body.string("item-texture").or(() -> body.string("item_texture"));
        if (itemTexture.isPresent()) {
            block = block.withItemTexture(itemTexture.get().trim());
        }
        return Optional.of(block);
    }

    // ---- shapes ----------------------------------------------------------

    /** What an author may write for each shape. */
    private static final Map<String, BlockInfo.Shape> SHAPES = Map.ofEntries(
            Map.entry("cube", BlockInfo.Shape.CUBE), Map.entry("block", BlockInfo.Shape.CUBE),
            Map.entry("full", BlockInfo.Shape.CUBE),
            Map.entry("stairs", BlockInfo.Shape.STAIRS), Map.entry("stair", BlockInfo.Shape.STAIRS),
            Map.entry("slab", BlockInfo.Shape.SLAB), Map.entry("door", BlockInfo.Shape.DOOR),
            Map.entry("trapdoor", BlockInfo.Shape.TRAPDOOR), Map.entry("grate", BlockInfo.Shape.GRATE),
            Map.entry("transparent", BlockInfo.Shape.GRATE), Map.entry("bulb", BlockInfo.Shape.BULB),
            Map.entry("lamp", BlockInfo.Shape.BULB));

    private static BlockInfo.Shape shapeOf(DefinitionNode body, String origin, String where,
                                           List<Diagnostic> diagnostics) {
        Optional<String> declared = body.string("shape");
        if (declared.isEmpty()) {
            return BlockInfo.Shape.CUBE;
        }
        BlockInfo.Shape shape = SHAPES.get(declared.get().trim().toLowerCase(Locale.ROOT));
        if (shape == null) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "shape: " + declared.get() + " is not cube, stairs, slab, door, trapdoor, grate or bulb. "
                            + "It is a cube."));
            return BlockInfo.Shape.CUBE;
        }
        return shape;
    }

    /** The shape a vanilla block's name says it is, or null. */
    static BlockInfo.Shape shapeNamedBy(String block) {
        String name = block.startsWith("minecraft:") ? block.substring("minecraft:".length()) : block;
        if (name.endsWith("_trapdoor")) return BlockInfo.Shape.TRAPDOOR;
        if (name.endsWith("_door")) return BlockInfo.Shape.DOOR;
        if (name.endsWith("_stairs")) return BlockInfo.Shape.STAIRS;
        if (name.endsWith("_slab")) return BlockInfo.Shape.SLAB;
        if (name.endsWith("_grate")) return BlockInfo.Shape.GRATE;
        if (name.endsWith("_bulb")) return BlockInfo.Shape.BULB;
        return null;
    }

    /**
     * A model for each part a shape is drawn with.
     *
     * <p>{@code models:} names them outright, by role. Otherwise they are made
     * the way the game makes its own: a stair is the game's {@code stairs},
     * {@code inner_stairs} and {@code outer_stairs} with your textures in them,
     * a door its eight {@code door_*} halves, and so on - so a pack with a
     * texture and nothing else gets a stair that joins and turns exactly as
     * an oak one does. Written inline, so nothing has to exist on disk.
     */
    static Map<String, String> roleModels(BlockInfo.Shape shape, DefinitionNode body, String model,
                                          String origin, String where, List<Diagnostic> diagnostics) {
        Map<String, String> out = new LinkedHashMap<>();
        DefinitionNode named = body.node("models").orElse(DefinitionNode.empty());
        for (String role : named.keys()) {
            if (!shape.roles().contains(role)) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "models." + role + " is not a part of a " + shape.name().toLowerCase(Locale.ROOT)
                                + ", which are " + shape.roles() + ". It was skipped."));
                continue;
            }
            Object raw = named.raw(role);
            if (raw instanceof Map) {
                out.put(role, new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(raw));
            } else {
                named.string(role).ifPresent(value -> out.put(role, value));
            }
        }

        Map<String, String> textures = textures(body);
        if (!textures.isEmpty()) {
            for (String role : shape.roles()) {
                out.putIfAbsent(role, generated(shape, role, textures));
            }
        } else if (model != null) {
            // One model for every part: right for a grate, and for anything
            // else better than the vanilla block showing through.
            for (String role : shape.roles()) {
                out.putIfAbsent(role, model);
            }
            if (out.size() > 1 && named.keys().isEmpty() && shape != BlockInfo.Shape.BULB) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "has one model for every part of a " + shape.name().toLowerCase(Locale.ROOT)
                                + ". Give it texture: and the parts are made for you, or name each under models: "
                                + shape.roles() + "."));
            }
        }
        return out;
    }

    /** {@code texture: x} for every face, or {@code textures:} as a map of faces or a list. */
    private static Map<String, String> textures(DefinitionNode body) {
        Map<String, String> out = new LinkedHashMap<>();
        body.string("texture").ifPresent(texture -> out.put("all", texture.trim()));
        Optional<DefinitionNode> map = body.node("textures");
        if (map.isPresent()) {
            for (String key : map.get().keys()) {
                map.get().string(key).ifPresent(value -> out.put(key, value.trim()));
            }
        } else if (!body.strings("textures").isEmpty()) {
            out.put("all", body.strings("textures").get(0).trim());
        }
        return out;
    }

    /** The texture for {@code slot}, falling back to the next most general one. */
    private static String face(Map<String, String> textures, String... slots) {
        for (String slot : slots) {
            if (textures.containsKey(slot)) {
                return textures.get(slot);
            }
        }
        for (String fallback : List.of("all", "texture", "side")) {
            if (textures.containsKey(fallback)) {
                return textures.get(fallback);
            }
        }
        return textures.values().iterator().next();
    }

    /** One part of a shape, made the way the game makes its own. */
    private static String generated(BlockInfo.Shape shape, String role, Map<String, String> textures) {
        String parent;
        Map<String, String> slots = new LinkedHashMap<>();
        switch (shape) {
            case STAIRS:
                parent = role.equals("straight") ? "stairs" : role + "_stairs";
                slots.put("bottom", face(textures, "bottom", "end"));
                slots.put("top", face(textures, "top", "end"));
                slots.put("side", face(textures, "side"));
                break;
            case SLAB:
                parent = role.equals("bottom") ? "slab" : role.equals("top") ? "slab_top" : "cube_bottom_top";
                slots.put("bottom", face(textures, "bottom", "end"));
                slots.put("top", face(textures, "top", "end"));
                slots.put("side", face(textures, "side"));
                break;
            case DOOR:
                parent = "door_" + role;
                slots.put("bottom", face(textures, "bottom", "lower"));
                slots.put("top", face(textures, "top", "upper"));
                break;
            case TRAPDOOR:
                parent = "template_trapdoor_" + role;
                slots.put("texture", face(textures, "texture"));
                break;
            case BULB:
                parent = "cube_all";
                slots.put("all", role.equals("on") ? face(textures, "on", "lit") : face(textures, "off", "unlit"));
                break;
            default:
                parent = "cube_all";
                slots.put("all", face(textures, "all"));
        }
        StringBuilder json = new StringBuilder("{\"parent\":\"minecraft:block/").append(parent)
                .append("\",\"textures\":{");
        int written = 0;
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            json.append(written++ == 0 ? "" : ",").append('"').append(slot.getKey()).append("\":\"")
                    .append(slot.getValue().replace("\\", "/").replace("\"", "")).append('"');
        }
        if (!slots.containsKey("particle")) {
            json.append(",\"particle\":\"").append(slots.values().iterator().next()
                    .replace("\\", "/").replace("\"", "")).append('"');
        }
        return json.append("}}").toString();
    }

    // ---- states ----------------------------------------------------------

    /**
     * A cube's properties and how each state is drawn.
     *
     * <p>The short form is {@code rotate: horizontal}, {@code all} or
     * {@code axis}: one model, turned the way a furnace, a dispenser or a log
     * is turned, which is what almost every block with a direction wants. The
     * long form names {@code properties:} and draws {@code appearances:}, and
     * is what an importer writes for somebody else's block with five states.
     * Either way {@code click: <property>} makes a right-click turn that
     * property to its next value.
     *
     * <p>A shaped block has its base block's properties already - the game
     * runs them - so it takes only {@code appearances:}, keyed by the base
     * block's own states.
     */
    private static BlockInfo states(BlockInfo block, DefinitionNode body, String origin, String where,
                                    List<Diagnostic> diagnostics) {
        List<BlockInfo.Property> properties = new ArrayList<>();
        List<BlockInfo.Appearance> appearances = new ArrayList<>();

        Optional<String> rotate = body.string("rotate").or(() -> body.string("rotation"));
        if (rotate.isPresent() && block.shape() == BlockInfo.Shape.CUBE) {
            switch (rotate.get().trim().toLowerCase(Locale.ROOT)) {
                case "horizontal":
                case "cardinal":
                case "furnace":
                    properties.add(BlockInfo.Property.of("facing", BlockInfo.Property.Kind.FACING));
                    appearances.add(BlockInfo.Appearance.of("facing=north", "", 0, 0, false));
                    appearances.add(BlockInfo.Appearance.of("facing=east", "", 0, 90, false));
                    appearances.add(BlockInfo.Appearance.of("facing=south", "", 0, 180, false));
                    appearances.add(BlockInfo.Appearance.of("facing=west", "", 0, 270, false));
                    break;
                case "all":
                case "dispenser":
                case "dropper":
                    properties.add(BlockInfo.Property.of("facing", BlockInfo.Property.Kind.FACING_ALL));
                    appearances.add(BlockInfo.Appearance.of("facing=north", "", 0, 0, false));
                    appearances.add(BlockInfo.Appearance.of("facing=east", "", 0, 90, false));
                    appearances.add(BlockInfo.Appearance.of("facing=south", "", 0, 180, false));
                    appearances.add(BlockInfo.Appearance.of("facing=west", "", 0, 270, false));
                    appearances.add(BlockInfo.Appearance.of("facing=up", "", 270, 0, false));
                    appearances.add(BlockInfo.Appearance.of("facing=down", "", 90, 0, false));
                    break;
                case "axis":
                case "log":
                case "pillar":
                    properties.add(BlockInfo.Property.of("axis", BlockInfo.Property.Kind.AXIS));
                    appearances.add(BlockInfo.Appearance.of("axis=y", "", 0, 0, false));
                    appearances.add(BlockInfo.Appearance.of("axis=x", "", 90, 90, false));
                    appearances.add(BlockInfo.Appearance.of("axis=z", "", 90, 0, false));
                    break;
                default:
                    diagnostics.add(Diagnostic.warning(origin, where,
                            "rotate: " + rotate.get() + " is not horizontal, all or axis, so it does not turn."));
            }
        } else if (rotate.isPresent()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "rotate: a " + block.shape().name().toLowerCase(Locale.ROOT)
                            + " already turns the way the game turns one, so it was ignored."));
        }

        DefinitionNode declared = body.node("properties").orElse(DefinitionNode.empty());
        for (String name : declared.keys()) {
            if (block.shape() != BlockInfo.Shape.CUBE) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "properties: a " + block.shape().name().toLowerCase(Locale.ROOT)
                                + " has its base block's own, so " + name + " was ignored."));
                continue;
            }
            if (properties.stream().anyMatch(known -> known.name().equals(name))) {
                continue;
            }
            List<String> values = declared.strings(name);
            if (values.size() > 1) {
                properties.add(BlockInfo.Property.values(name, values));
                continue;
            }
            String kind = values.isEmpty() ? "" : values.get(0).trim().toLowerCase(Locale.ROOT);
            switch (kind) {
                case "facing":
                case "horizontal":
                    properties.add(BlockInfo.Property.of(name, BlockInfo.Property.Kind.FACING));
                    break;
                case "facing-all":
                case "facing_all":
                case "all":
                    properties.add(BlockInfo.Property.of(name, BlockInfo.Property.Kind.FACING_ALL));
                    break;
                case "axis":
                    properties.add(BlockInfo.Property.of(name, BlockInfo.Property.Kind.AXIS));
                    break;
                case "boolean":
                case "bool":
                    properties.add(BlockInfo.Property.values(name, List.of("false", "true")));
                    break;
                default:
                    diagnostics.add(Diagnostic.warning(origin, where,
                            "properties." + name + " is not facing, facing-all, axis, boolean or a list of "
                                    + "values, so it was skipped."));
            }
        }

        DefinitionNode drawn = body.node("appearances").or(() -> body.node("variants"))
                .orElse(DefinitionNode.empty());
        for (String state : drawn.keys()) {
            Optional<DefinitionNode> detail = drawn.node(state);
            String key = state.trim().equals("*") ? "" : state.trim();
            if (detail.isPresent()) {
                appearances.add(BlockInfo.Appearance.of(key,
                        ai.resourcepack.engine.core.item.ItemDefinitions.model(detail.get()),
                        detail.get().integer("x").orElse(0), detail.get().integer("y").orElse(0),
                        detail.get().bool("uvlock").orElse(Boolean.FALSE)));
            } else {
                drawn.string(state).ifPresent(model -> appearances.add(BlockInfo.Appearance.of(key, model, 0, 0, false)));
            }
        }
        for (BlockInfo.Appearance appearance : appearances) {
            if (appearance.x() % 90 != 0 || appearance.y() % 90 != 0) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "an appearance is turned by " + appearance.x() + "/" + appearance.y()
                                + " degrees; a block model turns in quarter turns only."));
            }
        }

        // random: [a, b, c] is one look picked when it is placed, the way
        // vanilla turns flowers and lily pads.
        Object randomRaw = body.raw("random");
        List<?> randomList = randomRaw instanceof List ? (List<?>) randomRaw : List.of();
        if (randomList.size() > 1 && block.shape() == BlockInfo.Shape.CUBE) {
            List<String> picks = new ArrayList<>();
            for (int i = 0; i < randomList.size(); i++) {
                picks.add(String.valueOf(i));
                Object entry = randomList.get(i);
                if (entry instanceof Map) {
                    DefinitionNode pick = DefinitionNode.of((Map<?, ?>) entry);
                    appearances.add(BlockInfo.Appearance.of("variant=" + i,
                            ai.resourcepack.engine.core.item.ItemDefinitions.model(pick),
                            pick.integer("x").orElse(0), pick.integer("y").orElse(0),
                            pick.bool("uvlock").orElse(Boolean.FALSE)));
                } else if (entry != null) {
                    appearances.add(BlockInfo.Appearance.of("variant=" + i, String.valueOf(entry), 0, 0, false));
                }
            }
            properties.add(BlockInfo.Property.random("variant", picks));
        }

        // stages: [a, b, c] is a growing block's short form: an age property
        // with one value per stage, each drawn by its model.
        // Each stage is a model name or a model written inline.
        Object stagesRaw = body.raw("stages");
        List<?> stages = stagesRaw instanceof List ? (List<?>) stagesRaw : List.of();
        if (!stages.isEmpty() && block.shape() == BlockInfo.Shape.CUBE
                && properties.stream().noneMatch(known -> known.name().equals("age"))) {
            List<String> ages = new ArrayList<>();
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
            for (int i = 0; i < stages.size(); i++) {
                ages.add(String.valueOf(i));
                Object stage = stages.get(i);
                appearances.add(BlockInfo.Appearance.of("age=" + i,
                        stage instanceof Map ? gson.toJson(stage) : String.valueOf(stage), 0, 0, false));
            }
            properties.add(BlockInfo.Property.values("age", ages));
        }

        String cycle = body.string("click").or(() -> body.string("cycle")).map(String::trim).orElse(null);
        if (cycle != null && properties.stream().noneMatch(known -> known.name().equals(cycle))) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "click: " + cycle + " is not one of its properties, so a click changes nothing."));
        }

        if (properties.isEmpty() && appearances.isEmpty()) {
            return block;
        }
        int states = 1;
        for (BlockInfo.Property property : properties) {
            states *= property.values().size();
        }
        if (block.shape() == BlockInfo.Shape.CUBE && states > 16) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "has " + states + " states, and every one is a spare state of the pool ("
                            + BlockStates.capacity(block.base()) + " for a " + block.base().name().toLowerCase(Locale.ROOT)
                            + "). Consider fewer values, or mushroom_stem."));
        }
        BlockInfo stated = block.withStates(properties, appearances, cycle);
        return growth(stated, body, origin, where, diagnostics);
    }

    /**
     * {@code strip:}, {@code click-into:}, {@code falls:} and
     * {@code blast-resistant:}: what a block does that a vanilla one would.
     */
    private static BlockInfo behaviour(BlockInfo block, DefinitionNode body, String origin, String where,
                                       List<Diagnostic> diagnostics) {
        ContentId strip = null;
        ContentId stripDrop = null;
        Optional<DefinitionNode> stripNode = body.node("strip");
        if (stripNode.isPresent()) {
            strip = id(stripNode.get().string("into").orElse(null), block, "strip.into", origin, where, diagnostics);
            stripDrop = id(stripNode.get().string("drop").orElse(null), block, "strip.drop", origin, where,
                    diagnostics);
        } else {
            strip = id(body.string("strip").orElse(null), block, "strip", origin, where, diagnostics);
        }
        ContentId clickInto = id(body.string("click-into").or(() -> body.string("click_into")).orElse(null), block,
                "click-into", origin, where, diagnostics);
        boolean falls = body.bool("falls").orElse(Boolean.FALSE);
        boolean blastProof = body.bool("blast-resistant").or(() -> body.bool("blast_resistant"))
                .orElse(Boolean.FALSE);
        if (strip == null && clickInto == null && !falls && !blastProof) {
            return block;
        }
        if (falls && (block.shape() != BlockInfo.Shape.CUBE || block.base() == BlockInfo.Base.TRIPWIRE)) {
            diagnostics.add(Diagnostic.warning(origin, where, "falls: only a solid cube falls, so it does not."));
            falls = false;
        }
        return block.withBehaviour(new BlockInfo.Behaviour(strip, stripDrop, clickInto, falls, blastProof));
    }

    /** A block or item id, qualified by the block's own namespace when written bare. */
    private static ContentId id(String written, BlockInfo block, String key, String origin, String where,
                                List<Diagnostic> diagnostics) {
        if (written == null || written.isBlank()) {
            return null;
        }
        String value = written.trim();
        Optional<ContentId> parsed = ContentId.parse(value.contains(":") ? value
                : block.id().namespace() + ":" + value);
        if (parsed.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, where, key + ": " + written + " is not an id, so it was skipped."));
        }
        return parsed.orElse(null);
    }

    /**
     * {@code grow:}, a property stepping on by itself: {@code grow: 60s} for
     * the {@code age} property every minute or so, or a block with
     * {@code property}, {@code every}, {@code light} and {@code bone-meal}.
     */
    private static BlockInfo growth(BlockInfo block, DefinitionNode body, String origin, String where,
                                    List<Diagnostic> diagnostics) {
        if (body.raw("grow") == null) {
            return block;
        }
        DefinitionNode grow = body.node("grow").orElse(DefinitionNode.empty());
        String every = body.node("grow").isPresent() ? grow.string("every").orElse("60s")
                : body.string("grow").orElse("60s");
        if (every.equalsIgnoreCase("true")) {
            every = "60s";
        }
        String property = grow.string("property").orElse(null);
        if (property == null) {
            property = block.properties().stream().anyMatch(known -> known.name().equals("age")) ? "age"
                    : block.properties().stream().filter(known -> known.kind() == BlockInfo.Property.Kind.VALUES)
                    .map(BlockInfo.Property::name).findFirst().orElse(null);
        }
        String chosen = property;
        if (chosen == null || block.properties().stream().noneMatch(known -> known.name().equals(chosen)
                && known.kind() == BlockInfo.Property.Kind.VALUES)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "grow: there is no property with values to grow through (give it stages: or an age "
                            + "property), so it does not grow."));
            return block;
        }
        if (block.shape() != BlockInfo.Shape.CUBE) {
            diagnostics.add(Diagnostic.warning(origin, where, "grow: only a cube or a plant grows, so it does not."));
            return block;
        }
        Integer seconds = seconds(every);
        if (seconds == null) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "grow.every: " + every + " is not a time like 30s, 5m or 600t. Using 60s."));
            seconds = 60;
        }
        return block.withGrowth(BlockInfo.Growth.of(chosen, seconds, grow.integer("light").orElse(0),
                grow.bool("bone-meal").or(() -> grow.bool("bone_meal")).orElse(Boolean.TRUE)));
    }

    /** {@code 30s}, {@code 5m}, {@code 600t}, or a bare number of ticks, in whole seconds (at least 1). */
    static Integer seconds(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("ms")) {
                return Math.max(1, (int) (Double.parseDouble(value.substring(0, value.length() - 2)) / 1000));
            }
            if (value.endsWith("s")) {
                return Math.max(1, (int) Double.parseDouble(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("m")) {
                return Math.max(1, (int) (Double.parseDouble(value.substring(0, value.length() - 1)) * 60));
            }
            if (value.endsWith("h")) {
                return Math.max(1, (int) (Double.parseDouble(value.substring(0, value.length() - 1)) * 3600));
            }
            if (value.endsWith("t")) {
                value = value.substring(0, value.length() - 1);
            }
            return Math.max(1, (int) (Double.parseDouble(value) / 20));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * What the block does, written exactly as an item's {@code actions:}.
     *
     * <p>A block's id is the item that places it, so these are that item's
     * actions: {@code right_click} is a click with it in hand, and
     * {@code place}, {@code interact} and {@code remove} are the block itself
     * being put down, right-clicked and mined.
     */
    private static java.util.Map<ai.resourcepack.engine.api.ItemAction.Trigger,
            List<ai.resourcepack.engine.api.ItemAction>> actions(DefinitionNode body, ContentDefinition definition,
                                                                List<Diagnostic> diagnostics) {
        var parsed = ai.resourcepack.engine.core.item.ItemActions.parse(body, definition.id(),
                definition.origin(), diagnostics);
        ai.resourcepack.engine.core.item.ItemActions.validate(parsed, definition.id(), definition.origin(), diagnostics);
        return parsed;
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
