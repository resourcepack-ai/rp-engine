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
 * <p>So a command extends only as far as it is still <strong>a real
 * command</strong>. {@link #signatures} holds every one the command layer
 * answers to, as its words, and a message word is taken only while what has
 * been taken so far is the beginning of one of them.
 *
 * <p><strong>That is a prefix match, not a word list, and the difference is
 * the whole bug this class used to have.</strong> A bag of known words cannot
 * tell "/rp distribute off" followed by the sentence "stop serving it" from a
 * command ending in "stop", because "stop" is a word the command layer knows —
 * just not <em>there</em>. Worse, it could not tell that "/rp sync accept" is
 * a command at all, because no line of help spells "accept", so it truncated
 * to "/rp sync" and — having no placeholder left in it — wired that to RUN
 * rather than SUGGEST. The invitation a player is sent said "click here" and
 * ran the wrong command.
 */
public final class Chat {

    /**
     * The commands this plugin owns. Anything else beginning with a slash is
     * somebody else's and is left as text.
     */
    private static final Set<String> ROOTS = Set.of("rpengine", "rpe", "rp", "emote", "emotereply");

    /** Splitting a command into its words. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern ROOT = Pattern.compile("/([a-z]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Every command the command layer answers to, split into its words.
     *
     * <p>Replaced on a reload. Empty until the command layer has been built,
     * which means a message sent before then carries no clickable command
     * rather than a wrong one.
     */
    private static volatile List<String[]> signatures = List.of();

    private Chat() {
    }

    /**
     * Adopts the command layer's commands. Called once at startup.
     *
     * <p>Each entry is one command as its words, without the leading slash or
     * the root: {@code "sync accept"}, {@code "give <id> [n]"}.
     *
     * <p>Derived rather than written down for the same reason the router's
     * subcommand list is — a command spelled twice goes stale — and it must be
     * <em>every</em> command rather than every command that has a line of
     * help. That distinction is what used to be wrong: {@code sync accept} has
     * no help line of its own, so it was not a command as far as this class
     * was concerned, and the invitation naming it got truncated. A verb
     * missing from here is a verb a message gets cut off at.
     */
    public static void commands(Set<String> known) {
        List<String[]> parsed = new ArrayList<>();
        if (known != null) {
            for (String command : known) {
                if (command == null || command.trim().isEmpty()) {
                    continue;
                }
                parsed.add(WHITESPACE.split(command.trim().toLowerCase(Locale.ROOT)));
            }
        }
        signatures = List.copyOf(parsed);
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
     * <p>Word by word, keeping a word only while what has been kept so far is
     * still the beginning of a real command. See the class note: this is the
     * whole difficulty, and a word list cannot do it.
     */
    private static int endOf(String line, int at) {
        List<String> taken = new ArrayList<>(4);
        int end = at;
        int cursor = at;
        while (cursor < line.length()) {
            int start = cursor;
            while (start < line.length() && line.charAt(start) == ' ') {
                start++;
            }
            if (start >= line.length()) {
                break;
            }
            int stop = wordEnd(line, start);
            String raw = line.substring(start, stop);
            String word = strip(raw);
            if (word.isEmpty()) {
                break;
            }
            taken.add(word);
            if (!isPrefix(taken)) {
                break;
            }
            end = start + word.length();
            cursor = stop;
        }
        return end;
    }

    /**
     * Where one word of a message ends.
     *
     * <p>Normally the next space. The exception is a placeholder with spaces
     * inside it — {@code <text with :namespace:id: in it>} — which is one
     * argument however many words it looks like, and which used to be cut in
     * half at the first space and suggested as {@code /rp say &lt;text}.
     */
    private static int wordEnd(String line, int start) {
        char open = line.charAt(start);
        char close = open == '<' ? '>' : open == '[' ? ']' : 0;
        if (close != 0) {
            int found = line.indexOf(close, start);
            if (found >= 0) {
                return found + 1;
            }
        }
        int stop = start;
        while (stop < line.length() && line.charAt(stop) != ' ') {
            stop++;
        }
        return stop;
    }

    /**
     * Whether {@code taken} is the beginning of some command the layer has.
     *
     * <p>The one question this class asks, and the reason it can tell "/rp
     * distribute off" in the sentence "stop serving it" from a command that
     * ends in "stop": after {@code distribute off} no command continues with
     * {@code stop}, so the sentence resumes there.
     */
    private static boolean isPrefix(List<String> taken) {
        for (String[] signature : signatures) {
            if (signature.length < taken.size()) {
                continue;
            }
            boolean all = true;
            for (int i = 0; i < taken.size(); i++) {
                if (!wordMatches(taken.get(i), signature[i])) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one word of a message fills one word of a command.
     *
     * <p>A literal matches itself. A placeholder — {@code <id>}, {@code [n]},
     * {@code <id|clear>} — matches the placeholder written out as the message
     * usually does, one of the alternatives named inside it, or something that
     * is plainly an argument rather than prose: an {@code id:with:colons}, or
     * a number.
     *
     * <p>It does <strong>not</strong> match an arbitrary word. That is the
     * tightening: a placeholder that accepted anything would swallow the rest
     * of the sentence, which is the failure the class note describes from the
     * other direction.
     */
    private static boolean wordMatches(String word, String slot) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (isPlaceholder(slot)) {
            if (isPlaceholder(lower)) {
                return true;
            }
            for (String option : inside(slot).split("\\|")) {
                if (!option.isEmpty() && !isPlaceholder(option) && option.equals(lower)) {
                    return true;
                }
            }
            return lower.indexOf(':') > 0 || lower.chars().allMatch(Character::isDigit);
        }
        for (String option : slot.split("\\|")) {
            if (option.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlaceholder(String word) {
        return word.length() > 1
                && (word.charAt(0) == '<' || word.charAt(0) == '[');
    }

    /** A placeholder without its brackets, for reading the choices inside. */
    private static String inside(String slot) {
        int end = slot.length();
        char last = slot.charAt(end - 1);
        if (last == '>' || last == ']') {
            end--;
        }
        return slot.substring(1, Math.max(1, end));
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
