package ai.resourcepack.engine.core;

import ai.resourcepack.engine.api.EmoteMessages;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.logging.Logger;

/**
 * Whoever is running this library: the plugin whose scheduler, listeners and
 * persistent-data namespace the engine borrows.
 *
 * <p>Everything the engine used to reach for on {@code PresencePlugin} it now
 * asks for here, which is the whole of what made the engine a part of one
 * plugin rather than a library.
 *
 * <p><b>The namespace is the host's plugin name</b>, because that is what
 * {@code new NamespacedKey(plugin, name)} does. That is deliberate and it has a
 * consequence worth stating: rig state lives on entities in the world, so two
 * different hosts running the engine on one server keep separate state and
 * neither can see the other's rigs - which is the safe direction, since sharing
 * it would mean two animators fighting over the same entities. It also means
 * the ResourcePack AI plugin must keep its own name for ever, or every rig
 * placed by an older jar goes still.
 *
 * <p>Internal. Not part of the supported API.
 */
public final class Host {

    private final Plugin plugin;
    private final File dataFolder;
    private final EmoteMessages messages;
    private final String castPermission;

    /**
     * @param messages       what an emote says to somebody who did not run the
     *                       command. Null says nothing, which is the right
     *                       default: an engine that chose the wording would be
     *                       choosing the palette and the language for every
     *                       server that runs it
     * @param castPermission the node somebody needs to pull other players into
     *                       an emote, or null for no check at all
     */
    public Host(Plugin plugin, File dataFolder, EmoteMessages messages, String castPermission) {
        this.plugin = plugin;
        this.dataFolder = dataFolder;
        this.messages = messages;
        this.castPermission = castPermission;
    }

    /** The plugin the engine schedules tasks and registers listeners against. */
    public Plugin plugin() {
        return plugin;
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    /** Where manifests are persisted. Created if it isn't there. */
    public File dataFolder() {
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
            logger().warning("Couldn't create " + dataFolder + " - content won't survive a restart.");
        }
        return dataFolder;
    }

    /** A persistent-data key in this host's namespace. */
    public NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }


    /** What to tell players mid-emote, or null to say nothing. */
    public EmoteMessages messages() {
        return messages;
    }

    /**
     * Whether this player may pull others into an emote.
     *
     * <p>True when the host configured no permission at all: a library has no
     * business inventing a permission node on somebody's server, so the gate
     * exists only where a host asked for one. The ResourcePack AI plugin passes
     * {@code resourcepackai.emote.multi}, which is op-default.
     */
    public boolean mayLeadCast(Player player) {
        return castPermission == null || player.hasPermission(castPermission);
    }

    /** The permission node for leading a cast, or null. */
    public String castPermission() {
        return castPermission;
    }

    /**
     * Throws unless this is the main thread.
     *
     * <p>Bukkit entity access is not thread-safe and the failure is silent
     * corruption rather than an exception, so this asks loudly instead. Every
     * caller is somebody else's plugin, which has no way to know that the
     * library's own network paths hop back via {@code runTask} for the same
     * reason.
     */
    public static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "The ResourcePack AI library must be used on the main thread - "
                            + "wrap this in Bukkit.getScheduler().runTask(yourPlugin, ...)");
        }
    }
}
