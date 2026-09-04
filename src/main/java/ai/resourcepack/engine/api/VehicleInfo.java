package ai.resourcepack.engine.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said a vehicle is.
 *
 * <p>A vehicle is <strong>a model people ride</strong>: an invisible chassis
 * that exists in the world whether or not anybody is in it, wearing a model,
 * with an ordered list of seats and a handful of numbers saying how it moves.
 *
 * <p>Three decisions are worth knowing before changing anything here.
 *
 * <p><strong>The chassis is a real entity, not the driver.</strong> Making the
 * driver the vehicle — walking them around with a model attached — buys free
 * WASD, free gravity and zero input latency, and was seriously considered. It
 * loses on one thing that cannot be worked around: a player cannot be parked.
 * A vehicle has to exist with nobody in it, or there is no garage, no dock, no
 * shop that places one and nothing to survive a restart.
 *
 * <p><strong>The driver's look is the steering.</strong> A and D are the
 * driver's strafe keys and the server cannot see them below the floor of
 * {@link Feature#PLAYER_INPUT}. What it can always see is where they are
 * looking, which their own client renders the instant they move the mouse — so
 * a vehicle that follows the camera feels responsive even though the
 * translation is a server tick behind. {@link #turnSpeed()} is the clamp on
 * how fast the body catches up.
 *
 * <p><strong>Seat order is the contract.</strong> See {@link #seats()}.
 */
public final class VehicleInfo {

    private final ContentId id;
    private final String model;
    private final String carrier;
    private final String name;
    private final VehicleMedium medium;
    private final double weight;
    private final double speed;
    private final double acceleration;
    private final double turnSpeed;
    private final VehicleHitbox hitbox;
    private final VehicleFlight flight;
    private final List<VehicleSeat> seats;
    private final Map<VehicleState, String> animations;
    private final List<VehicleEmitter> emitters;

    private VehicleInfo(ContentId id, String model, String carrier, String name, VehicleMedium medium,
                        double weight, double speed, double acceleration, double turnSpeed,
                        VehicleHitbox hitbox, VehicleFlight flight, List<VehicleSeat> seats,
                        Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        this.id = id;
        this.model = model;
        this.carrier = carrier;
        this.name = name;
        this.medium = medium;
        this.weight = weight;
        this.speed = speed;
        this.acceleration = acceleration;
        this.turnSpeed = turnSpeed;
        this.hitbox = hitbox;
        this.flight = flight;
        this.seats = seats;
        this.animations = animations;
        this.emitters = emitters;
    }

    /**
     * Engine internal; built by the vehicle loader.
     *
     * <p>The arity without a {@link VehicleFlight} flies the way every air
     * vehicle did before that type existed — see {@link VehicleFlight#forSpeed}.
     * Kept rather than replaced so a caller that predates it still compiles and
     * still behaves.
     */
    public static VehicleInfo of(ContentId id, String model, String name, VehicleMedium medium,
                                 double weight, double speed, double acceleration, double turnSpeed,
                                 VehicleHitbox hitbox, List<VehicleSeat> seats,
                                 Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        return of(id, model, name, medium, weight, speed, acceleration, turnSpeed,
                hitbox, VehicleFlight.forSpeed(speed), seats, animations, emitters);
    }

    /** The same, saying how it flies. */
    public static VehicleInfo of(ContentId id, String model, String name, VehicleMedium medium,
                                 double weight, double speed, double acceleration, double turnSpeed,
                                 VehicleHitbox hitbox, VehicleFlight flight, List<VehicleSeat> seats,
                                 Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        return new VehicleInfo(
                Objects.requireNonNull(id, "id"),
                model == null ? "" : model,
                "",
                name == null ? "" : name,
                medium == null ? VehicleMedium.LAND : medium,
                weight, speed, acceleration, turnSpeed,
                hitbox == null ? VehicleHitbox.DEFAULT : hitbox,
                flight == null ? VehicleFlight.forSpeed(speed) : flight,
                seats == null ? List.of() : List.copyOf(seats),
                copyAnimations(animations), copyEmitters(emitters));
    }

    /**
     * The same, for a vehicle that arrived on a Studio push.
     *
     * <p>Its art is named differently and that is the whole difference — see
     * {@link #carrier()}. A second constructor rather than a nullable
     * argument, and the same shape {@code SoundInfo.pushed} already uses for
     * exactly the same reason: the two ways of naming a thing are separate
     * facts, and a caller has to say which one it is holding.
     */
    public static VehicleInfo pushed(ContentId id, String carrier, String name, VehicleMedium medium,
                                     double weight, double speed, double acceleration, double turnSpeed,
                                     VehicleHitbox hitbox, List<VehicleSeat> seats,
                                     Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        return pushed(id, carrier, name, medium, weight, speed, acceleration, turnSpeed,
                hitbox, VehicleFlight.forSpeed(speed), seats, animations, emitters);
    }

    /** The same, saying how it flies. */
    public static VehicleInfo pushed(ContentId id, String carrier, String name, VehicleMedium medium,
                                     double weight, double speed, double acceleration, double turnSpeed,
                                     VehicleHitbox hitbox, VehicleFlight flight, List<VehicleSeat> seats,
                                     Map<VehicleState, String> animations, List<VehicleEmitter> emitters) {
        return new VehicleInfo(
                Objects.requireNonNull(id, "id"),
                "",
                carrier == null ? "" : carrier,
                name == null ? "" : name,
                medium == null ? VehicleMedium.LAND : medium,
                weight, speed, acceleration, turnSpeed,
                hitbox == null ? VehicleHitbox.DEFAULT : hitbox,
                flight == null ? VehicleFlight.forSpeed(speed) : flight,
                seats == null ? List.of() : List.copyOf(seats),
                copyAnimations(animations), copyEmitters(emitters));
    }

    /**
     * An {@link EnumMap}, so iterating it is in {@link VehicleState}'s
     * declaration order — which is the animation precedence. Nothing depends
     * on that (see {@link VehicleState#choose}, which walks the enum rather
     * than the map), but a map whose order contradicts the rule beside it is
     * a thing somebody will eventually read as the rule.
     */
    private static Map<VehicleState, String> copyAnimations(Map<VehicleState, String> animations) {
        if (animations == null || animations.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<VehicleState, String> copy = new EnumMap<>(VehicleState.class);
        for (Map.Entry<VehicleState, String> entry : animations.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<VehicleEmitter> copyEmitters(List<VehicleEmitter> emitters) {
        return emitters == null ? List.of() : List.copyOf(emitters);
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /**
     * The item id whose model it wears, or empty for an invisible vehicle.
     *
     * <p>An item rather than a model file, for the same reason a placed model
     * and a custom entity both name one: the art already exists as an item,
     * and a second way to point at the same model is a second thing to keep in
     * step.
     */
    public Optional<ContentId> model() {
        return model.isEmpty() ? Optional.empty() : ContentId.parse(model);
    }

    /**
     * The {@code custom_model_data} string its art is drawn with, or empty
     * when it names an item instead.
     *
     * <p><strong>Exactly one of this and {@link #model()} is set</strong>, and
     * which one says how the art was delivered rather than where it came from.
     * A pack built here ships the model beside an item, so the item id is
     * enough. A pack built by Studio ships a zip with no plugin behind it, so
     * its models borrow a vanilla item — paper wearing a string — and the
     * string is the only handle there is.
     *
     * <p>This is the same split {@code SoundInfo.event()} makes, for the same
     * reason: two ways of naming one thing, and pretending they are one field
     * means one of them is wrong.
     */
    public Optional<String> carrier() {
        return carrier.isEmpty() ? Optional.empty() : Optional.of(carrier);
    }

    /** What to call it in a message, or empty for its id. */
    public Optional<String> name() {
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** What it moves through, which decides its vertical rule entirely. */
    public VehicleMedium medium() {
        return medium;
    }

    /**
     * How heavy it is, 1 to 100, with 10 as a car.
     *
     * <p>Abstract rather than kilograms: nothing in Minecraft weighs anything,
     * so a real unit would imply a physics this cannot honour. It scales how
     * long the vehicle takes to reach its speed and how long it takes to stop,
     * which is the whole of what mass does that a player can feel.
     */
    public double weight() {
        return weight;
    }

    /** Top speed, in blocks per second. A sprinting player is about 5.6. */
    public double speed() {
        return speed;
    }

    /** How fast it reaches that speed, in blocks per second squared. */
    public double acceleration() {
        return acceleration;
    }

    /**
     * How fast the body swings round to follow the driver's look, in degrees
     * per second.
     *
     * <p>Not A and D. See the class note.
     */
    public double turnSpeed() {
        return turnSpeed;
    }

    /**
     * How big it is to click on and to stand in front of.
     *
     * <p>Never null; a pack that says nothing gets {@link VehicleHitbox#DEFAULT}.
     */
    public VehicleHitbox hitbox() {
        return hitbox;
    }

    /**
     * How it gets off the ground and how it comes back down.
     *
     * <p>Never null, and read only when {@link #medium()} is
     * {@link VehicleMedium#AIR} — a car carries one and never looks at it. A
     * pack that says nothing gets {@link VehicleFlight#forSpeed}, which is the
     * behaviour every air vehicle had before these numbers existed.
     */
    public VehicleFlight flight() {
        return flight;
    }

    /**
     * The seats, in the order people are put in them.
     *
     * <p><strong>The order is the contract.</strong> The driver is index 0 and
     * the rest are passenger 1, 2, 3… in exactly this order — so somebody who
     * clicks the third seat gets the third seat, and a pack that reorders its
     * list has moved where people sit rather than only how the file reads.
     * {@code VehicleDefinitions} is what guarantees the driver is first, so
     * nothing downstream has to search for it.
     *
     * <p>Never empty: a vehicle with no driver seat is refused at load, because
     * one that loaded would be a model claiming to be a vehicle and doing
     * nothing at all.
     */
    public List<VehicleSeat> seats() {
        return seats;
    }

    /**
     * Which animation this vehicle plays in which {@link VehicleState}.
     *
     * <p>Empty for a vehicle that animates nothing, which is every vehicle
     * written before this existed and most of them since — a vehicle is a
     * model that moves, and whether its wheels also turn is a separate
     * ambition.
     *
     * <p><strong>A state with no entry falls through to the next one</strong>
     * rather than stopping what is playing; {@link VehicleState#choose} is the
     * rule and the reason. The names are the model's own animation names, so
     * they are only meaningful against a model that has a rig — one that
     * places as a single still display can carry this map and never use it,
     * which is a half-finished vehicle rather than an error.
     */
    public Map<VehicleState, String> animations() {
        return animations;
    }

    /**
     * Where it throws particles, and when.
     *
     * <p>Independent of each other and of {@link #animations()} — an exhaust
     * plume is not an animation and a pack that wants one should not need a
     * rig for it.
     */
    public List<VehicleEmitter> emitters() {
        return emitters;
    }

    /** The seat that steers. Always present, always {@code seats().get(0)}. */
    public VehicleSeat driverSeat() {
        return seats.get(0);
    }

    /** How many people fit. */
    public int capacity() {
        return seats.size();
    }

    @Override
    public String toString() {
        return id + " (" + medium.key() + ", " + seats.size() + " seats)";
    }
}
