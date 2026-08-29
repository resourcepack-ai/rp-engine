package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.AnimationSettings;
import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.ItemStats;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads item definitions out of a load report.
 *
 * <p>Separate from the loader on purpose: the loader knows what an id is and
 * deliberately does not know what an item is, so this is where the item's own
 * fields get their meaning. Adding a field to an item touches this class and
 * nothing else.
 *
 * <p>Free of Bukkit apart from {@link org.bukkit.Material}, whose enum
 * constants resolve without a server — its registry-backed methods do not, and
 * {@link #checkGivable} is where the one question that needs a server lives.
 * Validating a material name at load rather than at give time is the difference
 * between a console line naming the file and a player being told nothing
 * happened.
 */
public final class ItemDefinitions {

    /** The default texture for {@code mypack:ruby} is {@code item/ruby}. */
    static String defaultTexture(ContentId id) {
        return "item/" + id.path();
    }

    private ItemDefinitions() {
    }

    /** Everything of kind ITEM in {@code loaded}, parsed, in id order. */
    public static Result parse(LoadReport loaded) {
        Map<ContentId, ItemInfo> items = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.ITEM)) {
            parseOne(definition, diagnostics).ifPresent(item -> items.put(item.id(), item));
        }
        return new Result(Map.copyOf(items), List.copyOf(diagnostics));
    }

    private static Optional<ItemInfo> parseOne(ContentDefinition definition, List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        Optional<String> material = body.string("material");
        if (material.isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "No material. An item is a vanilla item wearing a different model, "
                            + "so it needs one to be built on - try material: PAPER."));
            return Optional.empty();
        }
        String name = material.get().trim().toUpperCase(Locale.ROOT);
        if (!isMaterial(name)) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "There is no vanilla item called " + material.get() + "."));
            return Optional.empty();
        }

        // A copied model is validated for SHAPE here and for existence later,
        // because the id it names may belong to a pack that has not loaded yet.
        ContentId copiedFrom = null;
        Optional<String> declared = body.string("copy-model");
        if (declared.isPresent()) {
            Optional<ContentId> parsed = ContentId.parse(declared.get());
            if (parsed.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "copy-model: " + declared.get() + " is not a namespace:id."));
                return Optional.empty();
            }
            copiedFrom = parsed.get();
        }

        int maxStack = body.integer("stack").orElse(0);
        if (maxStack < 0 || maxStack > 99) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "stack: " + maxStack + " is outside 1-99 and was ignored."));
            maxStack = 0;
        }

        java.util.Map<ai.resourcepack.engine.api.ItemAction.Trigger,
                java.util.List<ai.resourcepack.engine.api.ItemAction>> actions =
                ItemActions.parse(body, definition.id(), origin, diagnostics);
        ItemActions.validate(actions, definition.id(), origin, diagnostics);

        return Optional.of(ItemInfo.of(
                definition.id(),
                name,
                body.string("name").orElse(null),
                body.strings("lore"),
                body.string("texture").orElse(defaultTexture(definition.id())),
                body.string("model").orElse(null),
                copiedFrom,
                body.string("permission").orElse(null),
                armorSlot(body, origin, where, diagnostics),
                maxStack,
                body.bool("glow").orElse(Boolean.FALSE),
                body.bool("unbreakable").orElse(Boolean.FALSE))
                .withActions(actions)
                .withAnimations(animations(body, definition.id(), origin, diagnostics))
                .withStats(stats(body, definition.id(), origin, diagnostics))
                .withFlags(body.bool("hat").orElse(Boolean.FALSE),
                        body.bool("keep-on-death").orElse(Boolean.FALSE))
                .withHitboxes(hitboxes(body, definition.id(), origin, diagnostics))
                .withLiquid(liquid(body, origin, where, diagnostics)));
    }

    /**
     * {@code liquid:}, which makes the item a bucket of one.
     *
     * <p>Checked for SHAPE only, like {@code copy-model} above and for the
     * same reason: the liquid it names may live in a pack that has not loaded
     * yet, so the only thing knowable here is whether it is an id at all.
     */
    private static ContentId liquid(DefinitionNode body, String origin, String where,
                                    List<Diagnostic> diagnostics) {
        Optional<String> declared = body.string("liquid");
        if (declared.isEmpty()) {
            return null;
        }
        Optional<ContentId> parsed = ContentId.parse(declared.get());
        if (parsed.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "liquid: " + declared.get() + " is not a namespace:id. "
                            + "The item is not a bucket."));
            return null;
        }
        return parsed.get();
    }

    /**
     * {@code place.hitboxes}, which says what a hit on each bone is worth.
     *
     * <pre>
     * place:
     *   hitboxes:
     *     head: 2.0
     *     wing: 0.5
     * </pre>
     *
     * <p>A rule the PACK states rather than one the engine invents, which is
     * the difference between a headshot being a property of a model and being
     * an opinion about somebody's game.
     */
    private static Map<String, Double> hitboxes(DefinitionNode body, ContentId id,
                                                String origin, List<Diagnostic> diagnostics) {
        DefinitionNode place = body.node("place").orElse(null);
        DefinitionNode declared = place == null ? null : place.node("hitboxes").orElse(null);
        if (declared == null) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String bone : declared.keys()) {
            Optional<Double> multiplier = declared.decimal(bone);
            if (multiplier.isEmpty() || multiplier.get() < 0) {
                diagnostics.add(Diagnostic.warning(origin, id.path(),
                        "place.hitboxes." + bone + " is not a multiplier."));
                continue;
            }
            out.put(bone, multiplier.get());
        }
        return Map.copyOf(out);
    }

    /**
     * The vanilla numbers: {@code damage}, {@code durability},
     * {@code enchantments}, {@code food}.
     *
     * <pre>
     * sword:
     *   material: IRON_SWORD
     *   durability: 500
     *   enchantments: { sharpness: 3, unbreaking: 2 }
     *   attributes:
     *     - attack_damage: 9
     *     - attack_speed: -2.4
     *   food: { nutrition: 6, saturation: 7.2, always: false }
     * </pre>
     *
     * <p>Names are vanilla's, unprefixed, because that is what an author has
     * in front of them on the wiki. They are resolved against the server's own
     * registries at CREATE time rather than here \u2014 the registries need a
     * running server, and this parser is deliberately testable without one.
     * The consequence is honest: a misspelled enchantment is one line in the
     * console the first time the item is given, not at load.
     */
    private static ItemStats stats(DefinitionNode body, ContentId id,
                                   String origin, List<Diagnostic> diagnostics) {
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        body.node("enchantments").ifPresent(node -> {
            for (String name : node.keys()) {
                Optional<Integer> level = node.integer(name);
                if (level.isEmpty()) {
                    diagnostics.add(Diagnostic.warning(origin, id.path(),
                            "enchantments." + name + " is not a level."));
                    continue;
                }
                enchantments.put(name, Math.max(1, level.get()));
            }
        });

        List<ItemStats.Modifier> modifiers = new ArrayList<>();
        for (DefinitionNode entry : body.nodes("attributes")) {
            for (String name : entry.keys()) {
                // Either "attack_damage: 9" or a block with an operation and
                // a slot on it. The short form is what almost everybody wants
                // and the long one is there when they do not.
                Optional<DefinitionNode> detailed = entry.node(name);
                if (detailed.isPresent()) {
                    Optional<Double> amount = detailed.get().decimal("amount");
                    if (amount.isEmpty()) {
                        diagnostics.add(Diagnostic.warning(origin, id.path(),
                                "attributes." + name + " has no amount."));
                        continue;
                    }
                    modifiers.add(ItemStats.Modifier.of(name, amount.get(),
                            detailed.get().string("operation").orElse(null),
                            detailed.get().string("slot").orElse(null)));
                    continue;
                }
                Optional<Double> amount = entry.decimal(name);
                if (amount.isEmpty()) {
                    diagnostics.add(Diagnostic.warning(origin, id.path(),
                            "attributes." + name + " is not a number."));
                    continue;
                }
                modifiers.add(ItemStats.Modifier.of(name, amount.get(), null, null));
            }
        }

        Integer maxDamage = body.integer("durability").filter(value -> value > 0).orElse(null);
        ItemStats.Food food = body.node("food")
                .map(node -> ItemStats.Food.of(
                        node.integer("nutrition").orElse(0),
                        node.decimal("saturation").orElse(0d).floatValue(),
                        node.bool("always").orElse(Boolean.FALSE)))
                .orElse(null);

        return enchantments.isEmpty() && modifiers.isEmpty() && maxDamage == null && food == null
                ? ItemStats.none()
                : ItemStats.of(enchantments, modifiers, maxDamage, food);
    }

    /**
     * {@code place.animations}, which says how a model's animations play.
     *
     * <pre>
     * chair:
     *   place:
     *     animations:
     *       spin: { mode: loop, speed: 0.5, priority: 10, blend: 0.25 }
     * </pre>
     *
     * <p>Under {@code place:} because these are settings about the thing you
     * put down. A name nobody recognises is left alone rather than refused:
     * the animation names live in a {@code .bbmodel} this parser has never
     * opened, so it is in no position to say one is wrong.
     */
    private static Map<String, AnimationSettings> animations(DefinitionNode body, ContentId id,
                                                             String origin, List<Diagnostic> diagnostics) {
        DefinitionNode place = body.node("place").orElse(null);
        DefinitionNode declared = place == null ? null : place.node("animations").orElse(null);
        if (declared == null) {
            return Map.of();
        }
        Map<String, AnimationSettings> out = new LinkedHashMap<>();
        for (String name : declared.keys()) {
            DefinitionNode settings = declared.node(name).orElse(null);
            if (settings == null) {
                diagnostics.add(Diagnostic.error(origin, id.path(),
                        "place.animations." + name + " should be a block of settings, "
                                + "like { mode: hold, blend: 0.25 }."));
                continue;
            }
            AnimationSettings.Mode mode = null;
            Optional<String> writtenMode = settings.string("mode");
            if (writtenMode.isPresent()) {
                mode = AnimationSettings.Mode.parse(writtenMode.get()).orElse(null);
                if (mode == null) {
                    diagnostics.add(Diagnostic.warning(origin, id.path(),
                            "place.animations." + name + ".mode: " + writtenMode.get()
                                    + " is not loop, hold or once. The model's own was kept."));
                }
            }
            out.put(name, AnimationSettings.of(
                    mode,
                    settings.decimal("speed").orElse(0d),
                    settings.integer("priority").orElse(0),
                    settings.decimal("blend").orElse(0d),
                    settings.integer("layer").orElse(0),
                    settings.decimal("weight").orElse(0d),
                    settings.strings("bones")));
        }
        return Map.copyOf(out);
    }

    /** The body slots a piece of armour can be worn in. */
    private static final List<String> ARMOR_SLOTS = List.of("head", "chest", "legs", "feet");

    /**
     * Which slot this is worn in, or null for an item that is not armour.
     *
     * <p>Refused rather than guessed when it is not one of the four: a slot
     * the game does not have produces an item that cannot be worn at all, and
     * finding that out at load beats finding it out while wearing nothing.
     */
    private static String armorSlot(DefinitionNode body, String origin, String where,
                                    List<Diagnostic> diagnostics) {
        Optional<String> declared = body.string("armor");
        if (declared.isEmpty()) {
            return null;
        }
        String slot = declared.get().trim().toLowerCase(Locale.ROOT);
        if (!ARMOR_SLOTS.contains(slot)) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "armor: " + declared.get() + " is not a body slot. One of: "
                            + String.join(", ", ARMOR_SLOTS) + "."));
            return null;
        }
        return slot;
    }

    /**
     * Whether {@code name} names a {@link org.bukkit.Material} at all.
     *
     * <p>Only the enum constant, deliberately. {@code Material.isItem()} would
     * be the better question — a material that is only ever a block cannot be
     * given to anybody — but it resolves through Bukkit's registry and throws
     * without a running server, which would make the whole of this class
     * untestable. So the shape is checked here and the givable check is
     * {@link #checkGivable}, run by the plugin where a server exists.
     */
    private static boolean isMaterial(String name) {
        try {
            org.bukkit.Material.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Warns about items built on a material nobody can hold.
     *
     * <p><strong>Needs a running server</strong> and is therefore not unit
     * tested: {@code Material.isItem()} reaches Bukkit's registry. Kept apart
     * from {@link #parse} for exactly that reason — everything testable is in
     * there, and this is the one question that cannot be.
     *
     * <p>A warning rather than an error. The definition is still registered and
     * the pack still builds; it is the give that will do nothing, and saying so
     * at load time is the whole point.
     */
    public static List<Diagnostic> checkGivable(Result parsed) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ItemInfo item : parsed.items().values()) {
            try {
                if (!org.bukkit.Material.valueOf(item.material()).isItem()) {
                    diagnostics.add(Diagnostic.warning(item.id().namespace() + "/items", item.id().path(),
                            item.material() + " is a block that never exists as an item, "
                                    + "so this can be placed in a world but never given to anybody."));
                }
            } catch (IllegalArgumentException | NoClassDefFoundError | ExceptionInInitializerError e) {
                // No server, or a material that went away. Either way this
                // check is the wrong place to complain about it.
                return List.of();
            }
        }
        return List.copyOf(diagnostics);
    }

    /** The items, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, ItemInfo> items;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, ItemInfo> items, List<Diagnostic> diagnostics) {
            this.items = items;
            this.diagnostics = diagnostics;
        }

        /** Every item that parsed, keyed by id. */
        public Map<ContentId, ItemInfo> items() {
            return items;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
