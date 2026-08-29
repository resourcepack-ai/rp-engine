package ai.resourcepack.engine.core;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a command named in a message clickable.
 *
 * <p>Every message this plugin sends is written as an ordinary string, and
 * several of them tell somebody to run something — "/rp sync &lt;code&gt;
 * first", "/rp liquid corner". Left as text, that is an instruction to go and
 * type sixteen characters correctly, which on a phone or a controller is a
 * real ask and in every case is a step nobody enjoys.
 *
 * <p>So the string stays the interface for the code, and the last thing before
 * it goes out finds the commands in it and hands them a click. Two behaviours,
 * decided by what the command says rather than by the caller:
 *
 * <ul>
 *   <li>A command with a <strong>placeholder</strong> in it —
 *       {@code <code>}, {@code [player]}, {@code a|b} — is
 *       <strong>suggested</strong>: it goes into the chat box for them to
 *       finish, because running it as written would fail.</li>
 *   <li>Anything else is <strong>run</strong> on click.</li>
 * </ul>
 *
 * <h2>Knowing where a command ends</h2>
 *
 * <p>The hard part, and the reason this is not a regex. "/rp sync &lt;code&gt;
 * first." — a rule that swallowed following words would make the command
 * "/rp sync &lt;code&gt; first", which suggests nonsense.
 *
 * <p>So a command extends only over words the command layer actually has:
 * {@link #vocabulary} is every subcommand and verb, derived from the help
 * rather than listed here, plus anything that is obviously an argument (a
 * placeholder, an {@code id:with:colons}, a number). The first word that is
 * none of those ends the command, which is almost always the moment the
 * sentence resumes.
 */
public final class Chat {

    /**
     * The commands this plugin owns. Anything else beginning with a slash is
     * somebody else's and is left as text.
     */
    private static final Set<String> ROOTS = Set.of("rpengine", "rpe", "rp", "emote", "emotereply");

    /**
     * Words that are part of a command but appear in no help line.
     *
     * <p>Small on purpose. Everything a subcommand is called comes from
     * {@link #vocabulary(Set)}; these are the handful of literal arguments
     * that only ever appear inside a sentence — {@code distribute off},
     * {@code hud clear}.
     */
    private static final Set<String> LITERALS = Set.of("off", "on", "clear", "list", "stop");

    private static final Pattern ROOT = Pattern.compile("/([a-z]+)", Pattern.CASE_INSENSITIVE);

    /** Every word the command layer knows. Replaced on a reload. */
    private static volatile Set<String> vocabulary = Set.of();

    private Chat() {
    }

    /**
     * Adopts the command layer's vocabulary. Called once at startup.
     *
     * <p>Derived rather than written down for the same reason the router's
     * subcommand list is: a command that exists without a line of help is a
     * bug elsewhere, and one whose name is spelled twice goes stale.
     */
    public static void vocabulary(Set<String> words) {
        Set<String> all = new LinkedHashSet<>(LITERALS);
        if (words != null) {
            for (String word : words) {
                for (String part : word.toLowerCase(Locale.ROOT).split("\\s+")) {
                    if (!part.isEmpty()) {
                        all.add(part);
                    }
                }
            }
        }
        vocabulary = Set.copyOf(all);
    }

    /**
     * Sends a line, with any command in it clickable.
     *
     * <p>A console sender is sent the plain string: there is nothing there to
     * click, and a component message would only cost it its colours.
     */
    public static void send(CommandSender who, String line) {
        if (!(who instanceof Player)) {
            who.sendMessage(line);
            return;
        }
        ((Player) who).spigot().sendMessage(linkify(line));
    }

    /**
     * The line as components, with each command a click target.
     *
     * <p>Public for the places that need to put one inside a bigger message
     * rather than send it.
     */
    public static BaseComponent[] linkify(String line) {
        List<BaseComponent> out = new ArrayList<>();
        Matcher slash = ROOT.matcher(line);
        int from = 0;
        // The colour a command sits in the middle of, so what follows it does
        // not inherit the command's own.
        String carried = "";

        while (slash.find()) {
            if (slash.start() < from || !ROOTS.contains(slash.group(1).toLowerCase(Locale.ROOT))) {
                continue;
            }
            int end = endOf(line, slash.end());
            String command = line.substring(slash.start(), end);

            String before = line.substring(from, slash.start());
            addLegacy(out, carried + before);
            carried = trailingColour(carried + before);
            out.add(clickable(command));
            from = end;
        }
        addLegacy(out, carried + line.substring(from));
        return out.toArray(new BaseComponent[0]);
    }

    /**
     * Where the command starting at {@code at} stops.
     *
     * <p>Word by word, keeping only what the command layer would recognise.
     * See the class note: this is the whole difficulty.
     */
    private static int endOf(String line, int at) {
        int end = at;
        while (end < line.length()) {
            int start = end;
            while (start < line.length() && line.charAt(start) == ' ') {
                start++;
            }
            if (start == end || start >= line.length()) {
                break;
            }
            int stop = start;
            while (stop < line.length() && line.charAt(stop) != ' ') {
                stop++;
            }
            String word = strip(line.substring(start, stop));
            if (!partOfCommand(word)) {
                break;
            }
            end = start + word.length();
        }
        return end;
    }

    /** Whether a word belongs to the command rather than to the sentence. */
    private static boolean partOfCommand(String word) {
        if (word.isEmpty()) {
            return false;
        }
        char first = word.charAt(0);
        if (first == '<' || first == '[' || word.equals("|")) {
            return true;
        }
        if (word.indexOf(':') > 0 || word.chars().allMatch(Character::isDigit)) {
            return true;
        }
        // A choice written as one word — accept|deny, id|clear — is part of it
        // if any side of it is.
        for (String side : word.split("\\|")) {
            if (!side.isEmpty() && !vocabulary.contains(side.toLowerCase(Locale.ROOT))
                    && side.charAt(0) != '<' && side.charAt(0) != '[') {
                return false;
            }
        }
        return true;
    }

    /** Punctuation the sentence owns rather than the command. */
    private static String strip(String word) {
        int end = word.length();
        while (end > 0 && ".,;:!?".indexOf(word.charAt(end - 1)) >= 0) {
            end--;
        }
        return word.substring(0, end);
    }

    /** One command, coloured, underlined, and wired to a click. */
    private static TextComponent clickable(String command) {
        boolean fillIn = command.indexOf('<') >= 0 || command.indexOf('[') >= 0
                || command.indexOf('|') >= 0;

        TextComponent shown = new TextComponent(TextComponent.fromLegacyText(command));
        shown.setUnderlined(true);
        shown.setClickEvent(new ClickEvent(
                fillIn ? ClickEvent.Action.SUGGEST_COMMAND : ClickEvent.Action.RUN_COMMAND,
                command));
        shown.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(fillIn ? "Click to fill this in" : "Click to run this")));
        return shown;
    }

    private static void addLegacy(List<BaseComponent> out, String text) {
        if (!text.isEmpty()) {
            out.addAll(List.of(TextComponent.fromLegacyText(text)));
        }
    }

    /**
     * The colour codes still in force at the end of a piece of text.
     *
     * <p>Carried across a command so the rest of the sentence is the colour it
     * was, rather than whatever the last legacy code before the command left
     * behind — which is the one thing that goes visibly wrong if this class
     * splits a line in the wrong place.
     */
    private static String trailingColour(String text) {
        String colour = "";
        String formats = "";
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != ChatColor.COLOR_CHAR) {
                continue;
            }
            char code = Character.toLowerCase(text.charAt(i + 1));
            if ("0123456789abcdef".indexOf(code) >= 0) {
                colour = String.valueOf(ChatColor.COLOR_CHAR) + code;
                formats = "";
            } else if (code == 'r') {
                colour = "";
                formats = "";
            } else if ("klmno".indexOf(code) >= 0) {
                formats += String.valueOf(ChatColor.COLOR_CHAR) + code;
            } else if (code == 'x') {
                // A hex colour: §x§r§r§g§g§b§b. Taken whole, since the six
                // codes after it mean nothing on their own.
                int end = Math.min(text.length(), i + 14);
                colour = text.substring(i, end);
                formats = "";
                i = end - 1;
            }
        }
        return colour + formats;
    }
}
