package ai.resourcepack.engine.core.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * CraftEngine's templates, applied the way CraftEngine applies them.
 *
 * <p>A template is any value under a {@code templates:} section, kept by id.
 * A map anywhere in a definition that has a {@code template:} (or
 * {@code templates:}) key - one id or a list - is replaced by those templates
 * merged in order, then {@code overrides:} put over the top (top-level keys
 * replaced whole), then {@code merges:} merged in deep. Any other key beside
 * them is a merge too. {@code arguments:} fill {@code ${name}} placeholders
 * inside the templates, and an argument a template is already being given
 * from further out wins over one written closer in, which is CraftEngine's
 * rule. Every definition is given {@code __NAMESPACE__} and {@code __ID__}.
 *
 * <p>Placeholders: {@code ${name}}, {@code ${name:-default}},
 * {@code ${name^}} (capitalised) and {@code ${name^^}} (upper case). A value
 * that is nothing BUT one placeholder takes the argument's own type, so a
 * list argument can fill a list. A key that resolves to nothing drops its
 * entry. A template id with no namespace is {@code minecraft:}'s, again as
 * CraftEngine reads it.
 *
 * <p>Typed arguments - a map with a {@code type} - are evaluated:
 * {@code plain}, {@code object}, {@code map}, {@code list}, {@code null},
 * {@code condition}, {@code when}, {@code to_upper_case},
 * {@code to_lower_case}, {@code capitalize}, {@code self_increase_int} and
 * {@code expression} (arithmetic only).
 */
final class CraftEngineTemplates {

    /** Why a definition could not be expanded; the message names the template or argument. */
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message, null, false, false);
        }
    }

    private static final Set<String> KEYWORDS = Set.of("template", "templates", "arguments", "overrides", "merges");

    private final Map<String, Object> templates = new LinkedHashMap<>();

    /** Adds a template, its id already namespaced. The first definition of an id wins. */
    void define(String id, Object body) {
        templates.putIfAbsent(id, body);
    }

    boolean isEmpty() {
        return templates.isEmpty();
    }

    /**
     * Expands {@code value} for the definition {@code namespace:id}.
     *
     * @throws Failure naming the template or argument that is missing
     */
    Object expand(Object value, String namespace, String id) {
        Map<String, Supplier<Object>> arguments = new LinkedHashMap<>();
        arguments.put("__NAMESPACE__", constant(namespace));
        arguments.put("__ID__", constant(id));
        return process(value, arguments, 0);
    }

    private Object process(Object value, Map<String, Supplier<Object>> arguments, int depth) {
        if (depth > 64) {
            throw new Failure("templates nest more than 64 deep, which is a template that uses itself");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> body = CraftEngineYaml.cast(map);
            if (body.containsKey("template") || body.containsKey("templates")) {
                return applyTemplates(body, arguments, depth);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                Object key = substitute(entry.getKey(), arguments);
                if (key == null) {
                    continue;
                }
                out.put(key.toString(), process(entry.getValue(), arguments, depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(process(element, arguments, depth + 1));
            }
            return out;
        }
        if (value instanceof String text) {
            return substitute(text, arguments);
        }
        return value;
    }

    private Object applyTemplates(Map<String, Object> body, Map<String, Supplier<Object>> parent, int depth) {
        Object declared = body.containsKey("template") ? body.get("template") : body.get("templates");
        List<String> ids = new ArrayList<>();
        if (declared instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof String) {
                for (Object entry : list) {
                    if (entry != null) ids.add(entry.toString());
                }
            }
        } else if (declared != null) {
            ids.add(declared.toString());
        }

        Map<String, Supplier<Object>> arguments = mergeArguments(parent, body.get("arguments"), depth);

        List<Object> results = new ArrayList<>();
        for (String raw : ids) {
            Object resolved = substitute(raw, arguments);
            if (resolved == null) continue;
            String id = resolved.toString();
            String key = id.indexOf(':') >= 0 ? id : "minecraft:" + id;
            if (!templates.containsKey(key)) {
                throw new Failure("uses the template " + key + ", which no loaded CraftEngine pack defines");
            }
            Object expanded = process(templates.get(key), arguments, depth + 1);
            if (expanded != null) results.add(expanded);
        }

        Object overrides = process(body.get("overrides"), arguments, depth + 1);
        Object merges = process(body.get("merges"), arguments, depth + 1);
        // Any other key beside the keywords is a merge as well, its key read
        // with the arguments from further out.
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (KEYWORDS.contains(entry.getKey())) continue;
            Object key = substitute(entry.getKey(), parent);
            if (key == null) continue;
            extra.put(key.toString(), process(entry.getValue(), arguments, depth + 1));
        }
        if (!extra.isEmpty()) {
            if (merges instanceof Map<?, ?> explicit) {
                extra.putAll(CraftEngineYaml.cast(explicit));
            }
            merges = extra;
        }

        Object first = results.isEmpty() ? null : results.get(0);
        if (first instanceof Map || (first == null && (overrides instanceof Map || merges instanceof Map))) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object result : results) {
                if (result instanceof Map<?, ?> map) deepMerge(out, CraftEngineYaml.cast(map));
            }
            if (overrides instanceof Map<?, ?> map) out.putAll(CraftEngineYaml.cast(map));
            if (merges instanceof Map<?, ?> map) deepMerge(out, CraftEngineYaml.cast(map));
            return out;
        }
        if (first instanceof List || (first == null && (overrides instanceof List || merges instanceof List))) {
            List<Object> out = new ArrayList<>();
            for (Object result : results) {
                if (result instanceof List<?> list) out.addAll(list);
            }
            if (overrides instanceof List<?> list) {
                out = new ArrayList<>(list);
            }
            if (merges instanceof List<?> list) out.addAll(list);
            return out;
        }
        if (overrides != null) return overrides;
        if (merges != null) return merges;
        return results.isEmpty() ? null : results.get(results.size() - 1);
    }

    /** Maps merge, lists append, anything else is replaced. */
    @SuppressWarnings("unchecked")
    static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object existing = target.get(entry.getKey());
            Object incoming = entry.getValue();
            if (existing instanceof Map<?, ?> a && incoming instanceof Map<?, ?> b) {
                Map<String, Object> merged = new LinkedHashMap<>(CraftEngineYaml.cast(a));
                deepMerge(merged, CraftEngineYaml.cast(b));
                target.put(entry.getKey(), merged);
            } else if (existing instanceof List<?> a && incoming instanceof List<?> b) {
                List<Object> merged = new ArrayList<>(a);
                merged.addAll(b);
                target.put(entry.getKey(), merged);
            } else {
                target.put(entry.getKey(), incoming);
            }
        }
    }

    /** The parent's arguments, plus any declared here that the parent does not already give. */
    private Map<String, Supplier<Object>> mergeArguments(Map<String, Supplier<Object>> parent, Object declared,
                                                         int depth) {
        Map<String, Supplier<Object>> out = new LinkedHashMap<>(parent);
        if (!(declared instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<String, Object> entry : CraftEngineYaml.cast(map).entrySet()) {
            if (out.containsKey(entry.getKey())) continue;
            Object value = process(entry.getValue(), out, depth + 1);
            out.put(entry.getKey(), argument(value));
        }
        return out;
    }

    /** A value as an argument; a map with a string {@code type} is a typed one and is evaluated. */
    private Supplier<Object> argument(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return constant(value);
        }
        Map<String, Object> map = CraftEngineYaml.cast(raw);
        if (!(map.get("type") instanceof String declared) || map.containsKey("__skip_template_argument__")) {
            return constant(map);
        }
        String type = declared.toLowerCase(Locale.ROOT);
        if (type.startsWith("craftengine:")) type = type.substring("craftengine:".length());
        switch (type) {
            case "plain":
                return constant(String.valueOf(map.getOrDefault("value", "")));
            case "object":
                return constant(map.get("value"));
            case "map":
                return constant(first(map, "map", "value"));
            case "list":
                return constant(first(map, "list", "value"));
            case "null":
                return constant(null);
            case "to_upper_case":
                return constant(String.valueOf(map.get("value")).toUpperCase(Locale.ROOT));
            case "to_lower_case":
                return constant(String.valueOf(map.get("value")).toLowerCase(Locale.ROOT));
            case "capitalize": {
                String text = String.valueOf(map.get("value"));
                return constant(text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1));
            }
            case "condition": {
                boolean condition = Boolean.parseBoolean(String.valueOf(map.get("condition")).trim());
                Object branch = condition ? first(map, "on_true", "on-true") : first(map, "on_false", "on-false");
                return argument(branch);
            }
            case "when": {
                Object source = map.get("source");
                Object cases = map.get("when");
                if (cases instanceof Map<?, ?> when && source != null
                        && CraftEngineYaml.cast(when).containsKey(source.toString())) {
                    return argument(CraftEngineYaml.cast(when).get(source.toString()));
                }
                return argument(map.get("fallback"));
            }
            case "self_increase_int": {
                int from = number(map.get("from"), 0);
                int to = number(map.get("to"), Integer.MAX_VALUE);
                int step = number(map.get("step"), 1);
                int interval = Math.max(1, number(first(map, "step_interval", "step-interval"), 1));
                int[] state = {from, 0};
                return () -> {
                    int current = state[0];
                    if (++state[1] >= interval) {
                        state[1] = 0;
                        state[0] = Math.min(to, state[0] + step);
                    }
                    return String.valueOf(current);
                };
            }
            case "expression": {
                Object expression = map.get("expression");
                String valueType = String.valueOf(first(map, "value_type", "value-type") == null
                        ? "double" : first(map, "value_type", "value-type")).toLowerCase(Locale.ROOT);
                double result;
                try {
                    result = new Arithmetic(String.valueOf(expression)).evaluate();
                } catch (RuntimeException e) {
                    throw new Failure("has an expression argument (" + expression
                            + ") that is not plain arithmetic, which is all RP Engine evaluates");
                }
                switch (valueType) {
                    case "int":
                    case "short":
                    case "byte":
                        return constant((int) result);
                    case "long":
                        return constant((long) result);
                    case "float":
                        return constant((float) result);
                    case "boolean":
                        return constant(result != 0);
                    default:
                        return constant(result);
                }
            }
            default:
                throw new Failure("uses the template argument type " + declared + ", which RP Engine does not know");
        }
    }

    private static Object first(Map<String, Object> map, String a, String b) {
        return map.get(a) != null ? map.get(a) : map.get(b);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value == null ? fallback : (int) Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Supplier<Object> constant(Object value) {
        return () -> value;
    }

    // ---- placeholders -------------------------------------------------------

    /**
     * {@code text} with its placeholders filled. A string that is exactly one
     * placeholder becomes that argument's value, of whatever type; anything
     * else becomes a string.
     */
    static Object substitute(String text, Map<String, Supplier<Object>> arguments) {
        if (text == null || text.indexOf('$') < 0) {
            return text;
        }
        List<Object> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '$') {
                literal.append('$');
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int end = closing(text, i + 2);
                if (end < 0) {
                    literal.append(text.substring(i));
                    break;
                }
                if (literal.length() > 0) {
                    parts.add(literal.toString());
                    literal.setLength(0);
                }
                parts.add(new Object[] {resolve(text.substring(i + 2, end), arguments)});
                i = end + 1;
                continue;
            }
            literal.append(c);
            i++;
        }
        if (literal.length() > 0) parts.add(literal.toString());
        if (parts.size() == 1 && parts.get(0) instanceof Object[] only) {
            return only[0];
        }
        StringBuilder out = new StringBuilder();
        boolean allNull = true;
        for (Object part : parts) {
            Object value = part instanceof Object[] holder ? holder[0] : part;
            if (value != null) {
                allNull = false;
                out.append(value);
            }
        }
        return allNull ? null : out.toString();
    }

    /** The index of the brace closing one opened just before {@code from}, or -1. */
    private static int closing(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static Object resolve(String inside, Map<String, Supplier<Object>> arguments) {
        String name = inside;
        String fallback = null;
        int separator = indexOutsideBraces(inside, ":-");
        if (separator >= 0) {
            name = inside.substring(0, separator);
            fallback = inside.substring(separator + 2);
        }
        int caseChange = 0;
        if (name.endsWith("^^")) {
            caseChange = 2;
            name = name.substring(0, name.length() - 2);
        } else if (name.endsWith("^")) {
            caseChange = 1;
            name = name.substring(0, name.length() - 1);
        }
        Object value;
        if (arguments.containsKey(name)) {
            value = arguments.get(name).get();
        } else if (fallback != null) {
            value = literal(unescape(fallback), arguments);
        } else {
            throw new Failure("needs the template argument ${" + name + "}, and nothing gives it one");
        }
        if (caseChange == 2 && value != null) return value.toString().toUpperCase(Locale.ROOT);
        if (caseChange == 1 && value != null) {
            String text = value.toString();
            return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        return value;
    }

    private static int indexOutsideBraces(String text, String needle) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (depth == 0 && text.startsWith(needle, i)) return i;
        }
        return -1;
    }

    private static String unescape(String text) {
        return text.replace("\\{", "{").replace("\\}", "}");
    }

    /**
     * A default value as CraftEngine reads one: a number, {@code true} or
     * {@code false}, a quoted string, or otherwise the text as written, any
     * placeholders inside it filled.
     */
    private static Object literal(String text, Map<String, Supplier<Object>> arguments) {
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && (trimmed.startsWith("\"") && trimmed.endsWith("\"")
                || trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return substitute(trimmed.substring(1, trimmed.length() - 1), arguments);
        }
        if (trimmed.equals("true") || trimmed.equals("false")) return Boolean.parseBoolean(trimmed);
        if (trimmed.equals("null")) return null;
        try {
            if (trimmed.matches("-?\\d+")) return Integer.parseInt(trimmed);
            if (trimmed.matches("-?\\d*\\.\\d+([eE]-?\\d+)?[dDfF]?|-?\\d+[dDfF]")) {
                return Double.parseDouble(trimmed.replaceAll("[dDfF]$", ""));
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the text.
        }
        return substitute(trimmed, arguments);
    }

    /** {@code + - * / %}, unary minus and parentheses over numbers. Nothing else. */
    private static final class Arithmetic {
        private final String text;
        private int at;

        Arithmetic(String text) {
            this.text = text.replace(" ", "");
        }

        double evaluate() {
            double value = sum();
            if (at != text.length()) throw new IllegalArgumentException(text);
            return value;
        }

        private double sum() {
            double value = product();
            while (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                char op = text.charAt(at++);
                double right = product();
                value = op == '+' ? value + right : value - right;
            }
            return value;
        }

        private double product() {
            double value = unary();
            while (at < text.length() && "*/%".indexOf(text.charAt(at)) >= 0) {
                char op = text.charAt(at++);
                double right = unary();
                value = op == '*' ? value * right : op == '/' ? value / right : value % right;
            }
            return value;
        }

        private double unary() {
            if (at < text.length() && text.charAt(at) == '-') {
                at++;
                return -unary();
            }
            if (at < text.length() && text.charAt(at) == '(') {
                at++;
                double value = sum();
                if (at >= text.length() || text.charAt(at++) != ')') throw new IllegalArgumentException(text);
                return value;
            }
            int start = at;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) at++;
            if (start == at) throw new IllegalArgumentException(text);
            return Double.parseDouble(text.substring(start, at));
        }
    }
}
