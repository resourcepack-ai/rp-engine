package ai.resourcepack.engine.core.model;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.Sounds;
import ai.resourcepack.engine.api.event.PieceCookEvent;
import ai.resourcepack.engine.core.sound.SoundAt;
import ai.resourcepack.engine.core.vehicle.VehicleParticles;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Things that cook: an oven, a stove, a forge, placed as a model or a custom
 * block.
 *
 * <p><strong>The furnace is simulated, not borrowed.</strong> A display entity
 * cannot be a furnace block, and a real furnace hidden somewhere would still be
 * a block the client draws, so the three slots and the four numbers a furnace
 * runs on are kept on the thing (see {@link PieceMachines}) and stepped here
 * once a tick with the game's own rules: the same order of checks, the same
 * fuel lengths, the server's own recipes. The screen is the game's furnace
 * screen, a real {@code FurnaceMenu}, so its slots behave as a furnace's do and
 * its flame and arrow are driven by the same four numbers.
 *
 * <p>The screen is always the FURNACE screen, whatever the kind. The game's
 * smoker and blast furnace screens are the same picture, and Bukkit only lets a
 * plugin set the progress of a screen whose type is the furnace's.
 *
 * <p>Experience is kept per piece and handed out when somebody takes from the
 * result slot, or dropped as orbs when the piece is broken, as vanilla does.
 *
 * <p>Custom items are kept out of vanilla's recipes the way the engine keeps
 * them out of a real furnace's.
 */
public final class PieceFurnaces extends PieceMachines<ModelInfo.Furnace, PieceFurnaces.One> {

    /** Input, fuel, result: the furnace screen's own order. */
    static final int INPUT = 0;
    static final int FUEL = 1;
    static final int RESULT = 2;

    /** How long a recipe list is trusted before it is read again, in ms. A reload adds recipes. */
    private static final long RECIPES_FOR_MS = 10_000L;

    private final Items items;
    private final VehicleParticles particles;
    private final List<Consumer<Anchor>> litListeners = new ArrayList<>();
    private final Map<ModelInfo.Furnace.Kind, List<CookingRecipe<?>>> recipes =
            new EnumMap<>(ModelInfo.Furnace.Kind.class);
    private volatile long recipesReadAt;
    private volatile Boolean fuelSetsSpeed;
    private Sounds sounds;
    private Function<Anchor, Float> yaws = anchor -> 0f;

    PieceFurnaces(Plugin plugin, Items items) {
        super(plugin, "furnace");
        this.items = items;
        this.particles = new VehicleParticles(plugin.getLogger());
    }

    /** Told when a custom block starts or stops burning, to put on or take off its look. */
    public void onBlockLit(java.util.function.BiConsumer<org.bukkit.block.Block, Boolean> listener) {
        onLit(anchor -> {
            if (anchor instanceof BlockAnchor) {
                listener.accept(anchor.block(), lit(anchor));
            }
        });
    }

    void onLit(Consumer<Anchor> listener) {
        litListeners.add(listener);
    }

    void sounds(Sounds sounds) {
        this.sounds = sounds;
    }

    /** Which way a thing faces, so a particle offset turns with it. */
    void yaws(Function<Anchor, Float> yaws) {
        this.yaws = yaws;
    }

    /** Whether this thing is burning fuel right now. */
    boolean lit(Anchor anchor) {
        One one = liveAt(anchor);
        if (one != null) {
            return one.lit > 0;
        }
        int[] state = keptState(anchor);
        return state != null && state.length > 0 && state[0] > 0;
    }

    @Override
    protected int slotCount() {
        return 3;
    }

    @Override
    protected InventoryType screenType() {
        return InventoryType.FURNACE;
    }

    @Override
    protected One make(Anchor anchor, ContentId piece, ModelInfo.Furnace spec) {
        return new One(anchor, piece, spec);
    }

    @Override
    protected int[] state(One one) {
        return one.lit == 0 && one.litDuration == 0 && one.cook == 0 && one.cookTotal == 0
                ? null : new int[]{one.lit, one.litDuration, one.cook, one.cookTotal};
    }

    @Override
    protected void restore(One one, int[] state) {
        if (state.length >= 4) {
            one.lit = Math.max(0, state[0]);
            one.litDuration = Math.max(0, state[1]);
            one.cook = Math.max(0, state[2]);
            one.cookTotal = Math.max(0, state[3]);
        }
    }

    @Override
    protected boolean working(int[] state, ItemStack[] slots) {
        return (state != null && state.length >= 3 && (state[0] > 0 || state[2] > 0))
                || (present(slots[INPUT]) && present(slots[FUEL]));
    }

    @Override
    protected ContentId screenOf(ModelInfo.Furnace spec) {
        return spec.screen().orElse(null);
    }

    @Override
    protected void readExtra(One one, PersistentDataContainer data) {
        Float xp = data.get(xpKey(one.anchor), PersistentDataType.FLOAT);
        one.xp = xp == null ? 0f : xp;
        one.lastInput = copyOf(one.inventory.getItem(INPUT));
    }

    @Override
    protected void writeExtra(One one, PersistentDataContainer data) {
        if (one.xp > 0f) {
            data.set(xpKey(one.anchor), PersistentDataType.FLOAT, one.xp);
        } else {
            data.remove(xpKey(one.anchor));
        }
    }

    private NamespacedKey xpKey(Anchor anchor) {
        return anchor.key(plugin, "furnace-xp");
    }

    @Override
    protected void broken(Anchor anchor, One one) {
        PersistentDataContainer data = anchor.data();
        Float kept = data.get(xpKey(anchor), PersistentDataType.FLOAT);
        float xp = one != null ? one.xp : kept == null ? 0f : kept;
        data.remove(xpKey(anchor));
        orbs(anchor.centre(), (int) Math.floor(xp));
    }

    @Override
    protected int[] slotsFacing(HopperSide side) {
        switch (side) {
            case ABOVE:
                return new int[]{INPUT};
            case SIDE:
                return new int[]{FUEL};
            default:
                return new int[]{RESULT, FUEL};
        }
    }

    @Override
    protected boolean canInsert(One one, int slot, ItemStack item) {
        return slot == INPUT || (slot == FUEL && burnTime(item, one.spec.kind()) > 0);
    }

    @Override
    protected boolean canExtract(One one, int slot, ItemStack item) {
        return slot == RESULT || item.getType() == Material.BUCKET || item.getType() == Material.WATER_BUCKET;
    }

    @Override
    protected boolean idle(One one) {
        return one.lit == 0 && one.cook == 0 && !canStart(one);
    }

    @Override
    protected void afterStep(One one) {
        boolean isLit = one.lit > 0;
        if (isLit != one.wasLit) {
            one.wasLit = isLit;
            one.dirty = true;
            save(one);
            for (Consumer<Anchor> listener : litListeners) {
                listener.accept(one.anchor);
            }
        }
        if (isLit) {
            effects(one);
        }
    }

    private void effects(One one) {
        ModelInfo.Furnace.Effects effects = one.spec.effects();
        World world = one.at.getWorld();
        if (world == null) {
            return;
        }
        ModelInfo.Furnace.Particles spec = effects.particles();
        if (spec != null && ++one.sinceParticle >= spec.every()) {
            one.sinceParticle = 0;
            Particle particle = particles.resolve(spec.type());
            if (particle != null) {
                try {
                    world.spawnParticle(particle, particleAt(one, spec), spec.count(), spec.spread(), spec.spread(),
                            spec.spread(), 0.0);
                } catch (IllegalArgumentException wantsData) {
                    // A particle that takes colour or a block. A wrong pick
                    // should cost its smoke, never the furnace.
                    one.spec = one.spec.withEffects(effects.withoutParticles());
                    plugin.getLogger().warning("Particle " + spec.type() + " cannot be used on a furnace.");
                }
            }
        }
        if (effects.soundOn() && ThreadLocalRandom.current().nextInt(100) == 0) {
            String sound = effects.sound().orElseGet(() -> defaultSound(one.spec.kind()));
            SoundAt.play(sounds, one.at, sound, SoundCategory.BLOCKS, 1f, 1f);
        }
    }

    private Location particleAt(One one, ModelInfo.Furnace.Particles spec) {
        Location base = one.at.clone().subtract(0, 0.5, 0);
        if (!spec.positioned()) {
            double top = one.anchor.hitbox() == null ? 1.05 : one.anchor.hitbox().getInteractionHeight() + 0.05;
            return base.add(0, top, 0);
        }
        // The same frame as a seat's offset: right, up, forward of the piece.
        double radians = Math.toRadians(yaws.apply(one.anchor));
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return base.add(spec.right() * cos - spec.forward() * sin, spec.up(),
                spec.right() * sin + spec.forward() * cos);
    }

    private static String defaultSound(ModelInfo.Furnace.Kind kind) {
        switch (kind) {
            case SMOKER:
                return "minecraft:block.smoker.smoke";
            case BLAST_FURNACE:
                return "minecraft:block.blastfurnace.fire_crackle";
            default:
                return "minecraft:block.furnace.fire_crackle";
        }
    }

    /**
     * One tick of a furnace, in the order the game's own furnace does it.
     *
     * @return whether anything a save would keep changed
     */
    @Override
    protected boolean step(One one) {
        Inventory inventory = one.inventory;
        ItemStack input = inventory.getItem(INPUT);
        boolean changed = false;

        // A different input starts its cooking from nothing, as setting a
        // furnace's input slot to a different item does.
        if (!same(input, one.lastInput)) {
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
    private boolean canStart(One one) {
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

    private void burn(One one, CookingRecipe<?> recipe) {
        Inventory inventory = one.inventory;
        ItemStack input = inventory.getItem(INPUT);
        ItemStack single = input.clone();
        single.setAmount(1);
        PieceCookEvent event = new PieceCookEvent(one.piece, one.at, one.anchor.hitbox(), single,
                recipe.getResult().clone());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        ItemStack result = event.result();
        ItemStack out = inventory.getItem(RESULT);
        if (!present(out)) {
            inventory.setItem(RESULT, result);
        } else if (out.isSimilar(result)
                && out.getAmount() + result.getAmount() <= Math.min(inventory.getMaxStackSize(), out.getMaxStackSize())) {
            out.setAmount(out.getAmount() + result.getAmount());
            inventory.setItem(RESULT, out);
        } else if (one.at.getWorld() != null) {
            // A listener changed the result to something the slot cannot take.
            one.at.getWorld().dropItemNaturally(one.at, result);
        }
        // A wet sponge dried with a bucket in the fuel slot fills it, as in a real furnace.
        if (input.getType() == Material.WET_SPONGE) {
            ItemStack fuel = inventory.getItem(FUEL);
            if (fuel != null && fuel.getType() == Material.BUCKET && fuel.getAmount() == 1) {
                inventory.setItem(FUEL, new ItemStack(Material.WATER_BUCKET));
            }
        }
        input.setAmount(input.getAmount() - 1);
        inventory.setItem(INPUT, input.getAmount() > 0 ? input : null);
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
    @Override
    protected void show(HumanEntity viewer, One one) {
        viewer.setWindowProperty(InventoryView.Property.BURN_TIME, one.lit);
        viewer.setWindowProperty(InventoryView.Property.TICKS_FOR_CURRENT_FUEL, one.litDuration);
        viewer.setWindowProperty(InventoryView.Property.COOK_TIME, one.cook);
        viewer.setWindowProperty(InventoryView.Property.TICKS_FOR_CURRENT_SMELTING, one.cookTotal);
    }

    // ---- recipes and fuel --------------------------------------------------

    private CookingRecipe<?> recipeFor(One one, ItemStack input) {
        if (one.lastRecipe != null && accepts(one, one.lastRecipe, input)) {
            return one.lastRecipe;
        }
        for (CookingRecipe<?> recipe : recipes(one.spec.kind())) {
            if (accepts(one, recipe, input)) {
                one.lastRecipe = recipe;
                return recipe;
            }
        }
        return null;
    }

    /**
     * How many ticks this recipe takes in this one, at its {@code speed:}.
     *
     * <p>Up to 1.21 a smoker's and a blast furnace's recipes state the fast
     * time themselves. From 26.1 they state the furnace's time and the game
     * doubles the speed for those two blocks, through the fuel. Bukkit reports
     * only the recipe's number, so which of the two this server does is read
     * off one vanilla recipe.
     */
    private int cookTicks(One one, CookingRecipe<?> recipe) {
        int ticks = recipe.getCookingTime();
        if (one.spec.kind() != ModelInfo.Furnace.Kind.FURNACE && fuelSetsSpeed()) {
            ticks = (ticks + 1) / 2;
        }
        return Math.max(1, (int) Math.ceil(ticks / one.spec.speed()));
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

    private boolean accepts(One one, CookingRecipe<?> recipe, ItemStack input) {
        if (!recipe.getInputChoice().test(input) || !allowed(one.spec, recipe)) {
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

    /**
     * Whether a piece's {@code recipes:} lets this one through. An engine
     * recipe is registered under a key of the plugin's own, so the id a pack
     * wrote is compared in that form as well.
     */
    private boolean allowed(ModelInfo.Furnace spec, Recipe recipe) {
        if (spec.recipes().isEmpty()) {
            return true;
        }
        if (!(recipe instanceof Keyed)) {
            return false;
        }
        NamespacedKey key = ((Keyed) recipe).getKey();
        String written = key.toString();
        for (String id : spec.recipes()) {
            if (id.equals(written) || engineKey(id).equals(written)) {
                return true;
            }
        }
        return false;
    }

    private String engineKey(String id) {
        return plugin.getName().toLowerCase(Locale.ROOT) + ":"
                + id.replace(':', '_').replace('/', '_').toLowerCase(Locale.ROOT);
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
        if (!(holder instanceof One) || event.getRawSlot() != RESULT || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        One one = (One) holder;
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

    private void award(One one, Location at) {
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

    private static boolean same(ItemStack a, ItemStack b) {
        if (!present(a) || !present(b)) {
            return present(a) == present(b);
        }
        return a.isSimilar(b);
    }

    private static ItemStack copyOf(ItemStack stack) {
        return present(stack) ? stack.clone() : null;
    }

    /** One furnace somebody has open or that is cooking. */
    static final class One extends Live<ModelInfo.Furnace> {

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
        boolean wasLit;
        int sinceParticle;

        One(Anchor anchor, ContentId piece, ModelInfo.Furnace spec) {
            super(anchor, piece, spec);
        }
    }
}
