package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;

/**
 * One ingredient or result as a recipe wrote it, resolved against the server.
 *
 * <p>Three spellings, which cannot be mistaken for one another:
 *
 * <ul>
 *   <li>a <b>content id</b>, {@code mypack:ruby} - that item and nothing else.
 *       {@code minecraft:diamond} is read as the material, because nothing
 *       else could be meant by it;</li>
 *   <li>a <b>vanilla material</b>, {@code DIAMOND} - any diamond;</li>
 *   <li>a <b>vanilla potion</b>, {@code potion/awkward} - the material, a
 *       slash and the potion type, which is what a brewing recipe usually
 *       starts from. Legal in no other spelling: a content id needs a colon
 *       and a material has no slash.</li>
 * </ul>
 *
 * <p>Each answers two questions the stations need separately. Whether a stack
 * IS this ingredient ({@link #test}), which for a custom item is its id and
 * nothing else - not its wear, its enchantments or its name. And what to hand
 * the server's own recipe registry ({@link #exact} or {@link #loose}).
 */
final class RecipeItem {

    private final String written;
    private final ContentId custom;
    private final Material material;
    private final ItemStack stack;
    private final Items items;

    private RecipeItem(String written, ContentId custom, Material material, ItemStack stack, Items items) {
        this.written = written;
        this.custom = custom;
        this.material = material;
        this.stack = stack;
        this.items = items;
    }

    /** The ingredient, or empty when nothing on this server is called that. */
    static Optional<RecipeItem> resolve(String written, Items items) {
        if (written == null || written.isBlank()) {
            return Optional.empty();
        }
        String text = written.trim();
        int slash = text.indexOf('/');
        if (slash > 0 && text.indexOf(':') < 0) {
            return potion(text, slash, items);
        }
        Optional<ContentId> id = ContentId.parse(text);
        if (id.isPresent() && !id.get().namespace().equals("minecraft")) {
            return items.create(id.get())
                    .map(made -> new RecipeItem(text, id.get(), made.getType(), made, items));
        }
        String name = id.map(ContentId::path).orElse(text);
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        if (material == null || material.isAir() || !material.isItem()) {
            return Optional.empty();
        }
        return Optional.of(new RecipeItem(text, null, material, new ItemStack(material), items));
    }

    private static Optional<RecipeItem> potion(String text, int slash, Items items) {
        Material material = Material.matchMaterial(text.substring(0, slash).toUpperCase(Locale.ROOT));
        if (material == null) {
            return Optional.empty();
        }
        PotionType type;
        try {
            type = PotionType.valueOf(text.substring(slash + 1).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof PotionMeta)) {
            return Optional.empty();
        }
        if (!setPotion((PotionMeta) meta, type)) {
            return Optional.empty();
        }
        stack.setItemMeta(meta);
        return Optional.of(new RecipeItem(text, null, material, stack, items));
    }

    /**
     * Sets a potion's type the way this server can.
     *
     * <p>{@code setBasePotionType} arrived in 1.20.5 and is looked up rather
     * than called, so the engine still loads on 1.19.4; {@code PotionData},
     * which it replaced, is the older way and the only one there is below it.
     */
    @SuppressWarnings("deprecation")
    private static boolean setPotion(PotionMeta meta, PotionType type) {
        try {
            Method modern = PotionMeta.class.getMethod("setBasePotionType", PotionType.class);
            modern.invoke(meta, type);
            return true;
        } catch (NoSuchMethodException e) {
            try {
                meta.setBasePotionData(new PotionData(type));
                return true;
            } catch (RuntimeException old) {
                return false;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Whether {@code candidate} is this ingredient. Ignores how many there are. */
    boolean test(ItemStack candidate) {
        if (candidate == null || candidate.getType().isAir()) {
            return false;
        }
        if (custom != null) {
            return items.is(candidate, custom);
        }
        if (stack.hasItemMeta()) {
            return stack.isSimilar(candidate);
        }
        return candidate.getType() == material;
    }

    /**
     * What a recipe matches on as precisely as the server can: the whole
     * stack for a custom item or a potion, the material for a vanilla one.
     */
    RecipeChoice exact() {
        return custom != null || stack.hasItemMeta()
                ? new RecipeChoice.ExactChoice(stack.clone())
                : new RecipeChoice.MaterialChoice(material);
    }

    /**
     * Just the material underneath, for a recipe whose real check is
     * {@link #test} in an event. A smithing table will not let an item into a
     * slot unless some recipe could take it, and an exact match would refuse
     * a custom sword that has been used or enchanted.
     */
    RecipeChoice loose() {
        return new RecipeChoice.MaterialChoice(material);
    }

    /** A fresh stack of it, for a result. */
    ItemStack stack(int amount) {
        ItemStack made = stack.clone();
        made.setAmount(amount);
        return made;
    }

    /** Whether this is one of a pack's own items. */
    boolean isCustom() {
        return custom != null;
    }

    /** The material it is made of. */
    Material material() {
        return material;
    }

    @Override
    public String toString() {
        return written;
    }
}
