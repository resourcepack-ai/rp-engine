package ai.resourcepack.engine.core.dialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * A dialog's JSON, rewritten as the one-line SNBT a command can carry.
 *
 * <p>This is what lets a dialog open without the server having read a
 * datapack: {@code /dialog show} takes either a registry id or a whole dialog
 * written out in SNBT, and the second has been true since the first snapshot
 * that had dialogs at all. So the engine hands the game the dialog itself, and
 * nothing has to be registered, reloaded or restarted.
 *
 * <p><strong>This knows nothing about what a dialog is.</strong> It is a
 * transcription between two spellings of the same tree - objects, lists,
 * strings, numbers, booleans - and deliberately so: the engine's claim about
 * dialogs is that it transports the game's own JSON without interpreting it,
 * and a converter that knew the schema would be the second implementation of
 * somebody else's codec that the rest of this package exists to avoid. Every
 * field Mojang adds passes through here unread.
 *
 * <p>The transcription is needed rather than decorative for one reason each
 * way. A command is one LINE, and an authored dialog is written out over many;
 * and the two spellings do not agree about what a string may contain.
 *
 * <p><b>Three things SNBT cannot say</b>, and each answers {@link
 * Optional#empty()} rather than guessing:
 * <ul>
 *   <li><b>A control character in a string.</b> Not as a raw byte, not as
 *       {@code \n}, and not spelled as a unicode escape either - the parser
 *       reads the escape and then refuses the character it produced. Ordinary
 *       text and every private-use glyph an art pack allocates are fine; a
 *       newline inside one string is not.</li>
 *   <li><b>A null.</b> There is no such tag. Dropping the field would change
 *       what the dialog says and dropping a list entry would move every entry
 *       after it, so neither is done.</li>
 *   <li><b>A number that is not finite</b>, which no codec would take
 *       anyway.</li>
 * </ul>
 *
 * <p>An empty answer is not a failure to report to anybody: the caller falls
 * back to naming the dialog in the registry, which is the route that existed
 * before this did.
 */
public final class DialogSnbt {

    private DialogSnbt() {
    }

    /**
     * The dialog as a single line of SNBT, or empty if it cannot be one.
     *
     * @param json the {@code minecraft:dialog} object, as the pack wrote it
     */
    public static Optional<String> of(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            // Not valid JSON. Nothing to say here - the datapack write and the
            // game's own loader both report a malformed dialog already, and
            // saying it a third time from the open path would be noise.
            return Optional.empty();
        }
        // The game's dialog argument distinguishes a string from an object: a
        // string tag is read as a resource location and looked UP, which is
        // the registry route wearing this one's clothes. Only an object is the
        // dialog itself.
        if (!root.isJsonObject()) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        return write(root, out) ? Optional.of(out.toString()) : Optional.empty();
    }

    /** Appends one value, or answers false if SNBT has no spelling for it. */
    private static boolean write(JsonElement value, StringBuilder out) {
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                // Quoted, always. An unquoted key is legal SNBT only for a
                // narrow character set, and a key is the game's to choose.
                if (!quote(entry.getKey(), out)) {
                    return false;
                }
                out.append(':');
                if (!write(entry.getValue(), out)) {
                    return false;
                }
            }
            out.append('}');
            return true;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                if (!write(array.get(i), out)) {
                    return false;
                }
            }
            out.append(']');
            return true;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            out.append(primitive.getAsBoolean() ? "true" : "false");
            return true;
        }
        if (primitive.isNumber()) {
            return number(primitive, out);
        }
        return quote(primitive.getAsString(), out);
    }

    /**
     * Appends a number in the form the game's parser reads.
     *
     * <p>The lexical form is kept rather than the parsed value, because it is
     * what decides the TAG: {@code 5} is an int and {@code 5.0} a double, and
     * a codec expecting a float takes either while a codec expecting an int
     * does not take a double. Written as the pack wrote it, nothing changes
     * type on the way through.
     *
     * <p>Exponents are the exception and are spelled out. They are valid JSON,
     * and a dialog is more likely to be written by a generator than by hand,
     * so {@code 1e3} is a real possibility - and SNBT reads a bare {@code e}
     * as the start of a type suffix rather than as an exponent.
     */
    private static boolean number(JsonPrimitive primitive, StringBuilder out) {
        String text = primitive.getAsString();
        if (text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
            try {
                text = new BigDecimal(text).toPlainString();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // NaN and the infinities have no SNBT spelling. JSON has none either,
        // but Gson is lenient enough to hand one over.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '-' && c != '+' && c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        out.append(text);
        return true;
    }

    /**
     * Appends a quoted string, or answers false if it holds a control
     * character.
     *
     * <p>Only the quote and the backslash are escaped. Everything else goes
     * through as itself, private-use glyphs included - those are how a Studio
     * pack draws a dialog's picture, and they are ordinary characters as far
     * as both spellings are concerned.
     */
    private static boolean quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
        return true;
    }
}
