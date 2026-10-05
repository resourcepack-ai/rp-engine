package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.event.DialogSlotMoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
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
 * rearrangement, never one that makes anything.
 *
 * <h2>Why a player may run it</h2>
 *
 * The click is theirs, so the command has to be too; and it does only what the
 * click could do. {@code /rp slot} acts only when the dialog the engine last
 * showed that player holds a click running exactly that command, and only on the
 * player's own containers: their inventory and their ender chest. A player who
 * types it reaches the slots they were shown and nothing else.
 *
 * <h2>Keys</h2>
 *
 * A slot is named {@code <container>/<index>}: {@code inv/0}-{@code inv/35} the
 * player's inventory in Bukkit's numbering (0-8 the hotbar; their armour and
 * off hand are not offered), {@code ender/0}-{@code ender/26} their ender chest.
 * Studio writes the same keys into the dialog's markers and clicks.
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

    /** Weak on the player, so a session that ends takes its entry with it. */
    private final Map<Player, Held> held = Collections.synchronizedMap(new WeakHashMap<>());

    /** What one click did, for the command to answer with. */
    public enum Result {
        /** A stack was picked up: its slot is lit until the next click. */
        PICKED,
        /** The stack picked up was put down: moved, merged or swapped. */
        PUT,
        /** The stack picked up was put back where it was, or is gone. */
        RELEASED,
        /** Nothing to do: an empty slot with nothing picked up, or a move somebody refused. */
        NOTHING,
        /** Not a slot this player has. */
        NO_SUCH_SLOT
    }

    // ------------------------------------------------------------- containers

    /** The live container behind a name for this player, or null for one they do not have. */
    static Inventory container(Player player, String name) {
        if (player == null || name == null) {
            return null;
        }
        switch (name) {
            case INVENTORY:
                return player.getInventory();
            case ENDER_CHEST:
                return player.getEnderChest();
            default:
                return null;
        }
    }

    /** How many slots of a container a dialog may reach: the inventory's 36, any other one whole. */
    static int reach(String name, Inventory inventory) {
        return INVENTORY.equals(name) ? 36 : inventory.getSize();
    }

    /** What is in a slot now, as a copy, or null for empty — and null for a slot this player does not have. */
    static ItemStack get(Player player, Key key) {
        Inventory inventory = container(player, key.container());
        if (inventory == null || key.index() < 0 || key.index() >= reach(key.container(), inventory)) {
            return null;
        }
        ItemStack stack = inventory.getItem(key.index());
        return empty(stack) ? null : stack.clone();
    }

    /** Whether this player has such a slot at all. */
    static boolean exists(Player player, Key key) {
        Inventory inventory = container(player, key.container());
        return inventory != null && key.index() >= 0 && key.index() < reach(key.container(), inventory);
    }

    private static void set(Player player, Key key, ItemStack stack) {
        Inventory inventory = container(player, key.container());
        if (inventory != null) {
            inventory.setItem(key.index(), empty(stack) ? null : stack);
        }
    }

    static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** The most a slot holds of this item: the item's own limit and the container's, whichever is lower. */
    private static int most(Player player, Key key, ItemStack stack) {
        Inventory inventory = container(player, key.container());
        int item = Math.max(1, stack.getMaxStackSize());
        return inventory == null ? item : Math.max(1, Math.min(item, inventory.getMaxStackSize()));
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

    // ------------------------------------------------------------- the clicks

    /** The slot a player has picked up in this dialog, if any — what {@link DialogItems} lights. */
    public Optional<Key> held(Player player, ContentId dialog) {
        Held h = player == null ? null : held.get(player);
        return h != null && h.dialog().equals(dialog) ? Optional.of(h.key()) : Optional.empty();
    }

    /** A dialog was shown to a player: what they had picked up in another one is put back. */
    void shown(Player player, ContentId dialog) {
        if (player == null) {
            return;
        }
        Held h = held.get(player);
        if (h != null && !h.dialog().equals(dialog)) {
            held.remove(player);
        }
    }

    /** The player's dialog closed, or they left: nothing is picked up any more. */
    public void forget(Player player) {
        if (player != null) {
            held.remove(player);
        }
    }

    /**
     * A click on {@code key} in {@code dialog}: picks a stack up, or puts the one
     * picked up down there. The caller has checked the dialog holds that click.
     */
    public Result click(Player player, ContentId dialog, Key key) {
        if (player == null || dialog == null || key == null || !exists(player, key)) {
            return Result.NO_SUCH_SLOT;
        }
        Held before = held.get(player);
        Key from = before != null && before.dialog().equals(dialog) ? before.key() : null;
        ItemStack here = get(player, key);
        if (from == null) {
            if (here == null) {
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
        ItemStack moving = get(player, from);
        if (moving == null) {
            // What was picked up has gone since (eaten, dropped by a plugin,
            // moved by another screen): this click starts again from here.
            if (here != null) {
                held.put(player, new Held(dialog, key));
                return Result.PICKED;
            }
            return Result.RELEASED;
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
        Plan plan = plan(moving.getAmount(), here == null ? 0 : here.getAmount(),
                here != null && here.isSimilar(moving), most(player, key, moving));
        switch (plan.kind()) {
            case PLACE -> {
                set(player, key, moving);
                set(player, from, null);
            }
            case MERGE -> {
                here.setAmount(here.getAmount() + plan.amount());
                moving.setAmount(moving.getAmount() - plan.amount());
                set(player, key, here);
                set(player, from, moving.getAmount() > 0 ? moving : null);
            }
            case SWAP -> {
                set(player, key, moving);
                set(player, from, here);
            }
            case NONE -> {
                return Result.NOTHING;
            }
        }
        return Result.PUT;
    }

    /**
     * Whether a key is one a player's dialog may name in its markers: a container
     * this engine fills. Anything else is left as Studio wrote it, an empty slot.
     */
    static boolean known(String container) {
        return INVENTORY.equals(container) || ENDER_CHEST.equals(container);
    }
}
