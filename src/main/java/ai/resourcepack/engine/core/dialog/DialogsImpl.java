package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.DialogInfo;
import ai.resourcepack.engine.api.Dialogs;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

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

    private volatile Map<ContentId, DialogInfo> dialogs = Map.of();

    /**
     * Who is holding a pushed pack. Everybody, until told otherwise.
     *
     * <p>Same gate the overlays have and for the same reason: a pushed
     * dialog's picture is a glyph that only exists in the pack Studio sent to
     * one player, so opening it for anybody else is a screen of missing-glyph
     * boxes. The engine's own content is not gated — a server's bundle is what
     * its players are already wearing.
     */
    private volatile Predicate<Player> pushedAudience = viewer -> true;

    public DialogsImpl(DialogDatapack datapack, boolean supported) {
        this.datapack = datapack;
        this.supported = supported;
    }

    /** Says who may be shown a dialog whose art came from a pushed pack. */
    public void audience(Predicate<Player> holdsPushedPack) {
        this.pushedAudience = holdsPushedPack == null ? viewer -> true : holdsPushedPack;
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
        Optional<DialogInfo> found = info(id);
        if (found.isEmpty()) {
            return false;
        }
        return !found.get().fromPushedPack() || pushedAudience.test(viewer);
    }

    @Override
    public boolean show(Player viewer, ContentId id) {
        if (!canShow(viewer, id)) {
            return false;
        }
        DialogInfo info = dialogs.get(id);
        if (info == null) {
            return false;
        }
        String named = id.namespace() + ":" + id.path();

        // Decode JSON directly so multiline text and other values need not
        // pass through the command parser or wait for the datapack registry.
        if (DialogPackets.show(viewer, info.json())) return true;

        // The dialog itself, in the command. Nothing needs to be in the
        // registry for this, so nothing needs a restart — see the class note.
        Optional<String> inline = DialogSnbt.of(info.json());
        if (inline.isPresent() && dispatch("minecraft:dialog show " + viewer.getName() + " " + inline.get(),
                named, false) == Outcome.SHOWN) {
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

    @Override
    public void close(Player viewer) {
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
