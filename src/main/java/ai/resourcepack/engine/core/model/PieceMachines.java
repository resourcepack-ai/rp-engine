package ai.resourcepack.engine.core.model;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.storage.ItemBytes;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The life of a thing in the world that does work on its own: a furnace or a
 * brewing stand that is really a placed model or a custom block.
 *
 * <p>Neither kind of thing can be the game's block, so the slots and the
 * numbers are kept on the thing itself, through an {@link Anchor}: a placed
 * model's hitbox, or the chunk under a custom block. While one is working or
 * open it is LIVE: one inventory, stepped once a tick on its own scheduler (its
 * region's on Folia), written back every few seconds, on every close, when its
 * chunk unloads and when the plugin stops. A live one with nothing to do,
 * nobody looking and no hopper beside it is written back and let go.
 *
 * <p>What is here is everything the two share. What each one DOES is a
 * subclass's: {@link #step} and the numbers it keeps.
 *
 * @param <S> what the pack said about one
 * @param <L> a live one
 */
public abstract class PieceMachines<S, L extends PieceMachines.Live<S>> implements Listener {

    /** How often a live one with changes is written back, in ticks. */
    private static final int SAVE_EVERY = 100;

    /** A vanilla hopper's own cooldown. */
    private static final int HOPPER_EVERY = 8;

    /** Where one is and where its bytes go. */
    public interface Anchor {

        /** Unique among everything that can be live at once. */
        String id();

        /** The middle of its block, or of the piece's base. */
        Location centre();

        /** The block it stands in. */
        Block block();

        /** A live container: an entity's, or a chunk's. */
        PersistentDataContainer data();

        /** The key {@code name} goes under here. */
        NamespacedKey key(Plugin plugin, String name);

        /** Whether it is still in the world and loaded. */
        boolean present();

        /** Whether the calling thread may touch it. */
        boolean owned();

        Tasks.Handle everyTick(Plugin plugin, Runnable task);

        /** The hitbox, for a placed model; null for a block. */
        default Interaction hitbox() {
            return null;
        }
    }

    /** A placed model, whose bytes are on its hitbox. */
    static final class HitboxAnchor implements Anchor {

        private final Interaction hitbox;

        HitboxAnchor(Interaction hitbox) {
            this.hitbox = hitbox;
        }

        @Override
        public String id() {
            return hitbox.getUniqueId().toString();
        }

        @Override
        public Location centre() {
            return hitbox.getLocation().add(0, 0.5, 0);
        }

        @Override
        public Block block() {
            return hitbox.getLocation().getBlock();
        }

        @Override
        public PersistentDataContainer data() {
            return hitbox.getPersistentDataContainer();
        }

        @Override
        public NamespacedKey key(Plugin plugin, String name) {
            return new NamespacedKey(plugin, name);
        }

        @Override
        public boolean present() {
            Entity found = Bukkit.getEntity(hitbox.getUniqueId());
            return found instanceof Interaction && found.isValid();
        }

        @Override
        public boolean owned() {
            return Tasks.owns(hitbox);
        }

        @Override
        public Tasks.Handle everyTick(Plugin plugin, Runnable task) {
            return Tasks.onTimer(plugin, hitbox, task, 1L, 1L);
        }

        @Override
        public Interaction hitbox() {
            return hitbox;
        }
    }

    /**
     * A custom block, whose bytes are in its chunk under a key carrying its
     * position, as a block's {@code storage:} is.
     */
    static final class BlockAnchor implements Anchor {

        private final Block block;

        BlockAnchor(Block block) {
            this.block = block;
        }

        @Override
        public String id() {
            return block.getWorld().getUID() + "/" + position();
        }

        private String position() {
            return block.getX() + "_" + block.getY() + "_" + block.getZ();
        }

        @Override
        public Location centre() {
            return block.getLocation().add(0.5, 0.5, 0.5);
        }

        @Override
        public Block block() {
            return block;
        }

        @Override
        public PersistentDataContainer data() {
            return block.getChunk().getPersistentDataContainer();
        }

        @Override
        public NamespacedKey key(Plugin plugin, String name) {
            return new NamespacedKey(plugin, name + "/" + position());
        }

        @Override
        public boolean present() {
            return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
        }

        @Override
        public boolean owned() {
            return Tasks.owns(centre());
        }

        @Override
        public Tasks.Handle everyTick(Plugin plugin, Runnable task) {
            return Tasks.atTimer(plugin, centre(), task, 1L, 1L);
        }
    }

    /** One standing in the world, as whatever placed it knows it. */
    record Found<S>(Anchor anchor, S spec, ContentId piece, String title) {
    }

    /** What a custom block in the world says it is. */
    public record BlockMachine<S>(S spec, ContentId piece, String title) {
    }

    /** One live, and the holder of its inventory. */
    public abstract static class Live<S> implements InventoryHolder {

        final Anchor anchor;
        final ContentId piece;
        final Location at;
        S spec;
        Inventory inventory;
        Tasks.Handle ticker;
        boolean dirty;
        int sinceSave;
        int sinceHopper;
        /** Set once it has been let go; a close after that saves nothing. */
        boolean finished;

        Live(Anchor anchor, ContentId piece, S spec) {
            this.anchor = anchor;
            this.piece = piece;
            this.spec = spec;
            this.at = anchor.centre();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    protected final Plugin plugin;
    private final String name;
    private final Map<String, L> live = new ConcurrentHashMap<>();
    private final List<Function<Block, Optional<Found<S>>>> finders = new ArrayList<>();

    /** What a screen's art is called, for a title. Null until wired. */
    private Function<ContentId, Optional<String>> screens;

    PieceMachines(Plugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    /** How to tell one is standing in a block, for a hopper put down beside it and a chunk coming back. */
    void finder(Function<Block, Optional<Found<S>>> finder) {
        finders.add(finder);
    }

    /** How to tell a custom block in the world is one of these. */
    public void blockFinder(Function<Block, Optional<BlockMachine<S>>> finder) {
        finder(block -> finder.apply(block).map(found ->
                new Found<>(new BlockAnchor(block), found.spec(), found.piece(), found.title())));
    }

    /** Opens the one a custom block is. */
    public boolean openBlock(Player player, Block block, S spec, ContentId piece, String title) {
        return open(player, new BlockAnchor(block), spec, piece, title);
    }

    /** The custom block is being broken. */
    public List<ItemStack> removedBlock(Block block) {
        return removed(new BlockAnchor(block));
    }

    void screens(Function<ContentId, Optional<String>> screens) {
        this.screens = screens;
    }

    protected abstract int slotCount();

    protected abstract InventoryType screenType();

    protected abstract L make(Anchor anchor, ContentId piece, S spec);

    /** The numbers to keep, or null when there is nothing worth keeping. */
    protected abstract int[] state(L one);

    protected abstract void restore(L one, int[] state);

    /** One tick of its work. @return whether anything a save would keep changed */
    protected abstract boolean step(L one);

    /** Puts its progress on a viewer's screen. */
    protected abstract void show(HumanEntity viewer, L one);

    /** Whether it has nothing to do while nobody is looking. */
    protected abstract boolean idle(L one);

    /** Whether what was kept says it should be working now. */
    protected abstract boolean working(int[] state, ItemStack[] slots);

    /** The art a pack gave its screen, or null. */
    protected abstract ContentId screenOf(S spec);

    /** Called when it is live and has just been written back or read. */
    protected void afterStep(L one) {
    }

    protected void readExtra(L one, PersistentDataContainer data) {
    }

    protected void writeExtra(L one, PersistentDataContainer data) {
    }

    /** It is being broken. What is not in the slots: experience, say. */
    protected void broken(Anchor anchor, L one) {
    }

    /** Which slots a hopper reaches, from above, the side or below. */
    protected abstract int[] slotsFacing(HopperSide side);

    protected abstract boolean canInsert(L one, int slot, ItemStack item);

    protected abstract boolean canExtract(L one, int slot, ItemStack item);

    private NamespacedKey slotsKey(Anchor anchor) {
        return anchor.key(plugin, name);
    }

    private NamespacedKey stateKey(Anchor anchor) {
        return anchor.key(plugin, name + "-state");
    }

    /** Whether anything about one is kept here. */
    boolean holdsAnything(Anchor anchor) {
        PersistentDataContainer data = anchor.data();
        return data.has(slotsKey(anchor), PersistentDataType.BYTE_ARRAY)
                || data.has(stateKey(anchor), PersistentDataType.INTEGER_ARRAY);
    }

    protected L liveAt(Anchor anchor) {
        return live.get(anchor.id());
    }

    protected int[] keptState(Anchor anchor) {
        return anchor.data().get(stateKey(anchor), PersistentDataType.INTEGER_ARRAY);
    }

    /** @return whether a screen opened */
    boolean open(Player player, Anchor anchor, S spec, ContentId piece, String title) {
        L one = wake(anchor, spec, piece, title);
        if (one == null) {
            player.sendMessage(ChatColor.RED + "What is in this could not be read, so it was not opened. "
                    + "Nothing in it has been touched.");
            return false;
        }
        if (player.openInventory(one.inventory) == null) {
            return false;
        }
        show(player, one);
        return true;
    }

    /** One coming back with its chunk, or standing there at startup: if it has work, it carries on. */
    void adopt(Anchor anchor, S spec, ContentId piece, String title) {
        if (live.containsKey(anchor.id())) {
            return;
        }
        boolean hopper = PieceHoppers.beside(anchor.block());
        if (!hopper && !holdsAnything(anchor)) {
            return;
        }
        ItemStack[] slots = readSlots(anchor);
        if (slots != null && (hopper || working(keptState(anchor), slots))) {
            wake(anchor, spec, piece, title);
        }
    }

    private L wake(Anchor anchor, S spec, ContentId piece, String title) {
        L one = live.get(anchor.id());
        if (one != null) {
            one.spec = spec;
            return one;
        }
        ItemStack[] slots = readSlots(anchor);
        if (slots == null) {
            return null;
        }
        one = make(anchor, piece, spec);
        one.inventory = Bukkit.createInventory(one, screenType(), screenTitle(spec, title));
        one.inventory.setContents(slots);
        int[] state = keptState(anchor);
        if (state != null) {
            restore(one, state);
        }
        readExtra(one, anchor.data());
        live.put(anchor.id(), one);
        L ticking = one;
        one.ticker = anchor.everyTick(plugin, () -> tick(ticking));
        afterStep(one);
        return one;
    }

    private String screenTitle(S spec, String title) {
        ContentId screen = screenOf(spec);
        if (screen != null && screens != null) {
            Optional<String> art = screens.apply(screen);
            if (art.isPresent()) {
                return art.get();
            }
        }
        return ChatColor.translateAlternateColorCodes('&',
                ai.resourcepack.engine.core.textfx.TextEffectCatalogue.formatInstalled(title));
    }

    private ItemStack[] readSlots(Anchor anchor) {
        byte[] bytes = anchor.data().get(slotsKey(anchor), PersistentDataType.BYTE_ARRAY);
        ItemStack[] slots = new ItemStack[slotCount()];
        if (bytes == null) {
            return slots;
        }
        try {
            ItemStack[] read = ItemBytes.read(bytes);
            System.arraycopy(read, 0, slots, 0, Math.min(slots.length, read.length));
            return slots;
        } catch (IOException e) {
            // Opened empty, this would be saved back empty. Refused instead,
            // and the bytes stay where they are.
            plugin.getLogger().severe("Did not open " + anchor.id() + ": its contents are " + e.getMessage());
            return null;
        }
    }

    private void tick(L one) {
        if (!one.anchor.present()) {
            // Gone without the unload or the break reaching us: nothing left
            // to write onto.
            forget(one);
            close(one);
            return;
        }
        if (step(one)) {
            one.dirty = true;
        }
        if (++one.sinceHopper >= HOPPER_EVERY) {
            one.sinceHopper = 0;
            if (PieceHoppers.serve(one, this)) {
                one.dirty = true;
            }
        }
        for (HumanEntity viewer : one.inventory.getViewers()) {
            show(viewer, one);
        }
        afterStep(one);
        if (one.dirty && ++one.sinceSave >= SAVE_EVERY) {
            save(one);
        }
        if (one.inventory.getViewers().isEmpty() && idle(one) && !PieceHoppers.beside(one.anchor.block())) {
            save(one);
            forget(one);
        }
    }

    protected final void save(L one) {
        PersistentDataContainer data = one.anchor.data();
        ItemStack[] slots = one.inventory.getContents();
        if (!ItemBytes.anything(slots)) {
            data.remove(slotsKey(one.anchor));
        } else {
            try {
                data.set(slotsKey(one.anchor), PersistentDataType.BYTE_ARRAY, ItemBytes.write(slots));
            } catch (IOException | RuntimeException e) {
                // Loud, because the alternative is silence about somebody's items.
                plugin.getLogger().severe("Could not save " + one.anchor.id() + ": " + e.getMessage()
                        + ". The last saved contents are kept.");
            }
        }
        int[] state = state(one);
        if (state == null) {
            data.remove(stateKey(one.anchor));
        } else {
            data.set(stateKey(one.anchor), PersistentDataType.INTEGER_ARRAY, state);
        }
        writeExtra(one, data);
        one.dirty = false;
        one.sinceSave = 0;
    }

    private void forget(L one) {
        one.finished = true;
        live.remove(one.anchor.id(), one);
        if (one.ticker != null) {
            one.ticker.cancel();
            one.ticker = null;
        }
    }

    private void close(L one) {
        for (HumanEntity viewer : new ArrayList<>(one.inventory.getViewers())) {
            viewer.closeInventory();
        }
    }

    @SuppressWarnings("unchecked")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Live) || !live.containsValue(holder)) {
            return;
        }
        L one = (L) holder;
        if (!one.finished && one.anchor.present() && one.anchor.owned()) {
            save(one);
        }
    }

    /** Its chunk is unloading: written back while there is something to write onto, and let go. */
    void unload(Anchor anchor) {
        L one = live.get(anchor.id());
        if (one != null) {
            save(one);
            forget(one);
            close(one);
        }
    }

    /** It is being broken: every view closed and emptied first, then its slots handed back to spill. */
    List<ItemStack> removed(Anchor anchor) {
        L one = live.get(anchor.id());
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
            slots = readSlots(anchor);
            if (slots == null) {
                // Unreadable, and what carried it is about to go.
                plugin.getLogger().severe("Something was broken whose contents could not be read: " + anchor.id());
                slots = new ItemStack[0];
            }
        }
        broken(anchor, one);
        PersistentDataContainer data = anchor.data();
        data.remove(slotsKey(anchor));
        data.remove(stateKey(anchor));
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
        for (L one : new ArrayList<>(live.values())) {
            if (one.anchor.present()) {
                save(one);
            }
            forget(one);
            close(one);
        }
    }

    /** A hopper put down beside one wakes it, since nothing else would. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!PieceHoppers.isHopper(event.getBlockPlaced())) {
            return;
        }
        for (Block next : PieceHoppers.neighbours(event.getBlockPlaced())) {
            find(next).ifPresent(found -> adopt(found.anchor(), found.spec(), found.piece(), found.title()));
        }
    }

    private Optional<Found<S>> find(Block block) {
        for (Function<Block, Optional<Found<S>>> finder : finders) {
            Optional<Found<S>> found = finder.apply(block);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Custom blocks keep nothing an entity event would announce, so their chunk is read as it arrives. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        adoptBlocks(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (L one : new ArrayList<>(live.values())) {
            if (one.anchor instanceof BlockAnchor) {
                Block block = one.anchor.block();
                if (block.getWorld().equals(event.getWorld()) && block.getX() >> 4 == event.getChunk().getX()
                        && block.getZ() >> 4 == event.getChunk().getZ()) {
                    unload(one.anchor);
                }
            }
        }
    }

    /** Every custom block already standing in a loaded chunk. For startup and reload. */
    public void adoptLoadedBlocks() {
        if (Tasks.folia()) {
            // A region's chunks are not this thread's to read, and Folia keeps
            // none loaded at startup anyway; they arrive through the event.
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                adoptBlocks(chunk);
            }
        }
    }

    private void adoptBlocks(Chunk chunk) {
        String prefix = name + "/";
        String stateName = name + "-state/";
        for (NamespacedKey key : new ArrayList<>(chunk.getPersistentDataContainer().getKeys())) {
            if (!key.getNamespace().equals(plugin.getName().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            String path = key.getKey();
            String position = path.startsWith(prefix) ? path.substring(prefix.length())
                    : path.startsWith(stateName) ? path.substring(stateName.length()) : null;
            if (position == null) {
                continue;
            }
            String[] parts = position.split("_");
            if (parts.length != 3) {
                continue;
            }
            try {
                Block block = chunk.getWorld().getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
                find(block).ifPresent(found -> adopt(found.anchor(), found.spec(), found.piece(), found.title()));
            } catch (NumberFormatException ignored) {
                // A key of somebody else's shape.
            }
        }
    }

    static boolean present(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.getAmount() > 0;
    }

    /** Where a hopper is, against what it is serving. */
    enum HopperSide {
        ABOVE, SIDE, BELOW
    }
}
