package ai.resourcepack.engine.core.edit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writing a vehicle back out as the YAML a person would have typed.
 *
 * <p>Hand-written rather than dumped through SnakeYAML, and the reason is what
 * it looks like afterwards. A dump writes every key in whatever order the map
 * iterates, quotes strings it did not need to, and renders a seat as four
 * indented lines instead of the one-line flow map {@code FORMAT.md} uses and
 * every example in the docs shows. The file this produces has to be one an
 * author is willing to keep editing by hand, because that is the whole premise
 * of the content folder — the editor is a convenience, not a new front door.
 *
 * <p>The shape is exactly {@code FORMAT.md}'s Vehicles section, and
 * {@code VehicleDefinitions} is what reads it back. The round trip is tested:
 * writing a vehicle and parsing it must give the same vehicle.
 *
 * <p>Two things are deliberately omitted rather than written as defaults:
 * a seat's {@code pose} when it is sitting, its {@code yaw} when it is zero,
 * and its {@code name} when it has none. A file full of restated defaults is
 * harder to read than one that says only what was chosen, and the parser's
 * defaults are the same values.
 */
final class VehicleYaml {

    private static final String INDENT = "  ";

    private VehicleYaml() {
    }

    /**
     * The whole entry, including its {@code <key>:} line, without a trailing
     * newline.
     *
     * @param model the {@code model:} line's value — the item id whose model
     *              this vehicle wears. Carried through rather than sent over
     *              the wire: the editor has no opinion about it and cannot
     *              change it, and a rewrite that dropped it would leave a
     *              vehicle with no bodywork
     */
    static String write(String key, String model, EditWire.Vehicle vehicle) {
        StringBuilder out = new StringBuilder();
        out.append(key).append(":\n");
        if (model != null && !model.isEmpty()) {
            out.append(INDENT).append("model: ").append(model).append('\n');
        }
        if (vehicle.name != null && !vehicle.name.isEmpty()) {
            out.append(INDENT).append("name: ").append(quote(vehicle.name)).append('\n');
        }
        out.append(INDENT).append("medium: ").append(vehicle.medium == null ? "land" : vehicle.medium).append('\n');
        out.append(INDENT).append("speed: ").append(number(vehicle.speed)).append('\n');
        out.append(INDENT).append("acceleration: ").append(number(vehicle.acceleration)).append('\n');
        out.append(INDENT).append("turn-speed: ").append(number(vehicle.turnSpeed)).append('\n');
        // Written only when it is on, like `scale`: the parser's default is
        // the same false, so a car's file does not gain a line saying that it
        // steers the way every vehicle steers.
        if (Boolean.TRUE.equals(vehicle.turnInPlace)) {
            out.append(INDENT).append("turn-in-place: true").append('\n');
        }
        // The other way round from every optional above, because the default
        // is true: what is worth writing is the vehicle that takes a cape OFF.
        if (Boolean.FALSE.equals(vehicle.capes)) {
            out.append(INDENT).append("capes: false").append('\n');
        }
        out.append(INDENT).append("weight: ").append(number(vehicle.weight)).append('\n');
        if (vehicle.scale != null && vehicle.scale != 1) {
            out.append(INDENT).append("scale: ").append(number(vehicle.scale)).append('\n');
        }
        // Only when on: off is the default and every file written before the
        // key existed, so writing `jump: false` would be noise in each of them.
        if (vehicle.jump != null && vehicle.jump && "land".equalsIgnoreCase(vehicle.medium)) {
            out.append(INDENT).append("jump: true\n");
        }

        out.append(INDENT).append("hitbox:\n");
        out.append(INDENT).append(INDENT).append("width: ").append(number(vehicle.hitboxWidth)).append('\n');
        out.append(INDENT).append(INDENT).append("height: ").append(number(vehicle.hitboxHeight)).append('\n');
        out.append(INDENT).append(INDENT).append("length: ").append(number(vehicle.hitboxLength)).append('\n');

        // Only an aircraft, and only what the wire carried: a car's file never
        // gains a block the parser would warn about, and an aircraft that
        // declared nothing keeps declaring nothing unless the editor sent it.
        if ("air".equals(vehicle.medium) && (vehicle.takeoffSpeed != null || vehicle.climbRate != null
                || vehicle.diveRate != null || vehicle.stallSink != null)) {
            out.append(INDENT).append("flight:\n");
            flightNumber(out, "takeoff-speed", vehicle.takeoffSpeed);
            flightNumber(out, "climb-rate", vehicle.climbRate);
            flightNumber(out, "dive-rate", vehicle.diveRate);
            flightNumber(out, "stall-sink", vehicle.stallSink);
        }

        out.append(INDENT).append("seats:\n");
        List<EditWire.Seat> seats = vehicle.seats == null ? List.of() : vehicle.seats;
        for (EditWire.Seat seat : seats) {
            seat(out, seat);
        }

        if (vehicle.animations != null && !vehicle.animations.isEmpty()) {
            out.append(INDENT).append("animations:\n");
            for (Map.Entry<String, String> entry : vehicle.animations.entrySet()) {
                out.append(INDENT).append(INDENT).append(entry.getKey()).append(": ")
                        .append(quote(entry.getValue())).append('\n');
            }
        }

        if (vehicle.sounds != null && !vehicle.sounds.isEmpty()) {
            out.append(INDENT).append("sounds:\n");
            for (Map.Entry<String, String> entry : vehicle.sounds.entrySet()) {
                out.append(INDENT).append(INDENT).append(entry.getKey()).append(": ")
                        .append(quote(entry.getValue())).append('\n');
            }
        }

        if (vehicle.particles != null && !vehicle.particles.isEmpty()) {
            out.append(INDENT).append("particles:\n");
            for (EditWire.Emitter emitter : vehicle.particles) {
                emitter(out, emitter);
            }
        }

        if (vehicle.addons != null && !vehicle.addons.isEmpty()) {
            out.append(INDENT).append("addons:\n");
            for (Map.Entry<String, Map<String, Object>> addon : vehicle.addons.entrySet()) {
                if (addon.getKey() == null || addon.getValue() == null) continue;
                out.append(INDENT).append(INDENT).append(addon.getKey()).append(":\n");
                writeMap(out, addon.getValue(), 3);
            }
        }

        // The caller splices this between two lines of somebody's file, so it
        // ends where it ends rather than carrying a newline of its own.
        return out.length() > 0 && out.charAt(out.length() - 1) == '\n'
                ? out.substring(0, out.length() - 1)
                : out.toString();
    }

    private static void flightNumber(StringBuilder out, String key, Double value) {
        if (value != null) {
            out.append(INDENT).append(INDENT).append(key).append(": ").append(number(value)).append('\n');
        }
    }

    /**
     * One seat.
     *
     * <p>A flow map on one line while it fits, which is how the docs write one
     * and how a bench of four reads as a bench. A seat that dresses its
     * occupant cannot: {@code animations} is a map, and a nested flow map is
     * legal YAML that nobody wants to read, so that seat expands into a block.
     */
    private static void seat(StringBuilder out, EditWire.Seat seat) {
        StringBuilder inline = new StringBuilder();
        inline.append("role: ").append("driver".equals(seat.role) ? "driver" : "passenger");
        inline.append(", x: ").append(number(seat.x));
        inline.append(", y: ").append(number(seat.y));
        inline.append(", z: ").append(number(seat.z));
        if (seat.yaw != 0) {
            inline.append(", yaw: ").append(number(seat.yaw));
        }
        if ("standing".equals(seat.pose)) {
            inline.append(", pose: standing");
        }
        if (seat.name != null && !seat.name.isEmpty()) {
            inline.append(", name: ").append(quote(seat.name));
        }
        if (Boolean.TRUE.equals(seat.hidden)) {
            inline.append(", hidden: true");
        }

        boolean dresses = seat.animations != null && !seat.animations.isEmpty();
        if (!dresses) {
            out.append(INDENT).append(INDENT).append("- {").append(inline).append("}\n");
            return;
        }
        // Expanded. The first line carries the dash, everything after it lines
        // up under the first key — which is the ordinary block-sequence shape
        // and the only one that leaves room for a nested map.
        String[] pairs = inline.toString().split(", ");
        out.append(INDENT).append(INDENT).append("- ").append(pairs[0]).append('\n');
        for (int i = 1; i < pairs.length; i++) {
            out.append(INDENT).append(INDENT).append(INDENT).append(pairs[i]).append('\n');
        }
        out.append(INDENT).append(INDENT).append(INDENT).append("animations:\n");
        for (Map.Entry<String, String> entry : seat.animations.entrySet()) {
            out.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
                    .append(entry.getKey()).append(": ").append(quote(entry.getValue())).append('\n');
        }
    }

    /** One particle emitter, always expanded: it has too many fields to inline. */
    private static void emitter(StringBuilder out, EditWire.Emitter emitter) {
        String pad = INDENT + INDENT + INDENT;
        out.append(INDENT).append(INDENT).append("- effect: ")
                .append(emitter.effect == null ? "" : emitter.effect.toLowerCase(Locale.ROOT)).append('\n');
        StringBuilder states = new StringBuilder();
        if (emitter.states != null) {
            for (String state : emitter.states) {
                if (states.length() > 0) {
                    states.append(", ");
                }
                states.append(state);
            }
        }
        out.append(pad).append("states: [").append(states).append("]\n");
        out.append(pad).append("x: ").append(number(emitter.x)).append('\n');
        out.append(pad).append("y: ").append(number(emitter.y)).append('\n');
        out.append(pad).append("z: ").append(number(emitter.z)).append('\n');
        out.append(pad).append("count: ").append(emitter.count).append('\n');
        out.append(pad).append("interval: ").append(emitter.interval).append('\n');
        if (emitter.spread != 0) {
            out.append(pad).append("spread: ").append(number(emitter.spread)).append('\n');
        }
        if (emitter.speed != 0) {
            out.append(pad).append("speed: ").append(number(emitter.speed)).append('\n');
        }
        if (emitter.size != null && emitter.size != 1d) {
            out.append(pad).append("size: ").append(number(emitter.size)).append('\n');
        }
        if (emitter.color != null) {
            out.append(pad).append("color: ").append(quote(String.format(Locale.ROOT, "#%06x",
                    emitter.color & 0xFFFFFF))).append('\n');
        }
        // Written only when false. `enabled: true` on every emitter is four
        // words saying nothing, and the parser's default is true.
        if (Boolean.FALSE.equals(emitter.enabled)) {
            out.append(pad).append("enabled: false\n");
        }
    }

    /** Writes the scalar/list/map shapes addon configuration is allowed to carry. */
    @SuppressWarnings("unchecked")
    private static void writeMap(StringBuilder out, Map<String, Object> values, int depth) {
        String pad = INDENT.repeat(depth);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            Object value = entry.getValue();
            if (value instanceof Map) {
                out.append(pad).append(entry.getKey()).append(":\n");
                writeMap(out, (Map<String, Object>) value, depth + 1);
            } else if (value instanceof List) {
                out.append(pad).append(entry.getKey()).append(": [");
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(", ");
                    out.append(scalar(list.get(i)));
                }
                out.append("]\n");
            } else {
                out.append(pad).append(entry.getKey()).append(": ").append(scalar(value)).append('\n');
            }
        }
    }

    private static String scalar(Object value) {
        if (value instanceof Number) return number(((Number) value).doubleValue());
        if (value instanceof Boolean) return value.toString();
        return quote(value == null ? "" : value.toString());
    }

    /**
     * A number as somebody would write it.
     *
     * <p>Whole numbers without a decimal point, everything else to at most
     * four places with the trailing zeros gone. The alternative is
     * {@code speed: 18.0} and {@code x: -0.4000000000000001}, and the second
     * one is what a double does to a file that gets rewritten.
     */
    static String number(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() <= 0 ? rounded.toBigInteger().toString() : rounded.toPlainString();
    }

    /**
     * A string, quoted.
     *
     * <p>Always quoted rather than only when it has to be: these are names and
     * animation ids that come from an editor, and the values that need quoting
     * — one starting with {@code &}, one that reads as a number, one holding a
     * colon — are exactly the ones an author is most likely to write.
     */
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }
}
