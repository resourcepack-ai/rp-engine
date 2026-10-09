package ai.resourcepack.engine.core.dialog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;

/**
 * The parts of a dialog that are there only for some players. Internal.
 *
 * <h2>What it is for</h2>
 *
 * A Studio dialog can say of any piece of its picture "show this when": a
 * second vault only to a player with the permission for it, the vault the
 * player picked on a segmented control and not the others, a "Buy VIP" card to
 * everybody who is not. Studio cuts every piece that can come and go into
 * glyphs of its own, one for each way the pieces over that spot can be, and
 * wraps each — and every live word, face, item, bar and click inside such a
 * piece — in a component whose insertion says when it is wanted:
 *
 * <pre>
 *   {"text":"","insertion":"rp:if:{vault_number} == \"2\" &amp;&amp; perm:vaults.vip","extra":[ ... ]}
 * </pre>
 *
 * {@link #strip} keeps the ones whose condition holds for the player and
 * empties the rest, before anything else reads the dialog: a placeholder in a
 * piece nobody will see is never looked up, and the item markers, bar markers
 * and slot clicks of a hidden vault are not there for {@link DialogItems} and
 * {@link DialogSlots} to find.
 *
 * <h2>Why emptying one never moves anything</h2>
 *
 * The body is lines of exactly one width, and the game's word-wrap breaks it
 * where Studio did only while that stays true. Studio makes every wrapped thing
 * advance NOTHING: a glyph steps back over itself, a live run is followed by
 * its negative twin, and a click on a stretch of line two pieces can each own
 * is written as each candidate's clickable run followed by a step back over it,
 * and then the stretch itself — so whichever candidates survive, the line still
 * comes to its width, and the first survivor is the one the client clicks.
 * That is also what an engine older than this sees: every candidate, and the
 * whole of the line.
 *
 * <h2>The conditions</h2>
 *
 * Dialog Engine's {@code if:} language, ported, so an owner running both learns
 * one:
 *
 * <pre>
 *   {muted}                      true unless empty, false, 0, no, off, or unknown
 *   !{muted}
 *   {warnings} &gt;= 3              numbers compare as numbers, anything else as words
 *   {rank} == admin &amp;&amp; perm:server.staff
 *   ({page} &gt; 1 || {mode} != "quiet mode")
 * </pre>
 *
 * A placeholder is anything {@link DialogPlaceholders} fills — what the opener
 * gave, the player's own settings, published values, the built-ins and
 * PlaceholderAPI — and a CHOICE fills too, which is how Studio says "the
 * setting, or the control's default when the player has none":
 * <code>{vault_number?1:1|2:2|1:1}</code>. A condition this cannot read is
 * false, so a typo hides a piece rather than showing it to everybody.
 *
 * <h2>Still transported, not modelled</h2>
 *
 * The engine does not learn what the piece is: a wrapper is kept or emptied,
 * and nothing inside one is read here.
 */
public final class DialogConditions {

    private DialogConditions() {
    }

    /** The insertion Studio puts on a piece that is there only when its condition holds. */
    public static final String IF = "rp:if:";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Logger LOG = Logger.getLogger("RPEngine");

    /** Conditions already reported as unreadable, so a broken one is said once and not on every open. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /** Whether a dialog has any piece with a condition — so one with none costs one scan. */
    public static boolean any(String json) {
        return json != null && json.contains(IF);
    }

    /**
     * The JSON with every piece whose condition fails for this player emptied
     * to {@code {"text":""}}, and the condition taken off every piece whose
     * condition holds. The string itself when there is nothing to do.
     *
     * @param lookup     a placeholder's value, as {@link DialogPlaceholders} would fill it
     * @param permission whether the player has a permission
     */
    public static String strip(String json, Function<String, Optional<String>> lookup, Predicate<String> permission) {
        if (!any(json)) {
            return json;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return json;
        }
        // One dialog repeats a condition on every piece it covers: each is worked out once.
        Map<String, Boolean> known = new HashMap<>();
        walk(root, expression -> known.computeIfAbsent(expression, e -> test(e, lookup, permission)));
        return GSON.toJson(root);
    }

    private static void walk(JsonElement element, Predicate<String> holds) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                walk(child, holds);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        JsonElement insertion = obj.get("insertion");
        if (insertion != null && insertion.isJsonPrimitive() && insertion.getAsString().startsWith(IF)) {
            if (!holds.test(insertion.getAsString().substring(IF.length()))) {
                // Emptied in place rather than taken out of its array: the
                // array may be the one `extra` of a component, and an empty
                // `extra` is a dialog the game refuses.
                for (String key : new ArrayList<>(obj.keySet())) {
                    obj.remove(key);
                }
                obj.add("text", new JsonPrimitive(""));
                return;
            }
            obj.remove("insertion");
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            walk(entry.getValue(), holds);
        }
    }

    /**
     * Whether a condition holds. Empty holds; one that cannot be read does
     * not, and is reported once.
     */
    public static boolean test(String expression, Function<String, Optional<String>> lookup, Predicate<String> permission) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        try {
            Parser p = new Parser(tokenize(expression), lookup, permission);
            boolean result = p.or();
            if (p.pos < p.tokens.size()) {
                throw new IllegalArgumentException("unexpected '" + p.tokens.get(p.pos) + "'");
            }
            return result;
        } catch (RuntimeException e) {
            if (REPORTED.add(expression)) {
                LOG.warning("[RPEngine] A dialog piece's condition '" + expression + "' could not be read (" + e.getMessage()
                        + "), so the piece is hidden.");
            }
            return false;
        }
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("&&", i) || s.startsWith("||", i) || s.startsWith("==", i) || s.startsWith("!=", i)
                    || s.startsWith(">=", i) || s.startsWith("<=", i)) {
                out.add(s.substring(i, i + 2));
                i += 2;
            } else if (c == '>' || c == '<' || c == '!' || c == '(' || c == ')') {
                out.add(String.valueOf(c));
                i++;
            } else if (c == '"' || c == '\'') {
                int end = s.indexOf(c, i + 1);
                if (end < 0) {
                    end = s.length();
                }
                out.add("\u0000" + s.substring(i + 1, end));
                i = end + 1;
            } else {
                int start = i;
                int depth = 0;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '{') {
                        depth++;
                    } else if (d == '}') {
                        depth = Math.max(0, depth - 1);
                    } else if (depth == 0 && (Character.isWhitespace(d) || "&|=!<>()".indexOf(d) >= 0)) {
                        break;
                    }
                    i++;
                }
                out.add(s.substring(start, i));
            }
        }
        return out;
    }

    private static final class Parser {
        private final List<String> tokens;
        private final Function<String, Optional<String>> lookup;
        private final Predicate<String> permission;
        private int pos;

        Parser(List<String> tokens, Function<String, Optional<String>> lookup, Predicate<String> permission) {
            this.tokens = tokens;
            this.lookup = lookup;
            this.permission = permission;
        }

        boolean or() {
            boolean v = and();
            while (peek("||")) {
                pos++;
                boolean r = and();
                v = v || r;
            }
            return v;
        }

        boolean and() {
            boolean v = unary();
            while (peek("&&")) {
                pos++;
                boolean r = unary();
                v = v && r;
            }
            return v;
        }

        boolean unary() {
            if (peek("!")) {
                pos++;
                return !unary();
            }
            if (peek("(")) {
                pos++;
                boolean v = or();
                if (!peek(")")) {
                    throw new IllegalArgumentException("a '(' is never closed");
                }
                pos++;
                return v;
            }
            String left = operand();
            if (pos < tokens.size()) {
                String op = tokens.get(pos);
                if (op.equals("==") || op.equals("!=") || op.equals(">") || op.equals("<") || op.equals(">=") || op.equals("<=")) {
                    pos++;
                    String right = operand();
                    return compare(left, op, right);
                }
            }
            return truthy(left);
        }

        private String operand() {
            if (pos >= tokens.size()) {
                throw new IllegalArgumentException("it ends too early");
            }
            String t = tokens.get(pos++);
            if (t.equals("&&") || t.equals("||") || t.equals(")") || t.equals("==") || t.equals("!=")
                    || t.equals(">") || t.equals("<") || t.equals(">=") || t.equals("<=")) {
                throw new IllegalArgumentException("'" + t + "' where a value should be");
            }
            if (t.startsWith("\u0000")) {
                return fill(t.substring(1), lookup);
            }
            if (t.startsWith("perm:")) {
                String node = t.substring(5);
                return String.valueOf(!node.isEmpty() && permission.test(node));
            }
            return fill(t, lookup);
        }

        private boolean peek(String t) {
            return pos < tokens.size() && tokens.get(pos).equals(t);
        }
    }

    /**
     * An operand with its placeholders filled the way the text pass fills them
     * — names, and choices resolved — but raw: it is compared, not written into
     * JSON. A name nothing answers is left as written, which no condition takes
     * as true.
     */
    static String fill(String text, Function<String, Optional<String>> lookup) {
        Matcher m = DialogPlaceholders.NAME.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (m.find()) {
            Optional<String> value = lookup.apply(m.group(1)).map(DialogPlaceholders::clean);
            Optional<String> filled = m.group(2) != null ? DialogPlaceholders.choose(m.group(2), value) : value;
            if (filled.isPresent()) {
                out.append(text, at, m.start()).append(filled.get());
                at = m.end();
            }
        }
        return out.append(text, at, text.length()).toString();
    }

    /** True unless empty, false, 0, no, off, null, or a placeholder nothing filled. */
    static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off") || s.equals("null")) {
            return false;
        }
        return !(s.startsWith("{") && s.endsWith("}"));
    }

    private static boolean compare(String a, String op, String b) {
        Double x = number(a);
        Double y = number(b);
        int cmp;
        if (x != null && y != null) {
            cmp = Double.compare(x, y);
        } else {
            cmp = a.trim().compareToIgnoreCase(b.trim());
        }
        return switch (op) {
            case "==" -> cmp == 0;
            case "!=" -> cmp != 0;
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            default -> cmp <= 0;
        };
    }

    private static Double number(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
