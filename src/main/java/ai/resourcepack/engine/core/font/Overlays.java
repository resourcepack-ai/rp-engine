package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
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
        if (info.slot() == OverlayInfo.Slot.BOSS_BAR) {
            // **A boss bar cannot carry a font.** Bukkit's BossBar takes a
            // legacy String, and legacy formatting has codes for colour but
            // none for a font — so an overlay whose glyph lives in a font of
            // its own (every shader object does) cannot be drawn here at all.
            // Studio therefore sends those as ACTION_BAR; this branch stays for
            // the ordinary pushed and hand-authored overlays, which live in the
            // default font and only ever needed a colour.
            BossBar bar = bars.computeIfAbsent(viewer.getUniqueId(),
                    key -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
            bar.setTitle(legacy(info));
            // Invisible bar, visible art: the bar itself is a rendering
            // surface here rather than a meter.
            bar.setProgress(0d);
            bar.addPlayer(viewer);
            bar.setVisible(true);
        } else {
            // ACTION_BAR, and it really is the action bar.
            //
            // **This used to be `viewer.sendMessage`, which is CHAT** — so an
            // overlay declared `action_bar` drew a full-screen picture into
            // somebody's chat log, and the javadoc on the enum promising it is
            // "redrawn while shown" had nothing redrawing it. Same call
            // `ActionRunner` and `VehicleRuntime.overhead` already use; Paper's
            // `Player#sendActionBar` is not available on the Spigot this
            // compiles against.
            viewer.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, component(info));
        }
        return true;
    }

    /**
     * Draws one overlay for one player, with that player's values filled in.
     *
     * <p>What {@link OverlayRuntime}'s loop calls. Separate from {@link #draw}
     * because that one looks an id up and this one already has the info — and
     * because only this one knows about values.
     *
     * <p><b>Two components, not one.</b> The picture is a glyph in a font of
     * its own, drawn in a colour the pack's core shader matches on; the text is
     * ordinary words in the default font and an ordinary colour. One component
     * cannot be in two fonts, and drawing the text in the picture's colour
     * would run that shader object's shapes inside every letter — so they are
     * sent as two parts of one message.
     */
    public void send(Player viewer, OverlayInfo info, java.util.Map<String, String> values) {
        if (viewer == null || !viewer.isOnline() || info == null) {
            return;
        }
        String text = OverlayRuntime.fill(info.text(), values == null ? java.util.Map.of() : values);
        BaseComponent[] parts = text.isEmpty()
                ? component(info)
                : new BaseComponent[] {component(info)[0], new TextComponent(" " + text)};
        if (info.slot() == OverlayInfo.Slot.BOSS_BAR) {
            // See draw(): a boss bar cannot carry a font, so this surface only
            // ever holds the default-font overlays, and their text rides the
            // legacy string with them.
            BossBar bar = bars.computeIfAbsent(viewer.getUniqueId(),
                    key -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
            bar.setTitle(legacy(info) + (text.isEmpty() ? "" : ChatColor.RESET + " " + text));
            bar.setProgress(0d);
            bar.addPlayer(viewer);
            bar.setVisible(true);
            return;
        }
        viewer.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, parts);
    }

    /**
     * The run to draw, as a component.
     *
     * <p>Components rather than a legacy string because a shader overlay needs
     * two things a legacy string cannot carry: an exact RGB colour, and a font.
     * The colour is the object's address — the pack's core shader recognises it
     * by the vertex colour its text arrived with — so "close enough" renders
     * nothing.
     */
    static BaseComponent[] component(OverlayInfo info) {
        TextComponent text = new TextComponent(drawnText(info));
        // White unless the overlay named a colour: the client tints art with
        // whatever colour the surrounding text is drawn in, and anything but
        // white comes out muddy. A shader overlay is the exception, and for it
        // the colour is not a look at all.
        text.setColor(info.color().isEmpty()
                ? net.md_5.bungee.api.ChatColor.WHITE
                : net.md_5.bungee.api.ChatColor.of(info.color()));
        if (!info.font().isEmpty()) {
            text.setFont(info.font());
        }
        return new BaseComponent[] {text};
    }

    /**
     * The same run as a legacy string, for the boss bar.
     *
     * <p>White, or the client tints the art with whatever colour the
     * surrounding text is drawn in and it comes out muddy. A colour the overlay
     * named wins, spelled the legacy way ({@code §x§r§r§g§g§b§b}) since that is
     * all this surface can take.
     */
    static String legacy(OverlayInfo info) {
        String prefix = info.color().isEmpty()
                ? ChatColor.WHITE.toString()
                : net.md_5.bungee.api.ChatColor.of(info.color()).toString();
        return prefix + drawnText(info);
    }

    /** The characters themselves, without any colour in front of them. */
    private static String drawnText(OverlayInfo info) {
        if (!info.title().isEmpty()) {
            // A pushed pack did its own arithmetic with its own codepoints.
            return info.title();
        }
        return shift(info.offset()) + info.character();
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
        // A container title is a legacy string too, so it is the same run the
        // boss bar draws. One builder, so the two cannot drift.
        return legacy(info);
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
