package ai.resourcepack.engine.core.transition;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The title text that plays a transition: its character in a trigger colour,
 * written as legacy hex formatting ({@code §x§r§r§g§g§b§b}), which
 * {@code Player.sendTitle} understands on every server the engine runs on.
 * Studio's own vanilla commands write the same as JSON with
 * {@code "color": "#rrggbb"}.
 */
public final class TransitionTitles {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private TransitionTitles() {
    }

    /** Whether a colour is exactly {@code #rrggbb}, the only form a trigger colour can take. */
    public static boolean isColor(String color) {
        return color != null && HEX.matcher(color).matches();
    }

    /** {@code #ec04fc} as {@code §x§e§c§0§4§f§c}. */
    public static String legacyHex(String color) {
        if (!isColor(color)) {
            throw new IllegalArgumentException("Not a #rrggbb colour: " + color);
        }
        StringBuilder out = new StringBuilder(14).append('§').append('x');
        for (char c : color.substring(1).toLowerCase(Locale.ROOT).toCharArray()) {
            out.append('§').append(c);
        }
        return out.toString();
    }

    /** The whole title: the colour, then the character. */
    public static String title(String color, String glyph) {
        return legacyHex(color) + glyph;
    }

    /** A sound event as the game wants it: a bare path is vanilla's. */
    public static String soundKey(String sound) {
        return sound.indexOf(':') >= 0 ? sound : "minecraft:" + sound;
    }
}
