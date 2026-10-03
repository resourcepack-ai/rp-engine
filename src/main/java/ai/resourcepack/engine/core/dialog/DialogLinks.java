package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
