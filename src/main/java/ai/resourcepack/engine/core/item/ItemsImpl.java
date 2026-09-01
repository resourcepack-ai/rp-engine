package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.ItemStats;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.core.version.Compatibility;
import ai.resourcepack.engine.core.version.Vanilla;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns {@link ItemInfo} into real stacks and back.
 *
 * <p>Internal. The catalogue it serves is replaced whole on a reload, matching
 * the rule everywhere else: a namespace is replaced whole or not at all.
 *
 * <p><strong>The identity is in persistent data, not in the model.</strong> An
 * item renamed in an anvil is still itself, and a vanilla item somebody named
 * "Ruby" is still not one. That key is namespaced by the plugin, which is the
 * concrete reason this plugin can never be renamed once anybody has items in a
 * chest.
 */
public final class ItemsImpl implements Items {

    private final Plugin plugin;
    private final NamespacedKey key;
    private final ItemModelWiring wiring;
    private final ItemComponents components;
    private final Equipping equipping;
    private volatile Map<ContentId, ItemInfo> items;

    /**
     * @param compatibility what this server's version allows, which decides
     *                      how a model is addressed and which of an item's
     *                      options can be honoured at all
     * @param numbers       the allocator, used only on the versions that
     *                      address models by number
     */
    public ItemsImpl(Plugin plugin, Compatibility compatibility, ModelNumbers numbers) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "id");
        this.wiring = ItemModelWiring.forEra(compatibility.itemEra(), numbers);
        this.components = ItemComponents.forServer(compatibility);
        this.equipping = Equipping.forServer(compatibility);
        this.items = Map.of();
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, ItemInfo> loaded) {
        this.items = loaded == null ? Map.of() : Map.copyOf(loaded);
    }

    @Override
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(items.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    @Override
    public Optional<ItemInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(items.get(id));
    }

    @Override
    public Optional<ItemInfo> info(String id) {
        return ContentId.parse(id).flatMap(this::info);
    }

    @Override
    public Optional<ItemStack> create(ContentId id) {
        return create(id, 1);
    }

    @Override
    public Optional<ItemStack> create(ContentId id, int amount) {
        Optional<ItemInfo> found = info(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ItemInfo item = found.get();
        Material material;
        try {
            material = Material.valueOf(item.material());
        } catch (IllegalArgumentException e) {
            // Validated at load time, so this is a material that existed when
            // the pack was read and does not now — a server downgrade.
            return Optional.empty();
        }

        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.of(stack);
        }

        // The whole id scheme in one line: the id IS the model reference, so
        // there is nothing to allocate and nothing to keep in sync.
        wiring.apply(meta, item.modelId());
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());

        item.name().ifPresent(name -> meta.setDisplayName(colour(name)));
        if (!item.lore().isEmpty()) {
            List<String> lore = new ArrayList<>(item.lore().size());
            for (String line : item.lore()) {
                lore.add(colour(line));
            }
            meta.setLore(lore);
        }
        ItemComponents.Warner warner = warnerFor(item);
        item.armor().ifPresent(slot -> equipping.wearable(meta, item.id(), slot, warner));
        item.maxStack().ifPresent(size -> components.maxStackSize(meta, size, warner));
        if (item.glow()) {
            components.glint(meta, warner);
        }
        if (item.unbreakable()) {
            meta.setUnbreakable(true);
        }
        if (!item.stats().isEmpty()) {
            stats(meta, item, warner);
        }

        stack.setItemMeta(meta);
        return Optional.of(stack);
    }

    /**
     * The vanilla numbers, resolved against this server's own registries.
     *
     * <p>Here rather than in the parser because every lookup below needs a
     * running server, and the parser is deliberately testable without one.
     * The cost is stated in {@code ItemDefinitions.stats}: a misspelled
     * enchantment is one console line the first time the item is given rather
     * than one at load.
     *
     * <p>Each is skipped on its own if it does not resolve. An item that comes
     * out with three of its four enchantments is traceable; one that refuses
     * to exist because of a typo in a fourth is not.
     */
    private void stats(ItemMeta meta, ItemInfo item, ItemComponents.Warner warner) {
        ItemStats stats = item.stats();

        for (Map.Entry<String, Integer> entry : stats.enchantments().entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(entry.getKey().toLowerCase(Locale.ROOT));
            Enchantment enchantment = key == null ? null : Registry.ENCHANTMENT.get(key);
            if (enchantment == null) {
                warn(item, "no enchantment called " + entry.getKey());
                continue;
            }
            // Ignoring the level restrictions on purpose: a pack asking for
            // Sharpness X is asking for it, and the game applies it happily.
            meta.addEnchant(enchantment, entry.getValue(), true);
        }

        for (ItemStats.Modifier modifier : stats.modifiers()) {
            // Through Vanilla rather than the registry directly: the vanilla
            // attributes lost their "generic." prefix in 1.21.3, so a pack
            // written for either side of that resolves on both.
            Attribute attribute = Vanilla.attribute(modifier.attribute()).orElse(null);
            if (attribute == null) {
                warn(item, "no attribute called " + modifier.attribute());
                continue;
            }
            AttributeModifier.Operation operation = operation(modifier.operation());
            if (operation == null) {
                warn(item, modifier.operation() + " is not add, multiply_base or multiply");
                continue;
            }
            // The key names the item and the attribute, so two modifiers on
            // one item do not overwrite each other and a modifier from this
            // pack is distinguishable from anybody else's. Both arms key off
            // it — the older one derives its UUID from this same string.
            NamespacedKey id = new NamespacedKey(plugin,
                    item.id().namespace() + "." + item.id().path()
                            + "." + attribute.getKey().getKey());
            if (!components.modifier(meta, attribute, id, modifier.amount(), operation,
                    modifier.slot())) {
                warn(item, modifier.slot() + " is not a slot");
            }
        }

        stats.maxDamage().ifPresent(max -> {
            // Durability lives on Damageable rather than on ItemMeta, and a
            // material that cannot take damage simply has none — a paper
            // carrier with a durability line is a mistake worth naming rather
            // than a silent no-op. That is the pack's mistake and is reported
            // as such; the version's is reported by the arm.
            if (meta instanceof Damageable) {
                components.maxDamage(meta, max, warner);
            } else {
                warn(item, "durability on " + item.material() + ", which cannot be damaged");
            }
        });

        stats.food().ifPresent(food -> components.food(meta, food, warner));
    }

    private static AttributeModifier.Operation operation(String written) {
        switch (written.toLowerCase(Locale.ROOT)) {
            case "add":
            case "add_value":
                return AttributeModifier.Operation.ADD_NUMBER;
            case "multiply_base":
            case "add_multiplied_base":
                return AttributeModifier.Operation.ADD_SCALAR;
            case "multiply":
            case "add_multiplied_total":
                return AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            default:
                return null;
        }
    }

    @Override
    public boolean wearModel(ItemStack stack, ContentId modelId) {
        if (stack == null || modelId == null) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        wiring.apply(meta, modelId);
        stack.setItemMeta(meta);
        return true;
    }

    /**
     * Says once, per item and per option, that this server's Minecraft is too
     * old for something the pack asked for.
     *
     * <p>Through the plugin logger rather than a load diagnostic because it is
     * not a mistake in the pack: the same content on a newer server is
     * correct, and a diagnostic would tell an author to fix something that is
     * not wrong.
     */
    private ItemComponents.Warner warnerFor(ItemInfo item) {
        return (option, needs) -> plugin.getLogger().warning(
                item.id() + ": " + option + " needs Minecraft " + needs.since()
                        + " and this server is older. " + needs.without());
    }

    private void warn(ItemInfo item, String problem) {
        plugin.getLogger().warning(item.id() + ": " + problem + ".");
    }

    @Override
    public Optional<ContentId> idOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String id = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return ContentId.parse(id);
    }

    @Override
    public boolean is(ItemStack stack, ContentId id) {
        return id != null && idOf(stack).filter(id::equals).isPresent();
    }

    /**
     * Ampersand colour codes, because that is what a server owner writes in a
     * YAML file and has written in every plugin they have ever configured.
     */
    private static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
