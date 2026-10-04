package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.McVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two things CraftEngine's YAML reader does that a plain YAML parser does
 * not, applied to an already-parsed document.
 *
 * <ul>
 *   <li><strong>Version keys.</strong> A key {@code $$>=1.21.2},
 *       {@code $$<1.21.2}, {@code $$1.21.2~1.21.4}, {@code $$1.21.4} (exact)
 *       or {@code $$fallback} is a condition on the running Minecraft. A map
 *       whose keys are ALL version keys is a choice: it is replaced by the
 *       value of the last key that matches, else by {@code $$fallback}'s,
 *       else by nothing. In a map with ordinary keys beside them, every
 *       version key that matches has its map merged into the surrounding one
 *       (a {@code $$fallback} there does nothing, as in CraftEngine). Anything
 *       after a {@code #} in the key only makes it unique.</li>
 *   <li><strong>Deep keys.</strong> {@code settings::hardness: 3} is
 *       {@code settings: {hardness: 3}}, merged into whatever else is under
 *       {@code settings}.</li>
 * </ul>
 *
 * <p>Versions compare as CraftEngine compares them, as
 * {@code 10000 * major + 100 * minor + patch}, so {@code 26.1} is newer than
 * every {@code 1.21.x}.
 */
final class CraftEngineYaml {

    private static final String VERSION_PREFIX = "$$";
    private static final String DEEP = "::";

    private CraftEngineYaml() {
    }

    /** The server version as a comparable number; with none known, newer than anything. */
    static int number(McVersion version) {
        if (version == null) {
            return Integer.MAX_VALUE;
        }
        return version.major() * 10000 + version.minor() * 100 + version.patch();
    }

    /** Resolves every version key and deep key in {@code node}, at any depth. */
    static Object resolve(Object node, int version) {
        if (node instanceof Map<?, ?> map) {
            if (!map.isEmpty() && allVersionKeys(map)) {
                return resolve(choose(map, version), version);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().toString();
                if (key.startsWith(VERSION_PREFIX)) {
                    String spec = key.substring(VERSION_PREFIX.length());
                    Object value = resolve(entry.getValue(), version);
                    if (!spec.startsWith("fallback") && matches(spec, version) && value instanceof Map<?, ?> block) {
                        mergeInto(out, cast(block));
                    }
                    continue;
                }
                Object value = resolve(entry.getValue(), version);
                if (key.contains(DEEP)) {
                    String[] parts = key.split(DEEP);
                    Object nested = value;
                    for (int i = parts.length - 1; i >= 1; i--) {
                        Map<String, Object> wrapper = new LinkedHashMap<>();
                        wrapper.put(parts[i], nested);
                        nested = wrapper;
                    }
                    put(out, parts[0], nested);
                } else {
                    put(out, key, value);
                }
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(resolve(element, version));
            }
            return out;
        }
        return node;
    }

    private static boolean allVersionKeys(Map<?, ?> map) {
        for (Object key : map.keySet()) {
            if (key == null || !key.toString().startsWith(VERSION_PREFIX)) {
                return false;
            }
        }
        return true;
    }

    /** The last matching value, else the fallback, else null. */
    private static Object choose(Map<?, ?> map, int version) {
        Object matched = null;
        Object fallback = null;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String spec = entry.getKey().toString().substring(VERSION_PREFIX.length());
            if (stripSuffix(spec).equals("fallback")) {
                fallback = entry.getValue();
            } else if (matches(spec, version)) {
                matched = entry.getValue();
            }
        }
        return matched != null ? matched : fallback;
    }

    /** {@code >=1.21.2}, {@code >1.21.2}, {@code <=}, {@code <}, {@code a~b} inclusive, or exact. */
    static boolean matches(String rawSpec, int version) {
        String spec = stripSuffix(rawSpec).trim();
        if (spec.isEmpty()) {
            return false;
        }
        if (spec.startsWith(">=")) return version >= parse(spec.substring(2));
        if (spec.startsWith("<=")) return version <= parse(spec.substring(2));
        if (spec.startsWith(">")) return version > parse(spec.substring(1));
        if (spec.startsWith("<")) return version < parse(spec.substring(1));
        int tilde = spec.indexOf('~');
        if (tilde >= 0) {
            return version >= parse(spec.substring(0, tilde)) && version <= parse(spec.substring(tilde + 1));
        }
        return version == parse(spec);
    }

    private static String stripSuffix(String spec) {
        int hash = spec.indexOf('#');
        return hash < 0 ? spec : spec.substring(0, hash);
    }

    /** CraftEngine's reading of a version: digits and dots, everything else skipped. */
    static int parse(String text) {
        int[] parts = new int[3];
        int index = 0;
        boolean any = false;
        for (int i = 0; i < text.length() && index < 3; i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                parts[index] = parts[index] * 10 + (c - '0');
                any = true;
            } else if (c == '.') {
                index++;
            }
        }
        return any ? parts[0] * 10000 + parts[1] * 100 + parts[2] : 0;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        Object existing = out.get(key);
        if (existing instanceof Map<?, ?> a && value instanceof Map<?, ?> b) {
            Map<String, Object> merged = new LinkedHashMap<>(cast(a));
            mergeInto(merged, cast(b));
            out.put(key, merged);
        } else {
            out.put(key, value);
        }
    }

    /** Deep merge, {@code source} winning anything that is not two maps. */
    static void mergeInto(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            put(target, entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }
}
