package ai.resourcepack.engine.core.scoreboard;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ScoreboardInfo;
import ai.resourcepack.engine.api.Scoreboards;
import ai.resourcepack.engine.core.Regions;
import ai.resourcepack.engine.core.font.IconsImpl;
import ai.resourcepack.engine.core.font.OverlayRuntime;
import ai.resourcepack.engine.core.serve.PromptText;
import ai.resourcepack.engine.core.textfx.TextEffectCatalogue;
import fr.mrmicky.fastboard.FastBoard;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Shows scoreboards. Internal; {@link Scoreboards} is what a plugin sees.
 *
 * <p>One timer walks the players online. For each it works out which
 * scoreboard they should have, and when that one is due it fills the text in
 * for them and sends what changed. A sidebar is sent as packets to that player
 * alone (FastBoard, shaded in), so the server's own scoreboard and every
 * team on it are untouched: a name-tag plugin, a tab list plugin and this can
 * all run at once. It is also what makes it work on Folia, where a plugin
 * cannot make a scoreboard at all.
 */
public final class ScoreboardRuntime implements Scoreboards, Listener {

    /** How often the timer looks, in ticks: the fastest a scoreboard can be filled in. */
    private static final long PERIOD = 2;

    /** How often a player with no scoreboard is asked whether they have one now. */
    private static final long IDLE = 20;

    /** A hex colour as legacy text carries it, which a placeholder may hand back. */
    private static final Pattern LEGACY_HEX = Pattern.compile("(?i)§x((?:§[0-9a-f]){6})");

    private final Plugin plugin;
    private final IconsImpl icons;
    private final OverlayRuntime values;
    private final Predicate<UUID> plain;
    private final BooleanSupplier panels;
    private final NamespacedKey hiddenKey;

    private volatile Map<ContentId, ScoreboardDefinitions.Board> boards = Map.of();
    /** The ones that show themselves, the highest priority first. */
    private volatile List<ScoreboardDefinitions.Board> automatic = List.of();

    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    private final Map<UUID, ContentId> pinned = new ConcurrentHashMap<>();

    private Tasks.Handle task;
    /** Each player's part of the loop, on their own thread (their region's, on Folia). */
    private Regions.Fanout<Player> ticking;
    private volatile long now;
    /** Set when this server cannot be sent a sidebar this way, so it is said once and not tried again. */
    private volatile boolean unsupported;

    /** What one player is being shown. */
    private static final class View {
        FastBoard board;
        ContentId showing;
        volatile long due;
    }

    /**
     * @param plain  players who are sent no panel: the ones whose client cannot draw it
     * @param panels whether this server's version draws a sidebar's title under its lines and can hide
     *               its numbers (1.20.3 on); without it nobody is sent a panel
     */
    public ScoreboardRuntime(Plugin plugin, IconsImpl icons, OverlayRuntime values, Predicate<UUID> plain,
                             BooleanSupplier panels) {
        this.plugin = plugin;
        this.icons = icons;
        this.values = values;
        this.plain = plain;
        this.panels = panels;
        this.hiddenKey = new NamespacedKey(plugin, "scoreboard_hidden");
    }

    /** Replaces the scoreboards, as a reload does. Everybody's is filled in again at once. */
    public void replace(ScoreboardDefinitions.Result loaded) {
        Map<ContentId, ScoreboardDefinitions.Board> next = loaded == null ? Map.of() : loaded.boards();
        List<ScoreboardDefinitions.Board> shown = new ArrayList<>();
        for (ScoreboardDefinitions.Board board : next.values()) {
            if (board.info().automatic()) {
                shown.add(board);
            }
        }
        shown.sort((a, b) -> a.info().priority() != b.info().priority()
                ? Integer.compare(b.info().priority(), a.info().priority())
                : a.info().id().compareTo(b.info().id()));
        boards = next;
        automatic = List.copyOf(shown);
        pinned.values().removeIf(id -> !next.containsKey(id));
        for (View view : views.values()) {
            view.due = 0;
        }
        if (task == null && !next.isEmpty()) {
            ticking = new Regions.Fanout<>(plugin);
            task = Tasks.globalTimer(plugin, this::tick, PERIOD, PERIOD);
        }
    }

    /** Takes every scoreboard down and stops the timer, for shutdown. */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (ticking != null) {
            ticking.clear();
        }
        for (View view : views.values()) {
            take(view);
        }
        views.clear();
        boards = Map.of();
        automatic = List.of();
    }

    @Override
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(boards.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    @Override
    public Optional<ScoreboardInfo> info(ContentId id) {
        ScoreboardDefinitions.Board board = id == null ? null : boards.get(id);
        return board == null ? Optional.empty() : Optional.of(board.info());
    }

    @Override
    public boolean show(Player player, ContentId id) {
        if (player == null || id == null || !boards.containsKey(id)) {
            return false;
        }
        pinned.put(player.getUniqueId(), id);
        setHidden(player, false);
        return true;
    }

    @Override
    public void reset(Player player) {
        if (player != null) {
            pinned.remove(player.getUniqueId());
            soon(player);
        }
    }

    @Override
    public void setHidden(Player player, boolean hidden) {
        if (player == null) {
            return;
        }
        if (hidden) {
            player.getPersistentDataContainer().set(hiddenKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(hiddenKey);
        }
        soon(player);
    }

    @Override
    public boolean hidden(Player player) {
        return player != null && player.getPersistentDataContainer().has(hiddenKey, PersistentDataType.BYTE);
    }

    @Override
    public Optional<ContentId> showing(Player player) {
        View view = player == null ? null : views.get(player.getUniqueId());
        return view == null ? Optional.empty() : Optional.ofNullable(view.showing);
    }

    /** Whether somebody pinned this player's scoreboard, rather than where they are choosing it. */
    public boolean pinned(Player player) {
        return player != null && pinned.containsKey(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // Nothing to take down: the sidebar was theirs alone and went with them.
        views.remove(id);
        pinned.remove(id);
    }

    /** Has a player's scoreboard looked at again on the next pass, rather than when it was next due. */
    private void soon(Player player) {
        View view = views.get(player.getUniqueId());
        if (view != null) {
            view.due = 0;
        }
    }

    private void tick() {
        now += PERIOD;
        if (boards.isEmpty() && views.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            View view = views.get(player.getUniqueId());
            if (view != null && now < view.due) {
                continue;
            }
            // A player's world, permissions and placeholders belong to their
            // region's thread on Folia; elsewhere this runs right here.
            ticking.on(player, player, this::tick);
        }
    }

    private void tick(Player player) {
        View view = views.computeIfAbsent(player.getUniqueId(), key -> new View());
        ScoreboardDefinitions.Board wanted = unsupported ? null : wanted(player);
        if (wanted == null) {
            take(view);
            view.due = now + IDLE;
            return;
        }
        try {
            if (view.board == null || view.board.isDeleted()) {
                view.board = new FastBoard(player);
            }
            view.showing = wanted.info().id();
            Map<String, String> published = values.values(player);
            List<String> lines = new ArrayList<>(wanted.info().lines().size());
            for (String line : wanted.info().lines()) {
                lines.add(format(line, published, player));
            }
            boolean modern = panels.getAsBoolean();
            ScoreboardLayout.Composed composed = ScoreboardLayout.compose(
                    !modern || plain.test(player.getUniqueId()) ? null : wanted.panel(), wanted.codepoint(),
                    format(wanted.info().title(), published, player), lines, wanted.aligns(), wanted.minWidth(),
                    !modern);
            view.board.updateTitle(composed.title());
            view.board.updateLines(composed.lines());
            view.due = now + wanted.info().update();
        } catch (RuntimeException | LinkageError e) {
            // The packets are reached by reflection, so a server whose
            // internals this copy of FastBoard does not know cannot be sent
            // one. Said once; everything else in the plugin is unaffected.
            unsupported = true;
            view.board = null;
            view.showing = null;
            plugin.getLogger().log(Level.WARNING, "Scoreboards cannot be shown on this server version, so "
                    + "scoreboards/ is ignored until RP Engine is updated: " + e, e);
        }
    }

    /** The scoreboard a player should have now, or null for none. */
    private ScoreboardDefinitions.Board wanted(Player player) {
        if (hidden(player)) {
            return null;
        }
        ContentId forced = pinned.get(player.getUniqueId());
        if (forced != null) {
            return boards.get(forced);
        }
        String world = player.getWorld().getName();
        for (ScoreboardDefinitions.Board board : automatic) {
            ScoreboardInfo info = board.info();
            if (!info.worlds().isEmpty() && !info.worlds().contains(world)) {
                continue;
            }
            if (info.permission().isPresent() && !player.hasPermission(info.permission().get())) {
                continue;
            }
            return board;
        }
        return null;
    }

    /** Takes a player's sidebar off their screen, if they have one. */
    private static void take(View view) {
        FastBoard board = view.board;
        view.board = null;
        view.showing = null;
        if (board != null && !board.isDeleted()) {
            try {
                board.delete();
            } catch (RuntimeException | LinkageError e) {
                // They have gone, or the server is stopping: there is nobody to take it from.
            }
        }
    }

    /**
     * One piece of text as it is sent: placeholders filled for this player,
     * then colours ({@code &} codes, {@code &#rrggbb} and the tags the pack
     * prompt reads), then text effects, then icons. Icons go last because each
     * hands back the colour in force before it.
     */
    private String format(String text, Map<String, String> published, Player player) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String filled = OverlayRuntime.fill(text, published, player);
        if (filled.indexOf('§') >= 0) {
            // The colour reader knows a hex colour as &#rrggbb only.
            filled = LEGACY_HEX.matcher(filled).replaceAll(match -> "&#" + match.group(1).replace("§", ""));
        }
        return icons.formatNow(TextEffectCatalogue.formatInstalled(PromptText.toLegacy(filled)));
    }
}
