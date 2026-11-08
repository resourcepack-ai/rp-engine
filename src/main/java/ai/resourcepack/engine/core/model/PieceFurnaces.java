package ai.resourcepack.engine.core.model;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.core.storage.ItemBytes;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Placed pieces that cook: an oven, a stove, a forge.
 *
 * <p><strong>The furnace is simulated, not borrowed.</strong> A display entity
 * cannot be a furnace block, and a real furnace hidden somewhere would still be
 * a block the client draws, so the three slots and the four numbers a furnace
 * runs on (fuel left, the fuel's length, cooking progress, the recipe's length)
 * are kept on the piece's hitbox and stepped here once a tick with the game's
 * own rules: the same order of checks, the same fuel lengths, the server's own
 * recipes. The screen is the game's furnace screen, a real {@code FurnaceMenu},
 * so its slots behave as a furnace's do and its flame and arrow are driven by
 * the same four numbers through {@link HumanEntity#setWindowProperty}.
 *
 * <p>The screen is always the FURNACE screen, whatever the kind. The game's
 * smoker and blast furnace screens are the same picture, and Bukkit only lets a
 * plugin set the progress of a screen whose type is the furnace's, so a smoker
 * opened as a smoker would never show its arrow move.
 *
 * <p>Like the rest of a placed piece, the state lives in the world: on the
 * hitbox, saved with its chunk. A piece cooks while its chunk is loaded, as a
 * vanilla furnace does, and pauses while it is not. While one is cooking or
 * open it is LIVE: one inventory, ticking on the piece's own scheduler (its
 * region's on Folia), written back to the hitbox every few seconds, on every
 * close, when its chunk unloads and when the plugin stops. A live one with
 * nothing to do and nobody looking is written back and let go.
 *
 * <p>Experience is kept per piece and handed out when somebody takes from the
 * result slot, or dropped as orbs when the piece is broken, as vanilla does.
 *
 * <p>Custom items are kept out of vanilla's recipes the way the engine keeps
 * them out of a real furnace's: a tomato carried on paper is not paper to an
 * oven either.
 */
public final class PieceFurnaces implements Listener {

    /** Input, fuel, result: the furnace screen's own order. */
    static final int INPUT = 0;
    static final int FUEL = 1;
    static final int RESULT = 2;

    /** How often a live furnace with changes is written back, in ticks. */
    private static final int SAVE_EVERY = 100;

    /** How long a recipe list is trusted before it is read again, in ms. A reload adds recipes. */
    private static final long RECIPES_FOR_MS = 10_000L;

    private final Plugin plugin;
    private final Items items;
    private final Consumer<Interaction> litChanged;

    private final NamespacedKey slotsKey;
    private final NamespacedKey stateKey;
    private final NamespacedKey xpKey;

    /** Every live furnace, by hitbox. */
    private final Map<UUID, Live> live = new ConcurrentHashMap<>();

    private final Map<ModelInfo.Furnace.Kind, List<CookingRecipe<?>>> recipes = new EnumMap<>(ModelInfo.Furnace.Kind.class);
    private volatile long recipesReadAt;
    private volatile Boolean fuelSetsSpeed;

    /**
     * @param litChanged told when a piece starts or stops burning, to put on
     *                   or take off its lit look
     */
    PieceFurnaces(Plugin plugin, Items items, Consumer<Interaction> litChanged) {
        this.plugin = plugin;
        this.items = items;
        this.litChanged = litChanged;
        this.slotsKey = new NamespacedKey(plugin, "furnace");
        this.stateKey = new NamespacedKey(plugin, "furnace-state");
        this.xpKey = new NamespacedKey(plugin, "furnace-xp");
    }

    // ---- what the piece is doing ---------------------------------------

    /** Whether this piece is burning fuel right now. */
    boolean lit(Interaction hitbox) {
        Live one = live.get(hitbox.getUniqueId());
        if (one != null) {
            return one.lit > 0;
        }
        int[] state = hitbox.getPersistentDataContainer().get(stateKey, PersistentDataType.INTEGER_ARRAY);
        return state != null && state.length > 0 && state[0] > 0;
    }

    /** Whether anything about a furnace is kept on this hitbox. */
    boolean holdsAnything(Interaction hitbox) {
        PersistentDataContainer data = hitbox.getPersistentDataContainer();
        return data.has(slotsKey, PersistentDataType.BYTE_ARRAY) || data.has(stateKey, PersistentDataType.INTEGER_ARRAY)
                || data.has(xpKey, PersistentDataType.FLOAT);
    }

    // ---- opening ---------------------------------------------------------

    /**
     * Opens the piece's furnace for {@code player}.
     *
     * @param fallbackTitle what to call it when the pack did not: the item's name
     * @return whether a screen opened
     */
    boolean open(Player player, Interaction hitbox, ModelInfo.Furnace spec, String fallbackTitle) {
        Live one = wake(hitbox, spec, fallbackTitle);
        if (one == null) {
            player.sendMessage(ChatColor.RED + "This furnace's contents could not be read, so it was not opened. "
                    + "Nothing in it has been touched.");
            return false;
        }
        if (player.openInventory(one.inventory) == null) {
            return false;
        }
        show(player, one);
        return true;
    }

    /**
     * A piece coming back with its chunk, or standing there at startup: if it
     * was cooking, or has something it could start cooking, it carries on.
     */
    void adopt(Interaction hitbox, ModelInfo.Furnace spec, String fallbackTitle) {
        if (live.containsKey(hitbox.getUniqueId()) || !holdsAnything(hitbox)) {
            return;
        }
        int[] state = hitbox.getPersistentDataContainer().get(stateKey, PersistentDataType.INTEGER_ARRAY);
        boolean busy = state != null && state.length >= 3 && (state[0] > 0 || state[2] > 0);
        if (!busy) {
            ItemStack[] slots = readSlots(hitbox);
            busy = slots != null && present(slots[INPUT]) && present(slots[FUEL]);
        }
        if (busy) {
            wake(hitbox, spec, fallbackTitle);
        }
    }

    /** The live furnace for this piece, made live from what is kept on it if it was not. Null if unreadable. */
    private Live wake(Interaction hitbox, ModelInfo.Furnace spec, String fallbackTitle) {
        Live one = live.get(hitbox.getUniqueId());
        if (one != null) {
            one.spec = spec;
            return one;
        }
        ItemStack[] slots = readSlots(hitbox);
        if (slots == null) {
            return null;
        }
        String title = colour(spec.title().orElse(
                fallbackTitle == null || fallbackTitle.isBlank() ? spec.kind().title() : fallbackTitle));
        one = new Live(hitbox.getUniqueId(), spec, hitbox.getLocation().add(0, 0.5, 0));
        one.inventory = Bukkit.createInventory(one, InventoryType.FURNACE, title);
        one.inventory.setContents(slots);
        int[] state = hitbox.getPersistentDataContainer().get(stateKey, PersistentDataType.INTEGER_ARRAY);
        if (state != null && state.length >= 4) {
            one.lit = Math.max(0, state[0]);
            one.litDuration = Math.max(0, state[1]);
            one.cook = Math.max(0, state[2]);
            one.cookTotal = Math.max(0, state[3]);
        }
        Float xp = hitbox.getPersistentDataContainer().get(xpKey, PersistentDataType.FLOAT);
        one.xp = xp == null ? 0f : xp;
        one.lastInput = copyOf(slots[INPUT]);
        live.put(one.hitbox, one);
        Live ticking = one;
        one.ticker = Tasks.onTimer(plugin, hitbox, () -> tick(ticking), 1L, 1L);
        return one;
    }

    private ItemStack[] readSlots(Interaction hitbox) {
        byte[] bytes = hitbox.getPersistentDataContainer().get(slotsKey, PersistentDataType.BYTE_ARRAY);
        ItemStack[] slots = new ItemStack[3];
        if (bytes == null) {
            return slots;
        }
        try {
            ItemStack[] read = ItemBytes.read(bytes);
            System.arraycopy(read, 0, slots, 0, Math.min(3, read.length));
            return slots;
        } catch (IOException e) {
            // Opened empty, this would be saved back empty. Refused instead,
            // and the bytes stay where they are.
            plugin.getLogger().severe("Did not open the furnace " + hitbox.getUniqueId() + ": its contents are "
                    + e.getMessage());
            return null;
        }
    }

    // ---- ticking ---------------------------------------------------------

    private void tick(Live one) {
        Entity entity = Bukkit.getEntity(one.hitbox);
        if (!(entity instanceof Interaction) || !entity.isValid()) {
            // Gone without the unload or the break reaching us: nothing left
            // to write onto. Let go, and anybody still looking loses the view.
            forget(one);
            for (HumanEntity viewer : new ArrayList<>(one.inventory.getViewers())) {
                viewer.closeInventory();
            }
            return;
        }
        Interaction hitbox = (Interaction) entity;
        boolean wasLit = one.lit > 0;
        if (step(one)) {
            one.dirty = true;
        }
        boolean isLit = one.lit > 0;
        for (HumanEntity viewer : one.inventory.getViewers()) {
            show(viewer, one);
        }
        if (isLit && ThreadLocalRandom.current().nextInt(100) == 0) {
            crackle(one);
        }
        if (wasLit != isLit) {
            save(one, hitbox);
            litChanged.accept(hitbox);
        } else if (one.dirty && ++one.sinceSave >= SAVE_EVERY) {
            save(one, hitbox);
        }
        if (!isLit && one.cook == 0 && one.inventory.getViewers().isEmpty() && !canStart(one)) {
            save(one, hitbox);
            forget(one);
        }
    }

    /**
     * One tick of a furnace, in the order the game's own furnace does it.
     *
     * @return whether anything a save would keep changed
     */
    private boolean step(Live one) {
        Inventory inventory = one.inventory;
        ItemStack input = inventory.getItem(INPUT);
        boolean changed = false;

        // A different input starts its cooking from nothing, as setting a
        // furnace's input slot to a different item does.
        if (!same(input, one.lastInput)) {
            if (one.cook != 0 || !present(input)) {
                changed = true;
            }
            one.cook = 0;
            CookingRecipe<?> recipe = present(input) ? recipeFor(one, input) : null;
            one.cookTotal = recipe == null ? 0 : cookTicks(one, recipe);
            one.lastInput = copyOf(input);
            changed = true;
        }

        boolean wasLit = one.lit > 0;
        if (one.lit > 0) {
            one.lit--;
        }
        ItemStack fuel = inventory.getItem(FUEL);
        boolean hasFuel = present(fuel);
        boolean hasInput = present(input);
        if (one.lit > 0 || (hasFuel && hasInput)) {
            CookingRecipe<?> recipe = hasInput ? recipeFor(one, input) : null;
            if (one.lit <= 0 && canBurn(recipe, inventory)) {
                int length = burnTime(fuel, one.spec.kind());
                one.lit = length;
                one.litDuration = length;
                if (length > 0) {
                    useFuel(inventory, fuel);
                    changed = true;
                }
            }
            if (one.lit > 0 && canBurn(recipe, inventory)) {
                if (one.cookTotal <= 0) {
                    one.cookTotal = cookTicks(one, recipe);
                }
                one.cook++;
                if (one.cook >= one.cookTotal) {
                    one.cook = 0;
                    burn(one, recipe);
                    ItemStack left = inventory.getItem(INPUT);
                    CookingRecipe<?> next = present(left) ? recipeFor(one, left) : null;
                    one.cookTotal = next == null ? 0 : cookTicks(one, next);
                    one.lastInput = copyOf(left);
                    changed = true;
                }
            } else {
                one.cook = 0;
            }
        } else if (one.lit <= 0 && one.cook > 0) {
            one.cook = Math.max(0, Math.min(one.cook - 2, one.cookTotal));
        }
        return changed || wasLit != one.lit > 0;
    }

    /** Whether there is fuel to light and something it would cook. */
    private boolean canStart(Live one) {
        ItemStack input = one.inventory.getItem(INPUT);
        ItemStack fuel = one.inventory.getItem(FUEL);
        if (!present(input) || !present(fuel) || burnTime(fuel, one.spec.kind()) <= 0) {
            return false;
        }
        return canBurn(recipeFor(one, input), one.inventory);
    }

    private static boolean canBurn(CookingRecipe<?> recipe, Inventory inventory) {
        if (recipe == null || !present(inventory.getItem(INPUT))) {
            return false;
        }
        ItemStack result = recipe.getResult();
        if (!present(result)) {
            return false;
        }
        ItemStack out = inventory.getItem(RESULT);
        if (!present(out)) {
            return true;
        }
        if (!out.isSimilar(result)) {
            return false;
        }
        int room = Math.min(inventory.getMaxStackSize(), out.getMaxStackSize());
        return out.getAmount() + result.getAmount() <= room;
    }

    private void burn(Live one, CookingRecipe<?> recipe) {
        Inventory inventory = one.inventory;
        ItemStack result = recipe.getResult().clone();
        ItemStack out = inventory.getItem(RESULT);
        if (!present(out)) {
            inventory.setItem(RESULT, result);
        } else {
            out.setAmount(out.getAmount() + result.getAmount());
            inventory.setItem(RESULT, out);
        }
        ItemStack input = inventory.getItem(INPUT);
        if (input != null) {
            // A wet sponge dried with a bucket in the fuel slot fills it, as in a real furnace.
            if (input.getType() == Material.WET_SPONGE) {
                ItemStack fuel = inventory.getItem(FUEL);
                if (fuel != null && fuel.getType() == Material.BUCKET && fuel.getAmount() == 1) {
                    inventory.setItem(FUEL, new ItemStack(Material.WATER_BUCKET));
                }
            }
            input.setAmount(input.getAmount() - 1);
            inventory.setItem(INPUT, input.getAmount() > 0 ? input : null);
        }
        one.xp += recipe.getExperience();
    }

    private static void useFuel(Inventory inventory, ItemStack fuel) {
        Material remainder = fuel.getType().getCraftingRemainingItem();
        if (fuel.getAmount() == 1 && remainder != null && !remainder.isAir()) {
            // A lava bucket burns and leaves the bucket.
            inventory.setItem(FUEL, new ItemStack(remainder));
            return;
        }
        fuel.setAmount(fuel.getAmount() - 1);
        inventory.setItem(FUEL, fuel.getAmount() > 0 ? fuel : null);
    }

    /**
     * Puts the four numbers on a viewer's screen: the flame and the arrow.
     *
     * <p>Through the player and not through {@code InventoryView}, which was a
     * class before 1.21 and is an interface since, so no call on one compiled
     * here can link on both sides of that.
     */
    private static void show(HumanEntity viewer, Live one) {
        viewer.setWindowProperty(InventoryView.Property.BURN_TIME, one.lit);
        viewer.setWindowProperty(InventoryView.Property.TICKS_FOR_CURRENT_FUEL, one.litDuration);
        viewer.setWindowProperty(InventoryView.Property.COOK_TIME, one.cook);
        viewer.setWindowProperty(InventoryView.Property.TICKS_FOR_CURRENT_SMELTING, one.cookTotal);
    }

    private static void crackle(Live one) {
        World world = one.at.getWorld();
        if (world == null) {
            return;
        }
        String sound;
        switch (one.spec.kind()) {
            case SMOKER:
                sound = "minecraft:block.smoker.smoke";
                break;
            case BLAST_FURNACE:
                sound = "minecraft:block.blastfurnace.fire_crackle";
                break;
            default:
                sound = "minecraft:block.furnace.fire_crackle";
                break;
        }
        world.playSound(one.at, sound, SoundCategory.BLOCKS, 1f, 1f);
    }

    // ---- recipes and fuel --------------------------------------------------

    private CookingRecipe<?> recipeFor(Live one, ItemStack input) {
        if (one.lastRecipe != null && accepts(one.lastRecipe, input)) {
            return one.lastRecipe;
        }
        for (CookingRecipe<?> recipe : recipes(one.spec.kind())) {
            if (accepts(recipe, input)) {
                one.lastRecipe = recipe;
                return recipe;
            }
        }
        return null;
    }

    /**
     * How many ticks this recipe takes in this kind of furnace.
     *
     * <p>Up to 1.21 a smoker's and a blast furnace's recipes state the fast
     * time themselves. From 26.1 they state the furnace's time and the game
     * doubles the speed for those two blocks, through the fuel. Bukkit reports
     * only the recipe's number, so which of the two this server does is read
     * off one vanilla recipe.
     */
    private int cookTicks(Live one, CookingRecipe<?> recipe) {
        int ticks = recipe.getCookingTime();
        if (one.spec.kind() == ModelInfo.Furnace.Kind.FURNACE || !fuelSetsSpeed()) {
            return ticks;
        }
        return (ticks + 1) / 2;
    }

    private boolean fuelSetsSpeed() {
        Boolean known = fuelSetsSpeed;
        if (known == null) {
            Recipe reference = Bukkit.getRecipe(NamespacedKey.minecraft("cooked_beef_from_smoking"));
            known = reference instanceof CookingRecipe && ((CookingRecipe<?>) reference).getCookingTime() >= 200;
            fuelSetsSpeed = known;
        }
        return known;
    }

    private boolean accepts(CookingRecipe<?> recipe, ItemStack input) {
        if (!recipe.getInputChoice().test(input)) {
            return false;
        }
        // A custom item is its material underneath, and vanilla matches on
        // materials. Kept out of vanilla's recipes unless it is the item the
        // recipe makes, as a real furnace keeps it out.
        Optional<ContentId> custom = items.idOf(input);
        if (custom.isEmpty() || !isVanilla(recipe)) {
            return true;
        }
        return custom.equals(items.idOf(recipe.getResult()));
    }

    private static boolean isVanilla(Recipe recipe) {
        return recipe instanceof Keyed
                && NamespacedKey.MINECRAFT.equals(((Keyed) recipe).getKey().getNamespace());
    }

    private List<CookingRecipe<?>> recipes(ModelInfo.Furnace.Kind kind) {
        long now = System.currentTimeMillis();
        synchronized (recipes) {
            if (now - recipesReadAt > RECIPES_FOR_MS) {
                recipes.clear();
                recipesReadAt = now;
            }
            List<CookingRecipe<?>> known = recipes.get(kind);
            if (known != null) {
                return known;
            }
            List<CookingRecipe<?>> found = new ArrayList<>();
            Iterator<Recipe> all = Bukkit.recipeIterator();
            while (all.hasNext()) {
                Recipe recipe = all.next();
                if (kind == ModelInfo.Furnace.Kind.FURNACE ? recipe instanceof FurnaceRecipe
                        : kind == ModelInfo.Furnace.Kind.SMOKER ? recipe instanceof SmokingRecipe
                        : recipe instanceof BlastingRecipe) {
                    found.add((CookingRecipe<?>) recipe);
                }
            }
            recipes.put(kind, found);
            return found;
        }
    }

    /**
     * How many ticks one of {@code fuel} burns for, in this kind of furnace:
     * the game's own table, halved for a smoker and a blast furnace as the game
     * halves it. Zero for something that is not fuel.
     *
     * <p>Bukkit says whether a material is fuel but not for how long, so the
     * lengths are the game's, written down. Anything fuel that the table does
     * not name burns for 300, which is what most wooden things burn for.
     */
    static int burnTime(ItemStack fuel, ModelInfo.Furnace.Kind kind) {
        if (!present(fuel) || !fuel.getType().isFuel()) {
            return 0;
        }
        int ticks = burnTime(fuel.getType().name());
        return kind == ModelInfo.Furnace.Kind.FURNACE ? ticks : ticks / 2;
    }

    static int burnTime(String material) {
        switch (material) {
            case "LAVA_BUCKET":
                return 20000;
            case "COAL_BLOCK":
                return 16000;
            case "DRIED_KELP_BLOCK":
                return 4001;
            case "BLAZE_ROD":
                return 2400;
            case "COAL":
            case "CHARCOAL":
                return 1600;
            case "BOW":
            case "CROSSBOW":
            case "FISHING_ROD":
            case "LADDER":
                return 300;
            case "STICK":
            case "BOWL":
            case "AZALEA":
            case "FLOWERING_AZALEA":
            case "MANGROVE_PROPAGULE":
            case "DEAD_BUSH":
                return 100;
            case "BAMBOO":
            case "SCAFFOLDING":
                return 50;
            default:
                break;
        }
        if (material.endsWith("_BOAT") || material.endsWith("_RAFT")) {
            return 1200;
        }
        if (material.endsWith("_HANGING_SIGN")) {
            return 800;
        }
        if (material.startsWith("WOODEN_") || material.endsWith("_DOOR") || material.endsWith("_SIGN")) {
            return 200;
        }
        if (material.endsWith("_WOOL_SLAB")) {
            return 50;
        }
        if (material.endsWith("_SLAB")) {
            return 150;
        }
        if (material.endsWith("_SAPLING") || material.endsWith("_WOOL") || material.endsWith("_BUTTON")) {
            return 100;
        }
        if (material.endsWith("_CARPET")) {
            return 67;
        }
        return 300;
    }

    // ---- experience --------------------------------------------------------

    /**
     * Taking from the result slot hands out what the piece has cooked so far
     * in experience, as a real furnace does. Checked a tick later, by whether
     * the result went down, so every way of taking counts: a click, a shift
     * click, a number key, a drop.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Live) || event.getRawSlot() != RESULT || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Live one = (Live) holder;
        Player player = (Player) event.getWhoClicked();
        ItemStack before = one.inventory.getItem(RESULT);
        int had = before == null ? 0 : before.getAmount();
        Tasks.onLater(plugin, player, () -> {
            ItemStack after = one.inventory.getItem(RESULT);
            int has = after == null ? 0 : after.getAmount();
            if (has < had) {
                award(one, player.getLocation());
            }
        }, 1L);
    }

    private void award(Live one, Location at) {
        int whole = (int) Math.floor(one.xp);
        float part = one.xp - whole;
        if (part > 0 && ThreadLocalRandom.current().nextFloat() < part) {
            whole++;
        }
        one.xp = 0f;
        one.dirty = true;
        orbs(at, whole);
    }

    private static void orbs(Location at, int amount) {
        if (amount <= 0 || at.getWorld() == null) {
            return;
        }
        // Not the spawn that takes a function, whose signature changed in 1.20.
        at.getWorld().spawn(at, ExperienceOrb.class).setExperience(amount);
    }

    // ---- closing, unloading, breaking ------------------------------------

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
        if (one == null) {
            return;
        }
        save(one, hitbox);
        forget(one);
        close(one);
    }

    /**
     * The piece is being broken: every view of it closed and emptied first,
     * then its slots handed back for the caller to spill, and its experience
     * dropped where it stood.
     */
    List<ItemStack> removed(Interaction hitbox) {
        Live one = live.get(hitbox.getUniqueId());
        ItemStack[] slots;
        float xp;
        if (one != null) {
            forget(one);
            slots = one.inventory.getContents().clone();
            for (int i = 0; i < slots.length; i++) {
                slots[i] = slots[i] == null ? null : slots[i].clone();
            }
            one.inventory.clear();
            close(one);
            xp = one.xp;
        } else {
            slots = readSlots(hitbox);
            Float kept = hitbox.getPersistentDataContainer().get(xpKey, PersistentDataType.FLOAT);
            xp = kept == null ? 0f : kept;
            if (slots == null) {
                // Unreadable, and what carried it is about to go. Said loudly;
                // a later engine may still read the log's bytes.
                plugin.getLogger().severe("A furnace was broken whose contents could not be read: "
                        + hitbox.getUniqueId());
                slots = new ItemStack[0];
            }
        }
        PersistentDataContainer data = hitbox.getPersistentDataContainer();
        data.remove(slotsKey);
        data.remove(stateKey);
        data.remove(xpKey);
        orbs(hitbox.getLocation().add(0, 0.5, 0), (int) Math.floor(xp));
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

    /** How many furnaces are cooking or open right now. */
    public int liveCount() {
        return live.size();
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
                plugin.getLogger().severe("Could not save the furnace " + one.hitbox + ": " + e.getMessage()
                        + ". The last saved contents are kept.");
            }
        }
        if (one.lit == 0 && one.litDuration == 0 && one.cook == 0 && one.cookTotal == 0) {
            data.remove(stateKey);
        } else {
            data.set(stateKey, PersistentDataType.INTEGER_ARRAY,
                    new int[]{one.lit, one.litDuration, one.cook, one.cookTotal});
        }
        if (one.xp > 0f) {
            data.set(xpKey, PersistentDataType.FLOAT, one.xp);
        } else {
            data.remove(xpKey);
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

    // ---- helpers -------------------------------------------------------------

    private static boolean present(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.getAmount() > 0;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        if (!present(a) || !present(b)) {
            return present(a) == present(b);
        }
        return a.isSimilar(b);
    }

    private static ItemStack copyOf(ItemStack stack) {
        return present(stack) ? stack.clone() : null;
    }

    private static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&',
                ai.resourcepack.engine.core.textfx.TextEffectCatalogue.formatInstalled(text));
    }

    /** One furnace somebody has open or that is cooking, and the holder of its inventory. */
    static final class Live implements InventoryHolder {

        final UUID hitbox;
        ModelInfo.Furnace spec;
        final Location at;
        Inventory inventory;
        Tasks.Handle ticker;

        /** Ticks of fuel left. */
        int lit;
        /** How long the fuel now burning burns for, for the flame's height. */
        int litDuration;
        /** Ticks the input has cooked. */
        int cook;
        /** Ticks the input needs. */
        int cookTotal;
        /** Experience cooked and not yet handed out. */
        float xp;

        /** The input as the last tick saw it, to notice a different one. */
        ItemStack lastInput;
        CookingRecipe<?> lastRecipe;

        boolean dirty;
        int sinceSave;
        /** Set once it has been let go; a close after that saves nothing. */
        boolean finished;

        Live(UUID hitbox, ModelInfo.Furnace spec, Location at) {
            this.hitbox = hitbox;
            this.spec = spec;
            this.at = at;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
