package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemInfo;
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
                body.bool("unbreakable").orElse(Boolean.FALSE)));
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
