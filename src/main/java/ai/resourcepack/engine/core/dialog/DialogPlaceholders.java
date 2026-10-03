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
 *
 * <h2>Live values in a picture</h2>
 *
 * A Studio dialog can draw a placeholder IN its picture — "{player}" on a
 * profile card, "{coins}" on a balance — as words the game draws from text
 * rather than pixels baked into the art. The pack draws them and then steps
 * back over them with the same words in a font of negative spaces, so the body
 * line they sit in still comes to exactly its width; that is what keeps the
 * client from wrapping the picture somewhere else. Two things can still break
 * it, and neither is knowable until the value is: a character the pack's
 * negative font has no width for, and a value so long the words run off the
 * end of the line. So Studio puts a MARK in front of each such placeholder —
 * one private-use character, zero-width in every font it is drawn in — whose
 * codepoint says how big the words are and how much room they have
 * ({@link #LIVE_MARK_BASE}), and a marked value is kept to what the pack can
 * measure and to that room ({@link #fitLive}). An engine older than this fills
 * the placeholder without the mark meaning anything, which is right for every
 * value that was going to fit anyway.
 */
public final class DialogPlaceholders {

    private DialogPlaceholders() {
    }

    /**
     * The first live mark. A mark is {@code LIVE_MARK_BASE + mode * 512 +
     * room}: the mode is 2 for words drawn twice the size plus 1 for bold, and
     * the room is how many pixels the value may move the pen, at most 511.
     * Studio's dialog builder writes them; the two have to agree.
     */
    public static final int LIVE_MARK_BASE = 0xF000;

    /** One past the last live mark. */
    static final int LIVE_MARK_END = LIVE_MARK_BASE + 4 * 512;

    /** What a value cut short to fit its room ends with. */
    private static final int ELLIPSIS = 0x2026;

    /**
     * What a space in a live value is written as: a no-break space, which the
     * pack's live fonts make exactly as wide as a space. A real space anywhere
     * in a picture's body line is where the client's line splitter breaks it
     * when the next line's first character overflows — which every full line's
     * does — so one in a value would split the picture there.
     */
    private static final int LIVE_SPACE = 0x00A0;

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
                String clean = clean(value.get());
                char before = m.start() > 0 ? json.charAt(m.start() - 1) : 0;
                if (before >= LIVE_MARK_BASE && before < LIVE_MARK_END) {
                    clean = fitLive(clean, before);
                }
                out.append(json, at, m.start()).append(escape(clean));
                at = m.end();
            }
        } while (m.find());
        return out.append(json, at, json.length()).toString();
    }

    /**
     * A value as live words in a picture may draw it: every space a no-break
     * space ({@link #LIVE_SPACE}), every character the pack's fonts cannot
     * measure exactly turned into a {@code ?}, and the whole cut short — ending
     * in an ellipsis — when it would move the pen further than the mark's room.
     * Line breaks and tabs are among what becomes {@code ?}, because a line
     * break in a picture's body would split the picture.
     *
     * @param mark the live mark in front of the placeholder: see {@link #LIVE_MARK_BASE}
     */
    static String fitLive(String value, int mark) {
        int code = mark - LIVE_MARK_BASE;
        int mode = code / 512;
        int room = code % 512;
        int scale = (mode & 2) != 0 ? 2 : 1;
        boolean bold = (mode & 1) != 0;
        StringBuilder out = new StringBuilder(value.length());
        int used = 0;
        int ellipsis = DialogGlyphWidths.advance(ELLIPSIS, scale, bold);
        int[] codepoints = value.codePoints()
                .map(cp -> cp == ' ' || cp == LIVE_SPACE ? LIVE_SPACE : DialogGlyphWidths.covers(cp) ? cp : '?')
                .toArray();
        int total = 0;
        for (int cp : codepoints) {
            total += liveAdvance(cp, scale, bold);
        }
        if (total <= room) {
            for (int cp : codepoints) {
                out.appendCodePoint(cp);
            }
            return out.toString();
        }
        for (int cp : codepoints) {
            int advance = liveAdvance(cp, scale, bold);
            if (used + advance + ellipsis > room) {
                break;
            }
            out.appendCodePoint(cp);
            used += advance;
        }
        if (used + ellipsis <= room) {
            out.appendCodePoint(ELLIPSIS);
        }
        return out.toString();
    }

    /** A live character's advance: the table's, a no-break space measured as the space it stands for. */
    private static int liveAdvance(int codepoint, int scale, boolean bold) {
        return DialogGlyphWidths.advance(codepoint == LIVE_SPACE ? ' ' : codepoint, scale, bold);
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
