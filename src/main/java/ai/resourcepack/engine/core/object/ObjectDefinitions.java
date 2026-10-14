package ai.resourcepack.engine.core.object;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.Easing;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.ShaderObjectInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A content folder's {@code objects/}: 3D shader objects, and sequences for
 * them.
 *
 * <p>Each top-level key of a file is one entry, and an entry is one of two
 * things. With {@code model:} it IS an object, drawn by that item model in a
 * pack built elsewhere (Studio's export, or your own linker), with the states
 * it names:
 *
 * <pre>
 * nuke:
 *   model: rpai:shader3d_0
 *   size: 3
 *   states: [idle, detonate]
 *   sequences:
 *     drop:
 *       - move: {by: [0, -20, 0], over: 2.5s, ease: in}
 *       - size: {to: 40}
 *       - state: detonate
 *       - sound: {name: entity.generic.explode, volume: 16, pitch: 0.5}
 *       - wait: 45s
 *       - remove
 * </pre>
 *
 * With {@code for: studio:nuke} instead, it only adds its sequences to an
 * object that exists already, which is how a pushed object gets them: Studio
 * has nowhere to write one.
 */
public final class ObjectDefinitions {

    /** What a file can say, as the parse understood it. */
    public record Result(Map<ContentId, ShaderObjectInfo> objects,
                         Map<ContentId, Map<String, ObjectSequence>> sequences,
                         List<Diagnostic> diagnostics) {
    }

    /** The longest a single step may take: ten minutes. */
    private static final int MAX_TICKS = 20 * 600;

    private ObjectDefinitions() {
    }

    public static Result parse(LoadReport loaded) {
        Map<ContentId, ShaderObjectInfo> objects = new LinkedHashMap<>();
        Map<ContentId, Map<String, ObjectSequence>> sequences = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ContentDefinition definition : loaded.definitions(ContentKind.OBJECT)) {
            DefinitionNode body = definition.body();
            String origin = definition.origin();
            String where = definition.id().toString();

            ContentId target = definition.id();
            Optional<String> forId = body.string("for");
            if (forId.isPresent()) {
                Optional<ContentId> parsed = ContentId.parse(forId.get().trim());
                if (parsed.isEmpty()) {
                    diagnostics.add(Diagnostic.warning(origin, where, "for: " + forId.get()
                            + " is not an object id like studio:nuke. Skipped."));
                    continue;
                }
                target = parsed.get();
            } else {
                Optional<String> model = body.string("model");
                if (model.isEmpty() || !model.get().contains(":")) {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            "An object needs model: (the item model that draws it, like rpai:shader3d_0), "
                                    + "or for: (the object these sequences are for). Skipped."));
                    continue;
                }
                double size = body.decimal("size").orElse(1.0);
                if (!(size > 0) || size > 256) {
                    diagnostics.add(Diagnostic.warning(origin, where, "size: " + size + " is outside 0 to 256. Using 1."));
                    size = 1;
                }
                Map<String, Integer> states = new LinkedHashMap<>();
                List<String> named = body.strings("states");
                for (int i = 0; i < named.size() && i < ShaderObjectInfo.MAX_STATES; i++) {
                    states.put(named.get(i).trim().toLowerCase(Locale.ROOT), i);
                }
                if (named.size() > ShaderObjectInfo.MAX_STATES) {
                    diagnostics.add(Diagnostic.warning(origin, where, "Only the first "
                            + ShaderObjectInfo.MAX_STATES + " states can be shown; the rest are ignored."));
                }
                objects.put(target, new ShaderObjectInfo(target, model.get().trim(), size, states, null));
            }

            Object raw = body.raw("sequences");
            if (raw == null) {
                continue;
            }
            if (!(raw instanceof Map<?, ?> map)) {
                diagnostics.add(Diagnostic.warning(origin, where, "sequences: should be names, each a list of steps."));
                continue;
            }
            Map<String, ObjectSequence> own = sequences.computeIfAbsent(target, k -> new LinkedHashMap<>());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
                if (!(entry.getValue() instanceof List<?> list)) {
                    diagnostics.add(Diagnostic.warning(origin, where + " " + name, "A sequence is a list of steps."));
                    continue;
                }
                List<ObjectSequence.Step> steps = new ArrayList<>();
                for (Object step : list) {
                    ObjectSequence.Step read = step(step, origin, where + " " + name, diagnostics);
                    if (read != null) {
                        steps.add(read);
                    }
                }
                own.put(name, new ObjectSequence(name, steps));
            }
        }
        return new Result(Map.copyOf(objects), Map.copyOf(sequences), List.copyOf(diagnostics));
    }

    private static ObjectSequence.Step step(Object raw, String origin, String where, List<Diagnostic> diagnostics) {
        if (raw instanceof String word) {
            switch (word.trim().toLowerCase(Locale.ROOT)) {
                case "remove":
                    return new ObjectSequence.Remove();
                case "restart":
                case "loop":
                    return new ObjectSequence.Restart();
                case "settle":
                    return new ObjectSequence.Settle();
                default:
                    diagnostics.add(Diagnostic.warning(origin, where, "Unknown step: " + word + "."));
                    return null;
            }
        }
        if (!(raw instanceof Map<?, ?> map) || map.size() != 1) {
            diagnostics.add(Diagnostic.warning(origin, where, "A step is one word (remove, restart, settle) "
                    + "or one key: state, seed, move, turn, size, wait, sound, command."));
            return null;
        }
        Map.Entry<?, ?> only = map.entrySet().iterator().next();
        String key = String.valueOf(only.getKey()).trim().toLowerCase(Locale.ROOT);
        Object value = only.getValue();
        Map<?, ?> args = value instanceof Map<?, ?> m ? m : Map.of();
        switch (key) {
            case "state":
                if (value instanceof Map<?, ?>) {
                    return new ObjectSequence.State(string(args.get("name")), Boolean.TRUE.equals(args.get("replay")));
                }
                return new ObjectSequence.State(String.valueOf(value).trim(), false);
            case "replay":
                return new ObjectSequence.State(String.valueOf(value).trim(), true);
            case "seed":
                return new ObjectSequence.Seed((int) number(value, 0));
            case "wait":
                return new ObjectSequence.Wait(ticks(value, origin, where, diagnostics));
            case "move": {
                double[] to = vector(args.get("to"));
                double[] by = vector(args.get("by"));
                if ((to == null) == (by == null)) {
                    diagnostics.add(Diagnostic.warning(origin, where, "move: needs exactly one of to: [x, y, z] or by: [x, y, z]."));
                    return null;
                }
                return new ObjectSequence.Move(to, by, ticks(args.get("over"), origin, where, diagnostics),
                        easing(args.get("ease"), origin, where, diagnostics), Boolean.TRUE.equals(args.get("async")));
            }
            case "turn":
                return new ObjectSequence.Turn(angle(args.get("yaw")), angle(args.get("pitch")), angle(args.get("roll")),
                        Boolean.TRUE.equals(args.get("by")), ticks(args.get("over"), origin, where, diagnostics),
                        easing(args.get("ease"), origin, where, diagnostics), Boolean.TRUE.equals(args.get("async")));
            case "size": {
                if (!(value instanceof Map<?, ?>)) {
                    return new ObjectSequence.Resize(number(value, 1), null, 0, Easing.LINEAR, false);
                }
                Double to = args.get("to") == null ? null : number(args.get("to"), 1);
                Double times = args.get("times") == null ? null : number(args.get("times"), 1);
                if ((to == null) == (times == null)) {
                    diagnostics.add(Diagnostic.warning(origin, where, "size: needs exactly one of to: (blocks) or times: (a multiple)."));
                    return null;
                }
                return new ObjectSequence.Resize(to, times, ticks(args.get("over"), origin, where, diagnostics),
                        easing(args.get("ease"), origin, where, diagnostics), Boolean.TRUE.equals(args.get("async")));
            }
            case "sound":
                if (value instanceof Map<?, ?>) {
                    return new ObjectSequence.Sound(string(args.get("name")),
                            (float) number(args.get("volume"), 1), (float) number(args.get("pitch"), 1));
                }
                return new ObjectSequence.Sound(String.valueOf(value).trim(), 1, 1);
            case "command":
                return new ObjectSequence.Command(String.valueOf(value).trim().replaceFirst("^/", ""));
            default:
                diagnostics.add(Diagnostic.warning(origin, where, "Unknown step: " + key + "."));
                return null;
        }
    }

    /** Ticks from a number of ticks, or {@code "2s"}, {@code "1.5s"}, {@code "40t"}. */
    static int ticks(Object raw, String origin, String where, List<Diagnostic> diagnostics) {
        if (raw == null) {
            return 0;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        double scale = 1;
        if (text.endsWith("s")) {
            scale = 20;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("t")) {
            text = text.substring(0, text.length() - 1);
        }
        try {
            double value = Double.parseDouble(text) * scale;
            return (int) Math.max(0, Math.min(MAX_TICKS, Math.round(value)));
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.warning(origin, where, raw + " is not a time: write ticks (40) or seconds (2s)."));
            return 0;
        }
    }

    private static Easing easing(Object raw, String origin, String where, List<Diagnostic> diagnostics) {
        if (raw == null) {
            return Easing.IN_OUT;
        }
        Optional<Easing> easing = Easing.of(String.valueOf(raw));
        if (easing.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, where, "ease: " + raw + " is not linear, in, out or in_out."));
        }
        return easing.orElse(Easing.IN_OUT);
    }

    private static double[] vector(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() != 3) {
            return null;
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = number(list.get(i), 0);
        }
        return out;
    }

    private static Float angle(Object raw) {
        return raw == null ? null : (float) number(raw, 0);
    }

    private static double number(Object raw, double fallback) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return raw == null ? fallback : Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String string(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
