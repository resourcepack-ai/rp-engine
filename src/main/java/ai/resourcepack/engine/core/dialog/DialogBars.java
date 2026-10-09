package ai.resourcepack.engine.core.dialog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fills a Studio dialog's LIVE PROGRESS BARS as it opens. Internal.
 *
 * <h2>What arrives</h2>
 *
 * A progress bar in a Studio dialog can be filled from a player's own numbers —
 * <code>{xp_points}</code> out of <code>{xp_needed}</code>, a quest's progress, a
 * balance against a goal. A picture is drawn once, when the dialog is saved, so
 * the fill cannot be in it: the picture keeps the empty track, and the body
 * carries a MARKER where the fill goes — an empty text in a bar font
 * ({@code dialog_bar_<dy>}), coloured the fill's colour, whose {@code insertion}
 * is {@code rp:bar:<room>:<highlight>:<value>|<most>}. The value and the most are
 * the author's words, placeholders and all, so the ordinary pass
 * ({@link DialogPlaceholders}) has already filled them by the time this runs.
 *
 * <h2>What it becomes</h2>
 *
 * Both read as numbers the way a HUD bar reads them — commas and a percent sign
 * go, whole numbers and decimals both parse — and the fill is the value over the
 * most, kept between empty and full, times the room, rounded. The marker becomes
 * two runs of the bar font: the fill in its colour, then its one-row highlight
 * in the highlight's colour, each the widest rectangle that still fits followed
 * by a one-pixel step back (a rectangle w wide advances w + 1), again until the
 * pixels are drawn, then a step back over all of them. Neither run moves the
 * pen, which is what keeps the body line the width the client splits it at. A
 * value or a most that is not a number — a placeholder nothing answered — draws
 * no fill at all, and the track stays empty.
 *
 * <h2>Bars that stand up</h2>
 *
 * A VERTICAL bar fills from the bottom, and a text line only ever advances
 * across, so its fill cannot be assembled from rectangles. Studio ships every
 * fill such a bar can show instead: a font of its own in which
 * {@link #VBAR_BASE} plus k is the bar filled k rows, drawn in its real colours,
 * and {@link #VBAR_BACK} steps back over one. Its marker's insertion is
 * {@code rp:vbar:<room>:<value>|<most>}; it becomes that one glyph and the step
 * back, in white, or nothing at all.
 *
 * <h2>Why this is not "modelling" a dialog</h2>
 *
 * The same argument as {@link DialogItems}: this walks the JSON's components
 * and looks only at insertions Studio wrote for it to find. An engine older
 * than this leaves the marker, an empty text that draws nothing.
 */
public final class DialogBars {

    private DialogBars() {
    }

    /** What a bar marker's insertion starts with. Studio writes it. */
    static final String INSERTION = "rp:bar:";

    /** A fill rectangle 2^i wide in a bar font: this plus i. Studio's bar fonts declare it. */
    public static final int BODY_BASE = 0xE000;

    /** The same rectangle one row tall, the fill's highlight: this plus i. */
    public static final int RIM_BASE = 0xE010;

    /** A step back of 2^i: this plus i. The first, one pixel, closes the gap after each rectangle. */
    public static final int BACK_BASE = 0xE020;

    /** What a VERTICAL bar marker's insertion starts with. Studio writes it. */
    static final String VERTICAL = "rp:vbar:";

    /** A vertical bar filled k rows, in that bar's own font: this plus k. */
    public static final int VBAR_BASE = 0xE000;

    /** The step back over one glyph of a vertical bar's font. */
    public static final int VBAR_BACK = 0xE3FF;

    /** The most rows a vertical bar's font has a glyph for. */
    public static final int VBAR_MAX_ROOM = 256;

    /** Rectangles 1, 2, 4 … 256 wide: enough for any room a bar can have. */
    public static final int STEPS = 9;

    /**
     * What a bar takes as a number once its commas and percent sign are gone:
     * digits with a point or without, a sign, an exponent. Studio's
     * {@code BAR_NUMBER}, character for character — so "NaN", "Infinity" and
     * "0x10", which {@link Double#parseDouble} would take, are no number here,
     * as they are none in the editor.
     */
    static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    /** A colour the text codec takes: the dialog is refused whole over one it does not. */
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Whether a dialog has a live bar in it — so a dialog with none costs one scan. */
    public static boolean any(String json) {
        return json != null && (json.contains(INSERTION) || json.contains(VERTICAL));
    }

    /**
     * The JSON with every live bar's marker swapped for its fill. The string
     * itself when there is none, or when the JSON cannot be read — a dialog this
     * cannot parse is passed on as it came, for the game to judge.
     */
    public static String fill(String json) {
        if (!any(json)) {
            return json;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return json;
        }
        boolean[] changed = {false};
        JsonElement out = walk(root, changed);
        return changed[0] ? GSON.toJson(out) : json;
    }

    private static JsonElement walk(JsonElement e, boolean[] changed) {
        if (e.isJsonArray()) {
            JsonArray next = new JsonArray();
            for (JsonElement child : e.getAsJsonArray()) {
                Marker marker = marker(child);
                if (marker == null) {
                    next.add(walk(child, changed));
                    continue;
                }
                changed[0] = true;
                for (JsonObject part : fillOf(marker)) {
                    next.add(part);
                }
            }
            return next;
        }
        if (!e.isJsonObject()) {
            return e;
        }
        JsonObject next = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
            next.add(entry.getKey(), walk(entry.getValue(), changed));
        }
        return next;
    }

    /** A marker, read: its font and colour, and what its insertion says. */
    record Marker(String font, String color, int room, String rim, String value, String max, boolean vertical) {

        Marker(String font, String color, int room, String rim, String value, String max) {
            this(font, color, room, rim, value, max, false);
        }
    }

    /** The marker {@code e} is, or null when it is not one. */
    static Marker marker(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        String insertion = string(o, "insertion");
        if (insertion != null && insertion.startsWith(VERTICAL)) {
            return verticalMarker(o, insertion.substring(VERTICAL.length()));
        }
        if (insertion == null || !insertion.startsWith(INSERTION)) {
            return null;
        }
        String rest = insertion.substring(INSERTION.length());
        int afterRoom = rest.indexOf(':');
        int afterRim = afterRoom < 0 ? -1 : rest.indexOf(':', afterRoom + 1);
        int between = afterRim < 0 ? -1 : rest.indexOf('|', afterRim + 1);
        if (between < 0) {
            // Ours, but not one this can read: drawn as nothing, as before.
            return new Marker(string(o, "font"), string(o, "color"), 0, null, null, null);
        }
        int room;
        try {
            room = Integer.parseInt(rest.substring(0, afterRoom).trim());
        } catch (NumberFormatException notARoom) {
            room = 0;
        }
        return new Marker(string(o, "font"), string(o, "color"), Math.max(0, Math.min((1 << STEPS) - 1, room)),
                rest.substring(afterRoom + 1, afterRim), rest.substring(afterRim + 1, between), rest.substring(between + 1));
    }

    /** A vertical bar's marker, read from what follows its prefix: {@code <room>:<value>|<most>}. */
    private static Marker verticalMarker(JsonObject o, String rest) {
        int afterRoom = rest.indexOf(':');
        int between = afterRoom < 0 ? -1 : rest.indexOf('|', afterRoom + 1);
        if (between < 0) {
            return new Marker(string(o, "font"), "white", 0, null, null, null, true);
        }
        int room;
        try {
            room = Integer.parseInt(rest.substring(0, afterRoom).trim());
        } catch (NumberFormatException notARoom) {
            room = 0;
        }
        return new Marker(string(o, "font"), "white", Math.max(0, Math.min(VBAR_MAX_ROOM, room)), null,
                rest.substring(afterRoom + 1, between), rest.substring(between + 1), true);
    }

    /**
     * What a marker becomes: the fill and its highlight, or — when there is
     * nothing to fill — an empty text, which draws and moves nothing and keeps
     * the run list it is in from ever being empty (the codec refuses an empty
     * {@code extra}).
     */
    static List<JsonObject> fillOf(Marker marker) {
        int pixels = pixels(marker.room(), number(marker.value()), number(marker.max()));
        if (pixels <= 0 || marker.font() == null) {
            JsonObject nothing = new JsonObject();
            nothing.addProperty("text", "");
            return List.of(nothing);
        }
        if (marker.vertical()) {
            // Its glyphs carry their own colours: drawn white, they are drawn as they are.
            return List.of(part(vertical(pixels), marker.font(), "white"));
        }
        String color = marker.color() != null && HEX.matcher(marker.color()).matches() ? marker.color() : "white";
        String rim = marker.rim() != null && HEX.matcher(marker.rim()).matches() ? marker.rim() : color;
        return List.of(part(run(pixels, BODY_BASE), marker.font(), color), part(run(pixels, RIM_BASE), marker.font(), rim));
    }

    /** One run of a bar font, drawn flat: a fill is a picture, and the game would draw a darker copy of it a pixel off. */
    private static JsonObject part(String text, String font, String color) {
        JsonObject out = new JsonObject();
        out.addProperty("text", text);
        out.addProperty("font", font);
        out.addProperty("color", color);
        out.addProperty("shadow_color", 0);
        return out;
    }

    /**
     * How many of a room's pixels a value fills: none when either is not a
     * number or the most is not above zero; else the value over the most, kept
     * between empty and full, times the room, rounded. Studio's
     * {@code barFillPixels}, which draws the same bar in the editor.
     */
    static int pixels(int room, Double value, Double max) {
        if (value == null || max == null || !(max > 0)) {
            return 0;
        }
        double fraction = Math.max(0d, Math.min(1d, value / max));
        return (int) Math.round(room * fraction);
    }

    /**
     * The characters a fill of {@code pixels} is written in: the widest
     * rectangle that still fits and a one-pixel step back, again until the
     * pixels are drawn, then a step back over all of them. {@code base} is
     * {@link #BODY_BASE} for the fill and {@link #RIM_BASE} for its highlight.
     * Studio's {@code barRun}.
     */
    static String run(int pixels, int base) {
        StringBuilder out = new StringBuilder();
        int left = Math.max(0, pixels);
        for (int i = STEPS - 1; i >= 0; i--) {
            int width = 1 << i;
            while (left >= width) {
                out.append((char) (base + i)).append((char) BACK_BASE);
                left -= width;
            }
        }
        int back = Math.max(0, pixels);
        for (int i = STEPS - 1; i >= 0; i--) {
            int width = 1 << i;
            while (back >= width) {
                out.append((char) (BACK_BASE + i));
                back -= width;
            }
        }
        return out.toString();
    }

    /** The characters a vertical fill of {@code rows} is written in: its glyph and the step back. Studio's {@code vbarRun}. */
    static String vertical(int rows) {
        return rows <= 0 ? "" : new String(new char[] {(char) (VBAR_BASE + rows), (char) VBAR_BACK});
    }

    /** A number, or null: commas and a percent sign are what people type, and go. */
    static Double number(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim().replace(",", "").replace("%", "");
        if (!NUMBER.matcher(cleaned).matches()) {
            return null;
        }
        try {
            double n = Double.parseDouble(cleaned);
            return Double.isFinite(n) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement value = o.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
