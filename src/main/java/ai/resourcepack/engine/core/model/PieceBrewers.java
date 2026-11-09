package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.event.PieceBrewEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Things that brew: a brewing stand that looks like your model or custom block.
 *
 * <p>Simulated for the reason {@link PieceFurnaces} is, with the life in
 * {@link PieceMachines}. The steps are the game's, in the game's order. What
 * brews into what comes from {@link BrewingRules}.
 *
 * @see PieceMachines the spec here is the art for its screen, or null
 */
public final class PieceBrewers extends PieceMachines<ContentId, PieceBrewers.One> {

    /** The three bottles are slots 0 to 2. */
    private static final int BOTTLES = 3;
    private static final int REAGENT = 3;
    private static final int FUEL = 4;

    /** Ticks one brew takes. */
    private static final int BREW_TICKS = 400;

    /** Brews one blaze powder is good for. */
    private static final int POWDER_BREWS = 20;

    private BrewingRules rules;
    private boolean rulesLooked;

    PieceBrewers(Plugin plugin) {
        super(plugin, "brewer");
    }

    /** Whether this server can say what brews into what. */
    boolean available() {
        return rules() != null;
    }

    private synchronized BrewingRules rules() {
        if (!rulesLooked) {
            rulesLooked = true;
            rules = BrewingRules.find();
        }
        return rules;
    }

    @Override
    boolean open(Player player, Anchor anchor, ContentId spec, ContentId piece, String title) {
        if (rules() == null) {
            player.sendMessage(ChatColor.RED + "This server cannot run a brewing stand here.");
            return false;
        }
        return super.open(player, anchor, spec, piece, title);
    }

    @Override
    void adopt(Anchor anchor, ContentId spec, ContentId piece, String title) {
        if (rules() != null) {
            super.adopt(anchor, spec, piece, title);
        }
    }

    @Override
    protected int slotCount() {
        return 5;
    }

    @Override
    protected InventoryType screenType() {
        return InventoryType.BREWING;
    }

    @Override
    protected One make(Anchor anchor, ContentId piece, ContentId spec) {
        One one = new One(anchor, piece, spec);
        return one;
    }

    @Override
    protected int[] state(One one) {
        return one.brew == 0 && one.fuel == 0 ? null : new int[]{one.brew, one.fuel};
    }

    @Override
    protected void restore(One one, int[] state) {
        if (state.length >= 2) {
            one.brew = Math.max(0, state[0]);
            one.fuel = Math.max(0, state[1]);
        }
    }

    @Override
    protected boolean working(int[] state, ItemStack[] slots) {
        return state != null && state.length >= 1 && state[0] > 0;
    }

    @Override
    protected ContentId screenOf(ContentId spec) {
        return spec;
    }

    @Override
    protected void readExtra(One one, org.bukkit.persistence.PersistentDataContainer data) {
        ItemStack reagent = one.inventory.getItem(REAGENT);
        one.reagent = present(reagent) ? reagent.getType() : null;
    }

    @Override
    protected boolean idle(One one) {
        return one.brew == 0 && !canContinue(one);
    }

    @Override
    protected int[] slotsFacing(HopperSide side) {
        switch (side) {
            case ABOVE:
                return new int[]{REAGENT};
            case SIDE:
                return new int[]{0, 1, 2, FUEL};
            default:
                return new int[]{0, 1, 2, REAGENT};
        }
    }

    @Override
    protected boolean canInsert(One one, int slot, ItemStack item) {
        if (slot == REAGENT) {
            return true;
        }
        if (slot == FUEL) {
            return item.getType() == Material.BLAZE_POWDER;
        }
        Material type = item.getType();
        return (type == Material.POTION || type == Material.SPLASH_POTION || type == Material.LINGERING_POTION
                || type == Material.GLASS_BOTTLE) && !present(one.inventory.getItem(slot));
    }

    @Override
    protected boolean canExtract(One one, int slot, ItemStack item) {
        // A bottle still waiting on its brew stays put while it is brewing,
        // and only an emptied bottle comes out of the reagent slot.
        return slot == REAGENT ? item.getType() == Material.GLASS_BOTTLE : one.brew == 0;
    }

    /** One tick of a brewing stand, in the order the game's does it. */
    @Override
    protected boolean step(One one) {
        Inventory inventory = one.inventory;
        boolean changed = false;
        ItemStack powder = inventory.getItem(FUEL);
        if (one.fuel <= 0 && present(powder) && powder.getType() == Material.BLAZE_POWDER) {
            one.fuel = POWDER_BREWS;
            powder.setAmount(powder.getAmount() - 1);
            inventory.setItem(FUEL, powder.getAmount() > 0 ? powder : null);
            changed = true;
        }
        boolean can = canBrew(one);
        ItemStack reagent = inventory.getItem(REAGENT);
        if (one.brew > 0) {
            one.brew--;
            changed = true;
            if (one.brew == 0 && can) {
                brew(one);
            } else if (!can || !present(reagent) || reagent.getType() != one.reagent) {
                one.brew = 0;
            }
        } else if (can && one.fuel > 0) {
            one.fuel--;
            one.brew = BREW_TICKS;
            one.reagent = reagent.getType();
            changed = true;
        }
        return changed;
    }

    /** Whether the slots can brew. Worked out again only when the slots have changed. */
    private boolean canBrew(One one) {
        int signature = Arrays.hashCode(Arrays.copyOf(one.inventory.getContents(), REAGENT + 1));
        if (one.knownFor != null && one.knownFor == signature) {
            return one.can;
        }
        one.knownFor = signature;
        one.can = false;
        ItemStack reagent = one.inventory.getItem(REAGENT);
        if (present(reagent)) {
            for (int i = 0; i < BOTTLES && !one.can; i++) {
                ItemStack bottle = one.inventory.getItem(i);
                one.can = present(bottle) && rules().mix(bottle, reagent) != null;
            }
        }
        return one.can;
    }

    private boolean canContinue(One one) {
        if (!canBrew(one)) {
            return false;
        }
        ItemStack powder = one.inventory.getItem(FUEL);
        return one.fuel > 0 || (present(powder) && powder.getType() == Material.BLAZE_POWDER);
    }

    private void brew(One one) {
        Inventory inventory = one.inventory;
        ItemStack reagent = inventory.getItem(REAGENT);
        List<ItemStack> results = new ArrayList<>(BOTTLES);
        for (int i = 0; i < BOTTLES; i++) {
            ItemStack bottle = inventory.getItem(i);
            results.add(present(bottle) ? rules().mix(bottle, reagent) : null);
        }
        PieceBrewEvent event = new PieceBrewEvent(one.piece, one.at, one.anchor.hitbox(), reagent.clone(), results);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        for (int i = 0; i < BOTTLES; i++) {
            if (results.get(i) != null) {
                inventory.setItem(i, results.get(i));
            }
        }
        Material remainder = reagent.getType().getCraftingRemainingItem();
        reagent.setAmount(reagent.getAmount() - 1);
        World world = one.at.getWorld();
        if (reagent.getAmount() > 0) {
            inventory.setItem(REAGENT, reagent);
            if (remainder != null && !remainder.isAir() && world != null) {
                world.dropItemNaturally(one.at, new ItemStack(remainder));
            }
        } else {
            inventory.setItem(REAGENT, remainder != null && !remainder.isAir() ? new ItemStack(remainder) : null);
        }
        if (world != null) {
            world.playSound(one.at, "minecraft:block.brewing_stand.brew", SoundCategory.BLOCKS, 1f, 1f);
        }
    }

    /** Puts the bubbles and the fuel bar on a viewer's screen. */
    @Override
    protected void show(HumanEntity viewer, One one) {
        viewer.setWindowProperty(InventoryView.Property.BREW_TIME, one.brew);
        viewer.setWindowProperty(InventoryView.Property.FUEL_TIME, one.fuel);
        if (rules().sendsTotals()) {
            setData(viewer, 2, BREW_TICKS);
            setData(viewer, 3, POWDER_BREWS);
        }
    }

    /** The two totals have no Bukkit property. Without them the screen shows no progress, and nothing worse. */
    private static void setData(HumanEntity viewer, int id, int value) {
        try {
            Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
            Field menu = handle.getClass().getField("containerMenu");
            Object container = menu.get(handle);
            Method set = container.getClass().getMethod("setData", int.class, int.class);
            set.invoke(container, id, value);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // See the method's note.
        }
    }

    /** One brewing stand somebody has open or that is brewing. */
    static final class One extends Live<ContentId> {

        /** Ticks left of this brew. */
        int brew;
        /** Brews the powder burned so far is still good for. */
        int fuel;
        /** The reagent the brew started with; taking it out stops the brew. */
        Material reagent;
        /** The slots {@link #can} was worked out for. */
        Integer knownFor;
        boolean can;

        One(Anchor anchor, ContentId piece, ContentId spec) {
            super(anchor, piece, spec);
        }
    }
}
