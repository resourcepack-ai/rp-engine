package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.RecipeInfo;
import ai.resourcepack.engine.core.version.Compatibility;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The smithing table and the anvil, which take a recipe's word for what goes
 * in but not for what comes out.
 *
 * <h2>Smithing</h2>
 *
 * <p>A smithing recipe IS registered with the server - a smithing table will
 * not let an item into a slot unless some recipe could take it - but with
 * {@link RecipeItem#loose loose} choices, the material underneath, and the
 * real check is here. An exact match would refuse a custom sword with one
 * point of wear on it, and the whole point of an upgrade is the sword you
 * have been using. So the result is decided in {@link PrepareSmithingEvent}
 * by item identity, and the server's own take logic consumes one of each, as
 * it always does.
 *
 * <p>Deciding the result here is also what keeps the result the same on
 * every version. Vanilla copies the base onto the result, and how changed in
 * 1.20.5: before it, the base's whole tag replaced the result's, so a custom
 * result came out wearing the base's identity. Here the result is always the
 * recipe's own item, with the base's enchantments, wear and trim carried
 * across when {@code copy-data} is on.
 *
 * <h2>Anvil</h2>
 *
 * <p>There is no anvil recipe in the game or in Bukkit; an anvil repairs,
 * renames and combines enchantments and that is all. So an anvil recipe is
 * entirely this class: the result is offered in {@link PrepareAnvilEvent} and
 * taking it is done by hand, because vanilla's own take would empty the
 * whole left slot and refuses a cost of nothing.
 *
 * <p>Nothing here touches {@code InventoryView}. It became an interface in
 * 1.21, and a call compiled against the newer API fails on an older server
 * with an error the oldest-API audit cannot see; every method used instead is
 * on the event or the inventory, which never changed shape.
 */
final class RecipeStations implements Listener {

    /** What vanilla's anvil wears down by, per use. */
    private static final float ANVIL_WEAR_CHANCE = 0.12f;

    private final Items items;
    private final Compatibility compatibility;

    private List<Smithing> smithing = List.of();
    private Set<NamespacedKey> smithingKeys = Set.of();
    private List<Anvil> anvil = List.of();

    RecipeStations(Items items, Compatibility compatibility) {
        this.items = items;
        this.compatibility = compatibility;
    }

    /** One smithing recipe, resolved. A trim has no result. */
    static final class Smithing {
        final RecipeInfo info;
        final RecipeItem template;
        final RecipeItem base;
        final RecipeItem addition;
        final RecipeItem result;

        Smithing(RecipeInfo info, RecipeItem template, RecipeItem base, RecipeItem addition, RecipeItem result) {
            this.info = info;
            this.template = template;
            this.base = base;
            this.addition = addition;
            this.result = result;
        }
    }

    /** One anvil recipe, resolved. A repair has no result; a recipe with no addition wants that slot empty. */
    static final class Anvil {
        final RecipeInfo info;
        final RecipeItem base;
        final RecipeItem addition;
        final RecipeItem result;

        Anvil(RecipeInfo info, RecipeItem base, RecipeItem addition, RecipeItem result) {
            this.info = info;
            this.base = base;
            this.addition = addition;
            this.result = result;
        }
    }

    void replace(Map<NamespacedKey, Smithing> smithing, List<Anvil> anvil) {
        this.smithing = List.copyOf(smithing.values());
        this.smithingKeys = Set.copyOf(smithing.keySet());
        this.anvil = List.copyOf(anvil);
    }

    void clear() {
        smithing = List.of();
        smithingKeys = Set.of();
        anvil = List.of();
    }

    // ---- smithing ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (smithing.isEmpty()) {
            return;
        }
        SmithingInventory inventory = event.getInventory();
        // The two-slot table had no template, and its slots are numbered from
        // the base.
        boolean templates = compatibility.has(Feature.SMITHING_TEMPLATES);
        ItemStack template = templates ? inventory.getItem(0) : null;
        ItemStack base = inventory.getItem(templates ? 1 : 0);
        ItemStack addition = inventory.getItem(templates ? 2 : 1);

        for (Smithing recipe : smithing) {
            if ((templates && !recipe.template.test(template))
                    || !recipe.base.test(base) || !recipe.addition.test(addition)) {
                continue;
            }
            if (recipe.info.type() == RecipeInfo.Type.SMITHING) {
                event.setResult(upgraded(recipe, base));
            }
            // A trim's result is vanilla's own - the base, trimmed - and is
            // left as the server worked it out.
            return;
        }

        // One of ours was the recipe the server matched, on materials alone,
        // and the items are not the ones it names: nothing comes out.
        Recipe matched = inventory.getRecipe();
        if (matched instanceof Keyed && smithingKeys.contains(((Keyed) matched).getKey())) {
            event.setResult(null);
        }
    }

    private ItemStack upgraded(Smithing recipe, ItemStack base) {
        ItemStack made = recipe.result.stack(recipe.info.amount());
        if (!recipe.info.copyData() || base == null) {
            return made;
        }
        ItemMeta from = base.getItemMeta();
        ItemMeta to = made.getItemMeta();
        if (from == null || to == null) {
            return made;
        }
        for (Map.Entry<Enchantment, Integer> enchantment : from.getEnchants().entrySet()) {
            to.addEnchant(enchantment.getKey(), enchantment.getValue(), true);
        }
        if (from instanceof Damageable && to instanceof Damageable) {
            int damage = ((Damageable) from).getDamage();
            int max = maxDurability(made);
            if (damage > 0 && max > 0) {
                // Never so worn that the upgrade breaks on its first use.
                ((Damageable) to).setDamage(Math.min(damage, max - 1));
            }
        }
        if (from instanceof ArmorMeta && to instanceof ArmorMeta && ((ArmorMeta) from).hasTrim()) {
            ((ArmorMeta) to).setTrim(((ArmorMeta) from).getTrim());
        }
        made.setItemMeta(to);
        return made;
    }

    // ---- anvil ------------------------------------------------------------------------

    /** What an anvil makes from what is in it, and how much of the addition it uses. */
    private static final class Offer {
        final Anvil recipe;
        final ItemStack result;
        final int additionUsed;

        Offer(Anvil recipe, ItemStack result, int additionUsed) {
            this.recipe = recipe;
            this.result = result;
            this.additionUsed = additionUsed;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (anvil.isEmpty()) {
            return;
        }
        AnvilInventory inventory = event.getInventory();
        Offer offer = offer(inventory.getItem(0), inventory.getItem(1));
        if (offer == null) {
            return;
        }
        event.setResult(offer.result.clone());
        inventory.setRepairCost(offer.recipe.info.cost());
        inventory.setRepairCostAmount(offer.additionUsed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTakeFromAnvil(InventoryClickEvent event) {
        if (anvil.isEmpty() || event.getSlotType() != InventoryType.SlotType.RESULT
                || !(event.getClickedInventory() instanceof AnvilInventory)) {
            return;
        }
        AnvilInventory inventory = (AnvilInventory) event.getClickedInventory();
        ItemStack left = inventory.getItem(0);
        ItemStack right = inventory.getItem(1);
        Offer offer = offer(left, right);
        if (offer == null) {
            // Vanilla's own repair, rename or enchanting: none of ours.
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        int cost = offer.recipe.info.cost();
        if (!creative && player.getLevel() < cost) {
            return;
        }

        ItemStack result = offer.result.clone();
        ClickType click = event.getClick();
        boolean toInventory = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        ItemStack cursor = event.getCursor();
        if (!toInventory) {
            if (click != ClickType.LEFT && click != ClickType.RIGHT) {
                // Number keys, dropping, the off hand: not worth a second set
                // of rules for where the result lands. Take it with the mouse.
                return;
            }
            if (cursor != null && !cursor.getType().isAir()
                    && (!cursor.isSimilar(result)
                    || cursor.getAmount() + result.getAmount() > cursor.getMaxStackSize())) {
                return;
            }
        }

        if (!creative && cost > 0) {
            player.setLevel(player.getLevel() - cost);
        }
        // One of the base, even when there are more: vanilla empties the slot,
        // which is right for a sword and a loss for a stack of anything.
        inventory.setItem(0, less(left, 1));
        if (offer.recipe.addition != null) {
            inventory.setItem(1, less(right, offer.additionUsed));
        }
        inventory.setItem(2, null);

        if (toInventory) {
            for (ItemStack spill : player.getInventory().addItem(result).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), spill);
            }
        } else if (cursor == null || cursor.getType().isAir()) {
            player.setItemOnCursor(result);
        } else {
            ItemStack merged = cursor.clone();
            merged.setAmount(cursor.getAmount() + result.getAmount());
            player.setItemOnCursor(merged);
        }
        wear(inventory, creative);
        player.updateInventory();
    }

    private Offer offer(ItemStack left, ItemStack right) {
        if (left == null || left.getType().isAir()) {
            return null;
        }
        boolean rightEmpty = right == null || right.getType().isAir();
        for (Anvil recipe : anvil) {
            if (!recipe.base.test(left)) {
                continue;
            }
            int available = 0;
            if (recipe.addition == null) {
                if (!rightEmpty) {
                    continue;
                }
            } else {
                if (!recipe.addition.test(right) || right.getAmount() < recipe.info.additionAmount()) {
                    continue;
                }
                available = right.getAmount();
            }
            if (!recipe.info.isRepair()) {
                return new Offer(recipe, recipe.result.stack(recipe.info.amount()),
                        recipe.addition == null ? 0 : recipe.info.additionAmount());
            }
            Offer repaired = repaired(recipe, left, available);
            if (repaired != null) {
                return repaired;
            }
        }
        return null;
    }

    /**
     * An anvil repair, the way vanilla repairs with a material: as many uses
     * of the addition as it takes to mend the base, or as many as there are,
     * whichever is fewer. Nothing to offer when the base is not worn.
     */
    private Offer repaired(Anvil recipe, ItemStack left, int available) {
        ItemStack mended = left.clone();
        mended.setAmount(1);
        ItemMeta meta = mended.getItemMeta();
        if (!(meta instanceof Damageable)) {
            return null;
        }
        int damage = ((Damageable) meta).getDamage();
        int max = maxDurability(left);
        if (damage <= 0 || max <= 0) {
            return null;
        }
        RecipeInfo info = recipe.info;
        int perUse = info.repairPoints() > 0
                ? info.repairPoints()
                : Math.max(1, Math.round(max * info.repairFraction()));
        int uses = Math.min(available / info.additionAmount(), (damage + perUse - 1) / perUse);
        if (uses < 1) {
            return null;
        }
        ((Damageable) meta).setDamage(Math.max(0, damage - uses * perUse));
        mended.setItemMeta(meta);
        return new Offer(recipe, mended, uses * info.additionAmount());
    }

    /**
     * How much wear an item can take: a custom item's own durability where
     * this server applies one, otherwise its material's.
     */
    private int maxDurability(ItemStack stack) {
        if (compatibility.has(Feature.ITEM_COMPONENTS)) {
            Integer own = items.idOf(stack)
                    .flatMap(items::info)
                    .flatMap(info -> info.stats().maxDamage())
                    .orElse(null);
            if (own != null) {
                return own;
            }
        }
        return stack.getType().getMaxDurability();
    }

    private static ItemStack less(ItemStack stack, int by) {
        if (stack == null || stack.getAmount() <= by) {
            return null;
        }
        ItemStack left = stack.clone();
        left.setAmount(stack.getAmount() - by);
        return left;
    }

    /** What vanilla does to an anvil after a use: now and then it is a step more damaged. */
    private static void wear(AnvilInventory inventory, boolean creative) {
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        Block block = location.getBlock();
        Material next = null;
        if (!creative && ThreadLocalRandom.current().nextFloat() < ANVIL_WEAR_CHANCE) {
            switch (block.getType()) {
                case ANVIL:
                    next = Material.CHIPPED_ANVIL;
                    break;
                case CHIPPED_ANVIL:
                    next = Material.DAMAGED_ANVIL;
                    break;
                case DAMAGED_ANVIL:
                    next = Material.AIR;
                    break;
                default:
                    break;
            }
        }
        if (next == null) {
            location.getWorld().playEffect(location, Effect.ANVIL_USE, 0);
            return;
        }
        if (next == Material.AIR) {
            block.setType(Material.AIR);
            // The screen closes itself on the next tick, as vanilla's does,
            // once there is no anvil left for it to belong to.
            location.getWorld().playEffect(location, Effect.ANVIL_BREAK, 0);
            return;
        }
        BlockData before = block.getBlockData();
        block.setType(next);
        if (before instanceof Directional && block.getBlockData() instanceof Directional) {
            Directional after = (Directional) block.getBlockData();
            after.setFacing(((Directional) before).getFacing());
            block.setBlockData(after);
        }
        location.getWorld().playEffect(location, Effect.ANVIL_USE, 0);
    }
}
