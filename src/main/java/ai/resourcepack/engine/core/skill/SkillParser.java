package ai.resourcepack.engine.core.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads MythicMobs' skill line grammar, as written:
 *
 * <pre>
 * mechanic{k=v;k2=v2} @targeter{k=v} ~trigger:arg ?condition{..} ?!cond ?~cond &lt;50% 0.25
 * </pre>
 *
 * <p>Order after the mechanic does not matter, which is more forgiving than
 * MythicMobs and costs nothing. {@code [ ]} works wherever {@code { }} does.
 * Whitespace inside braces, brackets and quotes is kept; everything else is
 * split on it. Free of Bukkit, so it is tested without a server.
 */
final class SkillParser {

    private SkillParser() {
    }

    /** A parse: the line, or why there is none. */
    static final class Parsed {
        final SkillLine line;
        final String error;

        private Parsed(SkillLine line, String error) {
            this.line = line;
            this.error = error;
        }

        boolean ok() {
            return line != null;
        }
    }

    static Parsed parse(String raw) {
        if (raw == null) {
            return new Parsed(null, "An empty skill line.");
        }
        String text = raw.trim();
        if (text.startsWith("- ")) {
            text = text.substring(2).trim();
        }
        if (text.isEmpty()) {
            return new Parsed(null, "An empty skill line.");
        }
        List<String> tokens;
        try {
            tokens = tokens(text);
        } catch (IllegalArgumentException e) {
            return new Parsed(null, e.getMessage());
        }
        String[] head = split(tokens.get(0));
        String mechanic = head[0].toLowerCase(Locale.ROOT);
        if (mechanic.isEmpty()) {
            return new Parsed(null, "No mechanic before " + tokens.get(0) + ".");
        }
        Map<String, String> extra = new LinkedHashMap<>();
        // skill:Name, the shorthand MythicMobs accepts for skill{s=Name}.
        int colon = mechanic.indexOf(':');
        if (colon > 0 && head[1] == null) {
            String base = mechanic.substring(0, colon);
            if (base.equals("skill") || base.equals("metaskill") || base.equals("meta")) {
                extra.put("s", head[0].substring(colon + 1));
                mechanic = base;
            }
        }
        SkillLine.Args args = args(head[1], extra);
        SkillLine.Ref targeter = null;
        SkillLine.Trigger trigger = null;
        List<SkillLine.Cond> conditions = new ArrayList<>();
        SkillLine.HealthMod health = null;
        double chance = 1;
        boolean waiting = mechanic.equals("delay") || mechanic.equals("wait");
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            char first = token.charAt(0);
            if (first == '@') {
                if (targeter != null) {
                    return new Parsed(null, "Two targeters: " + targeter.name + " and " + token + ".");
                }
                String[] parts = split(token.substring(1));
                if (parts[0].isEmpty()) {
                    return new Parsed(null, "@ with no targeter after it.");
                }
                targeter = new SkillLine.Ref(parts[0].toLowerCase(Locale.ROOT), args(parts[1], Map.of()));
            } else if (first == '~') {
                if (trigger != null) {
                    return new Parsed(null, "Two triggers in one line: " + token + ".");
                }
                String body = token.substring(1);
                String name = body;
                String arg = null;
                int at = body.indexOf(':');
                if (at > 0) {
                    name = body.substring(0, at);
                    arg = body.substring(at + 1);
                }
                name = name.toLowerCase(Locale.ROOT);
                if (name.startsWith("on")) {
                    name = name.substring(2);
                }
                if (name.isEmpty()) {
                    return new Parsed(null, "~ with no trigger after it.");
                }
                trigger = new SkillLine.Trigger(name, arg);
            } else if (first == '?') {
                int at = 1;
                boolean negate = false;
                boolean onTrigger = false;
                while (at < token.length() && (token.charAt(at) == '!' || token.charAt(at) == '~')) {
                    if (token.charAt(at) == '!') {
                        negate = true;
                    } else {
                        onTrigger = true;
                    }
                    at++;
                }
                String[] parts = split(token.substring(at));
                if (parts[0].isEmpty()) {
                    return new Parsed(null, "? with no condition after it.");
                }
                conditions.add(new SkillLine.Cond(new SkillLine.Ref(parts[0].toLowerCase(Locale.ROOT),
                        args(parts[1], Map.of())), negate, onTrigger, "true", null));
            } else if (first == '<' || first == '>' || first == '=') {
                java.util.Optional<Ranged> range = Ranged.parse(token);
                if (range.isEmpty()) {
                    return new Parsed(null, token + " is not a health modifier like <50% or =30%-50%.");
                }
                health = new SkillLine.HealthMod(range.get(), first == '=');
            } else if (isNumber(token)) {
                double value = Double.parseDouble(token);
                if (waiting && !args.has("ticks", "t", "")) {
                    Map<String, String> with = new LinkedHashMap<>(args.values);
                    with.put("ticks", token);
                    args = new SkillLine.Args(with, args.raw.isEmpty() ? "ticks=" + token : args.raw);
                } else if (value < 0 || value > 1) {
                    return new Parsed(null, token + " is not a chance: a chance is 0 to 1.");
                } else {
                    chance = value;
                }
            } else {
                return new Parsed(null, "Did not understand " + token
                        + ". After the mechanic come @targeter, ~trigger, ?condition, a health modifier and a chance.");
            }
        }
        return new Parsed(new SkillLine(mechanic, args, targeter, trigger, conditions, health, chance, text), null);
    }

    /**
     * One line of a metaskill's {@code Conditions:} list:
     * {@code stance{s=phase2} true}, {@code distance{d=<6} orElseCast Charge}.
     */
    static Parsed condition(String raw, boolean onTrigger) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("- ")) {
            text = text.substring(2).trim();
        }
        if (text.isEmpty()) {
            return new Parsed(null, "An empty condition.");
        }
        List<String> tokens;
        try {
            tokens = tokens(text);
        } catch (IllegalArgumentException e) {
            return new Parsed(null, e.getMessage());
        }
        String[] head = split(tokens.get(0));
        String name = head[0].toLowerCase(Locale.ROOT);
        boolean negate = false;
        if (name.startsWith("!")) {
            negate = true;
            name = name.substring(1);
        }
        String action = "true";
        String actionArg = null;
        if (tokens.size() > 1) {
            action = tokens.get(1).toLowerCase(Locale.ROOT);
            if (tokens.size() > 2) {
                actionArg = tokens.get(2);
            }
            switch (action) {
                case "true":
                case "false":
                case "required":
                    break;
                case "cast":
                case "castinstead":
                case "orelsecast":
                case "power":
                    if (actionArg == null) {
                        return new Parsed(null, action + " needs something after it: " + text + ".");
                    }
                    break;
                default:
                    return new Parsed(null, tokens.get(1) + " is not a condition action. Use true, false, "
                            + "cast, castinstead, orElseCast or power.");
            }
        }
        SkillLine.Cond cond = new SkillLine.Cond(new SkillLine.Ref(name, args(head[1], Map.of())),
                negate, onTrigger, action.equals("required") ? "true" : action, actionArg);
        // Carried in a line so one Parsed type does for both.
        SkillLine holder = new SkillLine("condition", SkillLine.Args.NONE, null, null, List.of(cond), null, 1, text);
        return new Parsed(holder, null);
    }

    /**
     * The skills an argument lists inline, as MythicMobs writes them:
     * {@code [ - damage{a=1} - ignite ]}, or a bare metaskill name. A bare
     * name comes back as a {@code skill{s=Name}} line.
     */
    static List<String> inline(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        String text = value.trim();
        if (!text.startsWith("[")) {
            out.add("skill{s=" + text + "}");
            return out;
        }
        if (!text.endsWith("]")) {
            return out;
        }
        String body = text.substring(1, text.length() - 1);
        // Split at a "-" that starts an entry: at depth 0, after the start or whitespace.
        int depth = 0;
        char quote = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == '-' && depth == 0 && (i == 0 || Character.isWhitespace(body.charAt(i - 1)))
                    && i + 1 < body.length() && Character.isWhitespace(body.charAt(i + 1))) {
                if (!current.toString().isBlank()) {
                    out.add(current.toString().trim());
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().trim());
        }
        return out;
    }

    /** Whitespace-separated tokens, keeping braces, brackets and quotes whole. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if ((c == '"' || c == '\'') && depth > 0) {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("A " + c + " with nothing open before it.");
                }
            }
            if (Character.isWhitespace(c) && depth == 0) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (quote != 0) {
            throw new IllegalArgumentException("A quote that is never closed.");
        }
        if (depth != 0) {
            throw new IllegalArgumentException("A { or [ that is never closed.");
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("An empty skill line.");
        }
        return out;
    }

    /** {@code name{args}} into name and the text between the braces (null when there are none). */
    private static String[] split(String token) {
        int open = -1;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '{' || c == '[') {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return new String[]{token.trim(), null};
        }
        String name = token.substring(0, open).trim();
        int close = token.length() - 1;
        char end = token.charAt(close);
        if (end != '}' && end != ']') {
            // Something after the braces, e.g. a stray word glued on: keep the whole thing.
            close = token.lastIndexOf(token.charAt(open) == '{' ? '}' : ']');
        }
        return new String[]{name, token.substring(open + 1, Math.max(open + 1, close))};
    }

    /** {@code k=v;k2="v 2"} into a map. A piece with no {@code =} is kept under the empty key. */
    static SkillLine.Args args(String inner, Map<String, String> extra) {
        Map<String, String> values = new LinkedHashMap<>(extra);
        if (inner == null || inner.isBlank()) {
            return values.isEmpty() ? SkillLine.Args.NONE : new SkillLine.Args(values, "");
        }
        for (String piece : splitTop(inner, ';')) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = indexTop(trimmed, '=');
            if (eq < 0) {
                values.putIfAbsent("", unquote(trimmed));
                continue;
            }
            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            values.put(key, unquote(trimmed.substring(eq + 1).trim()));
        }
        return new SkillLine.Args(values, inner.trim());
    }

    static List<String> splitTop(String text, char separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == separator && depth == 0) {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    private static int indexTop(String text, char wanted) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == wanted && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static boolean isNumber(String token) {
        try {
            Double.parseDouble(token);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * The YAML long form, compiled to a line:
     * <pre>
     * - mechanic: leap
     *   args: {velocity: 1.4}
     *   target: "@target"
     *   trigger: onTimer:100
     *   if: ["distance{d=4to16}"]
     *   health: "&lt;50%"
     *   chance: 0.5
     * </pre>
     * Null when there is no mechanic.
     */
    static String longForm(Map<?, ?> entry) {
        Map<String, Object> lower = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : entry.entrySet()) {
            if (e.getKey() != null) {
                lower.put(e.getKey().toString().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        Object mechanic = lower.get("mechanic");
        if (mechanic == null) {
            mechanic = lower.get("do");
        }
        if (mechanic == null) {
            return null;
        }
        StringBuilder line = new StringBuilder(mechanic.toString().trim());
        Object args = lower.get("args");
        if (args instanceof Map) {
            List<String> pieces = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) args).entrySet()) {
                String value = String.valueOf(e.getValue());
                boolean quote = value.contains(";") || value.contains(" ") || value.contains("}");
                pieces.add(e.getKey() + "=" + (quote ? "\"" + value.replace("\"", "'") + "\"" : value));
            }
            if (!pieces.isEmpty()) {
                line.append('{').append(String.join(";", pieces)).append('}');
            }
        }
        Object target = lower.containsKey("target") ? lower.get("target") : lower.get("targeter");
        if (target != null) {
            String t = target.toString().trim();
            line.append(' ').append(t.startsWith("@") ? t : "@" + t);
        }
        Object trigger = lower.get("trigger");
        if (trigger != null) {
            String t = trigger.toString().trim();
            line.append(' ').append(t.startsWith("~") ? t : "~" + t);
        }
        Object conditions = lower.containsKey("if") ? lower.get("if") : lower.get("conditions");
        if (conditions instanceof List) {
            for (Object c : (List<?>) conditions) {
                String t = String.valueOf(c).trim();
                line.append(' ').append(t.startsWith("?") ? t : "?" + t);
            }
        } else if (conditions != null) {
            String t = conditions.toString().trim();
            line.append(' ').append(t.startsWith("?") ? t : "?" + t);
        }
        Object health = lower.get("health");
        if (health != null) {
            line.append(' ').append(health.toString().trim());
        }
        Object chance = lower.get("chance");
        if (chance != null) {
            line.append(' ').append(chance.toString().trim());
        }
        return line.toString();
    }
}
