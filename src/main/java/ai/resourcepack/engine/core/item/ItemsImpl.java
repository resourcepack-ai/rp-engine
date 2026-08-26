package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.Items;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    private final NamespacedKey key;
    private volatile Map<ContentId, ItemInfo> items;

    public ItemsImpl(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "id");
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
        ContentId model = item.modelId();
        meta.setItemModel(new NamespacedKey(model.namespace(), model.path()));
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());

        item.name().ifPresent(name -> meta.setDisplayName(colour(name)));
        if (!item.lore().isEmpty()) {
            List<String> lore = new ArrayList<>(item.lore().size());
            for (String line : item.lore()) {
                lore.add(colour(line));
            }
            meta.setLore(lore);
        }
        item.armor().ifPresent(slot -> wearable(meta, item, slot));
        item.maxStack().ifPresent(meta::setMaxStackSize);
        if (item.glow()) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        if (item.unbreakable()) {
            meta.setUnbreakable(true);
        }

        stack.setItemMeta(meta);
        return Optional.of(stack);
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
     * Makes a stack wearable, with the pack's own art on the body.
     *
     * <p>The component names an equipment asset the build wrote; see
     * {@code ItemAssets.writeEquipment}. Any item can carry it, so a wizard
     * hat can be a real helmet without also being a leather cap.
     */
    private static void wearable(ItemMeta meta, ItemInfo item, String slot) {
        EquippableComponent equippable = meta.getEquippable();
        equippable.setSlot(slotOf(slot));
        equippable.setModel(new NamespacedKey(item.id().namespace(), item.id().path()));
        meta.setEquippable(equippable);
    }

    private static EquipmentSlot slotOf(String slot) {
        switch (slot) {
            case "chest":
                return EquipmentSlot.CHEST;
            case "legs":
                return EquipmentSlot.LEGS;
            case "feet":
                return EquipmentSlot.FEET;
            default:
                return EquipmentSlot.HEAD;
        }
    }

    /**
     * Ampersand colour codes, because that is what a server owner writes in a
     * YAML file and has written in every plugin they have ever configured.
     */
    private static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
