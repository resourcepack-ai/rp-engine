package ai.resourcepack.engine.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A dialog — the data-driven screen a server opens on a client, added in
 * Minecraft 1.21.6.
 *
 * <p><strong>The engine does not model what is on it.</strong> A dialog is
 * defined by a JSON object whose schema is the game's, moves with the game, and
 * is read by the client rather than by us — so this carries that object as
 * TEXT and hands it to the game unread. Everything the engine does with a
 * dialog it can do without knowing whether the thing has three buttons or a
 * slider on it: write the file, and name it in a command.
 *
 * <p>That is deliberate rather than lazy. The alternative is a second
 * implementation of Minecraft's dialog codec living here, drifting from the
 * real one release by release, and turning every new field into a plugin
 * update before anybody can use it. What the engine adds is the part the game
 * does not: a NAME. A dialog in a datapack is addressed by a namespaced id
 * somebody has to type; one registered here is {@code mypack:shop}, listed by
 * {@code /rp dialogs} and openable from the API like any other content.
 *
 * <p>Two things about a dialog are worth knowing from outside this class.
 * A dialog is <em>registry</em> data, so it reaches a client through a
 * datapack rather than a resource pack — see the dialog datapack writer for
 * what that costs. And a pack drawn in Studio puts its picture in the dialog's
 * body as a font glyph, which means the art is in the resource pack and the
 * dialog is not: a player who has one without the other sees a screen with the
 * right buttons and no picture, or a picture with nothing to draw it in.
 */
public final class DialogInfo {

    private final ContentId id;
    private final String json;
    private final String name;
    private final boolean pushed;
    private final Map<String, List<String>> variables;
    private final Map<String, Integer> itemIcons;
    private final String command;
    private final String permission;
    private final ClickSound clickSound;
    private final List<CommandSound> clickSounds;
    private final boolean closesAfterClick;
    private final QuickAction quickAction;

    /** A command a dialog may be opened with: what a player types after the slash. */
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_-]{1,32}");

    /** A permission node: dotted words. */
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.*-]{1,96}");

    private DialogInfo(ContentId id, String json, String name, boolean pushed, Map<String, List<String>> variables) {
        this(id, json, name, pushed, variables, Map.of());
    }

    private DialogInfo(ContentId id, String json, String name, boolean pushed, Map<String, List<String>> variables,
                       Map<String, Integer> itemIcons) {
        this(id, json, name, pushed, variables, itemIcons, null, null);
    }

    private DialogInfo(ContentId id, String json, String name, boolean pushed, Map<String, List<String>> variables,
                       Map<String, Integer> itemIcons, String command, String permission) {
        this(id, json, name, pushed, variables, itemIcons, command, permission, null, List.of(), false, null);
    }

    private DialogInfo(ContentId id, String json, String name, boolean pushed, Map<String, List<String>> variables,
                       Map<String, Integer> itemIcons, String command, String permission,
                       ClickSound clickSound, List<CommandSound> clickSounds, boolean closesAfterClick,
                       QuickAction quickAction) {
        this.id = Objects.requireNonNull(id, "id");
        this.json = json == null ? "{}" : json;
        this.name = name == null || name.isEmpty() ? id.path() : name;
        this.pushed = pushed;
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(k, List.copyOf(v));
                }
            });
        }
        this.variables = Map.copyOf(copy);
        Map<String, Integer> icons = new LinkedHashMap<>();
        if (itemIcons != null) {
            itemIcons.forEach((k, v) -> {
                if (k != null && v != null) {
                    icons.put(k, v);
                }
            });
        }
        this.itemIcons = Map.copyOf(icons);
        this.command = commandName(command);
        this.permission = this.command == null || permission == null || !PERMISSION.matcher(permission.trim()).matches()
                ? null : permission.trim();
        this.clickSound = clickSound;
        List<CommandSound> sounds = new java.util.ArrayList<>();
        if (clickSounds != null) {
            for (CommandSound sound : clickSounds) {
                if (sound != null) {
                    sounds.add(sound);
                }
            }
        }
        this.clickSounds = List.copyOf(sounds);
        this.closesAfterClick = closesAfterClick;
        this.quickAction = quickAction;
    }

    /**
     * The same dialog, opened by {@code /<command>} as well — for whoever types
     * it, or only for those with {@code permission} when it names one. A command
     * that is not a plain word ({@code a-z 0-9 _ -}, up to 32, any slash taken
     * off) is no command at all.
     */
    public DialogInfo withCommand(String command, String permission) {
        return new DialogInfo(id, json, name, pushed, variables, itemIcons, command, permission, clickSound, clickSounds,
                closesAfterClick, quickAction);
    }

    /**
     * The same dialog, with what a click on its picture sounds like: {@code
     * clickSound} for any click, and {@code clickSounds} for the clicks that
     * run one of their commands, which come first. Null and empty leave the
     * engine's own click.
     */
    public DialogInfo withClickSounds(ClickSound clickSound, List<CommandSound> clickSounds) {
        return new DialogInfo(id, json, name, pushed, variables, itemIcons, command, permission, clickSound, clickSounds,
                closesAfterClick, quickAction);
    }

    /**
     * The same dialog, closed by the engine after a click — see
     * {@link #closesAfterClick()}. Its JSON should say {@code after_action:
     * none}, or the client closes it (or waits) by itself first.
     */
    public DialogInfo withClosesAfterClick(boolean closes) {
        return new DialogInfo(id, json, name, pushed, variables, itemIcons, command, permission, clickSound, clickSounds,
                closes, quickAction);
    }

    /**
     * The same dialog, on the game's Quick Actions key (G, Minecraft 1.21.6
     * and newer), or off it for null. See {@link #quickAction()}.
     */
    public DialogInfo withQuickAction(QuickAction quick) {
        return new DialogInfo(id, json, name, pushed, variables, itemIcons, command, permission, clickSound, clickSounds,
                closesAfterClick, quick);
    }

    /**
     * A command as a dialog may carry it: lower case, no slash, a plain word —
     * or null. Shared with Studio by rule rather than by code: Studio's editor
     * allows exactly this.
     */
    public static String commandName(String raw) {
        if (raw == null) {
            return null;
        }
        String word = raw.trim().toLowerCase(Locale.ROOT);
        if (word.startsWith("/")) {
            word = word.substring(1);
        }
        return COMMAND.matcher(word).matches() ? word : null;
    }

    /** One loaded from a content folder. */
    public static DialogInfo authored(ContentId id, String json, String name) {
        return new DialogInfo(id, json, name, false, Map.of());
    }

    /** One loaded from a content folder that declares variables — see {@link #variables()}. */
    public static DialogInfo authored(ContentId id, String json, String name, Map<String, List<String>> variables) {
        return new DialogInfo(id, json, name, false, variables);
    }

    /** One that arrived with a pushed Studio pack. */
    public static DialogInfo pushed(ContentId id, String json, String name) {
        return new DialogInfo(id, json, name, true, Map.of());
    }

    /** One that arrived with a pushed Studio pack and declares variables — see {@link #variables()}. */
    public static DialogInfo pushed(ContentId id, String json, String name, Map<String, List<String>> variables) {
        return new DialogInfo(id, json, name, true, variables);
    }

    /**
     * One that arrived with a pushed Studio pack, declares variables, and shows
     * live item slots — see {@link #itemIcons()}.
     */
    public static DialogInfo pushed(ContentId id, String json, String name, Map<String, List<String>> variables,
                                    Map<String, Integer> itemIcons) {
        return new DialogInfo(id, json, name, true, variables, itemIcons);
    }

    public ContentId id() {
        return id;
    }

    /** The {@code minecraft:dialog} object, as JSON. Written out verbatim. */
    public String json() {
        return json;
    }

    /** What to call it in a listing. */
    public String name() {
        return name;
    }

    /**
     * Whether this came from a pushed Studio pack rather than from the server's
     * own content folder.
     *
     * <p>Same gate a pushed overlay has, for the same reason: its picture is a
     * glyph that only exists in the pack that was pushed, so opening it for a
     * player wearing the server's own bundle is a screen of missing-glyph
     * boxes.
     */
    public boolean fromPushedPack() {
        return pushed;
    }

    /**
     * The player settings this dialog shows, each with every value it may take.
     *
     * <p>A Studio dialog can bind a control — a switch, a slider, a choice of
     * three — to a name, and draws it in whatever state each player's own
     * value says ({@code {name?value:…}} in its JSON). These are the names it
     * binds. They are also the whole of what a player may SET: {@code /rp var}
     * is run by the player whose click it is, so it accepts a name only if the
     * dialog they were last shown declares it, and a value only if that dialog
     * lists it. Empty for a dialog that binds nothing.
     */
    public Map<String, List<String>> variables() {
        return variables;
    }

    /**
     * Which picture draws each of its pack's own items in this dialog's live
     * item slots: the {@code custom_model_data} string a stack of the item
     * carries, to a cell of the icon sheet the same push's pack ships. A stack
     * whose string is here is drawn as itself rather than as the "no picture"
     * icon. Empty for a dialog with no live slots, or a pack with no items of
     * its own.
     */
    public Map<String, Integer> itemIcons() {
        return itemIcons;
    }

    /**
     * The command that opens it, {@code shop} for {@code /shop}: typed by a
     * player, it opens the dialog on them. Empty when it has none.
     */
    public Optional<String> command() {
        return Optional.ofNullable(command);
    }

    /** What a player needs to open it with its {@link #command()}; empty when anybody may. */
    public Optional<String> permission() {
        return Optional.ofNullable(permission);
    }

    /**
     * What a click on this dialog's picture sounds like. A click on a run of a
     * dialog's body text makes no sound in the game (only its buttons click),
     * so the engine plays one when such a click reaches the server: this, or
     * the server's configured click when it is empty. A {@link ClickSound}
     * with no sound is silence.
     */
    public Optional<ClickSound> clickSound() {
        return Optional.ofNullable(clickSound);
    }

    /**
     * The clicks with a sound of their own, by the command each runs, as the
     * dialog's JSON writes it ({@code {placeholders}} and all). Looked at
     * before {@link #clickSound()}; the first that matches is the one.
     */
    public List<CommandSound> clickSounds() {
        return clickSounds;
    }

    /**
     * Whether the engine closes this dialog after a click on it: "seamless".
     *
     * <p>Such a dialog goes to the client as {@code after_action: none}, so a
     * click leaves it on the screen exactly as it is, with no waiting screen and
     * no flash of the world. The engine then answers the click: a dialog it
     * opens takes this one's place, and when none does, the engine closes it.
     * A click that runs another plugin's command closes it BEFORE that command
     * runs, so whatever the command opens (its own dialog, a chest) arrives
     * after the close rather than being shut by it.
     *
     * <p>A click the server never hears of stays open: a link, a copy, and a
     * proxy's command ({@code /server}), which never reaches this server.
     */
    public boolean closesAfterClick() {
        return closesAfterClick;
    }

    /**
     * A click sound: a sound event, the game's ({@code ui.button.click}) or a
     * pack's ({@code custom.laser}), with its volume and pitch. An empty
     * {@code sound} is silence.
     */
    /**
     * Whether the Quick Actions key (G) opens this dialog, and how. The key is
     * the client's: it opens a dialog out of the registry the player was sent
     * as they joined, and the server never hears of the press, so a dialog on
     * it is written into a datapack and reaches the key after the next
     * restart.
     *
     * <p>Empty when it is not on the key.
     */
    public Optional<QuickAction> quickAction() {
        return Optional.ofNullable(quickAction);
    }

    /**
     * How a dialog is on the Quick Actions key.
     *
     * @param launcher false (the default): the key opens the dialog itself,
     *                 as it was for the player when they joined, so a
     *                 placeholder shows what it said then until they join
     *                 again. Paper only; anywhere else, and for a dialog with a
     *                 permission or the player's own items in it, this is the
     *                 launcher. True: the key opens a small screen with one
     *                 button that has the server open the dialog, drawn fresh,
     *                 like any other.
     * @param label    what the key's list and the launcher call it, or null
     *                 for the dialog's name
     */
    public record QuickAction(boolean launcher, String label) {
    }

    public record ClickSound(String sound, float volume, float pitch) {

        /**
         * A sound event as the server takes one: an optional namespace, then a
         * lower-case path. Studio's {@code CLICK_SOUND_EVENT} is the same rule.
         */
        public static final Pattern SOUND = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]{1,200}");

        /** What a click on a picture sounds like when nothing says otherwise: the game's button click, quietly. */
        public static final ClickSound GAME = new ClickSound("ui.button.click", 0.25f, 1f);

        /**
         * A click sound from what was written, or null when the sound is not
         * an event name (a typo should leave the click as it was rather than
         * silence it). The volume is kept to 0..1 and the pitch to the game's
         * 0.5..2; either missing is the {@link #GAME} click's.
         */
        public static ClickSound of(String sound, Number volume, Number pitch) {
            String key = sound == null ? "" : sound.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !SOUND.matcher(key).matches()) {
                return null;
            }
            float v = volume == null || !Float.isFinite(volume.floatValue()) ? GAME.volume : volume.floatValue();
            float p = pitch == null || !Float.isFinite(pitch.floatValue()) ? GAME.pitch : pitch.floatValue();
            return new ClickSound(key, Math.max(0f, Math.min(1f, v)), Math.max(0.5f, Math.min(2f, p)));
        }

        /** Whether this is the silence a dialog asked for. */
        public boolean silent() {
            return sound.isEmpty() || volume <= 0f;
        }
    }

    /** A click sound for the clicks that run {@code command}, as a dialog's JSON writes it. */
    public record CommandSound(String command, ClickSound sound) {
        public CommandSound {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(sound, "sound");
        }
    }

    @Override
    public String toString() {
        return "DialogInfo[" + id + "]";
    }
}
