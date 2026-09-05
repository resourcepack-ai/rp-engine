package ai.resourcepack.engine.core.edit;

import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.core.Chat;
import ai.resourcepack.engine.core.command.EngineCommand;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The open editor sessions, and the loop that watches them.
 *
 * <h2>Why a poll rather than a push</h2>
 *
 * <p>This plugin already holds a websocket to the pairing relay, so a push
 * would seem to be there for the taking — and it is not, for a reason worth
 * writing down. That socket exists once somebody has typed {@code /rp sync}
 * with a code from a Studio account, and <strong>the whole premise of this
 * feature is a server owner who has no Studio account.</strong> Reaching them
 * would mean a second, unauthenticated socket for every server that ever ran
 * one edit command, held open for hours, against a relay whose state is keyed
 * to pairings.
 *
 * <p>What is actually being waited for is one integer that changes once or
 * twice in a session, so a poll of a few bytes every few seconds costs less
 * than the connection would and needs nothing new at either end. The revision
 * is what is polled rather than the content, so the editor's own autosave —
 * which fires every second while somebody works — never reaches this server.
 * A save is a deliberate act there and a deliberate write here.
 *
 * <h2>Threading</h2>
 *
 * <p>Every HTTP call and every file write happens on the poll's own async
 * thread; the reload and the chat line that follows are posted back to the
 * main thread. The content folder is only read during a reload, so writing it
 * from another thread is safe as long as the reload comes after, which is the
 * one ordering this class has to get right.
 */
public final class EditSessions {

    /** One open session. */
    static final class Session {
        final String id;
        final String pullToken;
        final String url;
        final UUID owner;
        final String ownerName;
        final EditTarget target;
        final long expiresAt;
        /** The last revision written to disk. Zero until the first save. */
        volatile int applied;
        /**
         * Held while a pull is being applied.
         *
         * <p>An {@link AtomicBoolean} rather than a flag, because a repeating
         * async task <strong>overlaps itself</strong> when one run takes longer
         * than the period — which a pull that writes a megabyte over a slow
         * link routinely does. Two runs reading a plain flag both see it clear
         * and both start writing the same files.
         */
        final AtomicBoolean busy = new AtomicBoolean();

        Session(String id, String pullToken, String url, UUID owner, String ownerName,
                EditTarget target, long expiresAt) {
            this.id = id;
            this.pullToken = pullToken;
            this.url = url;
            this.owner = owner;
            this.ownerName = ownerName;
            this.target = target;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * How often the open sessions are asked whether anything has been saved.
     *
     * <p>Three seconds is the delay between pressing a button in a browser and
     * seeing the result in game, so it is chosen from what it feels like rather
     * than from what it costs — which is one small request per open session,
     * and there is rarely more than one.
     */
    private static final long POLL_TICKS = 60L;

    private final Plugin plugin;
    private final Path contentRoot;
    private final EditClient client;
    private final Consumer<CommandSender> reload;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private BukkitTask watch;

    public EditSessions(Plugin plugin, Path contentRoot, String studioUrl, Consumer<CommandSender> reload) {
        this.plugin = plugin;
        this.contentRoot = contentRoot;
        this.client = new EditClient(studioUrl, plugin.getDescription().getVersion());
        this.reload = reload;
    }

    /** Starts the watch loop. Called once the plugin is up. */
    public void start() {
        stop();
        watch = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, POLL_TICKS, POLL_TICKS);
    }

    /**
     * Stops watching and lets go of every session.
     *
     * <p>The far end is told, so a link the server can no longer act on dies
     * with it rather than sitting there taking edits nobody will apply.
     *
     * <p><strong>Bounded, and that is the whole of why this is not a loop.</strong>
     * This runs on the main thread inside {@code onDisable}, and each close is
     * an HTTP round trip with a ten-second connect timeout — so three
     * abandoned sessions and a network that has gone away is half a minute
     * added to somebody's restart. They go out together and are waited on for
     * two seconds; whatever has not finished is left to expire on its own,
     * which it would have done anyway.
     */
    public void stop() {
        if (watch != null) {
            watch.cancel();
            watch = null;
        }
        List<Session> open = new ArrayList<>(sessions.values());
        sessions.clear();
        if (open.isEmpty()) {
            return;
        }
        ExecutorService closing = Executors.newFixedThreadPool(Math.min(4, open.size()));
        for (Session session : open) {
            closing.submit(() -> client.close(session.id, session.pullToken));
        }
        closing.shutdown();
        try {
            closing.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closing.shutdownNow();
    }

    /**
     * What {@code /rp edit} with nothing after it lists: one line per session
     * this sender has open, in the order they were opened.
     */
    public List<String> describeOpen(CommandSender who) {
        List<String> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Session session : sessions.values()) {
            if (!session.ownerName.equals(who.getName())) {
                continue;
            }
            long minutes = session.expiresAt <= 0 ? -1 : (session.expiresAt - now) / 60_000;
            lines.add(session.target.id + (minutes < 0 ? "" : "  " + minutes + "m left")
                    + (session.applied > 0 ? "  saved " + session.applied
                            + (session.applied == 1 ? " time" : " times") : ""));
        }
        return lines;
    }

    /** The item's own sprite, in the pixel editor. */
    public void editTexture(CommandSender who, ItemInfo item) {
        async(who, () -> EditTargets.texture(contentRoot, item, client.client(), version()));
    }

    /** The 3D model an item wears, in the model editor. */
    public void editModel(CommandSender who, ItemInfo item) {
        async(who, () -> EditTargets.model(contentRoot, item, client.client(), version()));
    }

    /** A vehicle's seats and handling, in the vehicle editor. */
    public void editVehicle(CommandSender who, VehicleInfo vehicle, ItemInfo model) {
        async(who, () -> EditTargets.vehicle(contentRoot, vehicle, model, client.client(), version()));
    }

    /**
     * Resolves a target and opens a session, off the main thread.
     *
     * <p>Both halves have to be off it: resolving reads a project file that may
     * be a megabyte, and opening is an HTTP round trip to another continent.
     * Either one on the main thread is a server that stops while somebody types
     * a command.
     */
    private void async(CommandSender who, TargetSupplier supplier) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                open(who, supplier.get());
            } catch (EditException e) {
                main(() -> EngineCommand.say(who, e.getMessage()));
            }
        });
    }

    /** A target, or the sentence explaining why there is not one. */
    private interface TargetSupplier {
        EditTarget get() throws EditException;
    }

    private String version() {
        return Bukkit.getBukkitVersion().split("-")[0];
    }

    /**
     * Opens a session and puts the link in front of {@code who}.
     *
     * <p>Runs on the caller's thread and must therefore be called from an async
     * one — the command layer is what arranges that. Everything it prints goes
     * back through the main thread.
     */
    private void open(CommandSender who, EditTarget target) {
        try {
            EditWire.Opened opened = client.open(target.request);
            Session session = new Session(opened.id, opened.pullToken, opened.url,
                    who instanceof Player ? ((Player) who).getUniqueId() : null,
                    who.getName(), target, expiry(opened.expiresAt));
            sessions.put(session.id, session);
            main(() -> {
                Chat.link(who, EngineCommand.prefix() + "Edit " + target.id + " here: ", "open the editor",
                        opened.url);
                EngineCommand.say(who, "The link works once and lasts three hours. Nothing changes "
                        + "on this server until you press Send to server.");
            });
        } catch (EditException e) {
            main(() -> EngineCommand.say(who, e.getMessage()));
        }
    }

    /** Closes every session this sender opened. */
    public int close(CommandSender who) {
        int closed = 0;
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!session.ownerName.equals(who.getName())) {
                continue;
            }
            sessions.remove(session.id);
            final Session doomed = session;
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> client.close(doomed.id, doomed.pullToken));
            closed++;
        }
        return closed;
    }

    // ---- the loop -----------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.expiresAt > 0 && now > session.expiresAt) {
                sessions.remove(session.id);
                tell(session, "The editor link for " + session.target.id + " has expired.");
                continue;
            }
            if (!session.busy.compareAndSet(false, true)) {
                continue;
            }
            try {
                poll(session);
            } finally {
                session.busy.set(false);
            }
        }
    }

    /** One session's turn: ask, and pull if anything has been saved. */
    private void poll(Session session) {
        Optional<EditWire.Status> status = client.status(session.id, session.pullToken);
        if (status.isEmpty()) {
            // Gone at the far end: expired, or closed from somewhere else.
            // Dropped quietly — the owner is told only when something they
            // were waiting for stops, and nothing was.
            sessions.remove(session.id);
            return;
        }
        int revision = status.get().revision;
        if (revision > session.applied) {
            pull(session, revision);
        }
    }

    private void pull(Session session, int revision) {
        try {
            EditWire.Pull pull = client.pull(session.id, session.pullToken);
            EditApply.Applied applied = EditApply.apply(session.target, pull);
            // Recorded from what the pull actually carried rather than from the
            // status that prompted it: a save landing between the two would
            // otherwise leave this thinking it held an older revision than it
            // does, and the same content would be written again on the next
            // tick.
            session.applied = Math.max(revision, pull.revision);
            main(() -> {
                reload.accept(Bukkit.getConsoleSender());
                tell(session, "Saved " + session.target.id + " — "
                        + describe(applied) + ", and the content folder has been reloaded.");
            });
        } catch (EditException e) {
            // The revision is NOT recorded, so pressing Send again retries.
            plugin.getLogger().log(Level.WARNING, "edit: could not apply " + session.target.id
                    + ": " + e.getMessage());
            tell(session, e.getMessage());
        }
    }

    private static String describe(EditApply.Applied applied) {
        List<String> parts = new ArrayList<>();
        if (!applied.written.isEmpty()) {
            parts.add(applied.written.size() + (applied.written.size() == 1 ? " file" : " files") + " written");
        }
        if (!applied.deleted.isEmpty()) {
            parts.add(applied.deleted.size() + " deleted");
        }
        return String.join(", ", parts);
    }

    /** Says something to whoever opened the session, if they are still here. */
    private void tell(Session session, String line) {
        main(() -> {
            Player player = session.owner == null ? null : Bukkit.getPlayer(session.owner);
            if (player != null) {
                EngineCommand.say(player, line);
            } else {
                plugin.getLogger().info("edit: " + line);
            }
        });
    }

    private void main(Runnable work) {
        if (Bukkit.isPrimaryThread()) {
            work.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, work);
    }

    /** The far end's expiry as epoch millis, or 0 when it did not say. */
    private static long expiry(String iso) {
        if (iso == null || iso.isEmpty()) {
            return 0;
        }
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }
}
