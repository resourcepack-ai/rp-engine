package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.core.command.EngineCommand;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /rp item menu}: every custom item in a chest, to take from.
 *
 * <p>The menu the other item plugins have, and deliberately as plain as
 * theirs: a six-row chest, forty-five items a page, and a bottom row of
 * arrows. With more than one namespace loaded it opens on the namespaces
 * first, each drawn as its first item, because a server with three packs and
 * four hundred items is nine pages of somebody else's swords before your own.
 *
 * <p>A click takes one, a shift-click a stack. Nothing in the chest can be
 * moved, and nothing can be put in it: every click on the top is cancelled,
 * and so are the two clicks on the bottom that reach into the top (shift and
 * double-click).
 *
 * <p>Nothing here touches {@code InventoryView}, for the reason given in
 * {@code RecipeStations}: it became an interface in 1.21, and a call compiled
 * against it fails on an older server. Every lookup goes through the event.
 */
public final class ItemMenu implements Listener {

    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;

    private static final int BACK = 45;
    private static final int PREVIOUS = 48;
    private static final int CLOSE = 49;
    private static final int NEXT = 50;

    private final Plugin plugin;
    private final Items items;

    public ItemMenu(Plugin plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
    }

    /** Opens the menu where it should start: the namespaces, or the one there is. */
    public void open(Player player) {
        Map<String, List<ContentId>> spaces = namespaces();
        if (spaces.size() == 1) {
            player.openInventory(itemsPage(spaces.keySet().iterator().next(), 0, false));
        } else {
            player.openInventory(namespacesPage(0));
        }
    }

    /** Every item id by namespace, in the order {@link Items#ids()} gives them. */
    private Map<String, List<ContentId>> namespaces() {
        Map<String, List<ContentId>> spaces = new LinkedHashMap<>();
        for (ContentId id : items.ids()) {
            spaces.computeIfAbsent(id.namespace(), any -> new ArrayList<>()).add(id);
        }
        return spaces;
    }

    /** What one open chest is showing, so a click can be read back. */
    private static final class Page implements InventoryHolder {
        /** The namespace whose items these are, or null for the namespace list. */
        final String namespace;
        final int page;
        final int pages;
        /** Whether there is a namespace list to go back to. */
        final boolean back;
        /** Slot to what is in it: an item id, or a namespace. */
        final Map<Integer, Object> slots = new LinkedHashMap<>();
        Inventory inventory;

        Page(String namespace, int page, int pages, boolean back) {
            this.namespace = namespace;
            this.page = page;
            this.pages = pages;
            this.back = back;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private Inventory namespacesPage(int page) {
        Map<String, List<ContentId>> spaces = namespaces();
        List<String> names = new ArrayList<>(spaces.keySet());
        int pages = pageCount(names.size());
        page = Math.max(0, Math.min(page, pages - 1));
        Page holder = new Page(null, page, pages, false);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title("Items", page, pages));
        holder.inventory = inventory;

        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < names.size(); i++) {
            String name = names.get(from + i);
            List<ContentId> ids = spaces.get(name);
            ItemStack icon = items.create(ids.get(0)).orElseGet(() -> new ItemStack(Material.CHEST));
            label(icon, ChatColor.WHITE + name, ChatColor.GRAY + (ids.size() == 1 ? "1 item" : ids.size() + " items"));
            inventory.setItem(i, icon);
            holder.slots.put(i, name);
        }
        navigation(holder);
        return inventory;
    }

    private Inventory itemsPage(String namespace, int page, boolean back) {
        List<ContentId> ids = namespaces().getOrDefault(namespace, List.of());
        int pages = pageCount(ids.size());
        page = Math.max(0, Math.min(page, pages - 1));
        Page holder = new Page(namespace, page, pages, back);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title(namespace, page, pages));
        holder.inventory = inventory;

        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < ids.size(); i++) {
            ContentId id = ids.get(from + i);
            Optional<ItemStack> stack = items.create(id);
            if (stack.isPresent()) {
                inventory.setItem(i, stack.get());
                holder.slots.put(i, id);
            }
        }
        navigation(holder);
        return inventory;
    }

    private static int pageCount(int entries) {
        return Math.max(1, (entries + PER_PAGE - 1) / PER_PAGE);
    }

    private static String title(String name, int page, int pages) {
        return pages > 1 ? name + " (" + (page + 1) + "/" + pages + ")" : name;
    }

    /** The bottom row: glass, with arrows only where they lead somewhere. */
    private static void navigation(Page holder) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = PER_PAGE; slot < SIZE; slot++) {
            holder.inventory.setItem(slot, filler);
        }
        if (holder.back) {
            holder.inventory.setItem(BACK, button(Material.ARROW, ChatColor.WHITE + "Back"));
        }
        if (holder.page > 0) {
            holder.inventory.setItem(PREVIOUS, button(Material.ARROW, ChatColor.WHITE + "Previous page"));
        }
        holder.inventory.setItem(CLOSE, button(Material.BARRIER, ChatColor.WHITE + "Close"));
        if (holder.page < holder.pages - 1) {
            holder.inventory.setItem(NEXT, button(Material.ARROW, ChatColor.WHITE + "Next page"));
        }
    }

    private static ItemStack button(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        label(stack, name);
        return stack;
    }

    private static void label(ItemStack stack, String name, String... lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName(name);
        meta.setLore(lore.length == 0 ? null : List.of(lore));
        stack.setItemMeta(meta);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Page page)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked != null && clicked != event.getInventory()) {
            // Their own inventory is theirs to rearrange, except for the two
            // clicks that would put something into the menu or take from it.
            if (event.isShiftClick() || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (clicked == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getSlot();
        if (slot >= PER_PAGE) {
            switch (slot) {
                case BACK -> {
                    if (page.back) {
                        reopen(player, namespacesPage(0));
                    }
                }
                case PREVIOUS -> {
                    if (page.page > 0) {
                        reopen(player, sameKind(page, page.page - 1));
                    }
                }
                case NEXT -> {
                    if (page.page < page.pages - 1) {
                        reopen(player, sameKind(page, page.page + 1));
                    }
                }
                case CLOSE -> Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
                default -> {
                }
            }
            return;
        }

        Object what = page.slots.get(slot);
        if (what instanceof String namespace) {
            reopen(player, itemsPage(namespace, 0, true));
        } else if (what instanceof ContentId id) {
            take(player, id, event.isShiftClick());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Page)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private Inventory sameKind(Page page, int to) {
        return page.namespace == null ? namespacesPage(to) : itemsPage(page.namespace, to, page.back);
    }

    /**
     * Opens the next chest a tick later. Opening one from inside a click on
     * another is the thing Bukkit's own javadoc says not to do: the click is
     * still being processed against the chest being replaced.
     */
    private void reopen(Player player, Inventory next) {
        Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(next));
    }

    private void take(Player player, ContentId id, boolean stack) {
        // The permission is checked again here, not only when the menu opened:
        // a menu left open outlives a permission taken away.
        if (!player.hasPermission(EngineCommand.permissionFor("item"))) {
            Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
            return;
        }
        Optional<ItemStack> made = items.create(id);
        if (made.isEmpty()) {
            // Reloaded away while the menu was open.
            return;
        }
        ItemStack item = made.get();
        item.setAmount(stack ? item.getMaxStackSize() : 1);
        int asked = item.getAmount();
        int left = player.getInventory().addItem(item).values().stream().mapToInt(ItemStack::getAmount).sum();
        if (left == asked) {
            EngineCommand.say(player, "Your inventory is full.");
        }
    }
}
