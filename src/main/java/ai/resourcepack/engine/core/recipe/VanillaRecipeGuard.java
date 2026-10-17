package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.Items;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.StonecutterInventory;
import org.bukkit.inventory.StonecuttingRecipe;

import java.util.Optional;

/**
 * Keeps custom items out of vanilla's recipes.
 *
 * <p>A custom item is a vanilla material underneath, and the server matches a
 * vanilla recipe on materials. So a tomato carried on paper is paper to it:
 * three of them and some leather make a book, and a ruby carried on a diamond
 * makes a diamond sword. Nobody authoring a tomato meant that.
 *
 * <p>Only vanilla's own recipes, the {@code minecraft} namespace, are refused.
 * The engine's recipes name items exactly and so already ask for the item
 * they mean, and a recipe another plugin registered is that plugin's
 * decision. And a custom item is let into a vanilla recipe whose result is
 * that same item: dyeing custom leather armour, cloning a custom map, putting
 * a banner on a custom shield, upgrading a custom sword to netherite, each of
 * which carries the item through itself rather than using it as something
 * else. The dye, the banner and the ingot still have to be real ones.
 *
 * <p>An item with {@code vanilla-recipes: true} is let in as its material,
 * and {@code items.vanilla-recipes: true} in the config lets every item in,
 * which is how it behaved before this class existed.
 */
final class VanillaRecipeGuard implements Listener {

    private final Items items;
    private volatile boolean allowAll;

    VanillaRecipeGuard(Items items) {
        this.items = items;
    }

    void allowAll(boolean allowAll) {
        this.allowAll = allowAll;
    }

    boolean allowsAll() {
        return allowAll;
    }

    Optional<ContentId> idOf(ItemStack stack) {
        return stack == null ? Optional.empty() : items.idOf(stack);
    }

    // ---- crafting table and the inventory grid -------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (allowAll) {
            return;
        }
        Recipe recipe = event.getRecipe();
        // A tool repair is vanilla's even where the server hands back no
        // recipe for it, and the repaired tool is a new stack of the
        // material: two custom swords would come out a plain one.
        if (!event.isRepair() && !isVanilla(recipe)) {
            return;
        }
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        Optional<ContentId> made = items.idOf(result);
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (substitutes(ingredient, made)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // ---- furnace, smoker, blast furnace, campfire ----------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (allowAll || !substitutes(event.getSource(), items.idOf(event.getResult()))) {
            return;
        }
        // The event names no recipe on the oldest servers this runs on, so
        // the question is asked the other way round: is there a recipe that
        // is not vanilla's and takes this very item?
        for (Recipe recipe : Bukkit.getRecipesFor(event.getResult())) {
            if (recipe instanceof CookingRecipe && !isVanilla(recipe)
                    && ((CookingRecipe<?>) recipe).getInputChoice().test(event.getSource())) {
                return;
            }
        }
        // The item stays where it is, uncooked, rather than vanishing.
        event.setCancelled(true);
    }

    // ---- stonecutter ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStonecut(InventoryClickEvent event) {
        if (allowAll || event.getSlotType() != InventoryType.SlotType.RESULT
                || !(event.getClickedInventory() instanceof StonecutterInventory)) {
            return;
        }
        ItemStack input = event.getClickedInventory().getItem(0);
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir() || !substitutes(input, items.idOf(result))) {
            return;
        }
        for (Recipe recipe : Bukkit.getRecipesFor(result)) {
            if (recipe instanceof StonecuttingRecipe && !isVanilla(recipe)
                    && ((StonecuttingRecipe) recipe).getInputChoice().test(input)) {
                return;
            }
        }
        event.setCancelled(true);
    }

    // ---- smithing table ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (allowAll || !isVanilla(event.getInventory().getRecipe())) {
            return;
        }
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        // An upgrade or a trim carries the base's identity across, so a custom
        // diamond sword made netherite is still that sword and is let through.
        Optional<ContentId> made = items.idOf(result);
        for (ItemStack input : event.getInventory().getContents()) {
            if (substitutes(input, made)) {
                event.setResult(null);
                return;
            }
        }
    }

    // ---- anvil ---------------------------------------------------------------------------

    /**
     * A custom item as the material an anvil repairs WITH: a ruby on a diamond
     * mending a diamond pickaxe. The engine's own anvil recipes are offered
     * after this, at a higher priority, so one that names the ruby still works.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (allowAll) {
            return;
        }
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (!refused(right)) {
            return;
        }
        // Two of the same custom sword, combined: the anvil's own job, and the
        // result is the left one.
        Optional<ContentId> rightId = items.idOf(right);
        if (left != null && rightId.isPresent() && rightId.equals(items.idOf(left))) {
            return;
        }
        event.setResult(null);
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Whether this stack is a custom item that vanilla's recipes may not use. */
    private boolean refused(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return items.idOf(stack)
                .map(id -> !items.info(id).map(ItemInfo::vanillaRecipes).orElse(Boolean.FALSE))
                .orElse(Boolean.FALSE);
    }

    /**
     * Whether this input would be standing in for its material: a refused
     * custom item that is not the item the recipe makes.
     */
    boolean substitutes(ItemStack input, Optional<ContentId> made) {
        return refused(input) && !items.idOf(input).equals(made);
    }

    static boolean isVanilla(Recipe recipe) {
        return recipe instanceof Keyed
                && NamespacedKey.MINECRAFT.equals(((Keyed) recipe).getKey().getNamespace());
    }
}
