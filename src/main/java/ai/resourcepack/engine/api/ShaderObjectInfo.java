package ai.resourcepack.engine.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a 3D shader object IS: the item that draws it, the size it was made
 * at, the states its code names and the sequences written for it.
 *
 * <p>A 3D shader object is a picture a core shader draws in the world (a black
 * hole, a portal, a ball of fire) rather than a model of cubes. Its code is in
 * the resource pack; this is what the pack cannot say about it. See
 * {@link ShaderObjects}.
 */
public final class ShaderObjectInfo {

    /** How many states one object can have: the tint carries four bits of it. */
    public static final int MAX_STATES = 16;

    private final ContentId id;
    private final String model;
    private final double size;
    private final Map<String, Integer> states;
    private final Set<String> sequences;

    public ShaderObjectInfo(ContentId id, String model, double size, Map<String, Integer> states, Set<String> sequences) {
        this.id = Objects.requireNonNull(id, "id");
        this.model = Objects.requireNonNull(model, "model");
        this.size = size > 0 ? size : 1;
        Map<String, Integer> kept = new LinkedHashMap<>();
        if (states != null) {
            states.forEach((name, value) -> {
                if (name != null && value != null && value >= 0 && value < MAX_STATES) {
                    kept.put(name, value);
                }
            });
        }
        this.states = Collections.unmodifiableMap(kept);
        this.sequences = sequences == null ? Set.of() : Set.copyOf(sequences);
    }

    public ContentId id() {
        return id;
    }

    /** The item model that draws it, e.g. {@code rpai:shader3d_0}. */
    public String model() {
        return model;
    }

    /** The diameter in blocks it was made at, and is placed at unless asked otherwise. */
    public double size() {
        return size;
    }

    /** Its states by name, in value order. State 0 is what it shows when nobody drives it. */
    public Map<String, Integer> states() {
        return states;
    }

    /** The value of a state, by name or by number. */
    public Optional<Integer> state(String nameOrNumber) {
        if (nameOrNumber == null) {
            return Optional.empty();
        }
        Integer named = states.get(nameOrNumber.toLowerCase(java.util.Locale.ROOT));
        if (named != null) {
            return Optional.of(named);
        }
        try {
            int number = Integer.parseInt(nameOrNumber.trim());
            return number >= 0 && number < MAX_STATES ? Optional.of(number) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The name of a state, or its number when the code does not name it. */
    public String stateName(int value) {
        for (Map.Entry<String, Integer> entry : states.entrySet()) {
            if (entry.getValue() == value) {
                return entry.getKey();
            }
        }
        return Integer.toString(value);
    }

    /** The sequences a content file wrote for it, by name. */
    public Set<String> sequences() {
        return sequences;
    }

    /** The state names, in value order. */
    public List<String> stateNames() {
        return new ArrayList<>(states.keySet());
    }

    /** The same object with these sequences. */
    public ShaderObjectInfo withSequences(Set<String> names) {
        return new ShaderObjectInfo(id, model, size, states, names);
    }
}
