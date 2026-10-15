package ai.resourcepack.engine.core.dialog;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This server's icons in a Studio dialog's picture. Internal.
 *
 * <h2>Why a marker</h2>
 *
 * A Studio dialog can put one of the server's icons in its picture, written
 * {@code :namespace:id:} the way chat writes it. Studio cannot write the icon's
 * character into the dialog: a codepoint is handed out in id order when the
 * content is built, and moves whenever an icon is added before it. So it writes
 * a MARKER where the icon goes — a blank space in the dialog's space font, as
 * wide as the room the author gave the icon and the pixel after it, its
 * insertion that width and the id:
 *
 * <pre>
 *   {"text":"…","font":"minecraft:dialog_space","insertion":"rp:icon:10:mypack:thirst"}
 * </pre>
 *
 * and then a space as far back, so its line still comes to exactly its width.
 * As the dialog opens, {@link #fill} swaps each marker for the icon's character
 * and whatever space brings the icon to the same width, so an icon a pixel
 * wider or narrower than the author guessed moves nothing after it on the line.
 * An id that names no icon is left as it is: a blank space, the line intact.
 * The placeholder pass runs before this one, so an id may hold a placeholder —
 * {@code :status:{thirst_state}:} is a different icon for each player.
 *
 * <p>An id without a namespace is looked up in every namespace, first by id, as
 * {@code :wave:} is in chat.
 *
 * <p><b>The key order is the contract.</b> Studio's dialog builder writes the
 * marker exactly as {@link #MARKER} reads it, and Studio's dialog check runs
 * this pattern over a body it built, so the two cannot drift without it failing.
 * So do the three space constants, which are Studio's space font.
 */
public final class DialogIcons {

    private DialogIcons() {
    }

    /** What a marker's insertion starts with, before the advance and the id. */
    public static final String INSERTION = "rp:icon:";

    /** The dialog space font's first forward space, 1px; the next are 2, 4 … 256. */
    static final int SPACE = 0xE000;

    /** Its first backward space, -1px; the next are -2, -4 … -256. */
    static final int BACK = 0xE010;

    /** How many spaces each way: 1 to 256. */
    static final int STEPS = 9;

    /**
     * A marker, exactly as Studio writes it: its spaces (written out or escaped,
     * whichever way the JSON came), the space font, then the insertion — the
     * room in pixels and the icon's id, already filled.
     */
    static final Pattern MARKER = Pattern.compile(
            "\\{\"text\":\"(?:[\\uE000-\\uE008]|\\\\u[eE]00[0-8])+\",\"font\":\"minecraft:dialog_space\",\"insertion\":\"rp:icon:(\\d{1,3}):([^\"\\\\]{1,128})\"\\}");

    /** An icon a marker names: its character, and how far the game moves the pen over it. */
    public record Icon(String character, int advance) {
    }

    /** Whether a dialog has a marker in it — so one with none costs one scan. */
    public static boolean any(String json) {
        return json != null && json.contains("\"insertion\":\"" + INSERTION);
    }

    /**
     * The JSON with each marker whose icon {@code icons} knows swapped for that
     * icon, brought to the marker's width; the string itself when there is
     * nothing to swap.
     */
    public static String fill(String json, Function<String, Optional<Icon>> icons) {
        if (!any(json)) {
            return json;
        }
        Matcher m = MARKER.matcher(json);
        StringBuilder out = null;
        int at = 0;
        while (m.find()) {
            Optional<Icon> icon = icons.apply(m.group(2));
            if (icon.isEmpty()) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(json.length() + 64);
            }
            out.append(json, at, m.start()).append(component(icon.get(), Integer.parseInt(m.group(1))));
            at = m.end();
        }
        return out == null ? json : out.append(json, at, json.length()).toString();
    }

    /**
     * The icon in the game's default font, untinted and with no shadow, then the
     * space that brings it to {@code room}: forward when the icon is narrower,
     * back when it is wider.
     */
    static String component(Icon icon, int room) {
        StringBuilder c = new StringBuilder(64).append("{\"text\":\"").append(escape(icon.character()))
                .append("\",\"font\":\"minecraft:default\",\"color\":\"white\",\"shadow_color\":0");
        int rest = room - icon.advance();
        if (rest != 0) {
            c.append(",\"extra\":[{\"text\":\"").append(spaces(rest)).append("\",\"font\":\"minecraft:dialog_space\"}]");
        }
        return c.append('}').toString();
    }

    /** An advance as the fewest space-font characters that sum to it — backwards when it is negative. */
    static String spaces(int advance) {
        int rest = Math.abs(advance);
        int base = advance < 0 ? BACK : SPACE;
        StringBuilder out = new StringBuilder();
        for (int i = STEPS - 1; i >= 0; i--) {
            int step = 1 << i;
            while (rest >= step) {
                out.append((char) (base + i));
                rest -= step;
            }
        }
        return out.toString();
    }

    /** A character for a JSON string: an icon is a private-use character, but nothing here assumes it. */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
