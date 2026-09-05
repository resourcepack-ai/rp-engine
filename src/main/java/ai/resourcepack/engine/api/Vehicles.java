package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The vehicles this server holds, and the ones standing in its worlds.
 *
 * <p>Two halves, like {@link Models}: the catalogue — what a vehicle IS, from
 * the pack — and the world — which vehicles exist right now, who is in them,
 * and a {@link Vehicle} handle for each. A fuel system, a garage, a car
 * shop, a race, a "no vehicles in town" rule: all of them are a server's own
 * behaviour, and this is what they are built on. The events
 * ({@code VehicleEnterEvent}, {@code VehicleExitEvent},
 * {@code VehicleMoveEvent}, {@code VehicleStateEvent}) are the other half of
 * the same story.
 *
 * <p><b>Main thread only</b>, except {@link #ids}, {@link #info} and
 * {@link #isRiding}, which read concurrent state and are safe anywhere.
 *
 * <p>Every method tolerates a null argument by answering empty or false, so
 * an id read out of somebody's config never becomes a stack trace.
 */
public interface Vehicles {

    /** Every vehicle id this server holds, sorted. */
    List<ContentId> ids();

    /** What a vehicle is: its seats, its speed, its hitbox, its medium. */
    Optional<VehicleInfo> info(ContentId id);

    // ---- the world ----------------------------------------------------------

    /**
     * Parks a new one, facing the way {@code where} does.
     *
     * <p>It is ready at once: seats, model and body are in the world and
     * somebody can get in on the same tick. Nothing is asked first — this is
     * your plugin putting a vehicle down, and whether it may is your own
     * rule. The item a player places asks {@code ModelPlaceEvent}; a shop
     * that wants the same refusal calls it itself.
     *
     * @return the vehicle, or empty if there is no such id or no world
     */
    Optional<Vehicle> spawn(Location where, ContentId id);

    /**
     * The vehicle this entity is part of, if it is part of one.
     *
     * <p>The chassis, any seat, the body a player clicks to get in, the
     * model: all of them answer with the same handle. The cheap early-out for
     * an event handler that wants to know whether the thing that was clicked,
     * hit or looked at is a vehicle.
     */
    Optional<Vehicle> at(Entity entity);

    /** The vehicle this player is in, if they are in one of ours. */
    Optional<Vehicle> of(Player player);

    /**
     * Whether this player is in one of our vehicles.
     *
     * <p>Safe from any thread — the answer is a concurrent map, not an
     * entity — which is what a placeholder or a scoreboard wants.
     * {@link #of} is the same question with the vehicle attached.
     */
    boolean isRiding(UUID playerId);

    /** Every vehicle within {@code radius} blocks, nearest first. */
    List<Vehicle> near(Location near, double radius);

    /**
     * Every vehicle standing in a loaded chunk, in no particular order.
     *
     * <p>What a fuel plugin walks once a second. A vehicle in an unloaded
     * chunk is not here because nothing about it is happening; it comes back
     * when the chunk does, with its {@link Vehicle#data()} intact.
     */
    List<Vehicle> loaded();
}
