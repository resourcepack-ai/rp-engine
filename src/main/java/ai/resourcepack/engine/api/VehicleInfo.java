package ai.resourcepack.engine.api;

import java.util.List;
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
    private final List<VehicleSeat> seats;

    private VehicleInfo(ContentId id, String model, String carrier, String name, VehicleMedium medium,
                        double weight, double speed, double acceleration, double turnSpeed,
                        VehicleHitbox hitbox, List<VehicleSeat> seats) {
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
        this.seats = seats;
    }

    /** Engine internal; built by the vehicle loader. */
    public static VehicleInfo of(ContentId id, String model, String name, VehicleMedium medium,
                                 double weight, double speed, double acceleration, double turnSpeed,
                                 VehicleHitbox hitbox, List<VehicleSeat> seats) {
        return new VehicleInfo(
                Objects.requireNonNull(id, "id"),
                model == null ? "" : model,
                "",
                name == null ? "" : name,
                medium == null ? VehicleMedium.LAND : medium,
                weight, speed, acceleration, turnSpeed,
                hitbox == null ? VehicleHitbox.DEFAULT : hitbox,
                seats == null ? List.of() : List.copyOf(seats));
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
                                     VehicleHitbox hitbox, List<VehicleSeat> seats) {
        return new VehicleInfo(
                Objects.requireNonNull(id, "id"),
                "",
                carrier == null ? "" : carrier,
                name == null ? "" : name,
                medium == null ? VehicleMedium.LAND : medium,
                weight, speed, acceleration, turnSpeed,
                hitbox == null ? VehicleHitbox.DEFAULT : hitbox,
                seats == null ? List.of() : List.copyOf(seats));
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
