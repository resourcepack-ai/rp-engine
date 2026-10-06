package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Opening the screens a pack declares — Minecraft 1.21.6's dialogs.
 *
 * <p>Obtained from {@code RPEngineAPI.dialogs()}. Everything here is
 * main-thread only: showing a dialog dispatches a command.
 *
 * <p><b>A dialog needs 1.21.6.</b> On anything older {@link #supported()} is
 * false and {@link #show} answers false without doing anything — the engine
 * still loads the definitions and still lists them, so an addon can say "this
 * server is too old for that" rather than finding out from a stack trace.
 *
 * <p><b>It does not need a restart.</b> A dialog is registry data, and the
 * server builds that registry when it loads its world, before any plugin is
 * enabled — so for a while a dialog written by this load could not be opened
 * until the next start. It no longer works that way: the engine sends the
 * whole dialog inside {@code /dialog show}, which has accepted one written out
 * in full since the version this feature needs. A dialog loaded, pushed or
 * edited a moment ago opens now.
 *
 * <h2>Live values</h2>
 *
 * A dialog may print <code>{name}</code> placeholders — in its title, a
 * tooltip, a button, the command a click runs, and in a Studio dialog's
 * picture too, where words with a placeholder in them are drawn by the game
 * rather than baked into the art. They are filled for each player as the
 * dialog opens, from the first of these that answers:
 *
 * <ol>
 *   <li>the values the dialog was opened with ({@link #show(Player, ContentId, Map)},
 *       or {@code name=value} on {@code /rp dialog});</li>
 *   <li>the player's own settings — what a Studio dialog's bound switches and
 *       sliders show ({@link #setting});</li>
 *   <li>what a plugin published for the player with {@link #set} — the same
 *       values {@link Overlays#set} publishes, because a number called
 *       {@code coins} means one thing on a HUD and in a shop;</li>
 *   <li>the engine's built-ins ({@code {player}}, {@code {health}},
 *       {@code {world}} …, all about the player it is shown to);</li>
 *   <li>PlaceholderAPI, if the server has it.</li>
 * </ol>
 *
 * A name none of them answers is left as written. A dialog is drawn once, as it
 * opens: a value that changes afterwards shows the next time it opens, which
 * {@link #reopen} does on purpose.
 */
public interface Dialogs {

    /** Every dialog id, sorted. */
    Collection<ContentId> ids();

    /** What the pack said a dialog is. */
    Optional<DialogInfo> info(ContentId id);

    /** Whether this server's Minecraft version has dialogs at all. */
    boolean supported();

    /**
     * Whether a dialog on disk is not yet in the server's registry.
     *
     * <p>True from the moment a load writes something the running server has
     * not read, until the server is restarted.
     *
     * <p><b>This no longer gates anything.</b> It once meant "these will not
     * open yet"; it now means only that the ids are not addressable from
     * OUTSIDE the engine — from somebody's own datapack, a command block, or a
     * hand-written {@code minecraft:show_dialog} — because that is the one
     * thing a registry entry is still needed for. {@link #show} works either
     * way, so do not check this before calling it.
     */
    boolean pending();

    /**
     * Whether {@link #show} would get as far as the client.
     *
     * <p>Answers the half of a failure this engine knows for certain: the
     * version, whether the dialog exists, whether the player is online, and
     * whether they are holding the pack its art is in. What it cannot promise
     * is that the game will accept the dialog — that is the game's own codec
     * reading somebody's JSON, and the only way to find out is to send it.
     *
     * <p>It is here because the alternative is guessing. {@code show} returning
     * false used to be all a caller had, so "the player is wearing the server's
     * own pack" and "the server has not restarted" were one answer, and the
     * message an owner got named whichever the caller happened to check first.
     */
    boolean canShow(Player viewer, ContentId id);

    /**
     * Opens a dialog on a player's screen.
     *
     * @return false if there is no such dialog, the server is too old, the
     *         player is holding no pack the dialog's art is in, or the game
     *         refused the dialog itself — which is a fault in its JSON, and is
     *         reported to the console with what the game made of it
     */
    boolean show(Player viewer, ContentId id);

    /**
     * Opens a dialog on a player's screen, with its <code>{placeholders}</code>
     * filled from {@code values} first.
     *
     * <p>A dialog may say <code>{target}</code> anywhere a string goes — its
     * title, a tooltip, a button's label, the command a click runs — and this is
     * how it is told what that is: a punish menu opened with
     * {@code Map.of("target", "Steve")} is titled "Punish Steve" and its reasons
     * run {@code mute Steve 1h}. A name not in {@code values} is asked of the
     * same built-ins an overlay's are (<code>{player}</code>, <code>{ping}</code>,
     * <code>{world}</code>…, all about the VIEWER) and then of PlaceholderAPI;
     * one that nothing answers is left as written. Names are matched without
     * regard to case. {@link #show(Player, ContentId)} is this with no values.
     *
     * <p>A Studio dialog's picture is filled too, where its author wrote a
     * placeholder into the words — those are drawn by the game, not baked into
     * the art. A value there is kept to the characters the game's bitmap font
     * draws (anything else becomes {@code ?}) and to the room the words have,
     * ending in an ellipsis when it is cut. A dialog opened by its registry id
     * rather than as itself — the fallback for one the command line cannot
     * carry — is not filled at all, because the registry holds it unfilled.
     *
     * @return what {@link #show(Player, ContentId)} returns
     */
    default boolean show(Player viewer, ContentId id, Map<String, String> values) {
        return show(viewer, id);
    }

    /** As {@link #show(Player, ContentId)}, from the text form of an id. False for one that does not parse. */
    default boolean show(Player viewer, String id) {
        return ContentId.parse(id).map(parsed -> show(viewer, parsed)).orElse(false);
    }

    /** As {@link #show(Player, ContentId, Map)}, from the text form of an id. False for one that does not parse. */
    default boolean show(Player viewer, String id, Map<String, String> values) {
        return ContentId.parse(id).map(parsed -> show(viewer, parsed, values)).orElse(false);
    }

    /** Closes whatever dialog a player has open. */
    void close(Player viewer);

    /**
     * Opens the dialog this player was last shown again, with the values it was
     * opened with — so every placeholder is read afresh. What to call after
     * something on it changed: a store whose Buy button runs your command
     * reopens it, and the balance on it is the new one.
     *
     * <p>The server is not told when a player closes a dialog, so this opens it
     * whether or not it is still on their screen. Call it in answer to a click
     * on the dialog, not on a timer.
     *
     * @return false when there is nothing to reopen, or {@link #show} would refuse
     */
    default boolean reopen(Player viewer) {
        return false;
    }

    /**
     * Sets a value this player's dialogs can print as <code>{name}</code>.
     *
     * <p><b>The same values {@link Overlays#set} sets</b> — one set per player
     * for both, so a value your plugin publishes for a HUD is already in every
     * dialog and the other way round. A null value removes it. Safe from any
     * thread: it records the value and draws nothing. A dialog already open
     * keeps what it showed; {@link #reopen} draws it again.
     *
     * <p>The values a dialog was opened with, and the player's own settings,
     * win over this — see the interface note for the order.
     */
    default void set(Player viewer, String name, String value) {
    }

    /** What {@link #set} (or {@link Overlays#set}) last put there, if anything. */
    default Optional<String> value(Player viewer, String name) {
        return Optional.empty();
    }

    /**
     * Fills a LIST in this player's dialogs: a list called {@code warps}, drawn
     * in Studio as rows, shows {@code values} one to a row and hides the rows
     * past the end. Sets {@code warps_1}, {@code warps_2}… and
     * {@code warps_count} through {@link #set}, and removes the items a longer
     * list set before. A null or empty list empties it.
     *
     * <pre>
     * engine.dialogs().list(player, "warps", List.of("Spawn", "Shop", "Arena"));
     * engine.dialogs().show(player, "studio:warps");
     * </pre>
     */
    default void list(Player viewer, String name, java.util.List<String> values) {
        java.util.List<Map<String, String>> rows = new java.util.ArrayList<>();
        if (values != null) {
            for (String value : values) {
                rows.add(Map.of("", value == null ? "" : value));
            }
        }
        listRows(viewer, name, rows);
    }

    /**
     * A list whose rows have more than one field — a warp's name and what it is
     * for. Each map's {@code ""} entry is the row's own value
     * ({@code {warps_3}}, which decides whether the row shows at all) and every
     * other entry a field of it: {@code "note"} is {@code {warps_3_note}}, which
     * the row's template writes as {@code {warps_note}}.
     */
    default void listRows(Player viewer, String name, java.util.List<Map<String, String>> rows) {
        if (viewer == null || name == null || name.isEmpty()) {
            return;
        }
        int before = value(viewer, name + "_count").map(c -> {
            try {
                return Integer.parseInt(c.trim());
            } catch (NumberFormatException notACount) {
                return 0;
            }
        }).orElse(0);
        int count = rows == null ? 0 : rows.size();
        for (int k = 1; k <= count; k++) {
            for (Map.Entry<String, String> field : rows.get(k - 1).entrySet()) {
                String key = field.getKey() == null || field.getKey().isEmpty() ? name + "_" + k : name + "_" + k + "_" + field.getKey();
                set(viewer, key, field.getValue());
            }
        }
        for (int k = count + 1; k <= before; k++) {
            set(viewer, name + "_" + k, null);
        }
        set(viewer, name + "_count", String.valueOf(count));
    }

    /**
     * One of the player's own settings: the value a Studio dialog's bound
     * switch, slider or choice shows them, which they set by clicking it. Kept
     * on the player, across restarts. PlaceholderAPI reads the same value as
     * {@code %rpengine_var_<name>%}.
     */
    default Optional<String> setting(Player viewer, String name) {
        return Optional.empty();
    }

    /**
     * Sets one of the player's own settings, as if they had clicked it — any
     * name, not only one a dialog declares, because your plugin is not the
     * player. A null value clears it.
     *
     * <p>Names are lower-case letters, digits and {@code _}, starting with a
     * letter, up to 32; values are letters, digits and {@code _ . -}, up to 32;
     * a player keeps at most 64. Nothing reopens: call {@link #reopen} if they
     * are looking at the dialog.
     *
     * @return false when the name or value is not one a setting can be, or the
     *         player already has as many as they can keep
     */
    default boolean setSetting(Player viewer, String name, String value) {
        return false;
    }

    /**
     * Shows a container of your own in Studio dialogs: every grid of slots set
     * to show the container "from a plugin" called {@code name} shows what
     * {@code container} holds for whoever opens the dialog, and — where the grid
     * lets items move — lets them move items in and out of it. See
     * {@link DialogContainer}. Registering a name again replaces it; a null
     * container takes it away.
     *
     * @param name lower-case letters, digits, {@code _} and {@code -}, up to 24 —
     *             what the author types in Studio
     * @return false when the name is not one a container can have
     */
    default boolean container(String name, DialogContainer container) {
        return false;
    }
}
