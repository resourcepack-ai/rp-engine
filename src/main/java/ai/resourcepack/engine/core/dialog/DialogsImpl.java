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
 * the console, later, once the server has read that datapack.
 *
 * <p><b>A command rather than an API call</b> — {@code /dialog show} — because
 * Bukkit has no dialog API at all and Paper's is Paper's. The engine compiles
 * against Spigot and runs on both, and a vanilla command that has existed
 * since the feature did works the same on every server that has the feature.
 * The cost is that opening one is a console dispatch; the benefit is that
 * nothing here has to be written twice.
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
    public boolean show(Player viewer, ContentId id) {
        if (!supported || viewer == null || !viewer.isOnline()) {
            return false;
        }
        Optional<DialogInfo> found = info(id);
        if (found.isEmpty()) {
            return false;
        }
        if (found.get().fromPushedPack() && !pushedAudience.test(viewer)) {
            return false;
        }
        // Quoted as a selector rather than a name: a player whose name has
        // changed between login and now is still exactly one UUID, and the
        // command takes an entity selector wherever it takes a player.
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "minecraft:dialog show " + viewer.getName() + " " + id.namespace() + ":" + id.path());
    }

    @Override
    public void close(Player viewer) {
        if (!supported || viewer == null || !viewer.isOnline()) {
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:dialog clear " + viewer.getName());
    }
}
