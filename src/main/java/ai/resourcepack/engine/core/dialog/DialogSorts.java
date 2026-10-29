package ai.resourcepack.engine.core.dialog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The boxes of a dialog whose pieces a player sees in an order worked out for
 * them. Internal.
 *
 * <h2>What it is for</h2>
 *
 * A Studio dialog can sort a box's pieces — a shop's items by rarity and then
 * by price, the ones a player has not unlocked last, in an order a button in
 * the dialog picks — and show a page of them at a time. Nothing in a dialog's
 * picture can move per player, so Studio draws the box as SLOTS, and every
 * slot holds a copy of every piece, each kept under a condition
 * ({@link DialogConditions}):
 *
 * <pre>
 *   {shop_slot_3} == "5"
 * </pre>
 *
 * This answers those placeholders. The rules ride the body as the insertion of
 * an empty component, one per sorted box:
 *
 * <pre>
 *   {"text":"","insertion":"rp:sort:{\"name\":\"shop\",\"slots\":6,\"page\":\"{page?2:2|1:1}\",
 *     \"children\":[{\"show\":\"perm:shop.vip\",\"props\":{\"price\":{\"value\":\"450\",\"rules\":[...]}}}, ...],
 *     \"orders\":[{\"if\":\"{mode?price:price|rarity:rarity} == \\\"price\\\"\",\"keys\":[{\"prop\":\"price\",\"desc\":true}]}, ...]}"}
 * </pre>
 *
 * and {@link #apply} works the order out for the player before anything else
 * reads the dialog: which pieces they may see ({@code show}), each one's
 * properties (the first rule whose condition holds, else the default), the
 * first order whose condition holds, and the page. It answers
 * {@code {<name>_slot_<k>}} with the piece slot {@code k} shows (its index from
 * 0, empty for none), and {@code {<name>_page}}, {@code {<name>_pages}} and
 * {@code {<name>_count}}, which a dialog may print too.
 *
 * <h2>The order</h2>
 *
 * By each key in turn, lowest first unless {@code desc}; values compare as
 * numbers when both are, a number before words, words by their letters with
 * case folded; ties keep the order the pieces have in Studio. Studio's
 * {@code arrangeSorted} is the same arithmetic, and its {@code verify:dialogs}
 * runs this class's test cases through it.
 *
 * <h2>Still transported, not modelled</h2>
 *
 * Nothing here knows where a slot is or what a piece looks like: it answers
 * placeholders, and the conditions do the rest.
 */
public final class DialogSorts {

    private DialogSorts() {
    }

    /** The insertion Studio puts on the empty component carrying a sorted box's rules. */
    public static final String SORT = "rp:sort:";

    /** A sorted box's name, and a property's. */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,23}");

    /** What a value counts as a number: Studio's {@code conditionNumber}, so both ends agree. */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** Bounds on what one dialog may ask of this: Studio's are lower. */
    private static final int MAX_CHILDREN = 64;
    private static final int MAX_SLOTS = 64;
    private static final int MAX_ORDERS = 16;
    private static final int MAX_KEYS = 8;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Whether a dialog sorts anything — so one that does not costs one scan. */
    public static boolean any(String json) {
        return json != null && json.contains(SORT);
    }

    /**
     * A dialog worked out for one player: its JSON with the rules taken out,
     * and what they answer.
     */
    public record Sorted(String json, Map<String, String> values) {

        /** {@code lookup}, answering this dialog's sort placeholders first. */
        public Function<String, Optional<String>> over(Function<String, Optional<String>> lookup) {
            if (values.isEmpty()) {
                return lookup;
            }
            return name -> {
                String mine = values.get(name.toLowerCase(Locale.ROOT));
                return mine != null ? Optional.of(mine) : lookup.apply(name);
            };
        }
    }

    /**
     * Works out every sorted box in the dialog for one player, and empties the
     * components that carried their rules. The JSON as it is when there are none.
     *
     * @param lookup     a placeholder's value, as {@link DialogPlaceholders} would fill it
     * @param permission whether the player has a permission
     */
    public static Sorted apply(String json, Function<String, Optional<String>> lookup, Predicate<String> permission) {
        if (!any(json)) {
            return new Sorted(json, Map.of());
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return new Sorted(json, Map.of());
        }
        List<JsonObject> specs = new ArrayList<>();
        collect(root, specs);
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Boolean> known = new HashMap<>();
        Predicate<String> holds = e -> e == null || e.isBlank()
                || known.computeIfAbsent(e, x -> DialogConditions.test(x, lookup, permission));
        for (JsonObject spec : specs) {
            values.putAll(arrange(spec, lookup, holds));
        }
        return new Sorted(GSON.toJson(root), values);
    }

    /** Every sorted box's rules, each component that carried them emptied. */
    private static void collect(JsonElement element, List<JsonObject> out) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collect(child, out);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        JsonElement insertion = obj.get("insertion");
        if (insertion != null && insertion.isJsonPrimitive() && insertion.getAsString().startsWith(SORT)) {
            try {
                JsonElement spec = JsonParser.parseString(insertion.getAsString().substring(SORT.length()));
                if (spec.isJsonObject()) {
                    out.add(spec.getAsJsonObject());
                }
            } catch (JsonParseException | IllegalStateException ignored) {
                // A spec this cannot read answers nothing: its slots stay empty.
            }
            for (String key : new ArrayList<>(obj.keySet())) {
                obj.remove(key);
            }
            obj.add("text", new JsonPrimitive(""));
            return;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            collect(entry.getValue(), out);
        }
    }

    /** One sorted box's placeholders for the player. Nothing for one with no name it may have. */
    static Map<String, String> arrange(JsonObject spec, Function<String, Optional<String>> lookup, Predicate<String> holds) {
        String name = string(spec, "name");
        if (name == null || !NAME.matcher(name).matches()) {
            return Map.of();
        }
        JsonArray children = array(spec, "children");
        int count = Math.min(children.size(), MAX_CHILDREN);
        List<JsonObject> pieces = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JsonElement c = children.get(i);
            pieces.add(c.isJsonObject() ? c.getAsJsonObject() : new JsonObject());
        }

        // The first order whose condition holds; with none, Studio's order.
        List<String> keys = new ArrayList<>();
        List<Boolean> desc = new ArrayList<>();
        JsonArray orders = array(spec, "orders");
        for (int o = 0; o < Math.min(orders.size(), MAX_ORDERS); o++) {
            if (!orders.get(o).isJsonObject()) {
                continue;
            }
            JsonObject order = orders.get(o).getAsJsonObject();
            if (!holds.test(string(order, "if"))) {
                continue;
            }
            JsonArray ks = array(order, "keys");
            for (int k = 0; k < Math.min(ks.size(), MAX_KEYS); k++) {
                if (!ks.get(k).isJsonObject()) {
                    continue;
                }
                String prop = string(ks.get(k).getAsJsonObject(), "prop");
                if (prop != null) {
                    keys.add(prop);
                    JsonElement d = ks.get(k).getAsJsonObject().get("desc");
                    desc.add(d != null && d.isJsonPrimitive() && d.getAsJsonPrimitive().isBoolean() && d.getAsBoolean());
                }
            }
            break;
        }

        List<Integer> shown = new ArrayList<>();
        Map<Integer, List<String>> sortBy = new HashMap<>();
        for (int i = 0; i < pieces.size(); i++) {
            JsonObject piece = pieces.get(i);
            if (!holds.test(string(piece, "show"))) {
                continue;
            }
            shown.add(i);
            List<String> vs = new ArrayList<>();
            for (String key : keys) {
                vs.add(value(piece, key, lookup, holds));
            }
            sortBy.put(i, vs);
        }
        // List.sort is stable, so a tie keeps Studio's order.
        shown.sort((a, b) -> {
            List<String> va = sortBy.get(a);
            List<String> vb = sortBy.get(b);
            for (int k = 0; k < keys.size(); k++) {
                int c = compare(va.get(k), vb.get(k));
                if (c != 0) {
                    return desc.get(k) ? -c : c;
                }
            }
            return 0;
        });

        int slots = Math.max(1, Math.min(MAX_SLOTS, integer(spec, "slots", 1)));
        int pages = Math.max(1, (shown.size() + slots - 1) / slots);
        String asked = string(spec, "page");
        Double wanted = asked == null ? null : number(DialogConditions.fill(asked, lookup));
        int page = wanted == null ? 1 : (int) Math.max(1, Math.min(pages, Math.floor(wanted)));

        Map<String, String> out = new LinkedHashMap<>();
        out.put(name + "_page", String.valueOf(page));
        out.put(name + "_pages", String.valueOf(pages));
        out.put(name + "_count", String.valueOf(shown.size()));
        for (int k = 0; k < slots; k++) {
            int at = (page - 1) * slots + k;
            out.put(name + "_slot_" + (k + 1), at < shown.size() ? String.valueOf(shown.get(at)) : "");
        }
        return out;
    }

    /** A piece's property: the first rule whose condition holds, else its default, filled. Empty when it has none. */
    private static String value(JsonObject piece, String prop, Function<String, Optional<String>> lookup, Predicate<String> holds) {
        JsonElement props = piece.get("props");
        if (props == null || !props.isJsonObject()) {
            return "";
        }
        JsonElement p = props.getAsJsonObject().get(prop);
        if (p == null || !p.isJsonObject()) {
            return "";
        }
        JsonArray rules = array(p.getAsJsonObject(), "rules");
        for (JsonElement r : rules) {
            if (r.isJsonObject() && holds.test(string(r.getAsJsonObject(), "if"))) {
                String v = string(r.getAsJsonObject(), "value");
                return v == null ? "" : DialogConditions.fill(v, lookup);
            }
        }
        String v = string(p.getAsJsonObject(), "value");
        return v == null ? "" : DialogConditions.fill(v, lookup);
    }

    /**
     * How two values sort: as numbers when both are, a number before words,
     * words by their letters, case folded.
     */
    static int compare(String a, String b) {
        Double x = number(a);
        Double y = number(b);
        if (x != null && y != null) {
            return x.doubleValue() == y.doubleValue() ? 0 : x < y ? -1 : 1;
        }
        if (x != null) {
            return -1;
        }
        if (y != null) {
            return 1;
        }
        int c = a.trim().toLowerCase(Locale.ROOT).compareTo(b.trim().toLowerCase(Locale.ROOT));
        return Integer.signum(c);
    }

    private static Double number(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (!NUMBER.matcher(t).matches()) {
            return null;
        }
        double d = Double.parseDouble(t);
        return Double.isFinite(d) ? d : null;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int integer(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return e.getAsInt();
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
