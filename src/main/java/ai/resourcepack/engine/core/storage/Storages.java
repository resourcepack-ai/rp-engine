package ai.resourcepack.engine.core.storage;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Sounds;
import ai.resourcepack.engine.api.StorageSpec;
import ai.resourcepack.engine.core.sound.SoundAt;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Opens containers, and is the only thing that ever writes one back.
 *
 * <p><strong>The design is one rule: while anybody has a container open, there
 * is exactly one live {@link Inventory} for it.</strong> Every player who opens
 * the same holder is shown that same inventory, so two people looking into one
 * cabinet watch each other's moves, and there is never a second copy of the
 * contents to be saved over the first. Two copies racing to be saved last is
 * how every chest plugin has duplicated items at least once; keying the live
 * inventory by {@link StorageHolder#key()} is what rules it out.
 *
 * <p>The contents are written back on every close, and the live inventory is
 * let go once the last viewer has closed it. Every other way a view can end
 * goes through here too:
 * <ul>
 *   <li><b>the thing it belongs to is broken</b> — {@link #removed}: every
 *       viewer's view is closed and the inventory emptied BEFORE the contents
 *       are handed back to be spilled, so nothing can be taken out of a chest
 *       whose contents are already on the floor;</li>
 *   <li><b>its chunk unloads</b> — {@link #close}: saved, then closed, while the
 *       thing it is saved onto still exists;</li>
 *   <li><b>the plugin goes</b> — {@link #closeAll}, from {@code onDisable},
 *       which runs before the server saves its worlds;</li>
 *   <li><b>a player quits</b> — the game closes their view and fires the close
 *       event, which is the ordinary path.</li>
 * </ul>
 *
 * <p><strong>Nothing here knows what a container is FOR.</strong> A placed
 * model hands in a holder on its hitbox entity; a custom block will hand in
 * {@link PdcStorage#forBlock}. That is the whole of the integration.
 *
 * <p>Main thread only.
 */
public final class Storages implements Listener {

    private final Plugin plugin;
    private final Sounds sounds;

    /**
     * The key contents are kept under, on whatever carries them — a placed
     * piece's hitbox, and the dropped item of a shulker-style piece. One key
     * for both is what lets the bytes move from one to the other untouched.
     */
    private final NamespacedKey contentsKey;

    /** Every container somebody has open, by holder key. */
    private final Map<String, Live> open = new HashMap<>();

    /** Who is looking at their own ender chest because a piece opened it, and where. */
    private final Map<UUID, Location> enderViews = new HashMap<>();

    public Storages(Plugin plugin, Sounds sounds) {
        this.plugin = plugin;
        this.sounds = sounds;
        this.contentsKey = new NamespacedKey(plugin, "storage");
    }

    /** The key a container's bytes are kept under. */
    public NamespacedKey contentsKey() {
        return contentsKey;
    }

    /**
     * Opens a container for {@code player}.
     *
     * @param spec   what kind, how big, what it is called and sounds like
     * @param kind   the content id of what is being opened. A
     *               {@link StorageSpec.Type#PERSONAL} container is keyed by it,
     *               so every piece of one kind opens the same one for the same
     *               player
     * @param holder where the contents live, for a {@link StorageSpec.Type#CHEST}
     *               or {@link StorageSpec.Type#SHULKER}. Ignored, and may be
     *               null, for the other three: their contents are the player's
     *               or nobody's
     * @param title  what to call it when {@code spec} does not say — the name
     *               of the thing being opened. {@code &} colour codes work
     * @param at     where the open and close sounds play
     * @return whether a container opened. False when another plugin cancelled
     *         the open, or what is stored could not be read
     */
    public boolean open(Player player, StorageSpec spec, ContentId kind, StorageHolder holder,
                        String title, Location at) {
        if (player == null || spec == null) {
            return false;
        }
        String name = colour(spec.title().orElse(title == null || title.isBlank() ? "Storage" : title));
        switch (spec.type()) {
            case ENDERCHEST: {
                InventoryView view = player.openInventory(player.getEnderChest());
                if (view == null) {
                    return false;
                }
                enderViews.put(player.getUniqueId(), at == null ? null : at.clone());
                sound(at, spec.openSound().orElse(null));
                return true;
            }
            case DISPOSAL: {
                // Its own inventory every time, under a key nobody else can
                // reach: a bin shared between two players would let one of
                // them take back what the other threw away.
                Live live = new Live("disposal/" + UUID.randomUUID(), null, spec, at);
                live.inventory = Bukkit.createInventory(live, spec.size(), name);
                return show(player, live);
            }
            case PERSONAL:
                if (kind == null) {
                    return false;
                }
                holder = new PdcStorage("personal/" + player.getUniqueId() + "/" + kind,
                        player.getPersistentDataContainer(), personalKey(kind), plugin.getLogger());
                break;
            default:
                if (holder == null) {
                    return false;
                }
                break;
        }

        Live live = open.get(holder.key());
        if (live == null) {
            ItemStack[] stored;
            try {
                stored = holder.load();
            } catch (IOException e) {
                // Opened empty, this would be saved back empty the moment it
                // closed. Refused instead, and the bytes stay put.
                plugin.getLogger().severe("Did not open " + holder.key() + ": its contents are "
                        + e.getMessage());
                player.sendMessage(ChatColor.RED + "This container's contents could not be read, so it was "
                        + "not opened. Nothing in it has been touched.");
                return false;
            }
            live = new Live(holder.key(), holder, spec, at);
            int size = sizeFor(spec.size(), stored);
            live.inventory = Bukkit.createInventory(live, size, name);
            live.inventory.setContents(fit(stored, size));
        }
        return show(player, live);
    }

    private boolean show(Player player, Live live) {
        boolean first = live.inventory.getViewers().isEmpty();
        // In the map BEFORE the view opens, because opening fires an event a
        // plugin can answer by opening something else, which closes this.
        open.putIfAbsent(live.key, live);
        InventoryView view = player.openInventory(live.inventory);
        if (view == null) {
            // Another plugin said no. Nothing changed, so there is nothing to
            // save; just let go of it if nobody else is looking.
            if (live.inventory.getViewers().isEmpty()) {
                release(live);
            }
            return false;
        }
        if (first) {
            sound(live.at, live.spec.openSound().orElse(null));
        }
        return true;
    }

    /**
     * The thing a container belonged to is gone: close every view of it and
     * hand back what was inside, for the caller to spill or keep.
     *
     * <p>Empty for a type whose contents are not the holder's — a personal
     * container is the player's, an ender chest is the game's, a bin is
     * nobody's — and for a holder nobody ever put anything in.
     *
     * <p>Order is the duplication guard: the live inventory is let go and
     * emptied FIRST, then the contents copied out, then every viewer's view
     * closed. A viewer whose screen is still open in that tick sees an empty
     * container, rather than one they can keep taking from after its contents
     * are already on the ground.
     */
    public List<ItemStack> removed(StorageSpec spec, StorageHolder holder) {
        if (spec == null || holder == null || !spec.type().keepsContents()) {
            return List.of();
        }
        Live live = open.remove(holder.key());
        ItemStack[] contents;
        if (live != null) {
            live.finished = true;
            contents = copy(live.inventory.getContents());
            live.inventory.clear();
            for (HumanEntity viewer : new ArrayList<>(live.inventory.getViewers())) {
                viewer.closeInventory();
            }
        } else {
            try {
                contents = holder.load();
            } catch (IOException e) {
                // The thing carrying these bytes is about to go, and the bytes
                // with it. Kept on disk instead, so a newer engine or a person
                // can still get at them.
                quarantine(holder, e);
                holder.onRemoved();
                return List.of();
            }
        }
        holder.onRemoved();
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack slot : contents) {
            if (slot != null && !slot.getType().isAir() && slot.getAmount() > 0) {
                out.add(slot);
            }
        }
        return out;
    }

    /**
     * Saves and closes {@code key} if anybody has it open. For a holder that is
     * about to stop existing for a while — its chunk is unloading — so the save
     * lands while there is still something to save onto.
     */
    public void close(String key) {
        Live live = open.remove(key);
        if (live == null) {
            return;
        }
        live.finished = true;
        if (live.holder != null) {
            live.holder.save(copy(live.inventory.getContents()));
        } else {
            live.inventory.clear();
        }
        for (HumanEntity viewer : new ArrayList<>(live.inventory.getViewers())) {
            viewer.closeInventory();
        }
    }

    /** Whether anybody has {@code key} open. */
    public boolean isOpen(String key) {
        return open.containsKey(key);
    }

    /** Saves and closes everything. The plugin is going. */
    public void closeAll() {
        for (String key : new ArrayList<>(open.keySet())) {
            close(key);
        }
        for (UUID viewer : new ArrayList<>(enderViews.keySet())) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) {
                player.closeInventory();
            }
        }
        enderViews.clear();
    }

    // ---- carrying contents on an item ---------------------------------

    /**
     * Puts {@code contents} inside {@code stack}, for a shulker-style piece
     * being picked up. Nothing is written for an empty container, so an empty
     * crate is the same stack as a fresh one and the two still stack.
     */
    public void keepInside(ItemStack stack, List<ItemStack> contents) {
        if (stack == null || contents == null || contents.isEmpty()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        try {
            meta.getPersistentDataContainer().set(contentsKey, PersistentDataType.BYTE_ARRAY,
                    ItemBytes.write(contents.toArray(new ItemStack[0])));
            stack.setItemMeta(meta);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().severe("Could not keep a container's contents in its item: " + e.getMessage());
        }
    }

    /** The contents a stack is carrying, as stored, or null if it carries none. */
    public byte[] carried(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(contentsKey, PersistentDataType.BYTE_ARRAY);
    }

    /** {@code stack} with any contents it carries taken off it. */
    public ItemStack withoutContents(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(contentsKey, PersistentDataType.BYTE_ARRAY)) {
            return stack;
        }
        meta.getPersistentDataContainer().remove(contentsKey);
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- closing -------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder owner = event.getInventory().getHolder();
        if (!(owner instanceof Live)) {
            if (event.getInventory().getType() == InventoryType.ENDER_CHEST) {
                Location at = enderViews.remove(event.getPlayer().getUniqueId());
                if (at != null) {
                    sound(at, StorageSpec.ENDER_CLOSE);
                }
            }
            return;
        }
        Live live = (Live) owner;
        if (live.finished) {
            // Already handed back by removed() or saved by close(). Saving
            // again here would write the emptied inventory over contents that
            // are already on the floor or already on disk.
            return;
        }
        int remaining = 0;
        for (HumanEntity viewer : live.inventory.getViewers()) {
            if (!viewer.getUniqueId().equals(event.getPlayer().getUniqueId())) {
                remaining++;
            }
        }
        if (live.holder == null) {
            // A bin. What went in is gone, which is the point of one.
            if (remaining == 0) {
                live.inventory.clear();
                release(live);
            }
            return;
        }
        // On every close, not only the last: a server that crashes while the
        // second person is still looking has lost nothing the first one did.
        live.holder.save(copy(live.inventory.getContents()));
        if (remaining == 0) {
            release(live);
            sound(live.at, live.spec.closeSound().orElse(null));
        }
    }

    /**
     * A belt to the game's own braces: the server closes a quitting player's
     * view and fires the close event for it, which saves. This forgets an
     * ender-chest view that event might not reach.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        enderViews.remove(event.getPlayer().getUniqueId());
    }

    private void release(Live live) {
        live.finished = true;
        open.remove(live.key, live);
    }

    // ---- helpers -------------------------------------------------------

    private NamespacedKey personalKey(ContentId kind) {
        return new NamespacedKey(plugin, "personal-storage/" + kind.namespace() + "/" + kind.path());
    }

    /**
     * The size to open at: what the pack asks for, or more if more is stored.
     *
     * <p>A pack that shrinks a cabinet from six rows to three must not delete
     * the bottom three rows of every cabinet already full, which is what
     * opening at three and saving would do. So it opens big enough for what is
     * there, and shrinks to the new size once those slots are emptied.
     */
    static int sizeFor(int asked, ItemStack[] stored) {
        int last = -1;
        if (stored != null) {
            for (int i = 0; i < stored.length; i++) {
                if (stored[i] != null) {
                    last = i;
                }
            }
        }
        int needed = ((last + 1 + 8) / 9) * 9;
        return Math.min(54, Math.max(asked, needed));
    }

    private static ItemStack[] fit(ItemStack[] stored, int size) {
        ItemStack[] slots = new ItemStack[size];
        if (stored != null) {
            System.arraycopy(stored, 0, slots, 0, Math.min(size, stored.length));
        }
        return slots;
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            out[i] = contents[i] == null ? null : contents[i].clone();
        }
        return out;
    }

    private void sound(Location at, String key) {
        SoundAt.play(sounds, at, key, SoundCategory.BLOCKS, 1f, 1f);
    }

    private void quarantine(StorageHolder holder, IOException why) {
        String name = holder.key().replaceAll("[^A-Za-z0-9_.-]", "_").toLowerCase(Locale.ROOT);
        Path file = plugin.getDataFolder().toPath().resolve("unreadable-storage").resolve(name + ".bin");
        String message = "The contents of " + holder.key() + " could not be read (" + why.getMessage()
                + ") and what they were stored on is gone.";
        if (holder instanceof PdcStorage) {
            byte[] bytes = ((PdcStorage) holder).raw();
            if (bytes != null) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.write(file, bytes);
                    plugin.getLogger().severe(message + " The raw bytes are saved in " + file + ".");
                    return;
                } catch (IOException e) {
                    message += " Saving the raw bytes failed too: " + e.getMessage() + ".";
                }
            }
        }
        plugin.getLogger().severe(message);
    }

    private static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * One live container, and the holder that its inventory belongs to as far
     * as Bukkit is concerned — which is how a close event finds its way back
     * here without a lookup by inventory identity.
     */
    static final class Live implements InventoryHolder {

        final String key;
        /** Null for a bin, which keeps nothing. */
        final StorageHolder holder;
        final StorageSpec spec;
        final Location at;
        Inventory inventory;
        /** Set once its contents have been saved for the last time or handed back. */
        boolean finished;

        Live(String key, StorageHolder holder, StorageSpec spec, Location at) {
            this.key = key;
            this.holder = holder;
            this.spec = spec;
            this.at = at == null ? null : at.clone();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
