package ai.resourcepack.engine.core.model;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.core.storage.ItemBytes;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placed pieces that brew: a brewing stand that looks like your model.
 *
 * <p>Simulated for the reason {@link PieceFurnaces} is, and with the same life:
 * the slots and the two numbers are kept on the piece's hitbox, and a piece
 * that is brewing or open is LIVE and steps once a tick on its own scheduler.
 * The steps are the game's, in the game's order. What brews into what comes
 * from {@link BrewingRules}.
 *
 * <p>Main thread only, or the piece's region on Folia.
 */
public final class PieceBrewers implements Listener {

    /** The three bottles are slots 0 to 2. */
    private static final int BOTTLES = 3;
    private static final int REAGENT = 3;
    private static final int FUEL = 4;

    /** Ticks one brew takes. */
    private static final int BREW_TICKS = 400;

    /** Brews one blaze powder is good for. */
    private static final int POWDER_BREWS = 20;

    /** How often a live stand with changes is written back, in ticks. */
    private static final int SAVE_EVERY = 100;

    private final Plugin plugin;
    private final NamespacedKey slotsKey;
    private final NamespacedKey stateKey;
    private final Map<UUID, Live> live = new ConcurrentHashMap<>();

    private BrewingRules rules;
    private boolean rulesLooked;

    PieceBrewers(Plugin plugin) {
        this.plugin = plugin;
        this.slotsKey = new NamespacedKey(plugin, "brewer");
        this.stateKey = new NamespacedKey(plugin, "brewer-state");
    }

    /** Whether this server can say what brews into what. Asked after the server has its recipes. */
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

    /** Whether anything about a brewing stand is kept on this hitbox. */
    boolean holdsAnything(Interaction hitbox) {
        PersistentDataContainer data = hitbox.getPersistentDataContainer();
        return data.has(slotsKey, PersistentDataType.BYTE_ARRAY) || data.has(stateKey, PersistentDataType.INTEGER_ARRAY);
    }

    /** @return whether a screen opened */
    boolean open(Player player, Interaction hitbox, String title) {
        if (rules() == null) {
            player.sendMessage(ChatColor.RED + "This server cannot run a brewing stand here.");
            return false;
        }
        Live one = wake(hitbox, title);
        if (one == null) {
            player.sendMessage(ChatColor.RED + "This brewing stand's contents could not be read, so it was not "
                    + "opened. Nothing in it has been touched.");
            return false;
        }
        if (player.openInventory(one.inventory) == null) {
            return false;
        }
        show(player, one);
        return true;
    }

    /** A piece coming back with its chunk, or standing there at startup: if it was brewing it carries on. */
    void adopt(Interaction hitbox, String title) {
        if (live.containsKey(hitbox.getUniqueId()) || !holdsAnything(hitbox) || rules() == null) {
            return;
        }
        int[] state = hitbox.getPersistentDataContainer().get(stateKey, PersistentDataType.INTEGER_ARRAY);
        if (state != null && state.length >= 1 && state[0] > 0) {
            wake(hitbox, title);
        }
    }

    private Live wake(Interaction hitbox, String title) {
        Live one = live.get(hitbox.getUniqueId());
        if (one != null) {
            return one;
        }
        ItemStack[] slots = readSlots(hitbox);
        if (slots == null) {
            return null;
        }
        one = new Live(hitbox.getUniqueId(), hitbox.getLocation().add(0, 0.5, 0));
        one.inventory = Bukkit.createInventory(one, InventoryType.BREWING,
                ChatColor.translateAlternateColorCodes('&', title));
        one.inventory.setContents(slots);
        int[] state = hitbox.getPersistentDataContainer().get(stateKey, PersistentDataType.INTEGER_ARRAY);
        if (state != null && state.length >= 2) {
            one.brew = Math.max(0, state[0]);
            one.fuel = Math.max(0, state[1]);
        }
        ItemStack reagent = slots[REAGENT];
        one.reagent = present(reagent) ? reagent.getType() : null;
        live.put(one.hitbox, one);
        Live ticking = one;
        one.ticker = Tasks.onTimer(plugin, hitbox, () -> tick(ticking), 1L, 1L);
        return one;
    }

    private ItemStack[] readSlots(Interaction hitbox) {
        byte[] bytes = hitbox.getPersistentDataContainer().get(slotsKey, PersistentDataType.BYTE_ARRAY);
        ItemStack[] slots = new ItemStack[5];
        if (bytes == null) {
            return slots;
        }
        try {
            ItemStack[] read = ItemBytes.read(bytes);
            System.arraycopy(read, 0, slots, 0, Math.min(5, read.length));
            return slots;
        } catch (IOException e) {
            plugin.getLogger().severe("Did not open the brewing stand " + hitbox.getUniqueId() + ": its contents are "
                    + e.getMessage());
            return null;
        }
    }

    private void tick(Live one) {
        Entity entity = Bukkit.getEntity(one.hitbox);
        if (!(entity instanceof Interaction) || !entity.isValid()) {
            forget(one);
            close(one);
            return;
        }
        Interaction hitbox = (Interaction) entity;
        if (step(one)) {
            one.dirty = true;
        }
        for (HumanEntity viewer : one.inventory.getViewers()) {
            show(viewer, one);
        }
        if (one.dirty && ++one.sinceSave >= SAVE_EVERY) {
            save(one, hitbox);
        }
        if (one.brew == 0 && one.inventory.getViewers().isEmpty() && !canContinue(one)) {
            save(one, hitbox);
            forget(one);
        }
    }

    /** One tick of a brewing stand, in the order the game's does it. */
    private boolean step(Live one) {
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
    private boolean canBrew(Live one) {
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

    private boolean canContinue(Live one) {
        if (!canBrew(one)) {
            return false;
        }
        ItemStack powder = one.inventory.getItem(FUEL);
        return one.fuel > 0 || (present(powder) && powder.getType() == Material.BLAZE_POWDER);
    }

    private void brew(Live one) {
        Inventory inventory = one.inventory;
        ItemStack reagent = inventory.getItem(REAGENT);
        for (int i = 0; i < BOTTLES; i++) {
            ItemStack bottle = inventory.getItem(i);
            if (present(bottle)) {
                ItemStack made = rules().mix(bottle, reagent);
                if (made != null) {
                    inventory.setItem(i, made);
                }
            }
        }
        Material remainder = reagent.getType().getCraftingRemainingItem();
        reagent.setAmount(reagent.getAmount() - 1);
        if (reagent.getAmount() > 0) {
            inventory.setItem(REAGENT, reagent);
            if (remainder != null && !remainder.isAir() && one.at.getWorld() != null) {
                one.at.getWorld().dropItemNaturally(one.at, new ItemStack(remainder));
            }
        } else {
            inventory.setItem(REAGENT, remainder != null && !remainder.isAir() ? new ItemStack(remainder) : null);
        }
        World world = one.at.getWorld();
        if (world != null) {
            world.playSound(one.at, "minecraft:block.brewing_stand.brew", SoundCategory.BLOCKS, 1f, 1f);
        }
    }

    /** Puts the bubbles and the fuel bar on a viewer's screen. */
    private void show(HumanEntity viewer, Live one) {
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Live)) {
            return;
        }
        Live one = (Live) event.getInventory().getHolder();
        if (one.finished) {
            return;
        }
        Entity entity = Bukkit.getEntity(one.hitbox);
        if (entity instanceof Interaction && entity.isValid() && Tasks.owns(entity)) {
            save(one, (Interaction) entity);
        }
    }

    /** Its chunk is unloading: written back while there is something to write onto, and let go. */
    void unload(Interaction hitbox) {
        Live one = live.get(hitbox.getUniqueId());
        if (one != null) {
            save(one, hitbox);
            forget(one);
            close(one);
        }
    }

    /** The piece is being broken: every view closed and emptied first, then its slots handed back to spill. */
    List<ItemStack> removed(Interaction hitbox) {
        Live one = live.get(hitbox.getUniqueId());
        ItemStack[] slots;
        if (one != null) {
            forget(one);
            slots = one.inventory.getContents().clone();
            for (int i = 0; i < slots.length; i++) {
                slots[i] = slots[i] == null ? null : slots[i].clone();
            }
            one.inventory.clear();
            close(one);
        } else {
            slots = readSlots(hitbox);
            if (slots == null) {
                plugin.getLogger().severe("A brewing stand was broken whose contents could not be read: "
                        + hitbox.getUniqueId());
                slots = new ItemStack[0];
            }
        }
        PersistentDataContainer data = hitbox.getPersistentDataContainer();
        data.remove(slotsKey);
        data.remove(stateKey);
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack slot : slots) {
            if (present(slot)) {
                out.add(slot);
            }
        }
        return out;
    }

    /** Everything written back and closed. The plugin is going. */
    public void closeAll() {
        for (Live one : new ArrayList<>(live.values())) {
            Entity entity = Bukkit.getEntity(one.hitbox);
            if (entity instanceof Interaction && entity.isValid()) {
                save(one, (Interaction) entity);
            }
            forget(one);
            close(one);
        }
    }

    private void save(Live one, Interaction hitbox) {
        PersistentDataContainer data = hitbox.getPersistentDataContainer();
        ItemStack[] slots = one.inventory.getContents();
        if (!ItemBytes.anything(slots)) {
            data.remove(slotsKey);
        } else {
            try {
                data.set(slotsKey, PersistentDataType.BYTE_ARRAY, ItemBytes.write(slots));
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().severe("Could not save the brewing stand " + one.hitbox + ": " + e.getMessage()
                        + ". The last saved contents are kept.");
            }
        }
        if (one.brew == 0 && one.fuel == 0) {
            data.remove(stateKey);
        } else {
            data.set(stateKey, PersistentDataType.INTEGER_ARRAY, new int[]{one.brew, one.fuel});
        }
        one.dirty = false;
        one.sinceSave = 0;
    }

    private void forget(Live one) {
        one.finished = true;
        live.remove(one.hitbox, one);
        if (one.ticker != null) {
            one.ticker.cancel();
            one.ticker = null;
        }
    }

    private static void close(Live one) {
        for (HumanEntity viewer : new ArrayList<>(one.inventory.getViewers())) {
            viewer.closeInventory();
        }
    }

    private static boolean present(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.getAmount() > 0;
    }

    /** One brewing stand somebody has open or that is brewing, and the holder of its inventory. */
    static final class Live implements InventoryHolder {

        final UUID hitbox;
        final Location at;
        Inventory inventory;
        Tasks.Handle ticker;
        /** Ticks left of this brew. */
        int brew;
        /** Brews the powder burned so far is still good for. */
        int fuel;
        /** The reagent the brew started with; taking it out stops the brew. */
        Material reagent;
        /** The slots {@link #can} was worked out for. */
        Integer knownFor;
        boolean can;
        boolean dirty;
        int sinceSave;
        /** Set once it has been let go; a close after that saves nothing. */
        boolean finished;

        Live(UUID hitbox, Location at) {
            this.hitbox = hitbox;
            this.at = at;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
