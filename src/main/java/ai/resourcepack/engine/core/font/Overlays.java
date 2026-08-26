package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Opens screens and draws HUD overlays. Internal.
 *
 * <p>Both are one line of text with a picture in it. What differs is where the
 * game happens to render that line.
 */
public final class Overlays {

    private volatile Map<ContentId, OverlayInfo> screens = Map.of();
    private volatile Map<ContentId, OverlayInfo> huds = Map.of();
    private final Map<UUID, BossBar> bars = new HashMap<>();

    /** Replaces both catalogues, as a reload does. */
    public void replace(Map<ContentId, OverlayInfo> loadedScreens, Map<ContentId, OverlayInfo> loadedHuds) {
        this.screens = loadedScreens == null ? Map.of() : Map.copyOf(loadedScreens);
        this.huds = loadedHuds == null ? Map.of() : Map.copyOf(loadedHuds);
    }

    /** Every screen id, sorted. */
    public Collection<ContentId> screenIds() {
        return sorted(screens);
    }

    /** Every HUD id, sorted. */
    public Collection<ContentId> hudIds() {
        return sorted(huds);
    }

    private static Collection<ContentId> sorted(Map<ContentId, OverlayInfo> from) {
        List<ContentId> ids = new ArrayList<>(from.keySet());
        ids.sort(ContentId::compareTo);
        return List.copyOf(ids);
    }

    /** What the pack said a screen is. */
    public Optional<OverlayInfo> screen(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(screens.get(id));
    }

    /** What the pack said a HUD overlay is. */
    public Optional<OverlayInfo> hud(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(huds.get(id));
    }

    /**
     * Opens a screen for a player. Main thread only.
     *
     * @return the inventory, so a caller can fill its slots, or empty if there
     *         is no such screen
     */
    public Optional<Inventory> open(Player viewer, ContentId id) {
        Optional<OverlayInfo> found = screen(id);
        if (viewer == null || !viewer.isOnline() || found.isEmpty()) {
            return Optional.empty();
        }
        OverlayInfo info = found.get();
        Inventory inventory = create(info, title(info));
        if (inventory == null) {
            return Optional.empty();
        }
        viewer.openInventory(inventory);
        return Optional.of(inventory);
    }

    /** Draws a HUD overlay. Main thread only. */
    public boolean draw(Player viewer, ContentId id) {
        Optional<OverlayInfo> found = hud(id);
        if (viewer == null || !viewer.isOnline() || found.isEmpty()) {
            return false;
        }
        OverlayInfo info = found.get();
        String drawn = title(info);
        if (info.slot() == OverlayInfo.Slot.BOSS_BAR) {
            BossBar bar = bars.computeIfAbsent(viewer.getUniqueId(),
                    key -> Bukkit.createBossBar(drawn, BarColor.WHITE, BarStyle.SOLID));
            bar.setTitle(drawn);
            // Invisible bar, visible art: the bar itself is a rendering
            // surface here rather than a meter.
            bar.setProgress(0d);
            bar.addPlayer(viewer);
            bar.setVisible(true);
        } else {
            viewer.sendMessage(drawn);
        }
        return true;
    }

    /** Clears whatever this engine is drawing for a player. */
    public void clear(Player viewer) {
        if (viewer == null) {
            return;
        }
        BossBar bar = bars.remove(viewer.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    /**
     * The line of text a picture travels in.
     *
     * <p>White, or the client tints the art with whatever colour the
     * surrounding text is drawn in and it comes out muddy. Then the negative
     * space, then the glyph.
     */
    private static String title(OverlayInfo info) {
        if (!info.title().isEmpty()) {
            // A pushed pack did its own arithmetic with its own codepoints.
            return ChatColor.WHITE + info.title();
        }
        return ChatColor.WHITE + shift(info.offset()) + info.character();
    }

    /**
     * Negative space, in pixels, as a string of space glyphs.
     *
     * <p>Built out of powers of two — see {@link FontAssets} — so any shift is
     * at most nine characters rather than one per pixel.
     */
    static String shift(int pixels) {
        if (pixels <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int left = Math.min(pixels, (1 << FontAssets.SPACE_STEPS) - 1);
        for (int step = FontAssets.SPACE_STEPS - 1; step >= 0; step--) {
            int size = 1 << step;
            while (left >= size) {
                out.append(Character.toChars(FontAssets.FIRST_SPACE_CODEPOINT + step));
                left -= size;
            }
        }
        return out.toString();
    }

    /**
     * Containers whose name in a pack is not their name in Bukkit.
     *
     * <p>Each is a real disagreement rather than an oversight: {@code crafting}
     * is the workbench, which Bukkit still calls {@code WORKBENCH} long after
     * the block stopped being called that, and the other three are the game's
     * own words against Bukkit's longer ones. {@code chest_54} is not here
     * because it is a size rather than a type — see below.
     *
     * <p>Names rather than constants: {@link InventoryType} is registry-backed
     * now, so naming one in a static field asks Bukkit a question at class-load
     * time and gets null without a server — which is a test suite that cannot
     * run for a map it never reads.
     */
    private static final Map<String, String> ALIASES = Map.of(
            "crafting", "WORKBENCH",
            "enchanting", "ENCHANTING",
            "brewing", "BREWING",
            "cartography", "CARTOGRAPHY");

    /**
     * The container a screen opens as.
     *
     * <p>Chest rows are a size rather than a type, which is why they are split
     * out: {@code createInventory(null, 54, title)} is a six-row chest and
     * there is no {@code InventoryType} that says so. {@code chest_54} is the
     * same thing under the name a pushed Studio pack uses for it.
     */
    private static Inventory create(OverlayInfo info, String title) {
        String container = info.container().toLowerCase(Locale.ROOT);
        if (container.startsWith("chest_9x")) {
            int rows = container.charAt(container.length() - 1) - '0';
            return Bukkit.createInventory(null, rows * 9, title);
        }
        if (container.equals("chest_54")) {
            return Bukkit.createInventory(null, 54, title);
        }
        String named = ALIASES.getOrDefault(container, container.toUpperCase(Locale.ROOT));
        try {
            InventoryType type = InventoryType.valueOf(named);
            return Bukkit.createInventory(null, type, title);
        } catch (IllegalArgumentException e) {
            // Our own screens are validated at load, so this is either a
            // container Bukkit spells differently from the game — add it above
            // — or one a pushed pack named that this server is too old for.
            // Better empty than a stack trace.
            return null;
        }
    }
}
