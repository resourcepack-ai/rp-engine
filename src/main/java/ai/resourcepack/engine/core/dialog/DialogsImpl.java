package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import ai.resourcepack.engine.api.Dialogs;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opening dialogs. The implementation behind {@link Dialogs}.
 *
 * <p>Two things happen here and they are a long way apart in time. The
 * catalogue is replaced whenever content loads, and the datapack is rewritten
 * with it ({@link DialogDatapack}); showing one is a command dispatched from
 * the console, later.
 *
 * <p><b>A command rather than an API call</b> — {@code /dialog show} — because
 * Bukkit has no dialog API at all and Paper's is Paper's. The engine compiles
 * against Spigot and runs on both, and a vanilla command that has existed
 * since the feature did works the same on every server that has the feature.
 * The cost is that opening one is a console dispatch; the benefit is that
 * nothing here has to be written twice.
 *
 * <p><b>The dialog travels IN the command, which is why none of this needs a
 * restart.</b> {@code /dialog show} takes either a registry id or a whole
 * dialog written out in SNBT, and it has taken both since the first snapshot
 * that had dialogs at all — the same version this engine requires for them. So
 * the ordinary route hands the game the dialog itself ({@link DialogSnbt}) and
 * the registry is not consulted: a dialog that arrived in a push thirty
 * seconds ago opens now, and one edited in Studio opens as edited rather than
 * as the copy some earlier start happened to read.
 *
 * <p>Naming the id is still the fallback, for the narrow case of a dialog SNBT
 * cannot carry — a control character inside a string is the realistic one. That
 * route does need the restart, and is the only thing left that does.
 */
public final class DialogsImpl implements Dialogs {

    private final DialogDatapack datapack;
    private final boolean supported;
    /** Each player's own values for the dialogs' bound controls. Null in a test with none. */
    private final DialogVariables variables;
    /**
     * What plugins have published for each player — the overlays' values, which
     * a dialog prints too ({@link #set}). Null in a test with none.
     */
    private final ai.resourcepack.engine.core.font.OverlayRuntime published;

    private volatile Map<ContentId, DialogInfo> dialogs = Map.of();

    /**
     * What each player has picked up in a dialog whose items move, and the
     * containers a dialog's slots can show: see {@link DialogSlots}. Without
     * the plugin (a test) only the ones that keep nothing of their own.
     */
    private volatile DialogSlots slots = new DialogSlots(null);

    /**
     * The dialog each player was last shown, and what it was opened with — so
     * a click that changes one of their values can open it again, drawn in the
     * new state, about the same somebody. Weak on the player: a session that
     * ends takes its entry with it.
     */
    private final Map<Player, Shown> lastShown = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * A dialog as it was shown to somebody, and the pages its links turn to on
     * the client, without asking the server ({@link #instantPages}) — which the
     * player may be looking at instead by now.
     */
    public record Shown(ContentId id, Map<String, String> values, Set<ContentId> reach) {
        public Shown(ContentId id, Map<String, String> values) {
            this(id, values, Set.of());
        }
    }

    /** How far a dialog's pages are followed looking for the ones that turn on the client. */
    private static final int MAX_PAGES = 64;

    /**
     * Each player's own version of a pushed dialog. Null until told, which
     * opens the catalogue's for everybody.
     *
     * <p>Same gate the overlays have and for the same reason: a pushed
     * dialog's picture is a glyph that only exists in the pack Studio sent to
     * one player, so opening it for anybody else is a screen of missing-glyph
     * boxes — and on a server where several people sync, "anybody holding a
     * pushed pack" is not the same as holding THIS one, which is what the gate
     * used to ask. The engine's own content is not gated — a server's bundle
     * is what its players are already wearing.
     */
    private volatile java.util.function.BiFunction<Player, ContentId, Optional<DialogInfo>> pushedView;

    public DialogsImpl(DialogDatapack datapack, boolean supported) {
        this(datapack, supported, null, null);
    }

    public DialogsImpl(DialogDatapack datapack, boolean supported, DialogVariables variables) {
        this(datapack, supported, variables, null);
    }

    public DialogsImpl(DialogDatapack datapack, boolean supported, DialogVariables variables,
                       ai.resourcepack.engine.core.font.OverlayRuntime published) {
        this.datapack = datapack;
        this.supported = supported;
        this.variables = variables;
        this.published = published;
    }

    /** Says how to find a player's own version of a pushed dialog: their push's, or empty. */
    public void pushedView(java.util.function.BiFunction<Player, ContentId, Optional<DialogInfo>> view) {
        this.pushedView = view;
    }

    /**
     * What a dialog is for this player: the server's own as it is, and a pushed
     * one as their own push has it — empty if their push has none.
     */
    public Optional<DialogInfo> info(Player viewer, ContentId id) {
        Optional<DialogInfo> found = info(id);
        java.util.function.BiFunction<Player, ContentId, Optional<DialogInfo>> view = pushedView;
        if (found.isEmpty() || !found.get().fromPushedPack() || view == null) {
            return found;
        }
        return viewer == null ? Optional.empty() : view.apply(viewer, id);
    }

    /**
     * Replaces the catalogue and rewrites the datapack.
     *
     * <p>Both halves together, always: the file on disk IS the dialog as far
     * as the game is concerned, so a catalogue holding an id with no file
     * behind it is a command that reports success and opens nothing.
     */
    public void replace(Map<ContentId, DialogInfo> loaded) {
        this.dialogs = loaded == null ? Map.of() : Map.copyOf(loaded);
        if (supported) {
            datapack.write(this.dialogs.values());
        }
    }

    @Override
    public Collection<ContentId> ids() {
        List<ContentId> out = new ArrayList<>(dialogs.keySet());
        out.sort(ContentId::compareTo);
        return List.copyOf(out);
    }

    @Override
    public Optional<DialogInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(dialogs.get(id));
    }

    @Override
    public boolean supported() {
        return supported;
    }

    @Override
    public boolean pending() {
        return supported && datapack.reloadWanted();
    }

    @Override
    public boolean canShow(Player viewer, ContentId id) {
        if (!supported || viewer == null || !viewer.isOnline()) {
            return false;
        }
        return info(viewer, id).isPresent();
    }

    @Override
    public boolean show(Player viewer, ContentId id) {
        return show(viewer, id, Map.of());
    }

    @Override
    public boolean show(Player viewer, ContentId id, Map<String, String> values) {
        return open(viewer, id, values, false);
    }

    /**
     * Opens a dialog the player reached FROM the one they were looking at — a
     * page turned, a move made, a setting changed: the item it was opened from
     * (a backpack) is still the one its slots show. {@link #show} is for a
     * dialog opened afresh, which forgets it.
     */
    public boolean follow(Player viewer, ContentId id, Map<String, String> values) {
        return open(viewer, id, values, true);
    }

    /**
     * Opens a dialog from an item in the player's hand — an item's
     * {@code dialog:} action. When the dialog's slots show the item's own
     * contents, the item is the backpack they show; see
     * {@link DialogSlots#openFrom}.
     */
    public DialogSlots.Opening showFrom(Player viewer, ContentId id, Map<String, String> values,
                                        org.bukkit.inventory.ItemStack used) {
        Optional<DialogInfo> info = viewer == null ? Optional.empty() : info(viewer, id);
        DialogSlots.Opening opening = info.isPresent()
                ? slots.openFrom(viewer, id, info.get().json(), used)
                : DialogSlots.Opening.NOT_A_BACKPACK;
        if (opening == DialogSlots.Opening.STACKED || opening == DialogSlots.Opening.NOT_HELD) {
            return opening;
        }
        open(viewer, id, values, opening == DialogSlots.Opening.BOUND);
        return opening;
    }

    private boolean open(Player viewer, ContentId id, Map<String, String> values, boolean followed) {
        if (!canShow(viewer, id)) {
            return false;
        }
        DialogInfo info = info(viewer, id).orElse(null);
        if (info == null) {
            return false;
        }
        String named = id.namespace() + ":" + id.path();
        String json = filled(viewer, info.json(), values);
        // The player's own items in a Studio dialog's inventory slots, the one
        // they have picked up lit, and no item tooltip this server would refuse
        // the dialog over: DialogItems.
        slots.shown(viewer, id, followed);
        json = DialogItems.fill(json, slots.reader(viewer), slots.held(viewer, id).orElse(null), info.itemIcons());
        // Its links to pages the client already holds as they are turn there
        // without a round trip: see instantPages.
        Set<ContentId> instant = instantPages(viewer, id, info.json());
        if (!instant.isEmpty()) {
            json = DialogLinks.swap(json, instant::contains);
        }
        Shown shown = new Shown(id, values == null ? Map.of() : Map.copyOf(values), instant);

        // Decode JSON directly so multiline text and other values need not
        // pass through the command parser or wait for the datapack registry.
        if (DialogPackets.show(viewer, json)) {
            lastShown.put(viewer, shown);
            return true;
        }

        // The dialog itself, in the command. Nothing needs to be in the
        // registry for this, so nothing needs a restart — see the class note.
        Optional<String> inline = DialogSnbt.of(json);
        if (inline.isPresent() && dispatch("minecraft:dialog show " + viewer.getName() + " " + inline.get(),
                named, false) == Outcome.SHOWN) {
            lastShown.put(viewer, shown);
            return true;
        }

        // The old route, kept for the dialog the line above could not carry.
        // Reached in two cases and they want telling apart: SNBT had no
        // spelling for something in the JSON, or the game refused what was
        // written. Either way the registry is worth trying, because a server
        // that has been restarted since the last content load has the dialog
        // in it and this still works.
        Outcome byName = dispatch("minecraft:dialog show " + viewer.getName() + " " + named, named, true);
        if (byName == Outcome.SHOWN) {
            lastShown.put(viewer, shown);
            // A show that worked is the only proof available that the server
            // has read what was written — nothing can ask the registry
            // directly. So it is what clears the flag, and without this
            // `pending()` stayed true for the life of the process.
            datapack.read();
            return true;
        }
        if (byName == Outcome.REFUSED) {
            datapack.unread();
            // Both routes are out. Say WHICH, once: "the file is on disk and
            // unread" and "this dialog is not something a command can carry"
            // are different problems with different fixes, and reporting the
            // first for the second would send somebody restarting over a stray
            // newline in a string.
            if (inline.isEmpty()) {
                Bukkit.getLogger().warning("[RPEngine] " + named + " could not be opened as itself: its JSON "
                        + "holds something a command cannot carry, and a line break inside one string is how "
                        + "that usually happens. Split the line in two, or write it as two body lines. Until "
                        + "then this one dialog needs the server RESTARTED, because the registry is the only "
                        + "other way in — and if a restart has not done it either, the pack is in this world's "
                        + "DISABLED list (a server that once read it as incompatible puts it there): run "
                        + DialogDatapack.enableCommand() + " once, then restart.");
            } else {
                Bukkit.getLogger().info("[RPEngine] " + named + " was refused both as itself and by name — so "
                        + "this is the dialog rather than the registry. The line above is what the game made "
                        + "of it; a complaint about a field usually means it is written for a newer Minecraft "
                        + "than this server runs.");
            }
        }
        return false;
    }

    /**
     * The pages a player can be turned to from {@code from} by the client alone.
     *
     * <p>A {@code show_dialog} by id opens the copy in the registry the server
     * synced at join, with no round trip — see {@link DialogDatapack}. That
     * copy is the file written before this server last started, so a link may
     * go that way only to a page whose copy is EXACTLY what this player would be
     * sent, and only to one with nothing per-player in it, since nothing fills a
     * placeholder in a registry copy. And it is all or nothing for everything
     * reachable from here: the registry's pages link to each other the same way,
     * so a client that has turned to one can go on turning without the server,
     * and every page it could reach has to be the player's own — one stale page
     * would be a screen of somebody else's push, or of missing glyphs.
     *
     * <p>Empty whenever any of that cannot be shown: no restart since the
     * dialog was pushed, a different push for this player, the pack disabled
     * in this world, a server the engine cannot ask. Every link then stays the
     * {@code /rp page} it was, which is a round trip and always correct.
     */
    Set<ContentId> instantPages(Player viewer, ContentId from, String json) {
        return instantPages(from, json, next -> info(viewer, next));
    }

    /**
     * Whether a dialog's page links turn on the client, for the server's own
     * copy of it: what {@code /rp dialogs} says beside each one. Null for a
     * dialog with no page links at all.
     */
    public Boolean pagesTurnInstantly(ContentId id) {
        DialogInfo info = info(id).orElse(null);
        if (info == null || DialogLinks.targets(info.json()).isEmpty()) {
            return null;
        }
        return !instantPages(id, info.json(), this::info).isEmpty();
    }

    private Set<ContentId> instantPages(ContentId from, String json,
                                        java.util.function.Function<ContentId, Optional<DialogInfo>> lookup) {
        if (!datapack.instantPossible() || from == null || json == null) {
            return Set.of();
        }
        // Every page reachable from here by page links, as this player has it.
        Map<ContentId, String> pages = new LinkedHashMap<>();
        pages.put(from, json);
        Deque<ContentId> todo = new ArrayDeque<>(DialogLinks.targets(json));
        while (!todo.isEmpty() && pages.size() < MAX_PAGES) {
            ContentId next = todo.poll();
            if (pages.containsKey(next)) {
                continue;
            }
            Optional<DialogInfo> page = lookup.apply(next);
            if (page.isPresent()) {
                pages.put(next, page.get().json());
                todo.addAll(DialogLinks.targets(page.get().json()));
            }
        }
        if (pages.size() == 1) {
            return Set.of();
        }
        Set<ContentId> out = new LinkedHashSet<>();
        for (Map.Entry<ContentId, String> page : pages.entrySet()) {
            // Nothing filled per player can come from the registry's copy: not a
            // placeholder, and not a player's inventory in a page's slots.
            if (DialogPlaceholders.any(page.getValue()) || DialogItems.perPlayer(page.getValue())) {
                continue;
            }
            if (!datapack.registered(page.getKey(), DialogDatapack.fileContent(page.getValue(), pages))) {
                return Set.of();
            }
            out.add(page.getKey());
        }
        return Set.copyOf(out);
    }

    /**
     * Follows a player the client turned to another page by itself. A command
     * held by a click on one of the pages they could have reached, and not by
     * the page they were sent, says that is where they are — so a
     * {@link #reopen} after it (a store's Buy button, say) reopens that page
     * rather than the one they came from.
     */
    public void noteCommand(Player viewer, String command) {
        Shown shown = viewer == null || command == null ? null : lastShown.get(viewer);
        if (shown == null || shown.reach().isEmpty()) {
            return;
        }
        DialogInfo current = info(viewer, shown.id()).orElse(null);
        if (current != null && DialogLinks.holdsCommand(current.json(), command)) {
            return;
        }
        for (ContentId page : shown.reach()) {
            if (page.equals(shown.id())) {
                continue;
            }
            Optional<DialogInfo> info = info(viewer, page);
            if (info.isPresent() && DialogLinks.holdsCommand(info.get().json(), command)) {
                lastShown.put(viewer, new Shown(page, shown.values(), shown.reach()));
                return;
            }
        }
    }

    /** The dialog a player was last shown, and what it was opened with. */
    public Optional<Shown> lastShown(Player viewer) {
        return viewer == null ? Optional.empty() : Optional.ofNullable(lastShown.get(viewer));
    }

    /**
     * Opens the dialog a player was last shown again, with what it was opened
     * with — which, since their values are read as it opens, draws every bound
     * control in its current state. False when there is none to reopen.
     */
    @Override
    public boolean reopen(Player viewer) {
        Shown shown = viewer == null ? null : lastShown.get(viewer);
        return shown != null && info(viewer, shown.id()).isPresent() && follow(viewer, shown.id(), shown.values());
    }

    @Override
    public void set(Player viewer, String name, String value) {
        if (published != null) {
            published.set(viewer, name, value);
        }
    }

    @Override
    public Optional<String> value(Player viewer, String name) {
        return published == null ? Optional.empty() : published.value(viewer, name);
    }

    @Override
    public Optional<String> setting(Player viewer, String name) {
        return variables == null ? Optional.empty() : variables.get(viewer, name);
    }

    @Override
    public boolean setSetting(Player viewer, String name, String value) {
        if (variables == null || viewer == null || name == null) {
            return false;
        }
        if (value == null) {
            variables.clear(viewer, name);
            return true;
        }
        return variables.set(viewer, name, value);
    }

    /**
     * Whether the dialog a player was last shown turns to {@code target} — holds
     * a click running {@code /rp page <target>}. The check every {@code /rp page}
     * passes, because a player runs it: see {@link DialogLinks}. Read against the
     * catalogue as it is now, so a push that took the link away takes the page
     * with it.
     */
    public boolean links(Player viewer, ContentId target) {
        return linked(viewer).contains(target);
    }

    /**
     * Every dialog the one a player was last shown turns to — what {@code /rp page}
     * completes — and every one turned to from the pages the client could have
     * gone to by itself: a page reached without the server still links onward
     * through it, and the server has no other way to know the player is there.
     */
    public List<ContentId> linked(Player viewer) {
        Shown shown = viewer == null ? null : lastShown.get(viewer);
        if (shown == null) {
            return List.of();
        }
        Set<ContentId> out = new LinkedHashSet<>();
        Set<ContentId> from = new LinkedHashSet<>();
        from.add(shown.id());
        from.addAll(shown.reach());
        for (ContentId page : from) {
            info(viewer, page).ifPresent(info -> out.addAll(DialogLinks.targets(info.json())));
        }
        return List.copyOf(out);
    }

    /**
     * Whether a loaded dialog lets a player set {@code name} to {@code value}:
     * some dialog declares the name, and lists the value for it. The check
     * every {@code /rp var} passes, because a player runs it — see
     * {@link DialogVariables}.
     */
    public boolean declares(String name, String value) {
        for (DialogInfo info : dialogs.values()) {
            java.util.List<String> values = info.variables().get(name);
            if (values != null && values.stream().anyMatch(v -> v.equalsIgnoreCase(value))) {
                return true;
            }
        }
        return false;
    }

    /** Every variable any loaded dialog declares, with the values it may take. */
    public Map<String, java.util.List<String>> declared() {
        Map<String, java.util.List<String>> out = new java.util.TreeMap<>();
        for (DialogInfo info : dialogs.values()) {
            info.variables().forEach((name, values) -> out.merge(name, values, (a, b) -> {
                java.util.List<String> both = new java.util.ArrayList<>(a);
                for (String v : b) {
                    if (!both.contains(v)) {
                        both.add(v);
                    }
                }
                return both;
            }));
        }
        return out;
    }

    /** The store of player values, for the command that sets them. Null when this was made without one. */
    public DialogVariables variables() {
        return variables;
    }

    /**
     * The dialog's JSON with its placeholders filled for this viewer — see
     * {@link DialogPlaceholders}. What a caller handed over wins, matched
     * without regard to case; then the player's own dialog settings (what a
     * bound control shows); then what a plugin published for them
     * ({@link #set}, shared with the overlays); then the built-ins and
     * PlaceholderAPI.
     *
     * <p>Settings come before published values on purpose. A click on a bound
     * switch sets the setting and opens the dialog again, and the switch has to
     * come back in the state the click chose — a published value of the same
     * name would freeze it. A plugin that means to change a setting has
     * {@link #setSetting} for it.
     */
    private String filled(Player viewer, String json, Map<String, String> values) {
        if (!DialogPlaceholders.any(json)) {
            return json;
        }
        Map<String, String> given = new java.util.HashMap<>();
        if (values != null) {
            values.forEach((k, v) -> {
                if (k != null && v != null) {
                    given.put(k.toLowerCase(java.util.Locale.ROOT), v);
                }
            });
        }
        Map<String, String> plugins = published == null ? Map.of() : published.values(viewer);
        return DialogPlaceholders.fill(json, name -> {
            String mine = given.get(name.toLowerCase(java.util.Locale.ROOT));
            if (mine != null) {
                return Optional.of(mine);
            }
            Optional<String> stored = variables == null ? Optional.empty() : variables.get(viewer, name);
            return stored.isPresent()
                    ? stored
                    : ai.resourcepack.engine.core.font.Placeholders.lookup(viewer, name, plugins);
        });
    }

    /** What players have picked up in dialogs whose items move, and the containers slots can show. */
    public DialogSlots slots() {
        return slots;
    }

    /**
     * Gives the slots the plugin, for the keys a backpack and a player's
     * storage are kept under. Called once, at start, before anything can have
     * registered a container.
     */
    public void attach(org.bukkit.plugin.Plugin plugin) {
        this.slots = new DialogSlots(plugin);
    }

    @Override
    public boolean container(String name, ai.resourcepack.engine.api.DialogContainer container) {
        return slots.register(name, container);
    }

    /**
     * Whether the dialog a player was last shown holds a click running
     * {@code rp slot <key>} — the check every {@code /rp slot} passes, because a
     * player runs it: see {@link DialogSlots}.
     */
    public boolean holdsSlot(Player viewer, DialogSlots.Key key) {
        Shown shown = viewer == null || key == null ? null : lastShown.get(viewer);
        if (shown == null) {
            return false;
        }
        Optional<DialogInfo> info = info(viewer, shown.id());
        return info.isPresent() && DialogLinks.holdsCommand(info.get().json(), DialogSlots.COMMAND + " " + key);
    }

    @Override
    public void close(Player viewer) {
        slots.forget(viewer);
        if (!supported || viewer == null || !viewer.isOnline()) {
            return;
        }
        dispatch("minecraft:dialog clear " + viewer.getName(), null, true);
    }

    /**
     * What the game made of a command.
     *
     * <p>Three answers rather than a boolean, because the caller has two
     * routes to try and they fail for different reasons. REFUSED is the game
     * declining what it was handed - a dialog that is not in the registry, or
     * one whose JSON its own codec will not read - and is the ordinary case
     * rather than a fault. FAILED is anything else, and is logged with its
     * stack because nothing else will explain it.
     */
    private enum Outcome {
        SHOWN,
        REFUSED,
        FAILED
    }

    /**
     * The one failure that is ORDINARY, and it arrives looking like a crash.
     *
     * <p>A dialog the game will not take is a Brigadier parse error, and a
     * parse error inside {@link Bukkit#dispatchCommand} does not reach the
     * sender the way it would if a player had typed the command - Bukkit wraps
     * whatever escaped in a CommandException reading "Unhandled exception
     * executing ...". So the most common way this feature says no presented as
     * a stack trace, which reads as the plugin being broken rather than as the
     * ordinary thing it is.
     *
     * <p>Matched on the class NAME rather than by catching the type, because
     * Brigadier is the server's and this engine compiles against an API that
     * does not promise it.
     */
    private static boolean isRefusal(Throwable root) {
        return root.getClass().getName().endsWith("CommandSyntaxException");
    }

    /**
     * Runs a vanilla command from the console and says what came of it.
     *
     * <p><b>The try/catch is the whole point of this method</b>, for the
     * reason on {@link #isRefusal}. A refusal comes back as a value and the
     * caller decides what it means; only the cases nothing can explain reach
     * the log from here.
     *
     * @param explain whether a refusal is worth a line in the log. False for
     *                the inline attempt: that one has a fallback behind it, and
     *                a refusal there is the ordinary prelude to trying the
     *                registry rather than news. The caller reports it if BOTH
     *                routes are out, which is the only point at which anybody
     *                needs to hear about it.
     */
    private Outcome dispatch(String command, String named, boolean explain) {
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) ? Outcome.SHOWN : Outcome.FAILED;
        } catch (RuntimeException e) {
            // The CAUSE, not the message. Bukkit wraps whatever escaped in a
            // CommandException reading "Unhandled exception executing '<the
            // command>' in VanillaCommandWrapper(minecraft:dialog)", which
            // names the command we already know and nothing about the fault -
            // logging only that cost a round trip of guessing. The chain is
            // walked because the useful frame is usually two down, and the
            // whole throwable goes to the log so the stack names the class
            // inside the game that actually threw.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (isRefusal(root)) {
                if (explain) {
                    Bukkit.getLogger().info("[RPEngine] the game would not take "
                            + (named == null ? "that dialog" : named) + ": "
                            + (root.getMessage() == null ? root.getClass().getName() : root.getMessage()));
                }
                return Outcome.REFUSED;
            }
            Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                    "[RPEngine] " + command + " failed: " + root.getClass().getName()
                            + (root.getMessage() == null ? "" : ": " + root.getMessage()),
                    e);
            return Outcome.FAILED;
        }
    }
}
