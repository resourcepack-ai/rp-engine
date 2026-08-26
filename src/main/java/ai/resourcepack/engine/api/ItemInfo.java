package ai.resourcepack.engine.api;

import java.util.List;
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
    private final int maxStack;
    private final boolean glow;
    private final boolean unbreakable;

    private ItemInfo(ContentId id, String material, String name, List<String> lore,
                     String texture, String modelFile, ContentId copiedFrom, String permission,
                     int maxStack, boolean glow, boolean unbreakable) {
        this.id = id;
        this.material = material;
        this.name = name;
        this.lore = lore;
        this.texture = texture;
        this.modelFile = modelFile;
        this.copiedFrom = copiedFrom;
        this.permission = permission;
        this.maxStack = maxStack;
        this.glow = glow;
        this.unbreakable = unbreakable;
    }

    /** Engine internal; built by the item loader from a definition body. */
    public static ItemInfo of(ContentId id, String material, String name, List<String> lore,
                              String texture, String modelFile, ContentId copiedFrom, String permission,
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
                maxStack,
                glow,
                unbreakable);
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
