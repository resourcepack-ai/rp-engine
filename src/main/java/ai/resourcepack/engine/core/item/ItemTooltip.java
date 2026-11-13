package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A designed tooltip from a Studio pack, as the item components that show it.
 *
 * <p>The picture is a font of tiles in that pack. An item shows it by carrying
 * an empty name, one lore line of the tiles' characters in that font (white,
 * or the colour the pack's text shader animates, never italic and with no
 * shadow), enough empty lore lines to make the game's box as tall as the
 * picture, a tooltip style naming an empty box, and the lines the game would
 * add under it hidden. Studio works all of that out and sends it; nothing here
 * measures anything.
 *
 * <p>Needs Minecraft 1.21.5 (the hidden lines and the component syntax below);
 * the version check is the caller's ({@code Feature.ITEM_TOOLTIPS}).
 *
 * <p>Free of Bukkit, so the component text is unit tested. The stack itself is
 * made by the server's own item parser, see {@code TooltipStacks}.
 */
public final class ItemTooltip {

    /** A resource location: {@code namespace:path}. */
    private static final Pattern LOCATION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Far past any picture Studio draws (two 256-pixel tiles is fifty lines), and a bound on a stranger's push. */
    static final int MAX_EMPTY_LINES = 100;
    /** The line is a tile or two and a space glyph or two; anything long is not one. */
    static final int MAX_TEXT = 16;

    private final ContentId id;
    private final String name;
    private final String font;
    private final String text;
    private final String color;
    private final int emptyLines;
    private final String style;
    private final List<String> hidden;
    private final boolean glint;

    private ItemTooltip(ContentId id, String name, String font, String text, String color,
                        int emptyLines, String style, List<String> hidden, boolean glint) {
        this.id = id;
        this.name = name;
        this.font = font;
        this.text = text;
        this.color = color;
        this.emptyLines = emptyLines;
        this.style = style;
        this.hidden = hidden;
        this.glint = glint;
    }

    /**
     * One tooltip as it arrived, or the reason it cannot be worn. A hidden
     * component that is not a resource location is left out rather than
     * refusing the tooltip: the picture is still right without it.
     *
     * @return the tooltip, never null when {@code problem[0]} is left null
     */
    public static ItemTooltip of(ContentId id, String name, String font, String text, String color,
                                 Integer emptyLines, String style, List<String> hidden, boolean glint,
                                 String[] problem) {
        String why = null;
        if (font == null || !LOCATION.matcher(font).matches()) {
            why = "its font is not a namespace:path";
        } else if (text == null || text.isEmpty() || text.length() > MAX_TEXT) {
            why = "it has no picture line";
        } else if (color == null || !COLOR.matcher(color).matches()) {
            why = "its colour is not #rrggbb";
        } else if (style == null || !LOCATION.matcher(style).matches()) {
            why = "its tooltip style is not a namespace:path";
        }
        if (why != null) {
            if (problem != null && problem.length > 0) {
                problem[0] = why;
            }
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String component : hidden == null ? List.<String>of() : hidden) {
            String clean = component == null ? "" : component.trim().toLowerCase(Locale.ROOT);
            if (LOCATION.matcher(clean).matches() && !kept.contains(clean)) {
                kept.add(clean);
            }
        }
        int lines = emptyLines == null ? 0 : Math.max(0, Math.min(MAX_EMPTY_LINES, emptyLines));
        return new ItemTooltip(id, name == null || name.isBlank() ? id.path() : name, font, text,
                color.toLowerCase(Locale.ROOT), lines, style, List.copyOf(kept), glint);
    }

    public ContentId id() {
        return id;
    }

    /** What its author called it. */
    public String name() {
        return name;
    }

    /** The font its tiles are in. */
    public String font() {
        return font;
    }

    /** Lore line 0: the picture's characters. */
    public String text() {
        return text;
    }

    /** {@code #rrggbb}, lower case. */
    public String color() {
        return color;
    }

    /** Empty lore lines after the picture's own. */
    public int emptyLines() {
        return emptyLines;
    }

    /** The {@code tooltip_style}: the empty box the pack ships. */
    public String style() {
        return style;
    }

    /** The components the tooltip hides. */
    public List<String> hidden() {
        return hidden;
    }

    /** Whether it shimmers. */
    public boolean glint() {
        return glint;
    }

    /**
     * The components this tooltip sets, which an item's own copy of each has
     * to make way for: the item string refuses a component named twice, and an
     * item's own custom name would be drawn over the picture.
     */
    Set<String> replaces() {
        Set<String> keys = new java.util.LinkedHashSet<>(List.of(
                "minecraft:item_name", "minecraft:custom_name", "minecraft:lore",
                "minecraft:tooltip_style", "minecraft:tooltip_display"));
        if (glint) {
            keys.add("minecraft:enchantment_glint_override");
        }
        return keys;
    }

    /**
     * The components, as they go between the brackets of an item string
     * ({@code paper[...]}, what {@code /give} takes), in 1.21.5's syntax. The
     * same text Studio's give command writes for this tooltip.
     */
    public String components() {
        StringBuilder lore = new StringBuilder();
        lore.append("{text:").append(snbt(text))
                .append(",font:").append(snbt(font))
                .append(",color:").append(snbt(color))
                .append(",italic:false,shadow_color:0}");
        for (int i = 0; i < emptyLines; i++) {
            lore.append(",\"\"");
        }
        StringBuilder hiddenList = new StringBuilder();
        for (String component : hidden) {
            if (hiddenList.length() > 0) {
                hiddenList.append(',');
            }
            hiddenList.append(snbt(component));
        }
        StringBuilder out = new StringBuilder();
        out.append("minecraft:item_name=\"\"")
                .append(",minecraft:lore=[").append(lore).append(']')
                .append(",minecraft:tooltip_style=").append(snbt(style))
                .append(",minecraft:tooltip_display={hidden_components:[").append(hiddenList).append("]}");
        if (glint) {
            out.append(",minecraft:enchantment_glint_override=true");
        }
        return out.toString();
    }

    /**
     * A whole item string: {@code material} (a namespaced item id) with
     * {@code own} components, as {@code ItemMeta#getAsComponentString} wrote
     * them ({@code [a=1,b=2]}), and this tooltip's after them. Every one of the
     * item's own components this tooltip sets is left out (see
     * {@link #replaces}); everything else, its identity included, is kept as
     * written.
     */
    public String itemString(String material, String own) {
        Set<String> replaced = replaces();
        StringBuilder kept = new StringBuilder();
        for (String entry : topLevelEntries(own)) {
            if (replaced.contains(componentKey(entry))) {
                continue;
            }
            kept.append(entry).append(',');
        }
        return material + "[" + kept + components() + "]";
    }

    /**
     * The entries of a component list ({@code [a=1,b={c:[2,3]}]}), split at the
     * commas between them: not inside a nested bracket or brace, and not inside
     * a quoted string (with its backslash escapes). The surrounding brackets
     * are optional; blank entries are dropped.
     */
    static List<String> topLevelEntries(String list) {
        List<String> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        String body = list.trim();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{' || c == '(') {
                depth++;
            } else if (c == ']' || c == '}' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                add(out, body.substring(start, i));
                start = i + 1;
            }
        }
        add(out, body.substring(start));
        return out;
    }

    private static void add(List<String> out, String entry) {
        String trimmed = entry.trim();
        if (!trimmed.isEmpty()) {
            out.add(trimmed);
        }
    }

    /**
     * Which component an entry sets or removes: {@code minecraft:lore=[...]}
     * and {@code lore=[...]} are both {@code minecraft:lore}, and so is the
     * removal {@code !minecraft:lore}.
     */
    static String componentKey(String entry) {
        int equals = entry.indexOf('=');
        String key = (equals < 0 ? entry : entry.substring(0, equals)).trim();
        if (key.startsWith("!")) {
            key = key.substring(1).trim();
        }
        key = key.toLowerCase(Locale.ROOT);
        return key.contains(":") ? key : "minecraft:" + key;
    }

    /**
     * A string as SNBT in double quotes, anything past printable ASCII as a
     * {@code \}{@code u} escape (1.21.5's SNBT reads them), the way Studio
     * writes its give command.
     */
    static String snbt(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(code -> {
            if (code == '"' || code == '\\') {
                out.append('\\').appendCodePoint(code);
            } else if (code < 0x20 || code > 0x7e) {
                out.append(code > 0xffff
                        ? String.format(Locale.ROOT, "\\U%08x", code)
                        : String.format(Locale.ROOT, "\\u%04x", code));
            } else {
                out.appendCodePoint(code);
            }
        });
        return out.append('"').toString();
    }
}
