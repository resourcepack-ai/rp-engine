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

    /**
     * Who is actually holding a pushed pack. Everybody, until told otherwise.
     *
     * <p><b>A pushed overlay is not for the whole server.</b> Studio pushes a
     * pack to the player who asked for it; the rest are wearing whatever the
     * server itself serves, and the picture in an overlay is a glyph that only
     * exists in the pushed pack. So drawing one for them puts a row of
     * missing-glyph boxes over their hotbar for something they never asked to
     * see — and the manifest outlives the push, so after one sync every player
     * who ever joined got it, for good.
     *
     * <p>The engine's own content is not gated: a server's own bundle is what
     * its players are already wearing, and a server owner who turns hosting off
     * has made that decision themselves.
     */
    private volatile java.util.function.Predicate<Player> pushedAudience = viewer -> true;

    /**
     * Says who may be shown an overlay whose art came from a pushed pack.
     *
     * <p>Set once at start-up. Idempotent and safe to call again; a null
     * restores "everybody", which is what an engine with no sync would want.
     */
    public void audience(java.util.function.Predicate<Player> holdsPushedPack) {
        this.pushedAudience = holdsPushedPack == null ? viewer -> true : holdsPushedPack;
    }

    /** Whether this player can see this overlay at all. See {@link #audience}. */
    private boolean visible(Player viewer, OverlayInfo info) {
        return !info.fromPushedPack() || pushedAudience.test(viewer);
    }

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
        if (!visible(viewer, info)) {
            // Not an error: the overlay exists, this player has no pack to draw
            // it out of. False is what lets /rp hud say so rather than report a
            // success nobody can see.
            return false;
        }
        if (info.slot() == OverlayInfo.Slot.BOSS_BAR) {
            // One composer for both surfaces — see legacyComposed(), which also
            // records why a boss bar can hold a shader overlay now when it once
            // could not.
            BossBar bar = bars.computeIfAbsent(viewer.getUniqueId(),
                    key -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
            bar.setTitle(legacyComposed(info, java.util.Map.of(), viewer));
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
        if (viewer == null || !viewer.isOnline() || info == null || !visible(viewer, info)) {
            return;
        }
        java.util.Map<String, String> filled = values == null ? java.util.Map.of() : values;
        if (info.slot() == OverlayInfo.Slot.BOSS_BAR) {
            BossBar bar = bars.computeIfAbsent(viewer.getUniqueId(),
                    key -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
            bar.setTitle(legacyComposed(info, filled, viewer));
            // Invisible bar, visible art: the bar itself is a rendering surface
            // here rather than a meter.
            bar.setProgress(0d);
            bar.addPlayer(viewer);
            bar.setVisible(true);
            return;
        }
        // Whatever was on the boss bar comes off. An overlay that changed slot
        // between two pushes would otherwise be drawn twice — once on the bar
        // nothing is updating any more, and once where it now lives.
        BossBar stale = bars.remove(viewer.getUniqueId());
        if (stale != null) {
            stale.removeAll();
        }
        viewer.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                compose(info, filled, viewer));
    }

    /**
     * The same composed run, as the legacy string a boss bar takes.
     *
     * <p><b>A boss bar can carry a shader overlay now, and once could not.</b>
     * The objection was real: legacy formatting has codes for colour and none
     * for a font, so a canvas glyph living in a font of its own could not be
     * named here at all. The glyph moved into the pack's DEFAULT font — which it
     * did for an unrelated reason, so that a stale pack draws nothing rather
     * than four missing-glyph boxes — and with it the shift characters. Nothing
     * in a shader overlay needs a font any more, and {@code §x} spells the exact
     * RGB the shader matches on.
     *
     * <p>So this walks the same cursor {@link #compose} does, over the same
     * runs, and differs only in how it spells a colour. A run that genuinely
     * does name a font is the one thing that cannot survive: it is drawn
     * unstyled rather than dropped.
     */
    static String legacyComposed(OverlayInfo info, java.util.Map<String, String> filled, Player viewer) {
        StringBuilder out = new StringBuilder(legacy(info));
        boolean placeOurselves = info.positionsRuns();
        int cursor = info.advance();
        for (OverlayInfo.OverlayRun run : info.runs()) {
            Drawn drawn = drawnRun(info, run, filled, viewer);
            if (drawn.text().isEmpty()) {
                continue;
            }
            out.append(placeOurselves ? shiftTo(info, run.x() - cursor) : run.shift());
            // A bar's fill can name its own colour — the mark for whichever
            // threshold the value fell under — and it wins over the run's,
            // which is the mark for the bar's base colour. Everything else
            // reports null and keeps what the pack wrote.
            String tint = drawn.colorOr(run.color());
            out.append(tint.isEmpty()
                    ? ChatColor.WHITE.toString()
                    : net.md_5.bungee.api.ChatColor.of(tint).toString());
            out.append(drawn.text());
            cursor = run.x() + drawn.advance();
        }
        if (info.runs().isEmpty()) {
            String plain = OverlayRuntime.fill(info.text(), filled, viewer);
            if (!plain.isEmpty()) {
                out.append(ChatColor.RESET).append(' ').append(plain);
            }
        } else if (placeOurselves) {
            out.append(shiftTo(info, info.advance() - cursor));
        }
        return out.toString();
    }

    /**
     * What one run actually says for one player, placeholders filled.
     *
     * <p>Two substitutions, in this order and not the other: which CHARACTER
     * this player draws (a head is their own face, and the pack baked one glyph
     * each), and then what the {@code {name}} placeholders in it come to. A head
     * carries no placeholders and a label carries no per-player character, so
     * the order is only a rule for the case that never happens — but a rule
     * beats finding out.
     */
    private static Drawn drawnRun(OverlayInfo info, OverlayInfo.OverlayRun run,
                                  java.util.Map<String, String> filled, Player viewer) {
        if (run.bar() != null) {
            return barRun(info, run.bar(), filled, viewer);
        }
        String drawn = OverlayRuntime.fill(
                run.textFor(viewer == null ? null : viewer.getUniqueId()), filled, viewer);
        // A shader run's RGB is its positioning address. Legacy codes in a value
        // must not replace it or make half a label unpositioned.
        if (isRunMark(run.color())) {
            drawn = ChatColor.stripColor(drawn);
        }
        // The run's own figure when it has one, and a measurement otherwise.
        // A head's glyph is one the pack invented, and so is any picture the
        // author placed — no table of vanilla's widths can hold either, and
        // guessing puts every run after it in the wrong place.
        return new Drawn(drawn, run.advance() > 0 ? run.advance() : TextWidth.of(drawn), null);
    }

    /**
     * What a run came to, and how far it moved the cursor.
     *
     * <p>One value rather than two calls, because for a bar the two answers come
     * out of the same arithmetic — measuring the assembled string afterwards
     * would mean re-deriving a number we had already worked out, against a
     * width table that has never heard of the glyphs in it.
     */
    private record Drawn(String text, int advance, String color) {
        /** The colour to draw it in: the bar's chosen shade, else the run's own. */
        String colorOr(String fallback) {
            return color == null || color.isEmpty() ? fallback : color;
        }
    }

    /**
     * One of a bar's two runs, assembled.
     *
     * <p>The background is always the full length; the fill is as long as the
     * value says. Both are built the same way: take the widest rectangle that
     * still fits, then a single left shift to close the pixel the font renderer
     * puts after every glyph, and repeat. Greedy over powers of two, so any
     * length is about a dozen characters rather than one per pixel.
     *
     * <p>A value nothing can answer reads as zero, which draws an empty bar. A
     * MAX nothing can answer reads as the bar's own length, so the bar becomes a
     * plain pixel gauge rather than dividing by zero.
     */
    private static Drawn barRun(OverlayInfo info, OverlayInfo.OverlayRun.Bar bar,
                                java.util.Map<String, String> filled, Player viewer) {
        double fraction = 1d;
        if (bar.fill()) {
            double value = number(bar.value(), filled, viewer, 0d);
            double max = number(bar.max(), filled, viewer, bar.total());
            fraction = max <= 0d ? 0d : Math.max(0d, Math.min(1d, value / max));
        }
        String color = bar.fill() ? shadeFor(bar, fraction) : null;
        return bar.segments() > 0
                ? segmented(info, bar, fraction, color)
                : new Drawn(strip(info, bar, (int) Math.round(fraction * bar.total())),
                        (int) Math.round(fraction * bar.total()), color);
    }

    /**
     * The colour a fill takes at this fraction, or null to keep the run's own.
     *
     * <p>The list is lowest threshold first and its last entry is the bar's own
     * colour at 1, so the first match is always the right one and there is no
     * fallback to disagree with it. Every value in it is a MARK the pack has
     * declared a vertex branch for — see {@link OverlayInfo.OverlayRun.Bar.Shade}.
     */
    private static String shadeFor(OverlayInfo.OverlayRun.Bar bar, double fraction) {
        for (OverlayInfo.OverlayRun.Bar.Shade shade : bar.colors()) {
            if (fraction <= shade.at()) {
                return shade.color();
            }
        }
        return null;
    }

    /**
     * A run of rectangles that draws exactly {@code pixels} across.
     *
     * <p>Greedy from the widest, and every glyph followed by a one-pixel LEFT
     * shift: a bitmap glyph advances by its width plus the pixel the font
     * renderer puts between glyphs, and without closing that up a bar is a
     * dotted line.
     */
    private static String strip(OverlayInfo info, OverlayInfo.OverlayRun.Bar bar, int pixels) {
        StringBuilder out = new StringBuilder();
        int left = pixels;
        for (OverlayInfo.OverlayRun.Bar.Glyph glyph : bar.glyphs()) {
            while (glyph.px() > 0 && left >= glyph.px()) {
                out.append(glyph.character()).append(shiftTo(info, -1));
                left -= glyph.px();
            }
        }
        return out.toString();
    }

    /**
     * A bar drawn as discrete chunks rather than one strip.
     *
     * <p>Vanilla's own hearts and armour read this way, and a value out of ten
     * is easier to read as ten things than as a length. The chunks are the same
     * rectangles a solid bar is built from — what differs is that the fill
     * rounds UP to a whole chunk and each one is followed by a gap.
     *
     * <p>Rounds up rather than down so that any value above empty lights at
     * least one chunk: a bar showing nothing at 4% reads as broken, and the
     * whole point of chunks is that the last one going out is the warning.
     *
     * <p><b>The advance is the whole bar's, not the drawn part's.</b> Both runs
     * have to occupy the same width or the background and the fill would start
     * the cursor in different places and every run after them would move as the
     * value changed.
     */
    private static Drawn segmented(OverlayInfo info, OverlayInfo.OverlayRun.Bar bar,
                                   double fraction, String color) {
        int count = Math.max(1, bar.segments());
        int gap = Math.max(0, bar.gap());
        // What is left for the chunks once the gaps between them are taken out.
        int chunk = Math.max(1, (bar.total() - gap * (count - 1)) / count);
        int lit = (int) Math.ceil(fraction * count - 1e-9);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lit; i++) {
            if (i > 0) {
                out.append(shiftTo(info, gap));
            }
            out.append(strip(info, bar, chunk));
        }
        return new Drawn(out.toString(), chunk * count + gap * (count - 1), color);
    }

    /**
     * A number out of a placeholder, or out of the text itself.
     *
     * <p>Both spellings are ordinary — a mana bar is out of a number somebody
     * chose and a health bar is out of {@code health_max}, which only the server
     * knows — so a name that parses as a number is used as one and everything
     * else is looked up. That order matters: it means a bar can be given a plain
     * ceiling without inventing a placeholder to hold it.
     */
    private static double number(String name, java.util.Map<String, String> filled, Player viewer, double fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        Double literal = parse(name);
        if (literal != null) {
            return literal;
        }
        Double resolved = parse(Placeholders.resolve(viewer, name, filled));
        return resolved == null ? fallback : resolved;
    }

    /** A number, or null. Commas and a trailing percent are what people type. */
    private static Double parse(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim().replace(",", "").replace("%", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether this colour is a shader run's ADDRESS rather than a look.
     *
     * <p>The band is {@code #f0GGBB} with both of the last two channels a
     * multiple of eight, which is not decoration: the game draws every label a
     * second time at a quarter of its colour as a drop shadow, and the pack's
     * shader recognises that shadow so it can move it with the label it belongs
     * to. Spacing the channels is what keeps a shadow legible after being
     * divided by four — see Studio's `signatures.ts`, which is the other end of
     * this and has the full reasoning.
     *
     * <p>Matched loosely on purpose: a manifest from an older Studio uses the
     * previous {@code #fdGGBB} band, and a run of that vintage still wants its
     * legacy codes stripped for exactly the same reason.
     */
    static boolean isRunMark(String color) {
        return color.matches("(?i)#f0[0-9a-f][08][0-9a-f][08]")
                || color.matches("(?i)#fd[0-9a-f]{2}(0[2-9a-f]|1[0-9a-f]|2[01])");
    }



    /** Compose a stable-width action bar independently of the network send. */
    static BaseComponent[] compose(OverlayInfo info, java.util.Map<String, String> filled) {
        return compose(info, filled, null);
    }

    /** The same, for one player — whose head is their own. See {@code OverlayRun.textFor}. */
    static BaseComponent[] compose(OverlayInfo info, java.util.Map<String, String> filled, Player viewer) {
        java.util.List<BaseComponent> parts = new java.util.ArrayList<>();
        parts.add(component(info)[0]);

        // Positioned runs first, and the unpositioned `text` only if there are
        // none: they are two spellings of the same thing, and an overlay pushed
        // by a newer Studio carries the positioned form. Drawing both would put
        // every label on screen twice.
        if (!info.runs().isEmpty()) {
            // WHERE THE CURSOR IS, carried across the runs.
            //
            // This is the arithmetic the pack cannot do. Each run's shift was
            // worked out as if the cursor were still where the picture left it,
            // which is true of the first run and of no other: drawing a run
            // moves the cursor by however wide the drawn string turned out to
            // be, and the string is only finished here, once the placeholders
            // are filled. Without this the second label landed a whole label to
            // the right of where the author put it, and the third further
            // still.
            boolean placeOurselves = info.positionsRuns();
            int cursor = info.advance();
            for (OverlayInfo.OverlayRun run : info.runs()) {
                Drawn drawn = drawnRun(info, run, filled, viewer);
                if (drawn.text().isEmpty()) {
                    continue;
                }
                // The shift is space characters in the pack's own font, where
                // their advances are declared — so it is its own component. Put
                // them in the text's font and they are glyphs that font has
                // never heard of.
                String moveBy = placeOurselves ? shiftTo(info, run.x() - cursor) : run.shift();
                if (!moveBy.isEmpty()) {
                    TextComponent shift = new TextComponent(moveBy);
                    shift.setFont(info.font().isEmpty() ? null : info.font());
                    parts.add(shift);
                }
                TextComponent body = new TextComponent(drawn.text());
                if (!run.font().isEmpty()) {
                    body.setFont(run.font());
                }
                // See the legacy path: a bar's fill picks its own mark by value.
                String tint = drawn.colorOr(run.color());
                body.setColor(tint.isEmpty()
                        ? net.md_5.bungee.api.ChatColor.WHITE
                        : net.md_5.bungee.api.ChatColor.of(tint));
                parts.add(body);
                cursor = run.x() + drawn.advance();
            }
            // The action bar centres the FINAL advance, not the picture.
            // Restore the canvas width so labels and changing values cannot
            // drag the whole overlay sideways (including empty final runs).
            if (placeOurselves) {
                TextComponent end = new TextComponent(shiftTo(info, info.advance() - cursor));
                end.setFont(info.font().isEmpty() ? null : info.font());
                parts.add(end);
            }
        } else {
            String text = OverlayRuntime.fill(info.text(), filled, viewer);
            if (!text.isEmpty()) {
                parts.add(new TextComponent(" " + text));
            }
        }
        return parts.toArray(new BaseComponent[0]);
    }

    /** Powers of two, repeating the largest step when a move exceeds 1023 pixels. */
    static String shiftTo(OverlayInfo info, int pixels) {
        String alphabet = pixels < 0 ? info.shiftMinus() : info.shiftPlus();
        if (alphabet.isEmpty() || pixels == 0) {
            return "";
        }
        int left = Math.abs(pixels);
        StringBuilder out = new StringBuilder();
        for (int step = alphabet.length() - 1; step >= 0; step--) {
            int size = 1 << step;
            while (left >= size) {
                out.append(alphabet.charAt(step));
                left -= size;
            }
        }
        return out.toString();
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
