package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which dialogs a dialog turns to through {@code /rp page}. Internal.
 *
 * <h2>What it is for</h2>
 *
 * A dialog can have PAGES. A settings screen whose sidebar goes from Interface to
 * Audio is two dialogs, each drawn with its own sidebar button lit, and a click
 * on the sidebar has to open the other one. Neither way the game offers will do
 * it for a dialog that arrived in a push: {@code minecraft:show_dialog} names the
 * next page by a registry id that does not exist until the next restart — and
 * the server resolves that id while decoding the dialog HOLDING the button, so
 * the whole first page refuses to open over it — and writing the next page out
 * inside the click, which is what a link to another dialog does, never ends when
 * the pages link back to each other, as a sidebar's always do. So a page's click
 * runs {@code /rp page <id>} as the player, and the engine opens the page itself,
 * with the values the dialog was opened with.
 *
 * <h2>Why a player may run it</h2>
 *
 * The argument {@code /rp var} rests on: the click is the player's, so the
 * command has to be theirs too. What it does is only what clicking could do. It
 * opens a dialog only when the dialog the engine last showed that player holds a
 * click running EXACTLY that command — so a page is reachable from the page
 * before it and from nowhere else, and a player who types the command by hand
 * gets what the button would have given them and nothing more. Opening some
 * other dialog on somebody is still {@code /rp dialog}, behind its permission.
 *
 * <h2>Still transported, not modelled</h2>
 *
 * A pass over the dialog's JSON TEXT, the way {@link DialogPlaceholders} is. A
 * command is a whole JSON string under the game's {@code command} key, so it is
 * found between its two quotes — which is also what keeps
 * {@code rp page studio:settings} from matching inside
 * {@code rp page studio:settings.audio}. Nothing here learns whether the string
 * was a footer button's command or a click on a line of the body.
 *
 * <h2>Turning without asking the server</h2>
 *
 * A {@code show_dialog} by id is handled by the CLIENT: it reads the dialog out
 * of the registry the server synced at join and opens it, with no round trip.
 * So once a page is in that registry, and the copy there is exactly the page
 * this player would be sent, its link can be a {@code show_dialog} and the page
 * turns at once. {@link #swap} is that rewrite; whether a page qualifies is
 * {@link DialogsImpl}'s and {@link DialogDatapack}'s question.
 */
public final class DialogLinks {

    private DialogLinks() {
    }

    /**
     * A page link: a {@code command} holding {@code rp page <namespace:id>} and
     * nothing else, the root spelled any way the plugin answers to and the slash
     * optional, because an authored dialog writes its commands by hand.
     * {@code command} is the game's own key for both places a click can run one
     * — a click event in the body and a button's {@code run_command} action — so
     * a tooltip or a line of words that merely SAYS the command is not a link.
     */
    private static final Pattern LINK = Pattern.compile(
            "\"command\"[ ]*:[ ]*\"/?(?:rp|rpe|rpengine)[ ]+page[ ]+([a-z0-9_.-]+:[a-z0-9_./-]+)[ ]*\"",
            Pattern.CASE_INSENSITIVE);

    /** The command a link to {@code target} runs — what Studio writes into a page's clicks. */
    public static String command(ContentId target) {
        return "rp page " + target;
    }

    /** Whether a dialog's JSON holds a click that turns to {@code target}. */
    public static boolean opens(String json, ContentId target) {
        if (json == null || target == null) {
            return false;
        }
        String wanted = target.toString();
        Matcher m = LINK.matcher(json);
        while (m.find()) {
            if (m.group(1).toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** A whole command string that is a page link, and the page it names. */
    private static final Pattern WHOLE_LINK = Pattern.compile(
            "/?(?:rp|rpe|rpengine)[ ]+page[ ]+([a-z0-9_.-]+:[a-z0-9_./-]+)[ ]*", Pattern.CASE_INSENSITIVE);

    /** Written without HTML escaping, so a page's text reads the way it was sent. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * The JSON with every page link to an {@code allowed} page turned into a
     * {@code show_dialog} by id: a body click's {@code run_command} becomes
     * {@code {"action":"show_dialog","dialog":<id>}}, a footer button's
     * {@code minecraft:run_command} action becomes
     * {@code {"type":"minecraft:show_dialog","dialog":<id>}} — Studio's own two
     * spellings for its vanilla datapack. The string itself when nothing is
     * swapped (or the JSON cannot be read), so a dialog with no such link is
     * byte for byte what it was.
     *
     * <p>Deterministic, which the datapack depends on: the copy on disk and the
     * copy a player would be sent are compared as text.
     */
    public static String swap(String json, Predicate<ContentId> allowed) {
        if (json == null || allowed == null || !LINK.matcher(json).find()) {
            return json;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return json;
        }
        return swapIn(root, allowed) ? GSON.toJson(root) : json;
    }

    private static boolean swapIn(JsonElement element, Predicate<ContentId> allowed) {
        boolean changed = false;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                changed |= swapIn(child, allowed);
            }
            return changed;
        }
        if (!element.isJsonObject()) {
            return false;
        }
        JsonObject obj = element.getAsJsonObject();
        Optional<ContentId> target = linkIn(obj);
        if (target.isPresent() && allowed.test(target.get())) {
            obj.remove("command");
            if (obj.has("action")) {
                obj.addProperty("action", "show_dialog");
            } else {
                obj.addProperty("type", "minecraft:show_dialog");
            }
            obj.addProperty("dialog", target.get().toString());
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            changed |= swapIn(entry.getValue(), allowed);
        }
        return changed;
    }

    /** The page an object's click turns to, when the object IS a page link: a run_command whose command is one. */
    private static Optional<ContentId> linkIn(JsonObject obj) {
        JsonElement command = obj.get("command");
        if (command == null || !command.isJsonPrimitive() || !command.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        String kind = obj.has("action") ? text(obj.get("action")) : text(obj.get("type"));
        if (!"run_command".equals(kind) && !"minecraft:run_command".equals(kind)) {
            return Optional.empty();
        }
        Matcher m = WHOLE_LINK.matcher(command.getAsString().trim());
        return m.matches() ? ContentId.parse(m.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    private static String text(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    /**
     * Whether a dialog's JSON holds a click running exactly {@code command}, with
     * or without its slash — which is how the engine tells which page a player
     * is on when the client turned to it without asking.
     */
    public static boolean holdsCommand(String json, String command) {
        if (json == null || command == null) {
            return false;
        }
        String bare = command.startsWith("/") ? command.substring(1) : command;
        if (bare.isBlank()) {
            return false;
        }
        for (String spelled : new String[] {bare, "/" + bare}) {
            // The command as a JSON string literal, quotes and all.
            Pattern p = Pattern.compile("\"command\"[ ]*:[ ]*" + Pattern.quote(GSON.toJson(spelled)));
            if (p.matcher(json).find()) {
                return true;
            }
        }
        return false;
    }

    /** Every dialog a dialog's JSON turns to that way, each once, in the order it first names them. */
    public static List<ContentId> targets(String json) {
        if (json == null) {
            return List.of();
        }
        Set<ContentId> out = new LinkedHashSet<>();
        Matcher m = LINK.matcher(json);
        while (m.find()) {
            ContentId.parse(m.group(1).toLowerCase(Locale.ROOT)).ifPresent(out::add);
        }
        return List.copyOf(out);
    }
}
