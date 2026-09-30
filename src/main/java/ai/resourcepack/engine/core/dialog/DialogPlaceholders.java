package ai.resourcepack.engine.core.dialog;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the <code>{name}</code> placeholders in a dialog as it is opened. Internal.
 *
 * <h2>Why a dialog has them at all</h2>
 *
 * A dialog is usually ABOUT somebody. A punish menu is "Punish Steve", and every
 * reason on it runs {@code mute Steve 1h}; a profile is somebody's; a shop greets
 * whoever opened it. Without a way in, each of those is a dialog per player, which
 * is to say not a dialog at all. So a dialog may say <code>{target}</code>, and
 * whoever opens it says what that is — {@code /rp dialog punish Admin
 * target=Steve}, or {@code Dialogs.show(viewer, id, Map.of("target", "Steve"))} —
 * and the built-ins and PlaceholderAPI answer the rest, exactly as they do for an
 * overlay ({@code core.font.Placeholders}).
 *
 * <h2>Choices</h2>
 *
 * A placeholder may also CHOOSE: <code>{show_sidebar?off:A|on:B}</code> becomes
 * {@code A} when the value is {@code off}, {@code B} when it is {@code on}, and
 * the LAST entry when the value matches none of them or there is no value at
 * all — so whoever writes one puts the default last. Nothing about a choice is
 * specific to dialogs; what uses it is Studio's bound controls, a switch or a
 * slider drawn in every state it can show, of which the choice keeps the one
 * the player's own value names. The entry is inserted as it is written, not
 * escaped: it came out of the same JSON string it is going back into.
 *
 * <h2>Why this does not break "transported, not modelled"</h2>
 *
 * The engine still reads no field of a dialog. This is a pass over its JSON TEXT:
 * a brace, a name, a brace. JSON's own braces are never followed by a bare name
 * — an object opens with a quote or closes empty — so every match is inside a
 * string, and a value is escaped as one. Whether that string is a title, a
 * tooltip, a command or a label is the game's business, as it always was.
 *
 * <p>A name nothing answers is LEFT AS WRITTEN. An overlay blanks it, because a
 * leftover brace on a HUD reads as a broken pack; a dialog is text somebody typed,
 * where "{VIP}" may simply be the words.
 *
 * <p>One pass, left to right: a value that itself contains a brace is inserted
 * as it is and never read again, so a player named to look like a placeholder
 * cannot make one.
 */
public final class DialogPlaceholders {

    private DialogPlaceholders() {
    }

    /**
     * A placeholder: a name of letters, digits and underscores between braces —
     * or, after a {@code ?}, the entries of a choice, which hold no brace.
     */
    private static final Pattern NAME = Pattern.compile("\\{([A-Za-z0-9_]{1,48})(?:\\?([^{}]*))?\\}");

    /** A legacy formatting code. The game refuses a whole dialog over one in a command. */
    private static final Pattern LEGACY = Pattern.compile("§.?");

    /** Whether there is anything here to fill — so a dialog with none costs one scan. */
    public static boolean any(String json) {
        return json != null && NAME.matcher(json).find();
    }

    /**
     * The JSON with every placeholder {@code lookup} answers replaced by its
     * value, escaped for a JSON string, and every choice resolved.
     */
    public static String fill(String json, Function<String, Optional<String>> lookup) {
        if (json == null) {
            return null;
        }
        Matcher m = NAME.matcher(json);
        if (!m.find()) {
            return json;
        }
        StringBuilder out = new StringBuilder(json.length() + 32);
        int at = 0;
        do {
            Optional<String> value = lookup.apply(m.group(1));
            if (m.group(2) != null) {
                Optional<String> chosen = choose(m.group(2), value.map(DialogPlaceholders::clean));
                if (chosen.isPresent()) {
                    out.append(json, at, m.start()).append(chosen.get());
                    at = m.end();
                }
            } else if (value.isPresent()) {
                out.append(json, at, m.start()).append(escape(clean(value.get())));
                at = m.end();
            }
        } while (m.find());
        return out.append(json, at, json.length()).toString();
    }

    /**
     * The entry of a choice a value picks: the one whose key is the value, the
     * last when none is — which is also what an absent value gets. Empty only
     * when the choice has no well-formed entry at all, and is left as written.
     */
    static Optional<String> choose(String entries, Optional<String> value) {
        String wanted = value.map(v -> v.trim().toLowerCase(Locale.ROOT)).orElse(null);
        String last = null;
        for (String entry : entries.split("\\|", -1)) {
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = entry.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String text = entry.substring(colon + 1);
            if (wanted != null && key.equals(wanted)) {
                return Optional.of(text);
            }
            last = text;
        }
        return Optional.ofNullable(last);
    }

    /**
     * A value made safe to land anywhere in a dialog. Formatting codes go: a
     * display name coloured with them would be refused in a command, and a
     * command that is refused takes the whole dialog with it.
     */
    static String clean(String value) {
        return LEGACY.matcher(value).replaceAll("");
    }

    /** A value as the inside of a JSON string. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
