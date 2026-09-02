package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
                    node.string("name").orElse(null)));
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
                medium, weight, speed, acceleration, turnSpeed, hitbox, List.copyOf(ordered)));
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
