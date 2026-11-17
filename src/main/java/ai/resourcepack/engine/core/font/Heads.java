package ai.resourcepack.engine.core.font;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A player's face in an overlay's value. Internal; {@code Overlays.head} in the API makes one.
 *
 * <p>A value is a string and a face is not text: since 1.21.9 the game draws one as an
 * {@code object} text component, eight pixels square, from the player's own skin. So the value
 * carries a token that says which face, and the token becomes that component only when the line
 * is written as JSON for Adventure, which is how every overlay reaches a Paper client. Until then
 * it is a string like any other: placeholders put it in place, and {@link TextWidth} measures it
 * as the eight pixels the game moves the cursor by.
 *
 * <p>The token is {@link #OPEN}, the face's profile as JSON, {@link #SPLIT}, the colour, and
 * {@link #CLOSE}: Unicode's interlinear annotation characters, which nobody types and no font
 * draws. Where an overlay is not sent as JSON (Spigot, with no Adventure) there is no way to draw
 * a face, and the token becomes a space as wide, so nothing after it moves.
 */
public final class Heads {
    static final char OPEN = '\uFFF9';
    static final char SPLIT = '\uFFFA';
    static final char CLOSE = '\uFFFB';

    /** How far a face moves the cursor: the game's own glyph for one is eight wide, with no gap. */
    static final int WIDTH = 8;

    private Heads() {
    }

    /**
     * The token for a face. {@code player} is the face's profile as the game reads one: a JSON
     * string (a name), or an object with a {@code name}, an {@code id} and {@code properties}, or
     * a {@code texture} (one of the game's own skins, {@code "entity/player/wide/steve"}).
     * {@code color} is the colour it is drawn in, {@code #rrggbb}, which a pack's text shader can
     * read as it does a run's.
     */
    public static String token(String player, String color) {
        // Gson reads a bare word as a string, which would let a name in without its quotes.
        String first = player == null ? "" : player.strip();
        if (!first.startsWith("\"") && !first.startsWith("{")) {
            throw new IllegalArgumentException("not a profile: " + player);
        }
        JsonElement profile;
        try {
            profile = JsonParser.parseString(first);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a profile: " + player, e);
        }
        if (!(profile.isJsonObject() || (profile.isJsonPrimitive() && profile.getAsJsonPrimitive().isString()))) {
            throw new IllegalArgumentException("not a profile: " + player);
        }
        if (color == null || !color.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("not a colour: " + color);
        }
        return OPEN + profile.toString() + SPLIT + color.toLowerCase(java.util.Locale.ROOT) + CLOSE;
    }

    /** Whether {@code text} holds a face. */
    static boolean has(String text) {
        return text != null && text.indexOf(OPEN) >= 0;
    }

    /** Where the face starting at {@code open} ends, or -1 when it is not a whole token. */
    static int end(String text, int open) {
        int close = text.indexOf(CLOSE, open);
        return close > 0 && text.lastIndexOf(SPLIT, close) > open ? close : -1;
    }

    /** {@code text} with each face a space as wide (the default font's), for a line that is not JSON. */
    static String flat(String text) {
        if (!has(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf(OPEN, at);
            int close = open < 0 ? -1 : end(text, open);
            if (close < 0) {
                out.append(text, at, text.length());
                break;
            }
            out.append(text, at, open).append(Overlays.space(WIDTH));
            at = close + 1;
        }
        return out.toString();
    }

    /**
     * A line of JSON with every face in it made the component that draws it: each object whose
     * {@code text} holds one keeps everything else it says (colour, font, shadow) and has its
     * text moved into {@code extra}, as plain text either side of the faces.
     */
    static JsonElement expand(JsonElement json) {
        if (json.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : json.getAsJsonArray()) {
                out.add(expand(e));
            }
            return out;
        }
        if (!json.isJsonObject()) {
            return json;
        }
        JsonObject o = json.getAsJsonObject();
        if (o.has("extra")) {
            o.add("extra", expand(o.get("extra")));
        }
        JsonElement text = o.get("text");
        if (text == null || !text.isJsonPrimitive() || !has(text.getAsString())) {
            return o;
        }
        String s = text.getAsString();
        JsonArray parts = new JsonArray();
        int at = 0;
        while (at < s.length()) {
            int open = s.indexOf(OPEN, at);
            int close = open < 0 ? -1 : end(s, open);
            if (close < 0) {
                parts.add(plain(s.substring(at)));
                break;
            }
            if (open > at) {
                parts.add(plain(s.substring(at, open)));
            }
            int split = s.lastIndexOf(SPLIT, close);
            parts.add(face(s.substring(open + 1, split), s.substring(split + 1, close)));
            at = close + 1;
        }
        JsonArray extra = o.has("extra") ? o.getAsJsonArray("extra") : null;
        if (extra != null) {
            parts.addAll(extra);
        }
        o.addProperty("text", "");
        o.add("extra", parts);
        return o;
    }

    private static JsonObject plain(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        return o;
    }

    private static JsonObject face(String player, String color) {
        JsonObject o = new JsonObject();
        o.addProperty("object", "player");
        o.add("player", JsonParser.parseString(player));
        o.addProperty("hat", true);
        o.addProperty("color", color);
        // What a client from before faces draws instead (ViaBackwards hands it this): a space as
        // wide, so the line still lines up.
        o.add("fallback", plain(Overlays.space(WIDTH)));
        return o;
    }
}
