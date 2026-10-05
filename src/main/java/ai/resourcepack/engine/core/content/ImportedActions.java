package ai.resourcepack.engine.core.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Another plugin's behaviour, as an RP Engine {@code actions:} block.
 *
 * <p>Shared by the three importers because the target is the same and so are
 * the small conversions on the way: MiniMessage to {@code &} codes, ticks to
 * seconds, a zero-based amplifier to the one-based level an author writes, and
 * a permission condition to a {@code permission} step. Each importer decides
 * what their events MEAN; this only builds what ours look like.
 *
 * <p>The output is the plain map a definition body holds, trigger to a list
 * of one-key step maps, so it goes through {@code ItemActions} at load exactly
 * like one an author wrote, and a step this builds badly is a load warning
 * rather than a silent no-op.
 */
final class ImportedActions {

    private final Map<String, List<Map<String, Object>>> byTrigger = new LinkedHashMap<>();

    /** One step on one trigger, after any already there. */
    ImportedActions add(String trigger, String verb, Object argument) {
        byTrigger.computeIfAbsent(trigger, key -> new ArrayList<>()).add(step(verb, argument));
        return this;
    }

    /** Several steps on one trigger, in order, after any already there. */
    ImportedActions addAll(String trigger, List<Map<String, Object>> steps) {
        if (!steps.isEmpty()) {
            byTrigger.computeIfAbsent(trigger, key -> new ArrayList<>()).addAll(steps);
        }
        return this;
    }

    boolean isEmpty() {
        return byTrigger.isEmpty();
    }

    /** Puts these into {@code body} as its {@code actions:}, after any it already has. */
    void into(Map<String, Object> body) {
        if (byTrigger.isEmpty()) {
            return;
        }
        Map<String, Object> actions = new LinkedHashMap<>();
        Object existing = body.get("actions");
        if (existing instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) existing).entrySet()) {
                actions.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : byTrigger.entrySet()) {
            List<Object> steps = new ArrayList<>();
            Object already = actions.get(entry.getKey());
            if (already instanceof List) {
                steps.addAll((List<?>) already);
            }
            steps.addAll(entry.getValue());
            actions.put(entry.getKey(), steps);
        }
        body.put("actions", actions);
    }

    static Map<String, Object> step(String verb, Object argument) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put(verb, argument == null ? "" : argument);
        return step;
    }

    // ---- the conversions ------------------------------------------------------

    /** MiniMessage (or text already in {@code &} codes) as {@code &} codes, placeholders included. */
    static String text(String written) {
        if (written == null) {
            return "";
        }
        return CraftEngineText.convert(placeholders(written), key -> null).text();
    }

    /**
     * The other plugins' spellings of "the player's name" as ours.
     *
     * <p>{@code %p%} (Nexo and Oraxen commands), {@code <player>} (their click
     * actions) and {@code <arg:player.name>} (CraftEngine). Done before any
     * MiniMessage conversion, which would otherwise take the last two for
     * tags and drop them.
     */
    static String placeholders(String written) {
        return written
                .replace("%p%", "{player}")
                .replace("<player>", "{player}")
                .replace("<Player>", "{player}")
                .replace("<PLAYER>", "{player}")
                .replace("<arg:player.name>", "{player}")
                .replace("<arg:player.uuid>", "{uuid}")
                .replace("<arg:player.world>", "{world}");
    }

    /**
     * {@code effect: TYPE seconds level}.
     *
     * @param ticks     how long, in ticks, as the other plugins all count it;
     *                  rounded up to a whole second, since ours counts those
     * @param amplifier zero-based, as the game and the other plugins count it
     */
    static String effect(String type, double ticks, int amplifier) {
        String name = type.trim();
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        int seconds = Math.max(1, (int) Math.ceil(ticks / 20d));
        return name.toUpperCase(Locale.ROOT) + " " + seconds + " " + (Math.max(0, amplifier) + 1);
    }

    /** {@code sound: key [volume [pitch]]}. */
    static String sound(String key, Double volume, Double pitch) {
        StringBuilder out = new StringBuilder(key.trim());
        if (volume != null || pitch != null) {
            out.append(' ').append(trim(volume == null ? 1d : volume));
        }
        if (pitch != null) {
            out.append(' ').append(trim(pitch));
        }
        return out.toString();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static final Pattern HAS_PERMISSION = Pattern.compile(
            "^\\s*#?player\\.hasPermission\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*$");

    /**
     * The permission a condition checks, if checking one is ALL it does.
     *
     * <p>Nexo and Oraxen write conditions as expressions over the player
     * ({@code #player.hasPermission("x")}), and the only one with a step here
     * is that one. Anything else - a negation, a world check, a comparison -
     * is a branch, which actions deliberately do not have, so it is null and
     * the caller warns.
     */
    static String permissionOf(String condition) {
        if (condition == null) {
            return null;
        }
        String trimmed = condition.trim();
        if (trimmed.regionMatches(true, 0, "HAS_PERMISSION:", 0, "HAS_PERMISSION:".length())) {
            String permission = trimmed.substring("HAS_PERMISSION:".length()).trim();
            return permission.isEmpty() ? null : permission;
        }
        Matcher matcher = HAS_PERMISSION.matcher(trimmed);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
