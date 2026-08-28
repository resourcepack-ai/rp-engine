package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said an item is.
 *
 * <p>A value type, free of Bukkit, so the whole of parsing and asset emission
 * is testable without a server. Turning one of these into an {@code ItemStack}
 * is the only part that needs one.
 */
public final class ItemInfo {

    private final ContentId id;
    private final String material;
    private final String name;
    private final List<String> lore;
    private final String texture;
    private final String modelFile;
    private final ContentId copiedFrom;
    private final String permission;
    private final String armor;
    private final int maxStack;
    private final boolean glow;
    private final boolean unbreakable;
    private final Map<ItemAction.Trigger, List<ItemAction>> actions;

    private ItemInfo(ContentId id, String material, String name, List<String> lore,
                     String texture, String modelFile, ContentId copiedFrom, String permission, String armor,
                     int maxStack, boolean glow, boolean unbreakable,
                     Map<ItemAction.Trigger, List<ItemAction>> actions) {
        this.id = id;
        this.material = material;
        this.name = name;
        this.lore = lore;
        this.texture = texture;
        this.modelFile = modelFile;
        this.copiedFrom = copiedFrom;
        this.permission = permission;
        this.armor = armor;
        this.maxStack = maxStack;
        this.glow = glow;
        this.unbreakable = unbreakable;
        this.actions = actions;
    }

    /** Engine internal; built by the item loader from a definition body. */
    public static ItemInfo of(ContentId id, String material, String name, List<String> lore,
                              String texture, String modelFile, ContentId copiedFrom, String permission, String armor,
                              int maxStack, boolean glow, boolean unbreakable) {
        return new ItemInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(material, "material"),
                name == null ? "" : name,
                lore == null ? List.of() : List.copyOf(lore),
                texture == null ? "" : texture,
                modelFile == null ? "" : modelFile,
                copiedFrom,
                permission == null ? "" : permission,
                armor == null ? "" : armor,
                maxStack,
                glow,
                unbreakable,
                Map.of());
    }

    /**
     * The same item, with what it does when it is used.
     *
     * <p>A copy rather than a thirteenth argument to {@link #of}: that
     * factory is the supported surface, its shape is already at the limit of
     * what anybody can read, and adding to it would break every caller for a
     * field almost no item has.
     */
    public ItemInfo withActions(Map<ItemAction.Trigger, List<ItemAction>> actions) {
        return new ItemInfo(id, material, name, lore, texture, modelFile, copiedFrom, permission,
                armor, maxStack, glow, unbreakable,
                actions == null || actions.isEmpty() ? Map.of() : Map.copyOf(actions));
    }

    /** What this item does, by trigger. Empty for almost every item. */
    public Map<ItemAction.Trigger, List<ItemAction>> actions() {
        return actions;
    }

    /** What it does on one trigger, in order. Empty if it does nothing. */
    public List<ItemAction> actions(ItemAction.Trigger trigger) {
        return actions.getOrDefault(trigger, List.of());
    }

    /** The item's id. */
    public ContentId id() {
        return id;
    }

    /** The vanilla material it is built on, as written in the file. */
    public String material() {
        return material;
    }

    /** Its display name, or empty for the material's own. */
    public Optional<String> name() {
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** Its lore lines, possibly empty. */
    public List<String> lore() {
        return lore;
    }

    /**
     * The texture path within the pack's own namespace, without the extension
     * — {@code item/ruby} for {@code assets/textures/item/ruby.png}.
     */
    public String texture() {
        return texture;
    }

    /**
     * The model file this item is shaped by, under the pack's
     * {@code assets/models/}, without the extension.
     *
     * <p>Empty means a flat sprite: the texture extruded by
     * {@code minecraft:item/generated}, which is what a vanilla item is. A 3D
     * item names a Blockbench export here instead.
     */
    public Optional<String> model() {
        return modelFile.isEmpty() ? Optional.empty() : Optional.of(modelFile);
    }

    /**
     * Another content id whose model this item copies, if it said so.
     *
     * <p>Present means no model or texture is emitted for this item: it points
     * at somebody else's. That is how a pack ships five items that look the
     * same without five copies of one PNG.
     */
    public Optional<ContentId> copiedFrom() {
        return Optional.ofNullable(copiedFrom);
    }

    /**
     * The id whose asset path this item renders through — its own unless it
     * borrows one. This is what goes into the {@code item_model} component.
     */
    public ContentId modelId() {
        return copiedFrom == null ? id : copiedFrom;
    }

    /**
     * A permission node somebody needs to use this item, if the pack set one.
     *
     * <p>Checked when the item is USED, not when it is given or held. A
     * permission that stopped somebody holding an item would mean taking it out
     * of their inventory, which is a thing to do to somebody's stuff and not a
     * decision an engine makes.
     */
    public Optional<String> permission() {
        return permission.isEmpty() ? Optional.empty() : Optional.of(permission);
    }

    /**
     * Which body slot this is worn in, if it is armour.
     *
     * <p>{@code head}, {@code chest}, {@code legs} or {@code feet}. Empty for
     * everything that is not worn.
     *
     * <p>Armour here is the vanilla 1.21.4 path rather than the old dyed-
     * leather or trim tricks: the item declares an {@code equippable}
     * component naming an equipment asset, and the pack ships the layers that
     * asset points at. That means real armour with its own art, on any base
     * item, without spending the leather colour space.
     */
    public Optional<String> armor() {
        return armor.isEmpty() ? Optional.empty() : Optional.of(armor);
    }

    /** Its maximum stack size, or empty for the material's own. */
    public Optional<Integer> maxStack() {
        return maxStack > 0 ? Optional.of(maxStack) : Optional.empty();
    }

    /** Whether it has an enchantment glint. */
    public boolean glow() {
        return glow;
    }

    /** Whether it is unbreakable. */
    public boolean unbreakable() {
        return unbreakable;
    }

    @Override
    public String toString() {
        return id + " (" + material + ")";
    }
}
