package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.VehicleEmitter;
import ai.resourcepack.engine.api.VehicleFlight;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads vehicle definitions.
 *
 * <p>Pure and free of Bukkit, so all of it is tested without a server — which
 * matters more here than for most kinds, because the failure mode of a vehicle
 * that parsed slightly wrong is a bus that seats everybody in the same place.
 *
 * <p>Two rules it establishes that the whole runtime then assumes:
 *
 * <ul>
 *   <li><strong>Exactly one driver, and it is first.</strong> A second driver
 *       becomes a passenger, keeping its position among the rest. Nothing
 *       downstream searches for the driver; it takes seat zero.</li>
 *   <li><strong>A vehicle with no driver seat does not load at all.</strong>
 *       Not a warning — a refusal, naming the file. One that loaded would be a
 *       model claiming to be a vehicle with no way to move, and the author
 *       would have nothing to go on but it not working.</li>
 * </ul>
 */
public final class VehicleDefinitions {

    /**
     * The most people one vehicle carries.
     *
     * <p>Every seat costs a marker stand and an interaction hitbox that are
     * moved with the vehicle, and a client resolving a passenger chain it
     * cannot see the end of. Eight is the number studio's editor offers, and
     * the two have to agree or a pack built there fails to load here.
     */
    public static final int MAX_SEATS = 8;

    // Bounds, chosen so a bad number is a diagnostic rather than a vehicle
    // that flies off the map. They are studio's VEHICLE_LIMITS, and the same
    // hand-agreement applies: change one side, change the other.
    private static final double MIN_WEIGHT = 1;
    private static final double MAX_WEIGHT = 100;
    private static final double MIN_SPEED = 1;
    private static final double MAX_SPEED = 60;
    private static final double MIN_ACCELERATION = 0.5;
    private static final double MAX_ACCELERATION = 40;
    private static final double MIN_TURN = 15;
    private static final double MAX_TURN = 720;

    // A seat may sit a little off the geometry — a running board, a tow hook —
    // but not in the next chunk.
    private static final double MAX_SEAT_OFFSET = 8;

    private VehicleDefinitions() {
    }

    /** Everything of kind VEHICLE in {@code loaded}, parsed. */
    public static Result parse(LoadReport loaded) {
        Map<ContentId, VehicleInfo> vehicles = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.VEHICLE)) {
            parseOne(definition, diagnostics).ifPresent(vehicle -> vehicles.put(vehicle.id(), vehicle));
        }
        return new Result(Map.copyOf(vehicles), List.copyOf(diagnostics));
    }

    private static Optional<VehicleInfo> parseOne(ContentDefinition definition, List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        // The model is an item id, validated for SHAPE here and for existence
        // when one is spawned: the item may belong to a pack that has not
        // loaded yet. Same rule an entity's model follows.
        String model = body.string("model").orElse(null);
        if (model != null && ContentId.parse(model).isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "model: " + model + " is not a namespace:id."));
            return Optional.empty();
        }

        VehicleMedium medium = VehicleMedium.LAND;
        Optional<String> declaredMedium = body.string("medium");
        if (declaredMedium.isPresent()) {
            Optional<VehicleMedium> parsed = VehicleMedium.parse(declaredMedium.get());
            if (parsed.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "medium: " + declaredMedium.get() + " is not land, water or air."));
                return Optional.empty();
            }
            medium = parsed.get();
        }

        double weight = number(body, "weight", 10, MIN_WEIGHT, MAX_WEIGHT, origin, where, diagnostics);
        double speed = number(body, "speed", 12, MIN_SPEED, MAX_SPEED, origin, where, diagnostics);
        double acceleration =
                number(body, "acceleration", 6, MIN_ACCELERATION, MAX_ACCELERATION, origin, where, diagnostics);
        double turnSpeed = number(body, "turn-speed", 120, MIN_TURN, MAX_TURN, origin, where, diagnostics);

        // The body somebody can click and stand in front of. Absent means a
        // one-block cube rather than nothing: a vehicle with no hitbox has no
        // way in but its seat markers, which on a small cart overlap each
        // other and make which seat you get a matter of luck.
        VehicleHitbox hitbox = VehicleHitbox.DEFAULT;
        Optional<DefinitionNode> declaredHitbox = body.node("hitbox");
        if (declaredHitbox.isPresent()) {
            DefinitionNode box = declaredHitbox.get();
            hitbox = VehicleHitbox.of(
                    box.decimal("width").orElse(1d),
                    box.decimal("height").orElse(1d),
                    box.decimal("length").orElse(1d));
            for (String axis : new String[] {"width", "height", "length"}) {
                Optional<Double> value = box.decimal(axis);
                if (value.isPresent() && (value.get() < VehicleHitbox.MIN || value.get() > VehicleHitbox.MAX)) {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            "hitbox " + axis + ": " + value.get() + " is outside "
                                    + VehicleHitbox.MIN + " to " + VehicleHitbox.MAX + ". Clamped."));
                }
            }
        }

        // How it flies, which only an air vehicle reads. Absent means the way
        // every air vehicle flew before these numbers existed — see
        // VehicleFlight.forSpeed. Each field defaults on its own, so a pack
        // that writes only `takeoff-speed` keeps the old climb.
        VehicleFlight legacy = VehicleFlight.forSpeed(speed);
        VehicleFlight flight = legacy;
        Optional<DefinitionNode> declaredFlight = body.node("flight");
        if (declaredFlight.isPresent()) {
            DefinitionNode air = declaredFlight.get();
            flight = VehicleFlight.of(
                    number(air, "takeoff-speed", legacy.takeoffSpeed(),
                            VehicleFlight.MIN, VehicleFlight.MAX_TAKEOFF_SPEED, origin, where, diagnostics),
                    number(air, "climb-rate", legacy.climbRate(),
                            VehicleFlight.MIN, VehicleFlight.MAX_CLIMB_RATE, origin, where, diagnostics),
                    number(air, "dive-rate", legacy.diveRate(),
                            VehicleFlight.MIN, VehicleFlight.MAX_DIVE_RATE, origin, where, diagnostics),
                    number(air, "stall-sink", legacy.stallSink(),
                            VehicleFlight.MIN, VehicleFlight.MAX_STALL_SINK, origin, where, diagnostics));
            // Said rather than corrected: the numbers are legal and the vehicle
            // works, it just cannot get off the ground, which from the outside
            // looks exactly like the whole feature being broken.
            if (medium == VehicleMedium.AIR && flight.needsTakeoffRun() && flight.takeoffSpeed() > speed) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "flight takeoff-speed: " + flight.takeoffSpeed() + " is faster than speed: "
                                + speed + ", so this aircraft can never take off."));
            }
            if (medium != VehicleMedium.AIR) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "flight: is only read by medium: air, so it does nothing here."));
            }
        } else if (medium == VehicleMedium.AIR && body.has("flight")) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "flight: is not a map. Try flight: {takeoff-speed: 8, climb-rate: 6}."));
        }

        List<DefinitionNode> declaredSeats = body.nodes("seats");
        if (declaredSeats.isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "No seats. A vehicle needs at least a driver seat - try "
                            + "seats: [{role: driver, y: 0.6}]."));
            return Optional.empty();
        }
        if (declaredSeats.size() > MAX_SEATS) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    declaredSeats.size() + " seats, and a vehicle carries at most " + MAX_SEATS
                            + ". The ones past that are dropped."));
            declaredSeats = declaredSeats.subList(0, MAX_SEATS);
        }

        List<VehicleSeat> seats = new ArrayList<>();
        boolean driverTaken = false;
        for (DefinitionNode node : declaredSeats) {
            Optional<VehicleSeat.Role> role = role(node, origin, where, diagnostics);
            if (role.isEmpty()) {
                return Optional.empty();
            }
            // The first driver keeps the wheel; a second is seated as a
            // passenger rather than refused, because "which one is driving"
            // has no answer worth guessing at and dropping the seat would
            // silently shrink the vehicle.
            boolean driver = role.get() == VehicleSeat.Role.DRIVER && !driverTaken;
            if (role.get() == VehicleSeat.Role.DRIVER && driverTaken) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "More than one driver seat. Only the first steers; the rest carry passengers."));
            }
            driverTaken |= driver;

            Optional<VehicleSeat.Pose> pose = pose(node, origin, where, diagnostics);
            if (pose.isEmpty()) {
                return Optional.empty();
            }

            seats.add(VehicleSeat.of(
                    driver ? VehicleSeat.Role.DRIVER : VehicleSeat.Role.PASSENGER,
                    pose.get(),
                    offset(node, "x", origin, where, diagnostics),
                    offset(node, "y", origin, where, diagnostics),
                    offset(node, "z", origin, where, diagnostics),
                    (float) wrapDegrees(node.decimal("yaw").orElse(0d)),
                    node.string("name").orElse(null),
                    animations(node, origin, where, diagnostics)));
        }

        if (!driverTaken) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "No driver seat, so nobody could steer this. Give one of the seats "
                            + "role: driver."));
            return Optional.empty();
        }

        // The driver to the front, everything else keeping the order it was
        // written in. A partition rather than a sort: the intent is not a
        // comparison, it is "the driver is seat one".
        List<VehicleSeat> ordered = new ArrayList<>();
        for (VehicleSeat seat : seats) {
            if (seat.isDriver()) {
                ordered.add(seat);
            }
        }
        for (VehicleSeat seat : seats) {
            if (!seat.isDriver()) {
                ordered.add(seat);
            }
        }

        return Optional.of(VehicleInfo.of(definition.id(), model, body.string("name").orElse(null),
                medium, weight, speed, acceleration, turnSpeed, hitbox, flight, List.copyOf(ordered),
                animations(body, origin, where, diagnostics),
                emitters(body, origin, where, diagnostics)));
    }

    /**
     * The {@code animations:} map — a state name to one of the model's
     * animation names.
     *
     * <p>An unreadable state is a warning and a dropped entry rather than a
     * refused vehicle: the rest of the map is still exactly what the author
     * meant, and taking the seats and the handling down over a misspelt
     * {@code movnig} would be out of all proportion. The whole feature is
     * optional, so the failure it guards against is a state that silently does
     * nothing, which is what the diagnostic is for.
     */
    private static Map<VehicleState, String> animations(DefinitionNode body, String origin, String where,
                                                        List<Diagnostic> diagnostics) {
        Optional<DefinitionNode> declared = body.node("animations");
        if (declared.isEmpty()) {
            if (body.has("animations")) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "animations: is not a map of state to animation name. Try "
                                + "animations: {idle: idle, moving: drive}."));
            }
            return Map.of();
        }
        DefinitionNode node = declared.get();
        Map<VehicleState, String> animations = new EnumMap<>(VehicleState.class);
        for (String key : node.keys()) {
            Optional<VehicleState> state = VehicleState.parse(key);
            if (state.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "animations: " + key + " is not a vehicle state. Known states are "
                                + stateNames() + "."));
                continue;
            }
            String animation = node.string(key).orElse("").trim();
            if (animation.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "animations: " + key + " names no animation, so that state does nothing."));
                continue;
            }
            animations.put(state.get(), animation);
        }
        return animations;
    }

    /**
     * The {@code particles:} list.
     *
     * <p>Each entry is dropped on its own if it is unusable, for the same
     * reason an animation entry is: one bad exhaust pipe is not a reason to
     * lose a bus. <strong>The effect name is not validated here</strong> —
     * this class is free of Bukkit so that all of it is testable without a
     * server, and whether {@code SMOKE} is a particle on THIS server is a
     * question only a server can answer. {@code VehicleParticles} says so at
     * spawn time, once.
     */
    private static List<VehicleEmitter> emitters(DefinitionNode body, String origin, String where,
                                                 List<Diagnostic> diagnostics) {
        List<DefinitionNode> declared = body.nodes("particles");
        if (declared.isEmpty()) {
            if (body.has("particles")) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "particles: is not a list. Try particles: [{effect: smoke, states: [moving]}]."));
            }
            return List.of();
        }
        List<VehicleEmitter> emitters = new ArrayList<>();
        for (DefinitionNode node : declared) {
            String effect = node.string("effect").orElse("").trim();
            if (effect.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "A particle has no effect:, so there is nothing for it to throw. Dropped."));
                continue;
            }
            Set<VehicleState> states = EnumSet.noneOf(VehicleState.class);
            for (String raw : node.strings("states")) {
                Optional<VehicleState> state = VehicleState.parse(raw);
                if (state.isEmpty()) {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            "A particle's states: " + raw + " is not a vehicle state. Known states are "
                                    + stateNames() + "."));
                    continue;
                }
                states.add(state.get());
            }
            if (states.isEmpty()) {
                // Not a silent default of "always". An emitter that fires in
                // every state is a thing somebody can ask for by listing them,
                // and guessing it here would make a typo in the one state they
                // wrote into a particle storm they did not.
                diagnostics.add(Diagnostic.warning(origin, where,
                        "A particle (" + effect + ") names no states it fires in, so it never "
                                + "fires. Try states: [moving]."));
                continue;
            }
            emitters.add(VehicleEmitter.of(
                    effect,
                    states,
                    offset(node, "x", origin, where, diagnostics),
                    offset(node, "y", origin, where, diagnostics),
                    offset(node, "z", origin, where, diagnostics),
                    node.integer("count").orElse(1),
                    node.decimal("spread").orElse(0d),
                    node.decimal("speed").orElse(0d),
                    node.integer("interval").orElse(1),
                    node.bool("enabled").orElse(true),
                    color(node, origin, where, diagnostics),
                    node.decimal("size").orElse(1d)));
        }
        return emitters;
    }

    /**
     * A {@code color:}, as {@code #rrggbb} or a plain number, or
     * {@link VehicleEmitter#NO_COLOR}.
     *
     * <p>Only a dust particle reads it, and saying so in the diagnostic would
     * mean this knowing which effects those are — which is a Bukkit question
     * (see {@link #emitters}). So a colour on an effect that ignores one is
     * quietly ignored, exactly as the game ignores it.
     */
    private static int color(DefinitionNode node, String origin, String where,
                             List<Diagnostic> diagnostics) {
        Optional<String> declared = node.string("color");
        if (declared.isEmpty()) {
            return VehicleEmitter.NO_COLOR;
        }
        String raw = declared.get().trim();
        if (raw.isEmpty()) {
            return VehicleEmitter.NO_COLOR;
        }
        try {
            return Integer.parseInt(raw.startsWith("#") ? raw.substring(1) : raw, raw.startsWith("#") ? 16 : 10)
                    & 0xFFFFFF;
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "A particle's color: " + raw + " is not a colour. Try #ff8800."));
            return VehicleEmitter.NO_COLOR;
        }
    }

    /** The state vocabulary, for a diagnostic that has to list it. */
    private static String stateNames() {
        StringBuilder names = new StringBuilder();
        for (VehicleState state : VehicleState.values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(state.key());
        }
        return names.toString();
    }

    private static Optional<VehicleSeat.Role> role(DefinitionNode node, String origin, String where,
                                                   List<Diagnostic> diagnostics) {
        String declared = node.string("role").orElse("passenger").trim().toUpperCase(Locale.ROOT);
        try {
            return Optional.of(VehicleSeat.Role.valueOf(declared));
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "A seat's role: " + declared.toLowerCase(Locale.ROOT)
                            + " is not driver or passenger."));
            return Optional.empty();
        }
    }

    private static Optional<VehicleSeat.Pose> pose(DefinitionNode node, String origin, String where,
                                                   List<Diagnostic> diagnostics) {
        String declared = node.string("pose").orElse("sitting").trim().toUpperCase(Locale.ROOT);
        try {
            return Optional.of(VehicleSeat.Pose.valueOf(declared));
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "A seat's pose: " + declared.toLowerCase(Locale.ROOT)
                            + " is not sitting or standing."));
            return Optional.empty();
        }
    }

    /**
     * One axis of a seat offset, in blocks.
     *
     * <p>A missing axis is zero rather than an error — a seat in the middle of
     * a one-seater writes {@code y} and nothing else, and demanding all three
     * would be paperwork.
     */
    private static double offset(DefinitionNode node, String key, String origin, String where,
                                 List<Diagnostic> diagnostics) {
        Optional<Double> declared = node.decimal(key);
        if (declared.isEmpty()) {
            return 0;
        }
        double value = declared.get();
        if (!Double.isFinite(value) || Math.abs(value) > MAX_SEAT_OFFSET) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "A seat's " + key + ": " + value + " is more than " + MAX_SEAT_OFFSET
                            + " blocks from the vehicle. Using 0."));
            return 0;
        }
        return value;
    }

    /**
     * A handling number, clamped to its bounds with a line saying so.
     *
     * <p>Clamped rather than refused: a speed of 200 is somebody being
     * optimistic, and refusing the whole vehicle for it would take the seats
     * and the model down with a number that has an obvious nearest legal
     * value. A model that is not a namespace:id has no such value, which is
     * why that one is an error.
     */
    private static double number(DefinitionNode body, String key, double fallback,
                                 double min, double max, String origin, String where,
                                 List<Diagnostic> diagnostics) {
        Optional<Double> declared = body.decimal(key);
        if (declared.isEmpty()) {
            // Distinguishes "wrote nothing" from "wrote something unreadable":
            // a key that is present but not a number is worth a line, and an
            // absent one is the ordinary case.
            if (body.has(key)) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        key + ": " + body.raw(key) + " is not a number. Using " + fallback + "."));
            }
            return fallback;
        }
        double value = declared.get();
        if (!Double.isFinite(value)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + " is not a number. Using " + fallback + "."));
            return fallback;
        }
        if (value < min || value > max) {
            double clamped = Math.min(max, Math.max(min, value));
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + value + " is outside " + min + " to " + max + ". Using " + clamped + "."));
            return clamped;
        }
        return value;
    }

    /** Wrapped rather than clamped: -10 and 350 are the same direction. */
    private static double wrapDegrees(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        double wrapped = value % 360;
        return wrapped < 0 ? wrapped + 360 : wrapped;
    }

    /** The vehicles, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, VehicleInfo> vehicles;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, VehicleInfo> vehicles, List<Diagnostic> diagnostics) {
            this.vehicles = vehicles;
            this.diagnostics = diagnostics;
        }

        /** Every vehicle that parsed, keyed by id. */
        public Map<ContentId, VehicleInfo> vehicles() {
            return vehicles;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
