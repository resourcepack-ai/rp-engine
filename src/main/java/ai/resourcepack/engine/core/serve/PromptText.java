package ai.resourcepack.engine.core.serve;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code prompt.message} as the legacy string a pack request carries.
 *
 * <p>The accept screen's message is a legacy string on Spigot's API and on
 * Paper's alike ({@code §} codes, with {@code §x} for a hex colour), so this
 * is what every way of writing it is turned into. Three ways are read, mixed
 * freely: MiniMessage tags ({@code <red>}, {@code <#fa4943>},
 * {@code <bold>}, {@code <gradient:#9055FF:#13E2DA>}, {@code <rainbow>},
 * {@code <reset>} and the closing tags), {@code &} codes ({@code &c},
 * {@code &l}) and {@code &#rrggbb}. It is a converter rather than MiniMessage
 * itself because Spigot has no MiniMessage, and the message is the same text
 * on both.
 *
 * <p>A gradient or rainbow is coloured one character at a time, which is how
 * MiniMessage draws one too. A tag it does not know is left in the text as
 * typed, as MiniMessage leaves one.
 */
public final class PromptText {

    private static final Pattern TAG = Pattern.compile("<(/?)([!a-zA-Z0-9_#]+)((?::[^<>:]*)*)>");

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("black", "000000"), Map.entry("dark_blue", "0000AA"), Map.entry("dark_green", "00AA00"),
            Map.entry("dark_aqua", "00AAAA"), Map.entry("dark_red", "AA0000"), Map.entry("dark_purple", "AA00AA"),
            Map.entry("gold", "FFAA00"), Map.entry("gray", "AAAAAA"), Map.entry("grey", "AAAAAA"),
            Map.entry("dark_gray", "555555"), Map.entry("dark_grey", "555555"), Map.entry("blue", "5555FF"),
            Map.entry("green", "55FF55"), Map.entry("aqua", "55FFFF"), Map.entry("red", "FF5555"),
            Map.entry("light_purple", "FF55FF"), Map.entry("yellow", "FFFF55"), Map.entry("white", "FFFFFF"));

    /** The sixteen legacy colours by code, for {@code &c} and for writing a named colour back as one. */
    private static final String CODES = "0123456789abcdef";
    private static final String[] CODE_HEX = {
            "000000", "0000AA", "00AA00", "00AAAA", "AA0000", "AA00AA", "FFAA00", "AAAAAA",
            "555555", "5555FF", "55FF55", "55FFFF", "FF5555", "FF55FF", "FFFF55", "FFFFFF"};

    enum Deco {
        BOLD('l'), ITALIC('o'), UNDERLINED('n'), STRIKETHROUGH('m'), OBFUSCATED('k');

        final char code;

        Deco(char code) {
            this.code = code;
        }
    }

    private static final Map<String, Deco> DECOS = Map.ofEntries(
            Map.entry("bold", Deco.BOLD), Map.entry("b", Deco.BOLD),
            Map.entry("italic", Deco.ITALIC), Map.entry("i", Deco.ITALIC), Map.entry("em", Deco.ITALIC),
            Map.entry("underlined", Deco.UNDERLINED), Map.entry("u", Deco.UNDERLINED),
            Map.entry("strikethrough", Deco.STRIKETHROUGH), Map.entry("st", Deco.STRIKETHROUGH),
            Map.entry("obfuscated", Deco.OBFUSCATED), Map.entry("obf", Deco.OBFUSCATED));

    private PromptText() {
    }

    /** One open tag: the style it found, so closing it puts that back; a gradient also where it began. */
    private record Frame(String name, String colour, Set<Deco> decos, boolean gradient, List<String> stops, int start) {
    }

    /** One visible character and the style it is drawn in. */
    private record Styled(int codePoint, String colour, Set<Deco> decos) {
    }

    /** {@code text} as a legacy {@code §} string; empty in, empty out. */
    public static String toLegacy(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        List<Styled> chars = new ArrayList<>();
        Deque<Frame> open = new ArrayDeque<>();
        String colour = null;
        Set<Deco> decos = EnumSet.noneOf(Deco.class);

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' &&i + 1 < text.length() && text.charAt(i + 1) == '<') {
                chars.add(new Styled('<', colour, copy(decos)));
                i += 2;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                if (text.charAt(i + 1) == '#' && i + 8 <= text.length() && isHex(text.substring(i + 2, i + 8))) {
                    colour = text.substring(i + 2, i + 8).toUpperCase(Locale.ROOT);
                    decos = EnumSet.noneOf(Deco.class);
                    i += 8;
                    continue;
                }
                char code = Character.toLowerCase(text.charAt(i + 1));
                int index = CODES.indexOf(code);
                Deco deco = decoOf(code);
                if (index >= 0 || deco != null || code == 'r') {
                    if (index >= 0) {
                        colour = CODE_HEX[index];
                        decos = EnumSet.noneOf(Deco.class);
                    } else if (deco != null) {
                        decos = copy(decos);
                        decos.add(deco);
                    } else {
                        closeAll(open, chars);
                        colour = null;
                        decos = EnumSet.noneOf(Deco.class);
                    }
                    i += 2;
                    continue;
                }
            }
            if (c == '<') {
                Matcher tag = TAG.matcher(text);
                tag.region(i, text.length());
                if (tag.lookingAt()) {
                    boolean closing = !tag.group(1).isEmpty();
                    String name = tag.group(2).toLowerCase(Locale.ROOT);
                    String[] args = tag.group(3).isEmpty() ? new String[0] : tag.group(3).substring(1).split(":", -1);
                    boolean consumed = true;
                    if (closing) {
                        if (known(name)) {
                            Frame match = null;
                            for (Frame frame : open) {
                                if (sameTag(frame.name(), name)) {
                                    match = frame;
                                    break;
                                }
                            }
                            if (match != null) {
                                Frame popped;
                                do {
                                    popped = open.pop();
                                    finish(popped, chars);
                                } while (popped != match);
                                colour = match.colour();
                                decos = match.decos();
                            }
                            // A close for a tag never opened is dropped, as MiniMessage drops it.
                        } else {
                            consumed = false;
                        }
                    } else if (colourOf(name, args) != null) {
                        open.push(new Frame(name, colour, decos, false, null, 0));
                        colour = colourOf(name, args);
                    } else if (DECOS.containsKey(name)) {
                        open.push(new Frame(name, colour, decos, false, null, 0));
                        decos = copy(decos);
                        decos.add(DECOS.get(name));
                    } else if (name.equals("rainbow")) {
                        open.push(new Frame(name, colour, decos, true, null, chars.size()));
                    } else if (name.equals("gradient") && gradientStops(args).size() >= 2) {
                        open.push(new Frame(name, colour, decos, true, gradientStops(args), chars.size()));
                    } else if (name.equals("reset")) {
                        closeAll(open, chars);
                        colour = null;
                        decos = EnumSet.noneOf(Deco.class);
                    } else {
                        consumed = false;
                    }
                    if (consumed) {
                        i = tag.end();
                        continue;
                    }
                }
            }
            int cp = text.codePointAt(i);
            chars.add(new Styled(cp, colour, copy(decos)));
            i += Character.charCount(cp);
        }
        // Anything left open runs to the end of the text, as in MiniMessage.
        closeAll(open, chars);
        return write(chars);
    }

    private static boolean known(String name) {
        return name.equals("gradient") || name.equals("rainbow") || NAMED.containsKey(name)
                || DECOS.containsKey(name) || name.equals("color") || name.equals("colour") || name.equals("c")
                || (name.startsWith("#") && name.length() == 7);
    }

    /** {@code </#fa4943>} closes {@code <#fa4943>}, and {@code </color>} any colour tag. */
    private static boolean sameTag(String opened, String closing) {
        if (opened.equals(closing)) {
            return true;
        }
        boolean colourClose = closing.equals("color") || closing.equals("colour") || closing.equals("c");
        return colourClose && (opened.startsWith("#") || NAMED.containsKey(opened)
                || opened.equals("color") || opened.equals("colour") || opened.equals("c"));
    }

    /** Colours what a gradient spanned, once it is closed and its length known. */
    private static void finish(Frame frame, List<Styled> chars) {
        if (frame.gradient()) {
            paint(chars, frame.start(), chars.size(), frame.stops());
        }
    }

    private static void closeAll(Deque<Frame> open, List<Styled> chars) {
        while (!open.isEmpty()) {
            finish(open.pop(), chars);
        }
    }

    private static Set<Deco> copy(Set<Deco> decos) {
        return decos.isEmpty() ? EnumSet.noneOf(Deco.class) : EnumSet.copyOf(decos);
    }

    /** Colours {@code [from, to)} along {@code stops}, or a rainbow when null. Spaces count, as in MiniMessage. */
    private static void paint(List<Styled> chars, int from, int to, List<String> stops) {
        int length = to - from;
        if (length <= 0) {
            return;
        }
        for (int k = 0; k < length; k++) {
            double t = length == 1 ? 0 : (double) k / (length - 1);
            String hex = stops == null ? rainbow(t) : along(stops, t);
            Styled s = chars.get(from + k);
            chars.set(from + k, new Styled(s.codePoint(), hex, s.decos()));
        }
    }

    private static String along(List<String> stops, double t) {
        double scaled = t * (stops.size() - 1);
        int a = Math.min((int) Math.floor(scaled), stops.size() - 2);
        double f = scaled - a;
        int x = Integer.parseInt(stops.get(a), 16);
        int y = Integer.parseInt(stops.get(a + 1), 16);
        int r = (int) Math.round(((x >> 16) & 255) + (((y >> 16) & 255) - ((x >> 16) & 255)) * f);
        int g = (int) Math.round(((x >> 8) & 255) + (((y >> 8) & 255) - ((x >> 8) & 255)) * f);
        int b = (int) Math.round((x & 255) + ((y & 255) - (x & 255)) * f);
        return String.format(Locale.ROOT, "%02X%02X%02X", r, g, b);
    }

    private static String rainbow(double t) {
        int rgb = java.awt.Color.HSBtoRGB((float) t, 1f, 1f) & 0xFFFFFF;
        return String.format(Locale.ROOT, "%06X", rgb);
    }

    private static List<String> gradientStops(String[] args) {
        List<String> stops = new ArrayList<>();
        for (String arg : args) {
            String hex = colourOf(arg.toLowerCase(Locale.ROOT), new String[0]);
            if (hex != null) {
                stops.add(hex);
            }
        }
        return stops;
    }

    /** A colour tag's hex, or null when {@code name} is not one. */
    private static String colourOf(String name, String[] args) {
        if (name.startsWith("#") && name.length() == 7 && isHex(name.substring(1))) {
            return name.substring(1).toUpperCase(Locale.ROOT);
        }
        if (NAMED.containsKey(name)) {
            return NAMED.get(name);
        }
        if ((name.equals("color") || name.equals("colour") || name.equals("c")) && args.length > 0) {
            return colourOf(args[0].toLowerCase(Locale.ROOT), new String[0]);
        }
        return null;
    }

    private static Deco decoOf(char code) {
        for (Deco deco : Deco.values()) {
            if (deco.code == code) {
                return deco;
            }
        }
        return null;
    }

    private static boolean isHex(String s) {
        if (s.length() != 6) {
            return false;
        }
        for (int k = 0; k < 6; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Codes only where the style changes; a colour code clears decorations in legacy text, so they follow it. */
    private static String write(List<Styled> chars) {
        StringBuilder out = new StringBuilder();
        String colour = null;
        Set<Deco> decos = EnumSet.noneOf(Deco.class);
        for (Styled s : chars) {
            boolean colourChanged = !java.util.Objects.equals(colour, s.colour());
            boolean lostDeco = !s.decos().containsAll(decos);
            if (colourChanged || lostDeco) {
                out.append(colourCode(s.colour()));
                for (Deco deco : s.decos()) {
                    out.append('§').append(deco.code);
                }
            } else if (!decos.containsAll(s.decos())) {
                for (Deco deco : s.decos()) {
                    if (!decos.contains(deco)) {
                        out.append('§').append(deco.code);
                    }
                }
            }
            colour = s.colour();
            decos = s.decos();
            out.appendCodePoint(s.codePoint());
        }
        return out.toString();
    }

    private static String colourCode(String hex) {
        if (hex == null) {
            return "§r";
        }
        for (int k = 0; k < CODE_HEX.length; k++) {
            if (CODE_HEX[k].equals(hex)) {
                return "§" + CODES.charAt(k);
            }
        }
        StringBuilder out = new StringBuilder("§x");
        for (char c : hex.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append('§').append(c);
        }
        return out.toString();
    }
}
