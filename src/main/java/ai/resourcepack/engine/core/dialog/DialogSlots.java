package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogContainer;
import ai.resourcepack.engine.api.event.DialogSlotMoveEvent;
import ai.resourcepack.engine.core.storage.ItemBytes;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moving items in a Studio dialog: a player clicks a stack, then a slot, and
 * the stack goes there. Internal.
 *
 * <h2>What a dialog can do and what this does instead</h2>
 *
 * A dialog is not a container. It has no slots the game knows about, no cursor
 * stack, no drag and no shift-click; all the client can send the server is "this
 * click ran this command". So a grid of slots whose items MOVE is built out of
 * that: every slot's click runs {@code rp slot <key>} as the player, the first
 * click on a stack picks it up (its slot lights up, see {@link DialogItems}), the
 * second click puts it down, and the engine opens the dialog again with the items
 * where they now are. Studio sends such a dialog with {@code after_action: none},
 * so the old screen stays up until the new one replaces it — one round trip, and
 * no world showing in between.
 *
 * <h2>Why nothing can be duplicated</h2>
 *
 * Nothing is ever on a "cursor". A picked-up stack stays in its slot, marked,
 * until the second click moves it, and the move reads BOTH slots as they are at
 * that moment on the server — never as the picture the player was shown, which
 * may be out of date by any number of other changes. So every move is a
 * rearrangement of what is really there, done in one tick on the main thread;
 * a stale screen can only ask for a move that turns out to be a different (or no)
 * rearrangement, never one that makes anything. The containers that keep their
 * contents as bytes (a backpack, a player's storage) are read once per click and
 * written once, after both slots are decided.
 *
 * <h2>Why a player may run it</h2>
 *
 * The click is theirs, so the command has to be too; and it does only what the
 * click could do. {@code /rp slot} acts only when the dialog the engine last
 * showed that player holds a click running exactly that command, and only on
 * containers that are the player's own or that a plugin hands over for them. A
 * player who types it reaches the slots they were shown and nothing else.
 *
 * <h2>Containers</h2>
 *
 * A slot is named {@code <container>/<index>}. Studio writes the same keys into
 * the dialog's markers and clicks.
 *
 * <ul>
 *   <li>{@code inv} — the player's inventory, 0-35 in Bukkit's numbering (0-8
 *   the hotbar; armour and off hand are not offered);</li>
 *   <li>{@code ender} — their ender chest, 0-26;</li>
 *   <li>{@code item} — the item the dialog was opened FROM, by an item's
 *   {@code dialog:} action: a backpack. Its contents are kept on the item, the
 *   way a shulker box's are, and it is found again by a tag on it at every
 *   click, wherever it has been moved to in their inventory;</li>
 *   <li>{@code player.<name>} — storage kept on the player, every player their
 *   own, across restarts: a vault, a second ender chest;</li>
 *   <li>{@code api.<name>} — a plugin's {@link DialogContainer}.</li>
 * </ul>
 *
 * <h2>Two rules that are not the inventory's</h2>
 *
 * <ul>
 *   <li><b>No backpack goes in a backpack.</b> An item holding contents of its
 *   own (anything that has been opened as a backpack) cannot be put in an
 *   {@code item} container: one inside another is a stack of contents nobody
 *   can see, and the open one inside itself would be gone.</li>
 *   <li><b>The open backpack stays in the player's inventory.</b> Its contents
 *   are on it, and its grid is found by finding it there; moved into an ender
 *   chest it would leave a grid of nothing behind it.</li>
 * </ul>
 */
public final class DialogSlots {

    /** What a movable slot's click runs, then the slot's key. Studio's {@code SLOT_MOVE_COMMAND}. */
    public static final String COMMAND = "rp slot";

    /** A slot's key as Studio writes it. */
    static final Pattern KEY = Pattern.compile("([a-z0-9_.-]{1,32})/([0-9]{1,3})");

    /** The player's inventory: the 36 slots of the inventory screen, hotbar first. */
    public static final String INVENTORY = "inv";

    /** The player's ender chest. */
    public static final String ENDER_CHEST = "ender";

    /** The item the dialog was opened from: a backpack. */
    public static final String ITEM = "item";

    /** Storage kept on the player, by name: {@code player.vault}. */
    public static final String PLAYER = "player.";

    /** A plugin's container, by the name it registered: {@code api.bank}. */
    public static final String API = "api.";

    /** A name after {@code player.} or {@code api.}. */
    static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,24}");

    /** The most slots a container shows: Studio's biggest grid, 13 × 8. */
    public static final int MOST_SLOTS = 104;

    /** A slot of one of a player's containers. */
    public record Key(String container, int index) {

        /**
         * A key as written, or empty. A bare number is a slot of the player's
         * inventory, which is how the first build of this feature wrote one.
         */
        public static Optional<Key> parse(String text) {
            if (text == null) {
                return Optional.empty();
            }
            String t = text.trim();
            if (t.matches("[0-9]{1,3}")) {
                return Optional.of(new Key(INVENTORY, Integer.parseInt(t)));
            }
            Matcher m = KEY.matcher(t);
            return m.matches() ? Optional.of(new Key(m.group(1), Integer.parseInt(m.group(2)))) : Optional.empty();
        }

        @Override
        public String toString() {
            return container + "/" + index;
        }
    }

    /** What a player has picked up, and in which dialog: a different dialog drops it. */
    private record Held(ContentId dialog, Key key) {
    }

    /** The item a player opened a dialog from, by the tag on it, and the dialog. */
    private record Opened(ContentId dialog, String item) {
    }

    /** What one click did, for the command to answer with. */
    public enum Result {
        /** A stack was picked up: its slot is lit until the next click. */
        PICKED,
        /** The stack picked up was put down: moved, merged or swapped. */
        PUT,
        /** The stack picked up was put back where it was, or is gone. */
        RELEASED,
        /** Nothing to do: an empty slot with nothing picked up, or a move refused. */
        NOTHING,
        /** Not a slot this player has. */
        NO_SUCH_SLOT
    }

    private final Plugin plugin;
    /** The tag that names a backpack: a random id, written the first time it is opened. */
    private final NamespacedKey itemTag;
    /** The key a backpack's contents are kept under, on the item. */
    private final NamespacedKey contentsKey;

    /** Weak on the player, so a session that ends takes its entries with it. */
    private final Map<Player, Held> held = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Player, Opened> opened = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, DialogContainer> containers = new ConcurrentHashMap<>();

    /** @param plugin the engine, for the keys a backpack and a player's storage are kept under. Null in a test. */
    public DialogSlots(Plugin plugin) {
        this.plugin = plugin;
        this.itemTag = plugin == null ? null : new NamespacedKey(plugin, "dialog-item");
        this.contentsKey = plugin == null ? null : new NamespacedKey(plugin, "dialog-contents");
    }

    /** Whether a container's name is one this engine fills — its shape, not whether this player has one. */
    static boolean known(String container) {
        if (container == null) {
            return false;
        }
        if (INVENTORY.equals(container) || ENDER_CHEST.equals(container) || ITEM.equals(container)) {
            return true;
        }
        String rest = container.startsWith(PLAYER) ? container.substring(PLAYER.length())
                : container.startsWith(API) ? container.substring(API.length()) : null;
        return rest != null && NAME.matcher(rest).matches();
    }

    /** Registers a plugin's container under {@code api.<name>}; null takes it away. */
    public boolean register(String name, DialogContainer container) {
        if (name == null || !NAME.matcher(name).matches()) {
            return false;
        }
        if (container == null) {
            containers.remove(name);
        } else {
            containers.put(name, container);
        }
        return true;
    }

    // ------------------------------------------------------------- the boxes

    /**
     * One of a player's containers, opened for one click or one fill: read
     * once, changed slot by slot, written back once by {@link #commit}.
     */
    interface Box {
        /** How many slots a dialog may reach. */
        int size();

        /** What is in a slot, as a copy, or null for empty. */
        ItemStack get(int slot);

        /** Puts a stack in a slot; null empties it. */
        void set(int slot, ItemStack stack);

        /** The most a slot holds of this item. */
        default int most(ItemStack stack) {
            return Math.max(1, Math.min(stack.getMaxStackSize(), 99));
        }

        default boolean mayTake(int slot, ItemStack stack) {
            return true;
        }

        default boolean mayPlace(int slot, ItemStack stack) {
            return true;
        }

        /** Keeps what was changed. */
        default void commit() {
        }
    }

    /** A live inventory, written as it is changed: the player's own, their ender chest. */
    private static final class LiveBox implements Box {
        private final Inventory inventory;
        private final int size;

        LiveBox(Inventory inventory, int size) {
            this.inventory = inventory;
            this.size = size;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public ItemStack get(int slot) {
            ItemStack stack = slot >= 0 && slot < size ? inventory.getItem(slot) : null;
            return empty(stack) ? null : stack.clone();
        }

        @Override
        public void set(int slot, ItemStack stack) {
            inventory.setItem(slot, empty(stack) ? null : stack);
        }

        @Override
        public int most(ItemStack stack) {
            return Math.max(1, Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize()));
        }
    }

    /** Contents kept as bytes: read on opening, written on commit. */
    private abstract static class BytesBox implements Box {
        ItemStack[] contents;
        boolean dirty;

        BytesBox(ItemStack[] contents) {
            this.contents = contents == null ? new ItemStack[0] : contents;
        }

        @Override
        public int size() {
            return MOST_SLOTS;
        }

        @Override
        public ItemStack get(int slot) {
            ItemStack stack = slot >= 0 && slot < contents.length ? contents[slot] : null;
            return empty(stack) ? null : stack.clone();
        }

        @Override
        public void set(int slot, ItemStack stack) {
            if (slot >= contents.length) {
                contents = Arrays.copyOf(contents, slot + 1);
            }
            contents[slot] = empty(stack) ? null : stack;
            dirty = true;
        }

        /** The contents without the empty slots at the end, which cost nothing to leave off. */
        ItemStack[] trimmed() {
            int end = contents.length;
            while (end > 0 && empty(contents[end - 1])) {
                end--;
            }
            return Arrays.copyOf(contents, end);
        }
    }

    /** Storage kept on the player under a name. */
    private final class PlayerBox extends BytesBox {
        private final PersistentDataContainer data;
        private final NamespacedKey key;

        PlayerBox(PersistentDataContainer data, NamespacedKey key, ItemStack[] contents) {
            super(contents);
            this.data = data;
            this.key = key;
        }

        @Override
        public void commit() {
            if (!dirty) {
                return;
            }
            ItemStack[] keep = trimmed();
            if (!ItemBytes.anything(keep)) {
                data.remove(key);
                return;
            }
            try {
                data.set(key, PersistentDataType.BYTE_ARRAY, ItemBytes.write(keep));
            } catch (IOException | RuntimeException e) {
                // Loud: the alternative is silence about somebody's items. What
                // was kept before stays kept.
                plugin.getLogger().severe("Could not keep a dialog's storage (" + key + "): " + e.getMessage());
            }
        }
    }

    /** The contents of the item a dialog was opened from, kept on the item. */
    private final class ItemBox extends BytesBox {
        private final PlayerInventory inventory;
        private final String tag;

        ItemBox(PlayerInventory inventory, String tag, ItemStack[] contents) {
            super(contents);
            this.inventory = inventory;
            this.tag = tag;
        }

        @Override
        public boolean mayPlace(int slot, ItemStack stack) {
            // No backpack in a backpack: see the class note.
            return !holdsContents(stack);
        }

        @Override
        public void commit() {
            if (!dirty) {
                return;
            }
            // Found again now, after every other change of this click: the
            // inventory may have moved it.
            int at = find(inventory, tag);
            if (at < 0) {
                return;
            }
            ItemStack stack = inventory.getItem(at);
            ItemMeta meta = stack == null ? null : stack.getItemMeta();
            if (meta == null) {
                return;
            }
            ItemStack[] keep = trimmed();
            try {
                if (ItemBytes.anything(keep)) {
                    meta.getPersistentDataContainer().set(contentsKey, PersistentDataType.BYTE_ARRAY, ItemBytes.write(keep));
                } else {
                    meta.getPersistentDataContainer().remove(contentsKey);
                }
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().severe("Could not keep what is in a backpack: " + e.getMessage());
                return;
            }
            stack.setItemMeta(meta);
            inventory.setItem(at, stack);
        }
    }

    /** A plugin's container. */
    private static final class ApiBox implements Box {
        private final Player viewer;
        private final DialogContainer container;
        private final Inventory inventory;
        private boolean dirty;

        ApiBox(Player viewer, DialogContainer container, Inventory inventory) {
            this.viewer = viewer;
            this.container = container;
            this.inventory = inventory;
        }

        @Override
        public int size() {
            return Math.min(MOST_SLOTS, inventory.getSize());
        }

        @Override
        public ItemStack get(int slot) {
            ItemStack stack = slot >= 0 && slot < size() ? inventory.getItem(slot) : null;
            return empty(stack) ? null : stack.clone();
        }

        @Override
        public void set(int slot, ItemStack stack) {
            inventory.setItem(slot, empty(stack) ? null : stack);
            dirty = true;
        }

        @Override
        public int most(ItemStack stack) {
            return Math.max(1, Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize()));
        }

        @Override
        public boolean mayTake(int slot, ItemStack stack) {
            return container.mayTake(viewer, slot, stack.clone());
        }

        @Override
        public boolean mayPlace(int slot, ItemStack stack) {
            return container.mayPlace(viewer, slot, stack.clone());
        }

        @Override
        public void commit() {
            if (dirty) {
                container.changed(viewer);
            }
        }
    }

    /**
     * A container of this player's, opened, or null for one they do not have:
     * a name nothing answers, a backpack that is not in their inventory any
     * more, contents that cannot be read (never opened as empty — see
     * {@link ItemBytes}).
     */
    Box open(Player player, String name) {
        if (player == null || name == null) {
            return null;
        }
        if (INVENTORY.equals(name)) {
            return new LiveBox(player.getInventory(), 36);
        }
        if (ENDER_CHEST.equals(name)) {
            Inventory ender = player.getEnderChest();
            return new LiveBox(ender, ender.getSize());
        }
        if (name.startsWith(API)) {
            DialogContainer container = containers.get(name.substring(API.length()));
            Inventory inventory = null;
            if (container != null) {
                try {
                    inventory = container.inventory(player);
                } catch (RuntimeException e) {
                    if (plugin != null) {
                        plugin.getLogger().warning("The dialog container " + name + " failed: " + e);
                    }
                }
            }
            return inventory == null ? null : new ApiBox(player, container, inventory);
        }
        if (plugin == null) {
            return null;
        }
        try {
            if (name.startsWith(PLAYER) && NAME.matcher(name.substring(PLAYER.length())).matches()) {
                NamespacedKey key = new NamespacedKey(plugin, "dialog-storage/" + name.substring(PLAYER.length()));
                PersistentDataContainer data = player.getPersistentDataContainer();
                return new PlayerBox(data, key, ItemBytes.read(data.get(key, PersistentDataType.BYTE_ARRAY)));
            }
            if (ITEM.equals(name)) {
                Opened from = opened.get(player);
                int at = from == null ? -1 : find(player.getInventory(), from.item());
                if (at < 0) {
                    return null;
                }
                ItemStack stack = player.getInventory().getItem(at);
                ItemMeta meta = stack == null ? null : stack.getItemMeta();
                byte[] bytes = meta == null ? null : meta.getPersistentDataContainer().get(contentsKey, PersistentDataType.BYTE_ARRAY);
                return new ItemBox(player.getInventory(), from.item(), ItemBytes.read(bytes));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read what is in " + name + " for " + player.getName() + ": "
                    + e.getMessage() + " It is left as it is.");
        }
        return null;
    }

    /** The boxes of one click or one fill, each opened once. */
    private final class Boxes {
        private final Player player;
        private final Map<String, Optional<Box>> open = new HashMap<>();

        Boxes(Player player) {
            this.player = player;
        }

        Box get(String name) {
            return open.computeIfAbsent(name, n -> Optional.ofNullable(DialogSlots.this.open(player, n))).orElse(null);
        }

        void commit() {
            for (Optional<Box> box : open.values()) {
                box.ifPresent(Box::commit);
            }
        }
    }

    /**
     * What a dialog's slots hold for this player, as a reader that opens each
     * container once — what {@link DialogItems#fill} asks as the dialog opens.
     */
    public Function<Key, ItemStack> reader(Player player) {
        Boxes boxes = new Boxes(player);
        return key -> {
            Box box = key == null ? null : boxes.get(key.container());
            return box == null || key.index() < 0 || key.index() >= box.size() ? null : box.get(key.index());
        };
    }

    // --------------------------------------------------------------- the rule

    /** What putting a stack down does. */
    public enum Kind {
        /** Onto an empty slot: the whole stack goes there. */
        PLACE,
        /** Onto the same item: as much as fits joins it, the rest stays where it was. */
        MERGE,
        /** Onto a different item: the two change places. */
        SWAP,
        /** Onto the same item, already full: nothing moves. */
        NONE
    }

    /** A move, worked out: what kind, and for a merge how many. */
    public record Plan(Kind kind, int amount) {
    }

    /**
     * What putting {@code moving} items down on a slot does — the inventory's
     * own rule for a left click with a stack on the cursor, written without
     * any server so it can be tested.
     *
     * @param ontoCount how many are in the slot it goes on; 0 for an empty one
     * @param similar   whether the slot holds the same item, components and all
     * @param ontoMost  the most that slot holds of that item
     */
    public static Plan plan(int moving, int ontoCount, boolean similar, int ontoMost) {
        if (ontoCount <= 0) {
            return new Plan(Kind.PLACE, moving);
        }
        if (!similar) {
            return new Plan(Kind.SWAP, moving);
        }
        int room = Math.max(0, ontoMost - ontoCount);
        int amount = Math.min(room, moving);
        return amount > 0 ? new Plan(Kind.MERGE, amount) : new Plan(Kind.NONE, 0);
    }

    // ---------------------------------------------------------- the backpack

    /** The slot of the player's inventory (off hand and armour included) holding the item with this tag; -1 for none. */
    private int find(PlayerInventory inventory, String tag) {
        if (tag == null || itemTag == null) {
            return -1;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (tag.equals(tagOf(inventory.getItem(slot)))) {
                return slot;
            }
        }
        return -1;
    }

    private String tagOf(ItemStack stack) {
        if (empty(stack) || itemTag == null) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(itemTag, PersistentDataType.STRING);
    }

    /** Whether a stack has contents of its own: anything that has been opened as a backpack. */
    boolean holdsContents(ItemStack stack) {
        if (empty(stack) || itemTag == null) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        return data.has(itemTag, PersistentDataType.STRING) || data.has(contentsKey, PersistentDataType.BYTE_ARRAY);
    }

    /** What opening a dialog from an item came to. */
    public enum Opening {
        /** The dialog does not show the item's contents: nothing to do. */
        NOT_A_BACKPACK,
        /** Bound: its grid shows what is in the item. */
        BOUND,
        /** More than one in the stack: one at a time, or the contents would be copied with it. */
        STACKED,
        /** The stack is not in the player's hands to tag. */
        NOT_HELD
    }

    /**
     * A dialog is about to open from the item in a player's hand: when the
     * dialog shows that item's contents ({@code item/...} slots), the item is
     * tagged — once, with a random id — and remembered as the one it shows.
     */
    public Opening openFrom(Player player, ContentId dialog, String json, ItemStack used) {
        if (json == null || !(json.contains("rp:item:" + ITEM + "/") || json.contains("rp:slot:" + ITEM + "/"))) {
            return Opening.NOT_A_BACKPACK;
        }
        if (player == null || itemTag == null || empty(used)) {
            return Opening.NOT_HELD;
        }
        PlayerInventory inventory = player.getInventory();
        int at = -1;
        for (int slot : new int[] {inventory.getHeldItemSlot(), 40}) {
            ItemStack hand = inventory.getItem(slot);
            if (!empty(hand) && hand.isSimilar(used)) {
                at = slot;
                break;
            }
        }
        if (at < 0) {
            return Opening.NOT_HELD;
        }
        ItemStack stack = inventory.getItem(at);
        if (stack.getAmount() > 1) {
            // Tagging a stack of five tags five backpacks with one set of
            // contents, which splitting the stack would then copy.
            return Opening.STACKED;
        }
        String tag = tagOf(stack);
        if (tag == null) {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return Opening.NOT_HELD;
            }
            tag = UUID.randomUUID().toString();
            meta.getPersistentDataContainer().set(itemTag, PersistentDataType.STRING, tag);
            stack.setItemMeta(meta);
            inventory.setItem(at, stack);
        }
        opened.put(player, new Opened(dialog, tag));
        return Opening.BOUND;
    }

    // ------------------------------------------------------------- the clicks

    /** The slot a player has picked up in this dialog, if any — what {@link DialogItems} lights. */
    public Optional<Key> held(Player player, ContentId dialog) {
        Held h = player == null ? null : held.get(player);
        return h != null && h.dialog().equals(dialog) ? Optional.of(h.key()) : Optional.empty();
    }

    /**
     * A dialog was shown to a player. What they had picked up in another one is
     * put back. The item it was opened from stays its item when the dialog was
     * reached from that one — a page turned, a move made — and is forgotten when
     * some other dialog was opened afresh.
     */
    void shown(Player player, ContentId dialog, boolean followed) {
        if (player == null) {
            return;
        }
        Held h = held.get(player);
        if (h != null && !h.dialog().equals(dialog)) {
            held.remove(player);
        }
        Opened from = opened.get(player);
        if (from != null && !from.dialog().equals(dialog)) {
            if (followed) {
                opened.put(player, new Opened(dialog, from.item()));
            } else {
                opened.remove(player);
            }
        }
    }

    /** The player's dialog closed, or they left: nothing is picked up, and no item is open. */
    public void forget(Player player) {
        if (player != null) {
            held.remove(player);
            opened.remove(player);
        }
    }

    /**
     * A click on {@code key} in {@code dialog}: picks a stack up, or puts the one
     * picked up down there. The caller has checked the dialog holds that click.
     */
    public Result click(Player player, ContentId dialog, Key key) {
        if (player == null || dialog == null || key == null) {
            return Result.NO_SUCH_SLOT;
        }
        Boxes boxes = new Boxes(player);
        Box onto = boxes.get(key.container());
        if (onto == null || key.index() < 0 || key.index() >= onto.size()) {
            return Result.NO_SUCH_SLOT;
        }
        Held before = held.get(player);
        Key from = before != null && before.dialog().equals(dialog) ? before.key() : null;
        ItemStack here = onto.get(key.index());
        if (from == null) {
            if (here == null || !onto.mayTake(key.index(), here)) {
                held.remove(player);
                return Result.NOTHING;
            }
            held.put(player, new Held(dialog, key));
            return Result.PICKED;
        }
        held.remove(player);
        if (from.equals(key)) {
            return Result.RELEASED;
        }
        Box source = boxes.get(from.container());
        ItemStack moving = source == null || from.index() >= source.size() ? null : source.get(from.index());
        if (moving == null) {
            // What was picked up has gone since (eaten, dropped by a plugin,
            // moved by another screen): this click starts again from here.
            if (here != null && onto.mayTake(key.index(), here)) {
                held.put(player, new Held(dialog, key));
                return Result.PICKED;
            }
            return Result.RELEASED;
        }
        Plan plan = plan(moving.getAmount(), here == null ? 0 : here.getAmount(),
                here != null && here.isSimilar(moving), onto.most(moving));
        if (plan.kind() == Kind.NONE || !allowed(player, plan, source, from, moving, onto, key, here)) {
            return Result.NOTHING;
        }
        DialogSlotMoveEvent event = new DialogSlotMoveEvent(player, dialog, from.toString(), key.toString(),
                moving.clone(), here == null ? null : here.clone());
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException | LinkageError e) {
            // No server to ask (a test): nobody objects.
        }
        if (event.isCancelled()) {
            return Result.NOTHING;
        }
        switch (plan.kind()) {
            case PLACE -> {
                onto.set(key.index(), moving);
                source.set(from.index(), null);
            }
            case MERGE -> {
                here.setAmount(here.getAmount() + plan.amount());
                moving.setAmount(moving.getAmount() - plan.amount());
                onto.set(key.index(), here);
                source.set(from.index(), moving.getAmount() > 0 ? moving : null);
            }
            case SWAP -> {
                onto.set(key.index(), moving);
                source.set(from.index(), here);
            }
            default -> {
                return Result.NOTHING;
            }
        }
        boxes.commit();
        return Result.PUT;
    }

    /** Whether the containers on both ends let the move happen, and the open backpack stays at home. */
    private boolean allowed(Player player, Plan plan, Box source, Key from, ItemStack moving, Box onto, Key to, ItemStack here) {
        if (!source.mayTake(from.index(), moving) || !onto.mayPlace(to.index(), moving)) {
            return false;
        }
        if (plan.kind() == Kind.SWAP && (!onto.mayTake(to.index(), here) || !source.mayPlace(from.index(), here))) {
            return false;
        }
        Opened open = opened.get(player);
        if (open != null) {
            if (!INVENTORY.equals(to.container()) && open.item().equals(tagOf(moving))) {
                return false;
            }
            if (plan.kind() == Kind.SWAP && !INVENTORY.equals(from.container()) && open.item().equals(tagOf(here))) {
                return false;
            }
        }
        return true;
    }

    static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }
}
