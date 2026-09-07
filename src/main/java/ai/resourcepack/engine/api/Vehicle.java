package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * One vehicle standing in a world, whether or not anybody is in it.
 *
 * <p>The vehicle equivalent of {@link Placement}: a handle rather than the
 * thing itself. Ask it what it is and what it is doing, put people in it and
 * take them out, and switch it off. It is safe to hold on to — a handle to a
 * vehicle that has since been removed answers {@link #isValid} false and does
 * nothing, rather than throwing.
 *
 * <p><b>Main thread only</b>, like everything that touches an entity, except
 * {@link #id}, {@link #info} and {@link #uniqueId}, which are plain values.
 *
 * <h2>What a vehicle is, physically</h2>
 *
 * <p>The vehicle is a real entity — the <em>chassis</em> — that exists whether
 * or not anybody is aboard, so it can be parked, found again after a restart,
 * and sold in a shop. Its seats, its clickable body and its model are rebuilt
 * from the pack whenever the chassis is loaded, and are not something to
 * remember: {@link #uniqueId} is the chassis's and is the one stable key.
 *
 * <p>The engine holds the vehicle's position itself and pushes the entities to
 * it every tick. Teleporting the chassis from outside does not move the
 * vehicle for more than a tick, which is why there is no {@code teleport}
 * here; remove it and spawn another.
 *
 * <h2>Switching it off</h2>
 *
 * <p>{@link #setEnabled} is how a plugin builds anything that stops a vehicle
 * going: fuel, a breakdown, an ignition key, a pit lane. A disabled vehicle
 * ignores its driver — it coasts to a halt where it is and stays there, still
 * falling if it was in the air and still floating if it was on water — and
 * people get in and out of it as normal. The flag is written on the chassis,
 * so a car that ran dry is still dry after a restart. {@link #setSpeedLimit}
 * is the softer version, for a damaged engine or a zone with a limit, and is
 * deliberately not remembered.
 */
public interface Vehicle {

    /** Which vehicle this is — the id its pack gave it. */
    ContentId id();

    /** What the pack said this vehicle is: its seats, its speed, its hitbox. */
    VehicleInfo info();

    /**
     * The chassis's own id, which is the only stable key a vehicle has.
     *
     * <p>Use it for a map of your own — fuel per vehicle, owner per vehicle.
     * The seats and the model are re-created on every chunk load and have new
     * ids every time; the chassis is the entity that persists.
     */
    UUID uniqueId();

    /**
     * The chassis: the persistent, invisible entity the vehicle IS.
     *
     * <p>Its persistent data is {@link #data()}. Do not teleport it — the
     * engine moves it back on the next tick — and do not remove it directly;
     * {@link #remove} takes the seats and the model with it.
     */
    Entity chassis();

    /** Whether the vehicle is still standing. False once anything has removed it. */
    boolean isValid();

    /**
     * Where it is, facing the way it is going.
     *
     * <p>The engine's own position rather than the chassis entity's, which is
     * a tick behind it when the vehicle is moving. Yaw is {@link #heading()};
     * pitch is always zero.
     */
    Location location();

    /** Which way the body points, in degrees, {@code [0, 360)}. Minecraft's yaw. */
    double heading();

    /**
     * How fast it is going along its own heading, in blocks per second.
     *
     * <p>Negative is reversing. Zero for a vehicle nobody is driving once it
     * has coasted to a stop.
     */
    double speed();

    /** Up or down, in blocks per second. Positive is up. Zero on the ground. */
    double verticalSpeed();

    /**
     * What it is doing right now — moving, turning, airborne, idle and so on,
     * several at once.
     *
     * <p>The same set that decides which animation it plays and which
     * particles it throws, so a plugin reading it agrees with what the player
     * sees. Empty for a vehicle that has not been ticked yet, which is one in
     * a chunk that has only just loaded. {@link VehicleState#current} picks
     * the one that outranks the rest. {@code VehicleStateEvent} fires when
     * this changes.
     */
    Set<VehicleState> states();

    /** Whether it is in this state right now. */
    default boolean is(VehicleState state) {
        return state != null && states().contains(state);
    }

    // ---- who is aboard ---------------------------------------------------

    /** Whoever is in the driver's seat. */
    Optional<Player> driver();

    /**
     * Everybody aboard, driver first and then in seat order.
     *
     * <p>Only the people who are there — a seat nobody is in is skipped, so
     * this is shorter than {@link VehicleInfo#seats()} for a vehicle that is
     * not full. {@link #occupant} answers by seat.
     */
    List<Player> occupants();

    /**
     * Who is in seat {@code index}, counting from zero in the pack's order.
     *
     * <p>Seat zero is always the driver's — {@link VehicleInfo#seats()} is
     * driver first, whatever order the file listed them in. Empty for a free
     * seat, and for an index the vehicle does not have.
     */
    Optional<Player> occupant(int index);

    /** Which seat this player is in, or empty if they are not aboard this vehicle. */
    OptionalInt seatOf(Player player);

    /** Whether nobody is aboard. */
    default boolean isEmpty() {
        return occupants().isEmpty();
    }

    /** Whether every seat is taken. */
    default boolean isFull() {
        return occupants().size() >= info().capacity();
    }

    // ---- getting people in and out ---------------------------------------

    /**
     * Puts somebody in the first free seat, driver's seat first.
     *
     * <p>Exactly what clicking the vehicle's body does. {@code ModelSeatEvent}
     * and {@code VehicleEnterEvent} are asked first, so a server that refuses
     * seating refuses this too.
     *
     * @return false if the vehicle is full or gone, the player is already in
     *         a vehicle (any vehicle, including a vanilla one), or a listener
     *         said no
     */
    boolean seat(Player player);

    /**
     * Puts somebody in seat {@code index} in particular.
     *
     * <p>Exactly what clicking that seat does. Somebody already aboard this
     * vehicle is not moved between seats; take them out first.
     *
     * @return as {@link #seat(Player)}, and false for a seat that is taken or
     *         does not exist
     */
    boolean seat(Player player, int index);

    /**
     * Takes somebody out, leaving them standing beside their seat.
     *
     * <p>{@code VehicleExitEvent} fires with cause {@code EJECTED}. There is
     * no way to refuse it: a player who could not be let out would be a
     * player who is trapped.
     *
     * @return false if they were not aboard this vehicle
     */
    boolean eject(Player player);

    /** Takes everybody out. */
    void ejectAll();

    // ---- controlling it ---------------------------------------------------

    /**
     * Whether it answers its driver.
     *
     * <p>True unless something switched it off. A vehicle nobody has touched
     * is enabled.
     */
    boolean isEnabled();

    /**
     * Switches it on or off.
     *
     * <p>Off is what running out of fuel looks like: the driver's keys do
     * nothing, the vehicle coasts to a halt where it is, and it stays there.
     * It still falls if it was in the air and still floats if it was on water,
     * because those are the world's doing rather than the engine's. People get
     * in and out of it as normal, its animation and particles keep following
     * its state, and switching it back on hands the controls straight back.
     *
     * <p><strong>Remembered on the chassis</strong>, so it survives a chunk
     * unload and a restart — a car that ran dry is still dry tomorrow. It is
     * the one thing here that is.
     */
    void setEnabled(boolean enabled);

    /**
     * The top speed a plugin has imposed, in blocks per second, if one has.
     *
     * <p>Empty means the pack's own {@link VehicleInfo#speed()} applies.
     */
    OptionalDouble speedLimit();

    /**
     * Caps how fast it can go, in blocks per second.
     *
     * <p>For a damaged engine, a low tank, a road with a limit. Applied to
     * the top speed rather than the throttle, so reversing, braking and
     * coasting all scale with it — a vehicle limited to a crawl brakes like
     * one doing a crawl. A limit above the pack's own speed does nothing, and
     * an aircraft limited below its takeoff speed cannot take off, which is
     * the honest consequence. Zero, a negative number or a non-finite one
     * clears it.
     *
     * <p><strong>Not remembered</strong>, unlike {@link #setEnabled}: a
     * limit is a condition of the moment, and a plugin that wants one to
     * persist has the vehicle's {@link #data()} to keep it in.
     */
    void setSpeedLimit(double blocksPerSecond);

    /**
     * Stops it dead, this tick.
     *
     * <p>Speed to zero and the driver's throttle reset, as though it had hit
     * a wall. It still answers its driver afterwards; {@link #setEnabled}
     * is the switch that keeps it stopped.
     */
    void stop();

    /**
     * Takes it apart: everybody out, and the chassis, seats and model gone.
     *
     * <p>{@code VehicleExitEvent} fires for each occupant with cause
     * {@code REMOVED}. Nothing is dropped — a vehicle is not an item that was
     * placed, and what it should turn into is the shop's business.
     *
     * @return false if it was already gone
     */
    boolean remove();

    // ---- your own data ----------------------------------------------------

    /**
     * The chassis's persistent data — where anything your plugin wants to
     * remember about this particular vehicle belongs.
     *
     * <p>The fuel in the tank, who owns it, when it was bought. It is saved
     * with the world and survives everything the vehicle survives, and it is
     * the same container {@link #chassis()} carries. Keys in your own
     * namespace only; the engine's are its own.
     */
    PersistentDataContainer data();

    // ---- building on top of it -------------------------------------------

    /**
     * What the driver is asking for, as the engine read it this tick.
     *
     * <p>For a plugin that gives a vehicle behaviour the engine does not
     * have — a skateboard that is pushed rather than throttled, a bike that
     * wheelies on the sprint key. Reading the same input the physics read is
     * what keeps the plugin's idea of "the driver pressed forward" and the
     * engine's on one tick. {@link VehicleInput#NONE} when nobody is driving.
     */
    VehicleInput input();

    /**
     * Adds to the speed along the heading, blocks per second — a push, a kick,
     * a boost, a knock-back. Negative slows it. Takes effect this tick and is
     * then subject to everything the physics does: it coasts off, it is
     * capped by the top speed over the next tick, it scrubs in a slide.
     */
    void nudge(double blocksPerSecond);

    /**
     * Adds to the spin, degrees per second, clockwise positive. In the air
     * it carries, which is what makes a mid-air flick of a board a trick; on
     * the ground the tyres take it back within a tick or two.
     */
    void spin(double degreesPerSecond);

    /**
     * Dresses an occupant in {@code emoteId} instead of whatever their seat's
     * state says, until {@link #undress} or they get out.
     *
     * <p>For a state the engine does not have: a skater kicking off, a rider
     * tucking for speed. Worn over the seat's stance like a state's emote is,
     * so a seated rider keeps their legs. Somebody not in this vehicle is
     * ignored; an emote that does not exist is reported in the console once,
     * the way a seat's own refusal is.
     */
    void dress(Player occupant, String emoteId);

    /** Hands an occupant back to their seat's own states. */
    void undress(Player occupant);

    /**
     * Turns an occupant to face {@code yaw} degrees clockwise from the
     * vehicle's heading instead of the way their seat points, until cleared
     * with {@code null} or they get out.
     *
     * <p>A seat's {@code yaw} is the pack's answer for everybody; this is one
     * rider's. A skater's stance is the case it was made for: the same seat
     * faces 90 for a regular rider and 270 for a goofy one, and which is a
     * fact about the person, not the board. The rig, the camera and the
     * clickable seat all follow.
     */
    void turnOccupant(Player occupant, Double yaw);

    /** Speed over the ground in any direction, blocks per second — a drift is still moving. */
    double groundSpeed();

    /** A key in your plugin's namespace, for use with {@link #data()}. */
    default NamespacedKey key(Plugin plugin, String name) {
        return new NamespacedKey(plugin, name);
    }
}
