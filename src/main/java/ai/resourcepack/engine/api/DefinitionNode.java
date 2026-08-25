package ai.resourcepack.engine.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The body of a definition, as read from a content file.
 *
 * <p>A read-only map with typed accessors. It exists so the loader can hand a
 * definition's body to the layer that understands it without either of them
 * depending on a YAML library, and so that a content author's typo answers
 * empty instead of throwing a {@link ClassCastException} out of somebody
 * else's code.
 *
 * <p>Every accessor answers empty for a missing key, a null value, or a value
 * of the wrong shape. That is the same promise the rest of the API makes and
 * it matters more here than anywhere: this data came from a text file a human
 * wrote.
 */
public final class DefinitionNode {

    private static final DefinitionNode EMPTY = new DefinitionNode(Map.of());

    private final Map<String, Object> values;

    private DefinitionNode(Map<String, Object> values) {
        this.values = values;
    }

    /** A node with nothing in it. */
    public static DefinitionNode empty() {
        return EMPTY;
    }

    /**
     * Wraps a parsed map. Keys are taken as strings via {@code toString}, since
     * YAML hands back an Integer for a key written as {@code 1}.
     *
     * @return the node, or an empty one if {@code values} is null
     */
    public static DefinitionNode of(Map<?, ?> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return new DefinitionNode(Collections.unmodifiableMap(copy));
    }

    /** The keys present, in the order the file wrote them. */
    public Set<String> keys() {
        return values.keySet();
    }

    /** Whether this node holds nothing. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Whether {@code key} is present with a non-null value. */
    public boolean has(String key) {
        return key != null && values.get(key) != null;
    }

    /**
     * A string value.
     *
     * <p>Numbers and booleans are accepted and converted, because
     * {@code version: 1.0} is a double to YAML and a string to everybody who
     * writes it.
     */
    public Optional<String> string(String key) {
        Object value = raw(key);
        if (value == null || value instanceof Map || value instanceof List) {
            return Optional.empty();
        }
        return Optional.of(value.toString());
    }

    /** An integer value, or empty if it is absent or not a whole number. */
    public Optional<Integer> integer(String key) {
        Object value = raw(key);
        if (value instanceof Number) {
            return Optional.of(((Number) value).intValue());
        }
        if (value instanceof String) {
            try {
                return Optional.of(Integer.parseInt(((String) value).trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** A boolean value, or empty if it is absent or not one. */
    public Optional<Boolean> bool(String key) {
        Object value = raw(key);
        if (value instanceof Boolean) {
            return Optional.of((Boolean) value);
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.equalsIgnoreCase("true")) {
                return Optional.of(Boolean.TRUE);
            }
            if (text.equalsIgnoreCase("false")) {
                return Optional.of(Boolean.FALSE);
            }
        }
        return Optional.empty();
    }

    /**
     * A list of strings.
     *
     * <p>A single scalar counts as a one-element list, because
     * {@code bundles: main} is what people write and refusing it teaches
     * nothing. Entries that are maps or lists are dropped.
     */
    public List<String> strings(String key) {
        Object value = raw(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            return string(key).map(List::of).orElseGet(List::of);
        }
        List<String> out = new ArrayList<>();
        for (Object element : (List<?>) value) {
            if (element != null && !(element instanceof Map) && !(element instanceof List)) {
                out.add(element.toString());
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** A nested node, or empty if the key is absent or is not a map. */
    public Optional<DefinitionNode> node(String key) {
        Object value = raw(key);
        return value instanceof Map ? Optional.of(of((Map<?, ?>) value)) : Optional.empty();
    }

    /**
     * The untouched value, for a layer that needs a shape this class does not
     * model. Prefer a typed accessor, and add one here rather than casting at
     * the call site.
     */
    public Object raw(String key) {
        return key == null ? null : values.get(key);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
