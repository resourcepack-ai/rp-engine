package ai.resourcepack.engine;

import ai.resourcepack.engine.core.command.ChatStyle;
import ai.resourcepack.engine.core.command.EngineCommand;
import ai.resourcepack.engine.core.emote.EmoteDirector;
import ai.resourcepack.engine.core.model.DisplayCarry;
import ai.resourcepack.engine.core.model.RigPlacementListener;
import ai.resourcepack.engine.core.model.RigTags;
import ai.resourcepack.engine.core.model.Seats;
import ai.resourcepack.engine.core.version.Compatibility;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Reading config.yml into the places that hold a setting.
 *
 * <p>Its own class because these are the whole of what {@code /rp reload}
 * means beyond rebuilding the content, and because every one of them is a
 * one-way write into somewhere else — a static on the emote director, a
 * calibration on {@link Seats}, the chat palette. Grouped, the set of things a
 * reload actually touches can be read in one screen; scattered through an
 * entrypoint, the answer to "does this setting take effect without a restart"
 * is a search.
 *
 * <p>Not everything applied here is config. {@code DisplayCarry} and
 * {@code RigTags} are resolved from the server's version rather than from a
 * preference, and they are set alongside the settings because they answer the
 * same question the settings do: what should the engine do on this server,
 * decided once and handed to the things that need it.
 */
final class EngineOptions {

    private EngineOptions() {
    }

    /** Hands {@link Seats} the one number a server may have to look at to set. */
    static void seatOffset(FileConfiguration config, Seats seats) {
        if (seats != null) {
            seats.calibrate(config.getDouble("models.seat-offset", 0.0));
        }
    }

    /**
     * How a rig's held item is turned in its hand. <b>Calibration only.</b>
     *
     * <p>The defaults here are the answer as far as anybody knows, and a server
     * owner has no reason to set these. They exist because this turn has been
     * wrong four times and no test can see a rig: they make it something that
     * can be found by looking, with {@code /rpengine reload} between tries,
     * rather than one rebuild per guess. See {@code HeldItem.orient}.
     *
     * <p>Defaulted to the field values rather than to literals, so the answer
     * is stated once, in the class that explains it.
     */
    static void emotes(FileConfiguration config, Compatibility compatibility, Plugin plugin) {
        EmoteDirector.heldItemTurn(
            (float) config.getDouble("emotes.held-item-pitch", 90.0),
            (float) config.getDouble("emotes.held-item-yaw", 0.0),
            (float) config.getDouble("emotes.held-item-roll", 0.0));
        EmoteDirector.nameTagsSeeThrough(
            config.getBoolean("emotes.nametag-see-through", false));
        // Not config: whether a moved rig can be asked to glide is the
        // server's version, not a preference. See DisplayCarry.
        EmoteDirector.displayCarry(DisplayCarry.forServer(
            compatibility, EmoteDirector.interpolationTicks()));
        // Both holders of the same decision: where a rig part's identity is
        // carried. One object, so the two can never disagree about a stack.
        RigTags rigTags = RigTags.forServer(compatibility, plugin);
        EmoteDirector.rigTags(rigTags);
        RigPlacementListener.tags(rigTags);
    }

    static void chatStyle(FileConfiguration config) {
        // Read with no fallbacks: an absent key arrives as null and ChatStyle
        // falls back to its own default, so the defaults live in one place
        // rather than being restated here and in config.yml.
        EngineCommand.style(ChatStyle.of(
                config.getString("chat.prefix"),
                config.getString("chat.colour.prefix"),
                config.getString("chat.colour.brackets"),
                config.getString("chat.colour.body"),
                config.getString("chat.colour.accent"),
                config.getString("chat.colour.error"),
                config.getString("chat.colour.success")));
    }
}
