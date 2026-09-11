package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.EmoteResult;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.Placement;
import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleInput;
import ai.resourcepack.engine.api.VehicleImpactArea;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import ai.resourcepack.engine.api.event.ModelSeatEvent;
import ai.resourcepack.engine.api.event.VehicleEnterEvent;
import ai.resourcepack.engine.api.VehicleBail;
import ai.resourcepack.engine.api.event.VehicleBailEvent;
import ai.resourcepack.engine.api.event.VehicleExitEvent;
import ai.resourcepack.engine.api.event.VehicleMoveEvent;
import ai.resourcepack.engine.api.event.VehicleImpactEvent;
import ai.resourcepack.engine.api.event.VehicleStateEvent;
import ai.resourcepack.engine.core.Chat;
import ai.resourcepack.engine.core.animation.RigMath;
import ai.resourcepack.engine.core.model.DisplayCarry;
import ai.resourcepack.engine.core.model.DisplayLatency;
import ai.resourcepack.engine.core.model.MountOffset;
import ai.resourcepack.engine.core.model.RigCarrier;
import ai.resourcepack.engine.core.model.RigTags;
import ai.resourcepack.engine.core.version.Compatibility;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The vehicle runtime: a model people ride.
 *
 * <p>The API's view of this is {@link ai.resourcepack.engine.api.Vehicles}
 * (through {@link VehiclesImpl}) and the {@link Vehicle} handle, which is
 * {@link Handle} below. The runtime fires the four vehicle events — see
 * {@link #sit}, {@link Ride#vacate}, and {@link Ride#tick} — and everything a
 * plugin can change about a running vehicle comes through the handle into
 * {@link Ride}.
 *
 * <p><strong>The chassis is a real entity that exists whether or not anybody
 * is in it.</strong> That is the decision the whole class is shaped by. Making
 * the DRIVER the vehicle — walking them around with a model attached — buys
 * free WASD, free gravity and a driver with no input latency at all, and it
 * was the obvious design. It loses on one thing nothing works around: a player
 * cannot be parked. No garage, no dock, no shop that places one, nothing to
 * survive a restart, and every one of those becomes state this would have to
 * keep and re-materialise.
 *
 * <h2>What moves what</h2>
 *
 * <p>The engine holds the authoritative position itself, in {@link Ride}, and
 * pushes the entities at it. Nothing reads a position back off an entity to
 * decide where the vehicle is, so a nudge from another plugin, a piston or a
 * player walking into it cannot accumulate into drift.
 *
 * <p><strong>A seat's mount is a display, moved by teleport, and so is the
 * model — and that sameness is the whole of how a rider stays in their
 * seat.</strong> Where a rider is drawn is where their client believes their
 * mount is, and a client believes only what it is sent. An armour stand's
 * position goes out every third tick and is lerped over three; a display's
 * goes out every tick and glides over exactly what it is told
 * ({@link #MODEL_GLIDE_TICKS}). The model is a display too, told the same
 * number, so whatever the network does the rider and the bodywork lag by the
 * same amount and never separate. The rider's worn rig is pinned to the seat
 * by this class as well ({@code Emotes.anchor}), on the same window, in the
 * same tick as the model — three displays, one clock.
 *
 * <p>It used to be a small armour stand moved by velocity, and it did not
 * move at all: a stand without gravity has no physics and drops its velocity
 * on the floor, so the mount sat still until it was half a block behind and
 * then teleported. At speed that was every tick and looked fine; at a crawl
 * or in a turn it was a rider jumping seat to seat while the model glided.
 *
 * <p>Teleporting a ridden entity is the one thing an old CraftBukkit refuses.
 * {@link PassengerTeleport} has the arms; on a server where none of them
 * works the seat falls back to a stand WITH gravity, moved by velocity, which
 * is approximate but does at least move.
 *
 * <h2>What is derived and therefore never saved</h2>
 *
 * <p>Only the chassis persists. The seats, their hitboxes and the model are
 * rebuilt whenever a chassis is picked up again — the lesson of the 0.46.1
 * audit, where saving derived entities meant a chunk load spawned a second set
 * and orphaned the first, invisibly and for ever.
 */
public final class VehicleRuntime implements Listener {

    /** Seconds per tick, so the physics constants can be quoted per second. */
    private static final double DT = 1 / 20.0;

    /**
     * How long the client is asked to take gliding a display to each new
     * position — the model, and the mount each occupant rides.
     *
     * <p><strong>One number for both is the point, and the number itself
     * barely matters.</strong> Everything being three ticks behind is
     * invisible — there is nothing on screen to compare it against. Things
     * being behind by DIFFERENT amounts is what reads as a rider sliding out
     * of their seat, and with the mount and the model both displays carried
     * on this window there is no different amount. A worn rig in the seat is
     * carried on it too ({@code EmoteDirector.carryTicksForRider}), which is
     * the one place that other class has to agree with this one.
     *
     * <p>{@link DisplayLatency#TRACKED_ENTITY_TICKS} rather than the emote
     * rig's tighter {@code glideTicks(1)} because that is what the rider arm
     * over there already uses — the two were reasoned out separately once and
     * matched by hand, and this is the constant that records the agreement.
     */
    private static final int MODEL_GLIDE_TICKS = DisplayLatency.TRACKED_ENTITY_TICKS;

    /**
     * How far above their own position a seated occupant's backside is drawn:
     * the hip, twelve of the sixteen pixels a player model is tall below the
     * waist.
     *
     * <p><strong>This is the whole of what {@code pose: sitting} means</strong>
     * — a sitting seat's point is where somebody's backside goes and a standing
     * one's is where their feet go, and this is the distance between the two.
     * An entity's position is at its feet, the legs pivot at the hip, and a
     * riding player's legs swing forward from there; so seating somebody with
     * their backside on the point means putting their feet a hip below it.
     *
     * <p>It was 0.3 — {@code Seats}' figure for a chair — and that made the two
     * poses less than a third of a block apart, so a seat marked {@code sitting}
     * put its occupant almost exactly where {@code standing} would have. The
     * format's own documentation says the difference is "about a metre", and
     * studio's editor draws it as one: {@code HIP_HEIGHT} in
     * {@code seat-overlay.tsx} is this same 12/16, which is what makes the
     * preview and the game agree about where a rider ends up.
     *
     * <p>{@code Seats} keeps 0.3 deliberately. A chair's surface is a number
     * somebody typed while looking at their own model and then nudged with
     * {@code models.seat-offset} until it sat right, so servers have calibrated
     * against that figure; a vehicle seat is placed in an editor that draws the
     * occupant, and the editor is the thing it has to match.
     */
    private static final double SEATED_POSE = 12 / 16.0;

    /**
     * How far above the chassis the model display sits.
     *
     * <p>An {@link ItemDisplay} draws its model CENTRED on itself: model space
     * 0..16 renders as -0.5..+0.5 around the entity, whatever the model's own
     * height. So a display at the chassis buries the bottom half of the
     * vehicle in the ground, and half a block up puts the model's base exactly
     * on the chassis — which is the ground, and is what every other number
     * here is measured from. A seat's {@code y} is "above the base" for the
     * same reason {@code place: seat:} is.
     *
     * <p>Independent of how tall the model is: 0 is always half a block below
     * the display, so a three-block vehicle stands on the ground too.
     */
    private static final double MODEL_LIFT = 0.5;

    /**
     * How far the model is turned from the way the vehicle is going.
     *
     * <p>Half a turn, found in a client rather than derived: without it the
     * car drove backwards down the road. <strong>Measured on 2026-09-04, what
     * it actually does is put the model's {@code +z} at the front of the
     * vehicle and its {@code +x} on the vehicle's left</strong> — a studio
     * model's grille is at high {@code z}, and that is the end that leads.
     * The explanation this used to carry (that a Blockbench model faces
     * {@code -z}) was the wrong story for the right number.
     *
     * <p>It is a named constant so that it is the one thing to change if a
     * model ever faces the wrong way again. Seat positions are in the
     * vehicle's frame, not the model's, so turning the art does not move
     * anybody — but studio's editor converts model pixels INTO that frame,
     * and its conversion assumes exactly the half turn above. Change this and
     * every pushed seat is on the wrong end of its vehicle.
     */
    private static final float MODEL_YAW_OFFSET = 180;

    /*
     * There is deliberately NO second offset for an occupant's rig. A rig's
     * facing is read the way a player's yaw is — an emote on foot is drawn at
     * the player's own yaw and faces where they face — so the number that
     * aims the camera as somebody sits down (heading + seat yaw, below) is
     * the number the rig wants too, untouched. A half turn was added here
     * once (2026-09-05) because every seat then arriving from studio said
     * 180 for a forward-facing driver, and the rig faced the stern; the
     * offset fixed the rig and turned the camera the wrong way instead, the
     * two disagreeing by exactly that half turn for a day. The seats were
     * what was wrong. Bodywork is a different fact — see MODEL_YAW_OFFSET.
     */

    /** How far ahead a solid block stops the vehicle, in blocks. */
    private static final double NOSE = 0.6;

    /**
     * A vehicle can climb this much in one step, like a player.
     *
     * <p><strong>A maximum, not the step.</strong> It used to be the step
     * itself — anything in the way that a block above was clear of raised the
     * vehicle by a whole block, whatever the thing actually was — so a car
     * driving onto a slab hopped a block into the air and fell back half of
     * it, twice per slab. What it climbs now is the measured top of whatever
     * is in the way (see {@link BlockSurfaces#obstruction}); this is only how
     * high that may be before it counts as a wall.
     */
    private static final double MAX_STEP_UP = 1.0;

    /**
     * How far below its base a vehicle still counts as standing on something,
     * in blocks.
     *
     * <p>Nothing is ever exactly on a surface: a step lands a vehicle a
     * hair above or below the thing it settled onto, and a support test with
     * no slack in it reads that as thin air and starts the vehicle falling.
     */
    private static final double SUPPORT_REACH = 0.15;

    /**
     * Where a hull's base settles in a source block of water, as a fraction
     * of the block: a little under the eight ninths the water is drawn at.
     * Flowing water settles the same shade under its own level.
     */
    private static final double WATER_SURFACE = 0.85;

    /**
     * How slowly a vehicle has to be going before a held occupant's sneak
     * gets them out after all, blocks per second.
     *
     * <p>{@link Vehicle#holdOccupant} promises that nobody is ever trapped,
     * and this constant is that promise: it is the engine's own reverse
     * threshold, small enough that a vehicle a rider considers stopped is
     * stopped, and large enough that a board drifting to a halt under them
     * does not throw them off mid-trick.
     */
    private static final double HOLD_RELEASE_SPEED = VehiclePhysics.REVERSE_THRESHOLD;

    /**
     * How far to either side a wall counts as one to ride, in blocks.
     *
     * <p>Measured from the vehicle's centre, so it is half a hitbox plus a
     * little: close enough that you have to actually go at the wall, far
     * enough that the hair of clearance the collision resolver leaves does not
     * read as open air.
     */
    /** How many degrees of the bodywork's lean a rider absorbs outright. See Ride.ridden. */
    private static final double RIDER_LEAN_SLACK = 6.0;

    /**
     * How much of what is left of a lean the rider takes.
     *
     * <p>A third, and it should stay small. Somebody riding something into a
     * corner is holding themselves up against the lean, not lying in it: the
     * bodywork goes over and the rider mostly does not. Taking even half of a
     * board's cornering lean read as a rider falling off the side of it.
     * A wall ride is not this case at all - there the rider takes the whole
     * angle, because there they really are lying on the thing.
     */
    private static final double RIDER_LEAN_SHARE = 0.33;

    private static final double WALL_REACH = 1.2;

    /** The four ways a wall can be, as world x/z steps. See Ride.wallBeside. */
    private static final double[][] WALL_DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * How slowly a wall ride has to be going before the wall lets go, blocks a
     * second.
     *
     * <p>Low, because running out of speed on a wall should be something a
     * rider watches happen rather than a threshold that snatches the ride
     * away. Holding space keeps you on it until you are barely moving.
     */
    private static final double WALL_RIDE_END_SPEED = 1.0;

    /**
     * Longest a press of sneak can be and still mean "let me out", ticks.
     *
     * <p>Only for a rider the engine is holding in their seat
     * ({@link Vehicle#holdOccupant}) - otherwise sneak is vanilla's dismount
     * and none of this is reached. A hold is the plugin's to give a meaning
     * to; a tap still gets you off, because a rider who cannot get off
     * something is a bug however good the reason.
     *
     * <p>A quarter of a second: long enough to be deliberate, short enough
     * that whatever the plugin does with a hold does not feel delayed.
     */
    private static final int SNEAK_TAP_TICKS = 5;

    /**
     * Where beside the vehicle the wall has to be solid, in blocks relative to
     * its own base. Either sample will do.
     *
     * <p><strong>Level with the vehicle or above it, NEVER below.</strong>
     * Both ways round have been tried and both were wrong in a way that took a
     * screenshot to see. Sampling a block ABOVE only is what made a ride
     * impossible to start: it asks for wall above wherever the jump got to, so
     * a rider coming down a two-block wall's face finds thin air. Sampling
     * BELOW, which was the fix for that, is worse - half a block under a
     * vehicle standing on a plaza is the plaza, so the floor answered as a
     * wall in all four directions and a rider who jumped in the middle of an
     * empty field was rolled eighty degrees onto nothing.
     *
     * <p>Level with the vehicle is the honest question, and it happens to
     * answer both: a wall you are alongside is solid at your own height at any
     * height up it, a kerb you have jumped over is not, and the floor never
     * is.
     */
    private static final double WALL_LOW = 0.1;
    private static final double WALL_HIGH = 0.8;

    /** How hard a vehicle shoves somebody out of its way, blocks per tick. */
    private static final double SHOVE = 0.35;

    /**
     * How much two vehicles' heights have to overlap, in blocks, before they
     * count as touching each other.
     *
     * <p>Not zero. A vehicle parked on another's roof has its base exactly at
     * the other's top, and a boat riding a river shares a plane with the bank
     * it is beside; both would otherwise be a permanent collision resolved
     * sideways for ever. A tenth of a block is under one pixel of model.
     */
    private static final double VERTICAL_TOUCH = 0.1;

    /**
     * How far past itself a vehicle looks for placed models, in blocks.
     *
     * <p><strong>A placed model is found by its anchor and is not bounded by
     * it.</strong> The entity search matches an {@link Interaction}'s own box,
     * which is one block for a Studio placement however big the art is — so
     * without this a wide model is simply not seen until the vehicle is nearly
     * on top of its anchor, and the overhanging half of it is driven through.
     *
     * <p>Four blocks covers an eight-block-wide piece, which is past anything
     * furniture-shaped and past every scale the give card offers on a
     * block-sized model. A model wider than that, placed at a large multiplier,
     * can still have its far corner missed — the honest fix for which would be
     * a search keyed on the widest model in the pack rather than a bigger
     * constant, and nothing has needed it.
     */
    private static final double MODEL_SEARCH_MARGIN = 4.0;

    /**
     * How far apart the samples are when testing against placed models, in
     * blocks.
     *
     * <p>A quarter block, against a whole one for blocks. The thinnest thing a
     * BLOCK world can put in the way is a full cube, so five corner samples
     * always land in it; a model can be two pixels thick, and a rail sampled
     * that coarsely is one a car drives through the middle of. Two pixels is
     * 0.125, so this still misses the very thinnest art — going finer costs
     * every vehicle near any furniture, and a fence you can drive through the
     * rail of but not the posts is not the complaint anybody had.
     */
    private static final double MODEL_SAMPLE_STEP = 0.25;

    /**
     * How often a vehicle nobody is in and that is going nowhere does a full
     * tick.
     *
     * <p><strong>The class note said empty vehicles are not ticked at all, and
     * that was not true of the code.</strong> Every chassis in a loaded chunk
     * is adopted and every adopted chassis got the whole tick: five block
     * reads, a physics step, and then {@link Ride#place} teleporting the
     * chassis, every seat mount, every seat hitbox, every body tile and the
     * model — twenty times a second, for a car park nobody is standing in. The
     * promise that a hundred parked vehicles cost nothing was the design, and
     * the arithmetic was doing the opposite.
     *
     * <p>So a settled empty vehicle does the cheap half — its animation and its
     * particles, both of which return immediately for a vehicle that has none —
     * and re-reads the world on this clock. Half a second is short enough that
     * a vehicle whose ground was mined starts falling before anybody has
     * finished watching it not fall, and long enough that the saving is an
     * order of magnitude.
     *
     * <p>It is also what keeps {@link Ride#place}'s drift correction alive: a
     * chassis shoved by another plugin is put back on the next poll rather than
     * never.
     */
    private static final int PARKED_POLL_TICKS = 10;

    /**
     * How often the driver's speed is written above their hotbar, in ticks.
     *
     * <p>Five times a second reads as live without the number flickering
     * through every intermediate value; the action bar is a metadata packet
     * and this is one every four ticks per driver, which is nothing.
     */
    private static final int SPEEDOMETER_TICKS = 4;

    /**
     * How long the speedometer stays quiet after something else has used the
     * action bar, in ticks. Three seconds: long enough to read which seat you
     * got and what the controls are, which is what it would otherwise paint
     * over on the very next tick.
     */
    private static final int HUSH_TICKS = 60;

    /**
     * Blocks per second to kilometres per hour. A block is a metre, and a
     * speedometer in blocks per second means nothing to anybody.
     */
    private static final double KMH = 3.6;

    private final Plugin plugin;
    private final Items items;
    private final Logger log;
    private final VehicleControls controls;
    private final DisplayCarry carry;

    /**
     * How far above an occupant's feet their mount is spawned, in blocks.
     *
     * <p>Two figures because there are two shapes of mount. The display every
     * server that can teleport a ridden entity gets has no dimensions at all,
     * so vanilla seats its rider exactly as it would on a marker stand — the
     * measured figure. The stand the fallback arm uses is a small one with a
     * real attachment point of its own. See {@link MountOffset}.
     */
    private final double displayMountOffset;
    private final double standMountOffset;

    /**
     * The player being moved from one mount to another this instant, or null.
     *
     * <p>Swapping a mount ejects its rider, and an ejection is a dismount,
     * and a dismount is how the engine learns somebody got out. This is the
     * one case where they did not — see {@link Ride#reseat}.
     */
    private UUID reseating;

    /**
     * How a vehicle wears an ANIMATED model, or null on a build with no rig
     * system wired in.
     *
     * <p>A vehicle whose model has no rig — which is most of them — is one
     * still {@link ItemDisplay} exactly as before, and nothing here changes for
     * it. See {@link RigCarrier} for why a vehicle can use neither of the two
     * arrangements that already existed.
     */
    private final RigCarrier rigs;

    /** Throws whatever the pack asked for, for the state the vehicle is in. */
    private final VehicleParticles particles;

    /**
     * The server's custom sounds, for a vehicle that names one per state.
     *
     * <p>The registry rather than a copy, and asked at play time rather than
     * at load: a vehicle may name a sound that arrives in a later push, and
     * resolving once at load would leave that vehicle permanently silent with
     * nothing to say why.
     */
    private final ai.resourcepack.engine.api.Sounds sounds;

    /**
     * How an occupant's body is dressed, or null on a build without emotes.
     *
     * <p>A vehicle occupant is the first thing that wants a worn rig for a
     * reason that is not movement, which is what {@code Emotes.wear} was added
     * for. Nullable because a vehicle whose seats animate nothing must not
     * depend on the emote system existing at all.
     */
    private final ai.resourcepack.engine.api.Emotes emotes;

    /**
     * Where a string custom_model_data lives on this server.
     *
     * <p>Only a PUSHED vehicle needs it — an authored one names an item and
     * the item factory writes its own tags. Resolved once here rather than
     * read off RigPlacementListener's static: that one is set from the plugin
     * for the rig path, and a second reader of somebody else's private state
     * is a coupling nobody would find.
     */
    private final RigTags tags;

    /**
     * How this server moves a seat somebody is sitting on.
     *
     * <p>Resolved once. See {@link PassengerTeleport} — this is the difference
     * between a rider who stays in their seat and one who is left standing in
     * the road.
     */
    private final PassengerTeleport seatMover;

    /**
     * How the world's placed models are found, so a vehicle stops at a fence
     * instead of driving through it.
     *
     * <p>See {@link ModelObstacles}. Held here rather than made per tick
     * because it owns the two persistent-data keys the placement listeners
     * write.
     */
    private final ModelObstacles.Sensor obstacles;

    /** Marks a chassis as ours, and says which vehicle it is. */
    private final NamespacedKey idKey;

    /**
     * Present on a chassis a plugin has switched off — see
     * {@link Vehicle#setEnabled}.
     *
     * <p>On the chassis rather than in {@link Ride}, because a Ride is
     * rebuilt on every chunk load and the flag has to outlive that: a car
     * that ran out of fuel is still out of fuel after a restart. Presence is
     * the flag, so an enabled vehicle carries nothing and every vehicle
     * parked before this existed is enabled.
     */
    private final NamespacedKey disabledKey;

    /** Names of rig bones removed from this persistent chassis. */
    private final NamespacedKey detachedPartsKey;

    /** Temporary physical hosts and displays created by detached bodywork. */
    private final List<Debris> debris = new ArrayList<>();

    /**
     * Every derived entity, to the chassis it belongs to.
     *
     * <p>Seat mounts, seat hitboxes, body tiles and the model display. It is
     * what lets {@link #at} answer for the thing a player actually clicked,
     * which is never the chassis — that is a marker with nothing to click.
     * Maintained by {@link Ride#spawnParts} and {@link Ride#despawnParts},
     * and by the mount swap in {@link Ride#reseat}.
     */
    private final Map<UUID, UUID> parts = new ConcurrentHashMap<>();

    /**
     * Why the person being ejected right now is leaving, or null for a
     * dismount of their own.
     *
     * <p>{@code Entity.eject} fires the dismount event synchronously, and the
     * dismount listener cannot tell an eviction from somebody pressing sneak.
     * So whoever evicts says why first, the same way {@link #reseating}
     * says "this one is not leaving at all".
     */
    private VehicleExitEvent.Cause leaving;

    private volatile Map<ContentId, VehicleInfo> catalogue = Map.of();

    /** Chassis uuid -> what is going on with it. Only occupied vehicles are in here. */
    private final Map<UUID, Ride> live = new ConcurrentHashMap<>();

    /** Player uuid -> the chassis they are on, so a quit or a dismount can find it. */
    private final Map<UUID, UUID> riders = new ConcurrentHashMap<>();

    /**
     * Who is in a seat that draws nobody.
     *
     * <p>Per-vehicles rather than per-Ride: a rider is hidden from every client
     * on the server, not from the ones near their car, and a player joining has
     * to be told about all of them at once. See {@link HiddenRiders}.
     */
    private final HiddenRiders concealed;

    /** Said once, however many vehicles are stuck. */
    private volatile boolean warnedFrozen;

    /**
     * Which (rider, emote, reason) triples have already been reported.
     *
     * <p>Bounded by how many distinct things can go wrong rather than by how
     * long the server has been up: a pack has a handful of seat emotes and a
     * refusal has a handful of reasons, so this is tens of strings on a server
     * where something is wrong and empty on one where nothing is.
     */
    private final Set<String> warnedRefusals = ConcurrentHashMap.newKeySet();

    /**
     * Added to every vehicle seat, from config.yml.
     *
     * <p>The same escape hatch {@code models.seat-offset} is for furniture.
     * It moves the occupant's FEET, so the worn rig and the mount under the
     * player go together: the rig is placed by arithmetic and is exact, but
     * the player themselves sit wherever vanilla's passenger rule puts them
     * over the mount, which is measured per version ({@link MountOffset})
     * and can be a little out on one nobody has checked. Rather than have an
     * owner wait for a release, they nudge every seat and type /rp reload.
     */
    private volatile double seatOffset;

    /**
     * Added to every vehicle seat along the vehicle's FORWARD axis, from
     * config.yml.
     *
     * <p>The horizontal twin of {@link #seatOffset}, and it exists for the
     * same reason plus a worse one. Where a rider ends up is
     * {@code chassis + seat + vanilla's passenger placement}, and only the
     * first two are ours: the third is a rule inside the game that a plugin
     * cannot read, that has changed between versions before, and that is
     * measured rather than derived everywhere it appears in this codebase
     * (see {@link MountOffset}).
     *
     * <p>So when the editor and the game disagree by a constant, this and
     * {@code seat-offset} are how a server owner closes it in one reload
     * instead of waiting for somebody to guess the right number from the
     * outside. Positive is toward the front of the vehicle.
     */
    private volatile double seatForward;

    /**
     * Whether a moving vehicle shoves PLAYERS out of its way, from config.yml.
     *
     * <p>Off by default, which is the opposite of what it was. Shoving is
     * right for a mob wandering into the road and wrong for the people you are
     * driving with: a car park of vehicles nudging each other's drivers about,
     * and a passenger getting out being flung, are both worse than a vehicle
     * that passes through somebody. Mobs are shoved either way — a cow should
     * not stand in the road.
     */
    private volatile boolean pushPlayers;

    /** Whether vehicles collide with each other at all — {@code vehicles.collide}. */
    private volatile boolean collide = true;

    /**
     * Half the diagonal of the biggest hitbox in the catalogue, in blocks.
     *
     * <p>How far past its own edge a vehicle has to look to be sure it has
     * found everything it might be touching, since a neighbour is found by
     * where its CENTRE is. Recomputed with the catalogue rather than per tick,
     * and zero for a server with no vehicles at all. See
     * {@link Ride#impactReach}.
     */
    private volatile double widest;

    /**
     * Whether an occupant nobody dressed is put in their rig anyway, from
     * config.yml.
     *
     * <p>On by default, and the default is the point. A vehicle seat exists to
     * put somebody IN something, and vanilla draws a passenger standing up to
     * their waist in the hull at whatever angle it likes — so a rider in their
     * own rig, sitting the way the editor drew them, is the right picture even
     * on a pack that never authored a pose. {@code Emotes.BUILT_IN_SITTING} is
     * what fills that gap.
     *
     * <p>Off is for a server that would rather see ordinary players: it costs
     * a rig's worth of entities per occupant, and a full bus is a real number
     * of them. A seat that NAMES an emote is unaffected either way — that is
     * the pack asking for something rather than the engine defaulting.
     */
    private volatile boolean seatRig = true;

    /**
     * Whether to report, per seat, where an occupant actually ended up against
     * where this class put them — {@code vehicles.debug-seats}.
     *
     * <p>Off, and it belongs beside {@code seat-offset} rather than in a
     * developer's build for the same reason that one does: where a rider ends
     * up is the chassis, plus the seat, plus a vanilla rule no plugin can read,
     * and only the first two are ours. That comment tells an owner to compare
     * a seat with the editor by eye and type the difference in; this measures
     * the difference for them, per seat, so a bench seat that is fine and a
     * pillion that is not can be told apart instead of averaged.
     */
    private volatile boolean debugSeats;

    /** Whether the driver sees their speed above the hotbar. {@code vehicles.speedometer}. */
    private volatile boolean speedometer = true;

    /**
     * Whether somebody getting in is told the controls. {@code
     * vehicles.mount-hint}.
     *
     * <p>On by default, because a server whose keys cannot be read steers by
     * LOOK and nothing anywhere else says so. Off for a server that has told
     * its players once and would rather the screen stayed clean.
     */
    private volatile boolean mountHint = true;

    public VehicleRuntime(Plugin plugin, Items items, Compatibility compatibility, RigCarrier rigs,
                    ai.resourcepack.engine.api.Emotes emotes, ai.resourcepack.engine.api.Sounds sounds) {
        this.emotes = emotes;
        this.sounds = sounds;
        this.plugin = plugin;
        this.items = items;
        this.log = plugin.getLogger();
        this.rigs = rigs;
        this.particles = new VehicleParticles(plugin.getLogger());
        this.controls = VehicleControls.forServer(compatibility, plugin.getLogger());
        this.carry = DisplayCarry.forServer(compatibility, MODEL_GLIDE_TICKS);
        this.displayMountOffset = MountOffset.forServer(compatibility);
        this.standMountOffset = MountOffset.forVehicleSeat(compatibility);
        this.tags = RigTags.forServer(compatibility, plugin);
        this.seatMover = PassengerTeleport.forServer();
        this.concealed = new HiddenRiders(plugin);
        this.obstacles = new ModelObstacles.Sensor(plugin);
        this.idKey = new NamespacedKey(plugin, "vehicle");
        this.disabledKey = new NamespacedKey(plugin, "vehicle-disabled");
        this.detachedPartsKey = new NamespacedKey(plugin, "vehicle-detached-parts");
    }

    /** The control arm, so the plugin can register it and report it. */
    public VehicleControls controls() {
        return controls;
    }

    /**
     * Adopts the answers about a placed model: whether it stops a vehicle, what
     * it is shaped like, and how big its definition asks for it to be drawn.
     *
     * <p>Wired once. Every answer reads through to live state — a content
     * folder's {@code vehicle-collision:} is re-read on a reload, a pushed
     * pack's arrives on a websocket frame whenever somebody presses Sync, and
     * the shapes are read out of whichever pack is on disk — so there is
     * nothing to re-wire.
     *
     * <p><strong>A lookup rather than a catalogue.</strong> The places that
     * know are the content-folder loader, the pushed manifest and the pushed
     * pack's own geometry, and none of them belongs in this package: a vehicle
     * wants an answer about a string, and copying those maps in here would be a
     * fourth answer to keep in step with the three that already exist.
     */
    public void modelCollision(PlacedModels models) {
        obstacles.models(models);
    }

    /**
     * Adopts {@code vehicles.seat-offset}, {@code vehicles.push-players} and
     * {@code vehicles.seat-rig}. Called on enable and on every reload.
     */
    public void configure(double seatOffset, double seatForward, boolean pushPlayers,
                          boolean seatRig, boolean debugSeats, boolean speedometer,
                          boolean collide, boolean mountHint) {
        this.seatOffset = seatOffset;
        this.seatForward = seatForward;
        this.pushPlayers = pushPlayers;
        this.seatRig = seatRig;
        this.debugSeats = debugSeats;
        this.speedometer = speedometer;
        this.collide = collide;
        this.mountHint = mountHint;
    }

    /**
     * Replaces the catalogue, as a reload or a push does.
     *
     * <p><strong>Live vehicles are rebuilt, not left holding the old
     * definition.</strong> {@link #adopt} refuses a chassis it is already
     * tracking — which is right for a chunk loading twice and wrong here: a
     * re-sync that moved a seat or resized the hitbox would change the
     * catalogue and change nothing anybody could see, because every vehicle
     * already standing in the world kept the definition it was adopted with.
     * That is most of "I sync and it works, then it stops matching".
     *
     * <p>Rebuilding ejects whoever is in one, and they are <strong>put
     * back</strong>. The eviction itself is not negotiable — a passenger cannot
     * be left sitting in a seat that no longer exists, on a vehicle whose
     * numbers nobody can account for — but "you are standing in the road now"
     * is not the honest cost of it, it is a step that was simply not taken. A
     * re-sync is the single most common reason this runs, and the person who
     * pressed it is usually sitting in the thing they are adjusting; throwing
     * them out every time reads as the vehicle breaking on every save.
     *
     * @see #adoptLoaded() which is where they are re-seated, because that is
     *      the call that knows the new parts exist
     */
    public void replace(Map<ContentId, VehicleInfo> loaded) {
        this.catalogue = loaded == null ? Map.of() : Map.copyOf(loaded);
        double biggest = 0;
        for (VehicleInfo vehicle : this.catalogue.values()) {
            VehicleHitbox box = vehicle.hitbox();
            biggest = Math.max(biggest, Math.hypot(box.width(), box.length()) / 2);
        }
        this.widest = biggest;
        reseat.clear();
        for (UUID chassisId : List.copyOf(live.keySet())) {
            Ride ride = live.remove(chassisId);
            if (ride == null) {
                continue;
            }
            reseat.put(chassisId, ride.seating());
            ride.evictAll(VehicleExitEvent.Cause.RELOADED);
            ride.despawnParts();
        }
        // Not re-adopted here: adoptLoaded() runs straight after every call to
        // this, from the one place that knows the whole load finished. Doing it
        // twice would spawn a set of parts and immediately drop them.
    }

    /**
     * Who was in which seat when {@link #replace} took a vehicle apart.
     *
     * <p>Chassis id to a list whose INDEX is the seat number and whose value is
     * the occupant, exactly as {@code Ride.occupants} holds it. Consumed by
     * {@link #adoptLoaded()} and empty at every other moment — this is a handoff
     * between two calls that always run together, not state.
     */
    private final Map<UUID, List<UUID>> reseat = new ConcurrentHashMap<>();

    /** Every vehicle id, sorted. */
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(catalogue.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    /** What the pack said a vehicle is. */
    public Optional<VehicleInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(catalogue.get(id));
    }

    /** Which vehicle this chassis is, or empty for any other entity. */
    public Optional<ContentId> idOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return ContentId.parse(entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    // ------------------------------------------------------------------
    // Spawning and parking
    // ------------------------------------------------------------------

    /**
     * Puts one down. Main thread only.
     *
     * @return the chassis, or empty if there is no such vehicle
     */
    public Optional<Entity> spawn(Location where, ContentId id) {
        Optional<VehicleInfo> found = info(id);
        if (where == null || where.getWorld() == null || found.isEmpty()) {
            return Optional.empty();
        }
        ArmorStand chassis = where.getWorld().spawn(where, ArmorStand.class, stand -> {
            stand.setMarker(true);
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            enableChassisSounds(stand);
            // The one thing here that IS saved: a parked vehicle has to still
            // be there tomorrow. Everything hanging off it is rebuilt.
            stand.setPersistent(true);
        });
        chassis.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        return Optional.of(chassis);
    }

    /**
     * Makes a chassis usable by Minecraft's entity-bound sound instance.
     *
     * <p>The chassis itself has no ambient, hurt or equipment sounds: it is an
     * invisible, invulnerable marker carrying no equipment. Marking it silent
     * therefore buys nothing, but the flag reaches the client and suppresses
     * every custom sound attached to that entity too.
     */
    static void enableChassisSounds(Entity chassis) {
        if (chassis != null) {
            chassis.setSilent(false);
        }
    }

    /**
     * Takes a parked vehicle apart. Main thread only.
     *
     * @return whether that was one of ours
     */
    public boolean remove(Entity chassis) {
        if (idOf(chassis).isEmpty()) {
            return false;
        }
        Ride ride = live.remove(chassis.getUniqueId());
        if (ride != null) {
            ride.evictAll(VehicleExitEvent.Cause.REMOVED);
            ride.despawnParts();
        }
        chassis.remove();
        return true;
    }

    // ------------------------------------------------------------------
    // The API's view
    // ------------------------------------------------------------------

    /**
     * {@link ai.resourcepack.engine.api.Vehicles#spawn}: parks one and gives
     * it its parts at once, so the caller's handle is live on the same tick.
     */
    public Optional<Vehicle> spawnAndAdopt(Location where, ContentId id) {
        Optional<Entity> chassis = spawn(where, id);
        if (chassis.isEmpty()) {
            return Optional.empty();
        }
        adopt(chassis.get());
        return Optional.of(new Handle(chassis.get().getUniqueId()));
    }

    /** {@link ai.resourcepack.engine.api.Vehicles#at}: the chassis, or any part of one. */
    public Optional<Vehicle> at(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        if (idOf(entity).isPresent()) {
            return Optional.of(new Handle(entity.getUniqueId()));
        }
        UUID owner = parts.get(entity.getUniqueId());
        return owner == null ? Optional.empty() : Optional.of(new Handle(owner));
    }

    /** {@link ai.resourcepack.engine.api.Vehicles#of}: the vehicle a player is in. */
    public Optional<Vehicle> of(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        UUID chassis = riders.get(player.getUniqueId());
        return chassis == null ? Optional.empty() : Optional.of(new Handle(chassis));
    }

    /** {@link ai.resourcepack.engine.api.Vehicles#isRiding}. Any thread. */
    public boolean isRiding(UUID playerId) {
        return playerId != null && riders.containsKey(playerId);
    }

    /** {@link ai.resourcepack.engine.api.Vehicles#near}: handles, nearest first. */
    public List<Vehicle> handlesNear(Location near, double radius) {
        List<Entity> found = near(near, radius);
        found.sort((a, b) -> Double.compare(
                a.getLocation().distanceSquared(near), b.getLocation().distanceSquared(near)));
        List<Vehicle> handles = new ArrayList<>(found.size());
        for (Entity chassis : found) {
            handles.add(new Handle(chassis.getUniqueId()));
        }
        return handles;
    }

    /** {@link ai.resourcepack.engine.api.Vehicles#loaded}: every adopted chassis. */
    public List<Vehicle> loaded() {
        List<Vehicle> handles = new ArrayList<>(live.size());
        for (UUID chassis : live.keySet()) {
            handles.add(new Handle(chassis));
        }
        return handles;
    }

    /** The handle for a chassis, for an event about it. */
    private Vehicle handle(UUID chassisId) {
        return new Handle(chassisId);
    }

    /** Every chassis within {@code radius} blocks of {@code near}. */
    public List<Entity> near(Location near, double radius) {
        List<Entity> found = new ArrayList<>();
        if (near == null || near.getWorld() == null) {
            return found;
        }
        for (Entity entity : near.getWorld().getNearbyEntities(near, radius, radius, radius)) {
            if (idOf(entity).isPresent()) {
                found.add(entity);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // Getting in and out
    // ------------------------------------------------------------------

    /**
     * A vehicle's own item, placed against a block, parks the vehicle.
     *
     * <p><strong>Until this, the item studio hands over after a sync placed
     * a statue.</strong> The item is the model's — paper wearing the model's
     * {@code custom_model_data} string — and the model placer claims every
     * one of those, so a tractor went down as a rig you could look at and not
     * get into, and the only way to a driveable one was {@code /rp vehicle}.
     * From the player's side that is "I placed it, I right-clicked it,
     * nothing happened".
     *
     * <p>Lowest priority so this runs before the model placer, which skips a
     * cancelled event; a model that is a vehicle is a vehicle. Matched on the
     * CARRIER string rather than the id, because that is exactly what the
     * item carries — see {@link VehicleInfo#carrier()}. The same
     * {@link ModelPlaceEvent} is asked first, so whatever keeps statues out
     * of a lobby keeps vehicles out of it too.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlaceItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        VehicleInfo info = vehicleOfItem(item);
        if (info == null) {
            return;
        }
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        // Chests, doors and the like keep their vanilla click unless the
        // player sneaks - the model placer's rule, and a block's.
        if (clicked.getType().isInteractable() && !player.isSneaking()) {
            return;
        }
        // Ours from here on: never also the vanilla use, and never a statue.
        event.setCancelled(true);
        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.getType().isAir()) {
            return;
        }
        ModelPlaceEvent ask = new ModelPlaceEvent(player, info.id(), target);
        plugin.getServer().getPluginManager().callEvent(ask);
        if (ask.isCancelled()) {
            return;
        }
        // Centred in the block, pointed the way the player is looking - a
        // vehicle is parked facing away from whoever parked it, as the
        // command does.
        Location where = target.getLocation().add(0.5, 0, 0.5);
        where.setYaw(player.getLocation().getYaw());
        Optional<Entity> chassis = spawn(where, info.id());
        if (chassis.isEmpty()) {
            return;
        }
        // Adopted now rather than on the next chunk load, which for a chunk
        // that is already loaded would be never.
        adopt(chassis.get());
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        player.swingMainHand();
        overhead(player, "Parked " + nameOf(info) + " - right-click a seat to get in.");
    }

    /**
     * The vehicle an item in somebody's hand parks, or null.
     *
     * <p>Two kinds of item park a vehicle. A pushed vehicle's is Studio's
     * carrier: paper wearing the vehicle's carrier string. An authored
     * vehicle's is the item whose model it wears — the {@code model:} line in
     * its definition — which is what a server owner gives out of a shop and
     * what an addon hands over on a command. The second used to do nothing
     * on a right-click, so a hand-authored car could only be parked by
     * {@code /rp vehicle}, which is a command and not a thing you can sell.
     */
    private VehicleInfo vehicleOfItem(ItemStack item) {
        if (item.getType() == Material.PAPER) {
            List<String> strings = tags.read(item);
            if (!strings.isEmpty() && strings.get(0) != null) {
                String carrier = strings.get(0);
                for (VehicleInfo candidate : catalogue.values()) {
                    if (candidate.carrier().filter(carrier::equals).isPresent()) {
                        return candidate;
                    }
                }
            }
        }
        Optional<ContentId> id = items.idOf(item);
        if (id.isPresent()) {
            for (VehicleInfo candidate : catalogue.values()) {
                if (candidate.model().filter(id.get()::equals).isPresent()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * A click on a seat's hitbox puts you in THAT seat.
     *
     * <p>The seat you clicked rather than the first free one, because the
     * whole point of the ordered list is that a player who picks the third
     * seat gets the third seat. Clicking the chassis itself is not a way in —
     * there is nothing to click, the chassis is a marker.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClickSeat(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        UUID clicked = event.getRightClicked().getUniqueId();
        for (Ride ride : live.values()) {
            int index = ride.seatIndexOfHitbox(clicked);
            if (index < 0 && ride.isBody(clicked)) {
                // The body seats you in the first free seat rather than a
                // particular one — see firstFreeSeat.
                index = ride.firstFreeSeat();
                if (index < 0) {
                    event.setCancelled(true);
                    Chat.send(event.getPlayer(), nameOf(ride.info) + " is full.");
                    return;
                }
            }
            if (index >= 0) {
                event.setCancelled(true);
                sit(event.getPlayer(), ride, index);
                return;
            }
        }
    }

    /**
     * Wakes a parked vehicle up when somebody walks up to it.
     *
     * <p>A parked vehicle has no seats and no model in the world — they are
     * derived, and spawning them for every chassis in every loaded chunk would
     * be thousands of entities nobody is looking at. They appear when the
     * chunk holding the chassis loads, which is the first moment somebody
     * could see it.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            adopt(entity);
        }
    }

    /**
     * Gives a chassis its seats, hitboxes and model, if it does not have them.
     *
     * <p>Called on chunk load and on enable. Idempotent, which it has to be:
     * a chunk can load more than once and a second set of seats would be a
     * second set of invisible entities with nothing to find them by.
     */
    public void adopt(Entity entity) {
        Optional<ContentId> id = idOf(entity);
        if (id.isEmpty() || live.containsKey(entity.getUniqueId())) {
            return;
        }
        Optional<VehicleInfo> found = info(id.get());
        if (found.isEmpty()) {
            // A chassis whose pack is gone. Left alone rather than deleted:
            // somebody's vehicle disappearing because a pack failed to load
            // is not recoverable, and a reload may well bring it back.
            return;
        }
        Ride ride = new Ride(found.get(), entity);
        ride.spawnParts();
        live.put(entity.getUniqueId(), ride);
    }

    /** Adopts every chassis in every loaded chunk. Called after a load. */
    public void adoptLoaded() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                adopt(entity);
            }
        }
        putEveryoneBack();
    }

    /**
     * Sits everybody {@link #replace} evicted back where they were.
     *
     * <p><strong>A tick later, deliberately.</strong> The eject and the respawn
     * both happened in the call above; {@code sit} refuses anybody who is still
     * {@code isInsideVehicle()}, and whether that has caught up within the same
     * tick is exactly the kind of thing that works on one server and not
     * another. A tick is imperceptible and removes the question.
     *
     * <p>Best effort by design. A seat that no longer exists, a vehicle whose
     * definition went away with the pack, somebody who logged out in between —
     * all of them simply leave that person standing, which is where the old
     * behaviour left everybody.
     */
    private void putEveryoneBack() {
        if (reseat.isEmpty()) {
            return;
        }
        Map<UUID, List<UUID>> pending = Map.copyOf(reseat);
        reseat.clear();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Map.Entry<UUID, List<UUID>> entry : pending.entrySet()) {
                Ride ride = live.get(entry.getKey());
                if (ride == null) {
                    continue;
                }
                List<UUID> seating = entry.getValue();
                for (int index = 0; index < seating.size() && index < ride.info.seats().size(); index++) {
                    UUID id = seating.get(index);
                    Player player = id == null ? null : plugin.getServer().getPlayer(id);
                    if (player != null) {
                        sit(player, ride, index, true);
                    }
                }
            }
        });
    }

    private boolean sit(Player player, Ride ride, int index) {
        return sit(player, ride, index, false);
    }

    /**
     * @param quiet whether to skip the line that says which seat this is —
     *              true when the engine is putting somebody back where they
     *              already were, which is not news
     */
    private boolean sit(Player player, Ride ride, int index, boolean quiet) {
        if (index < 0 || index >= ride.info.seats().size()) {
            return false;
        }
        if (riders.containsKey(player.getUniqueId()) || player.isInsideVehicle()) {
            return false;
        }
        if (ride.occupant(index) != null) {
            return false;
        }
        Location at = ride.seatLocation(index);

        // The same event a chair and a seat bone fire, deliberately: a server
        // that refuses seating means all of them, and inventing a second
        // cancellable event would make "may this player sit here" two
        // questions with two answers.
        // Before either event, because a refusal here is not a decision any
        // listener should have to make: the pack said who may ride this.
        Optional<String> needed = ride.info.permission();
        if (needed.isPresent() && !player.hasPermission(needed.get())) {
            overhead(player, "You cannot ride that.");
            return false;
        }
        ModelSeatEvent asked = new ModelSeatEvent(player, at);
        plugin.getServer().getPluginManager().callEvent(asked);
        if (asked.isCancelled()) {
            return false;
        }
        // Then the narrow question, with the vehicle attached — but not when
        // the engine is putting somebody back where they already were after a
        // rebuild: they never left, as far as a plugin's rule is concerned,
        // and the exit that preceded this said RELOADED for exactly that
        // reason.
        if (!quiet) {
            VehicleEnterEvent entering = new VehicleEnterEvent(player, handle(ride.chassisId), index);
            plugin.getServer().getPluginManager().callEvent(entering);
            if (entering.isCancelled()) {
                return false;
            }
        }

        // Teleported before mounting, which is what aims them: a seat's yaw
        // says which way the occupant faces, and once they are a passenger
        // their look is their own. So this points a rear bench backwards as
        // somebody sits down and never fights them afterwards.
        player.teleport(at);
        if (!ride.mount(index, player)) {
            return false;
        }
        riders.put(player.getUniqueId(), ride.chassisId);
        VehicleSeat seat = ride.info.seats().get(index);
        if (seat.isDriver()) {
            controls.take(player.getUniqueId());
        }
        // A seat that draws nobody. Done here rather than in the tick because
        // it is a fact about the SEAT and not about what the vehicle is doing:
        // asking every tick would be a hide packet twenty times a second for
        // something that changes when somebody sits down.
        if (seat.hidden()) {
            concealed.hide(player);
        }
        // <strong>On the action bar, not in chat.</strong> Which seat you got
        // is worth saying — on a small vehicle the markers overlap, and
        // somebody who meant to drive and got a passenger seat has nothing else
        // to tell them — and so are the controls, which differ by server and
        // are not in any startup report a player reads. What it is not worth is
        // a permanent line in the chat log every time anybody gets into
        // anything. The action bar is exactly the right shape for it: read once
        // as you sit down, gone by the time you are driving.
        if (!quiet && mountHint) {
            ride.hush();
            // The CONTROLS, and not what they just got into. A player who has
            // this moment right-clicked a skateboard knows it is a skateboard;
            // naming it back at them is the sort of line that reads as a plugin
            // talking about itself. What they cannot know is which keys this
            // server drives with, because that depends on what the server can
            // read. A passenger has no controls at all, so they get which seat
            // they took, which IS worth saying: on a small vehicle the markers
            // overlap and somebody who meant to drive needs to know they did
            // not.
            overhead(player, seat.isDriver()
                    ? controls.describe()
                    : "Riding in " + seatName(ride.info, seat).toLowerCase(Locale.ROOT));
        }
        return true;
    }

    /**
     * Puts a line above the hotbar rather than in the chat log.
     *
     * <p><strong>Where every transient thing a vehicle says now goes.</strong>
     * Which seat you took and whether a hull is beached are both conditions of
     * the next few seconds; in chat they are permanent, they stack, and driving
     * a boat around a shoreline for a minute leaves a page of them. The action
     * bar says it where somebody is already looking and takes it away again.
     *
     * <p>Spigot's own API, which is the same one {@code ActionRunner} uses for
     * an item's action bar message — this engine compiles against Spigot, and
     * {@code Player#sendActionBar} is Paper's.
     */
    private static void overhead(Player player, String line) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                        ChatColor.translateAlternateColorCodes('&', line)));
    }

    /** What to call a seat in a message: its own name, or its role and number. */
    private static String seatName(VehicleInfo info, VehicleSeat seat) {
        if (seat.name().isPresent()) {
            return seat.name().get();
        }
        if (seat.isDriver()) {
            return "the driver seat";
        }
        int number = 0;
        for (VehicleSeat other : info.seats()) {
            if (!other.isDriver()) {
                number++;
            }
            if (other == seat) {
                break;
            }
        }
        return "passenger seat " + number;
    }

    /**
     * Listens for dismounts, whichever package this server keeps that event
     * in.
     *
     * <p>Same reflective registration, for the same reason, as {@code Seats}:
     * {@code EntityDismountEvent} moved package in 1.20.1, and an
     * {@code @EventHandler} naming the class this server does not have fails
     * the whole listener registration.
     */
    @SuppressWarnings("unchecked")
    public void registerDismount(Plugin owner) {
        Class<?> found = null;
        for (String name : new String[] {
                "org.bukkit.event.entity.EntityDismountEvent",
                "org.spigotmc.event.entity.EntityDismountEvent" }) {
            try {
                found = Class.forName(name);
                break;
            } catch (ClassNotFoundException ignored) {
                // Try the other spelling.
            }
        }
        if (found == null || !Event.class.isAssignableFrom(found)) {
            log.warning("This server has no EntityDismountEvent, so getting out of a vehicle is "
                    + "noticed when you log out rather than the moment you step off.");
            return;
        }
        // FIRST: refuse the dismount of a held occupant of a moving vehicle,
        // so the sneak key is free to mean something else. See
        // Vehicle#holdOccupant, and `holds` for why a stopped vehicle always
        // lets go.
        owner.getServer().getPluginManager().registerEvent(
                (Class<? extends Event>) found, this, EventPriority.HIGHEST,
                (listener, event) -> {
                    if (!(event instanceof Cancellable) || !(event instanceof EntityEvent)) {
                        return;
                    }
                    Entity who = ((EntityEvent) event).getEntity();
                    if (!(who instanceof Player) || leaving != null) {
                        // An eviction the engine itself started is not a
                        // sneak, and must never be refused.
                        return;
                    }
                    UUID chassis = riders.get(who.getUniqueId());
                    Ride ride = chassis == null ? null : live.get(chassis);
                    if (ride != null && ride.holds(who.getUniqueId())) {
                        ((Cancellable) event).setCancelled(true);
                    }
                },
                owner);
        // THEN: notice the ones that went through. `ignoreCancelled` so a
        // refused dismount is not also recorded as somebody leaving — which
        // would drop the ride's state while the rider was still sitting in it.
        owner.getServer().getPluginManager().registerEvent(
                (Class<? extends Event>) found, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (event instanceof EntityEvent
                            && ((EntityEvent) event).getEntity() instanceof Player) {
                        // Their own dismount, unless an eviction is under way
                        // and this is the event it caused — see `leaving`.
                        left((Player) ((EntityEvent) event).getEntity(),
                                leaving != null ? leaving : VehicleExitEvent.Cause.DISMOUNTED);
                    }
                },
                owner, true);
    }

    /** Somebody got off, or logged out. */
    private void left(Player player, VehicleExitEvent.Cause cause) {
        if (player.getUniqueId().equals(reseating)) {
            // Moved from one mount to another, not out. See Ride.reseat.
            return;
        }
        UUID chassis = riders.remove(player.getUniqueId());
        controls.forget(player.getUniqueId());
        if (chassis == null) {
            return;
        }
        Ride ride = live.get(chassis);
        if (ride != null) {
            ride.vacate(player.getUniqueId(), cause);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        left(event.getPlayer(), VehicleExitEvent.Cause.QUIT);
    }

    /**
     * Tells somebody who just arrived about the riders they cannot see.
     *
     * <p>{@code hidePlayer} is per-viewer and is sent once, to the clients that
     * were online at the time — so without this a player logging in beside a
     * tank is the only person in the world who can see its driver sitting
     * inside it.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        concealed.greet(event.getPlayer());
    }

    // ------------------------------------------------------------------
    // The tick
    // ------------------------------------------------------------------

    /** Starts the driving loop. Called once, on enable. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Removes temporary debris immediately when the host plugin stops. */
    public void stop() {
        for (Debris part : List.copyOf(debris)) {
            part.remove();
        }
        debris.clear();
    }

    /**
     * One tick of every vehicle somebody is in.
     *
     * <p>A parked vehicle is not ticked at all — it has no driver, so it has
     * nowhere to go, and a server with a hundred cars in a car park should pay
     * for none of them. That is also why the seats are only rebuilt when a
     * chunk loads: this loop never touches them.
     *
     * <p><strong>Three passes, not one, and the middle one is why.</strong>
     * Every vehicle takes its step; then they are let hit each other; then each
     * puts its entities where it ended up. A vehicle cannot resolve a collision
     * during its own step, because the thing it is hitting may not have moved
     * yet — whichever of the two the map happened to hand out first would get a
     * different answer from the one that came second, and two cars meeting
     * head-on would behave differently depending on which was spawned first.
     * Stepping everybody before anybody is asked is what makes the exchange
     * symmetric. And placing afterwards is what keeps a collision from being
     * visible a tick late: the entities are teleported once, to where the
     * vehicle finished, impact included.
     */
    private void tick() {
        for (Ride ride : live.values()) {
            try {
                ride.tick();
            } catch (RuntimeException e) {
                // One broken vehicle must not stop every other vehicle on the
                // server. Logged at the level a server owner sees, because a
                // vehicle that silently stopped moving is the exact failure
                // this whole class is written to make visible.
                log.warning("Vehicle " + ride.info.id() + " failed a tick: " + e);
            }
        }
        try {
            impacts();
        } catch (RuntimeException e) {
            log.warning("Vehicle collisions failed a tick: " + e);
        }
        for (Ride ride : live.values()) {
            try {
                ride.settle();
            } catch (RuntimeException e) {
                log.warning("Vehicle " + ride.info.id() + " failed to settle: " + e);
            }
        }
        debris.removeIf(Debris::tick);
    }

    /** Lets one detached display follow a small vanilla-physics host until expiry. */
    private final class Debris {
        private final UUID hostId;
        private final UUID displayId;
        private double yaw;
        private double spin;
        private long remaining;

        Debris(ArmorStand host, ItemDisplay display, double spin, long remaining) {
            this.hostId = host.getUniqueId();
            this.displayId = display.getUniqueId();
            this.yaw = display.getLocation().getYaw();
            this.spin = Math.max(-720, Math.min(720, Double.isFinite(spin) ? spin : 0));
            this.remaining = Math.max(1, Math.min(20L * 60L * 10L, remaining));
        }

        /** @return true when this entry is finished and should leave the list */
        boolean tick() {
            Entity host = plugin.getServer().getEntity(hostId);
            Entity part = plugin.getServer().getEntity(displayId);
            if (!(host instanceof ArmorStand) || !(part instanceof ItemDisplay)
                    || !host.isValid() || !part.isValid() || --remaining <= 0) {
                remove();
                return true;
            }
            yaw = VehiclePhysics.wrap360(yaw + spin * DT);
            if (host.isOnGround()) {
                spin *= 0.86;
            }
            Location at = host.getLocation();
            at.setYaw((float) yaw);
            at.setPitch(0);
            part.teleport(at);
            return false;
        }

        void remove() {
            Entity host = plugin.getServer().getEntity(hostId);
            Entity part = plugin.getServer().getEntity(displayId);
            if (host != null) host.remove();
            if (part != null) part.remove();
        }
    }

    private void throwDebris(ItemDisplay display, Vector velocity, double spin, long despawnTicks) {
        if (display == null || !display.isValid()) return;
        Location at = display.getLocation();
        ArmorStand host = at.getWorld().spawn(at, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setSmall(true);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setGravity(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(false);
        });
        Vector perTick = velocity == null ? new Vector() : velocity.clone().multiply(DT);
        if (!Double.isFinite(perTick.getX()) || !Double.isFinite(perTick.getY())
                || !Double.isFinite(perTick.getZ())) {
            perTick.zero();
        }
        double length = perTick.length();
        if (length > 4) perTick.multiply(4 / length);
        host.setVelocity(perTick);
        display.setPersistent(false);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        debris.add(new Debris(host, display, spin, despawnTicks));
    }

    /**
     * Lets every vehicle that moved this tick hit whatever it landed on.
     *
     * <p><strong>Asked only of vehicles that took a full tick.</strong> A
     * parked one — empty and going nowhere — is not asked, so a car park of a
     * hundred cars costs a hundred nothings, which is the same bargain
     * {@code parked} strikes one level up and the reason this can afford to run
     * every tick. It is still a perfectly good thing to be hit BY, which is
     * what makes shunting one out of the way work; see {@link Ride#ticked} for
     * why the test is not "did it move".
     *
     * <p>The neighbours come from the world's own entity index rather than from
     * a spatial structure of ours, on the same terms {@link Ride#shoveAside}
     * already uses: {@code live} is keyed by chassis id, so turning a nearby
     * entity into a vehicle is one map lookup, and the chunk index does the
     * part that would otherwise need a grid.
     *
     * <p>Each pair is resolved once. Where both ticked, the lower chassis id
     * takes it, which is an arbitrary rule and only has to be a consistent
     * one — resolving a pair twice in a tick is a collision that hits twice as
     * hard as it should.
     *
     * <p>Applied as they are found rather than gathered and applied together,
     * so a car shunted into a third car passes the shove on within the same
     * tick. That is sequential impulse solving, which is what every physics
     * engine does and for the same reason: the alternative needs a solver, and
     * what it buys is exactness in a pile-up.
     */
    private void impacts() {
        // One vehicle in the world has nothing to hit, and the entity search
        // below is the most expensive call in this pass. Most servers running
        // one car around a test world never get past this line.
        if (!collide || live.size() < 2) {
            return;
        }
        for (Ride ride : live.values()) {
            if (!ride.collidable() || !ride.ticked()) {
                continue;
            }
            Location where = ride.at;
            double reach = ride.impactReach();
            for (Entity nearby : where.getWorld().getNearbyEntities(where, reach, reach, reach)) {
                Ride other = live.get(nearby.getUniqueId());
                if (other == null || other == ride || !other.collidable()) {
                    continue;
                }
                // The other side will take this pair if it ticked too and
                // sorts first. Two parked vehicles are a pair nobody takes.
                if (other.ticked() && other.chassisId.compareTo(ride.chassisId) < 0) {
                    continue;
                }
                ride.collideWith(other);
            }
        }
    }

    /**
     * Says once that armour stands are not ticking, which is the one server
     * setting that stops all of this working.
     *
     * <p>Detected rather than read out of a config file, because the setting
     * is Paper's and the engine runs on Spigot too. What it looks like from
     * here is a chassis that was teleported and is not where it was sent.
     */
    /**
     * Says once that riders are not being put in their rigs, and why.
     *
     * <p>The default stance needs one thing the engine cannot supply: a baked
     * rig for that player, which is their skin and lives in the pack. So on a
     * pack with no emote rigs every occupant of every vehicle sits as an
     * ordinary player — correct, and completely silent, which is the failure
     * this whole class keeps having to make visible.
     *
     * <p>Not sent to the riders. It is one fact about the pack rather than
     * about any of them, and a chat line per person per seat per journey would
     * be worse than the thing it is reporting.
     */
    private void warnIfNoSeatRigs(Player player, EmoteResult result) {
        // Once per PLAYER per reason, not once per server. It was the latter,
        // and that is close to no diagnostic at all for the question it is
        // there to answer: "everybody else rides as themselves and I do not"
        // is a fact about which players the pack carries a rig for, so a line
        // that fires once and names nobody cannot distinguish one unlucky
        // rider from all of them. The set is bounded by players times reasons,
        // which on a server where this is happening is small and on one where
        // it is not is empty.
        if (!warnedRefusals.add(player.getUniqueId() + "/@stance/" + result.reason())) {
            return;
        }
        log.warning("Vehicle: " + player.getName() + " is riding as an ordinary player rather than "
                + "in their rig (" + result.reason() + "). A seat puts somebody in their emote rig "
                + "by default, which needs the pack to carry a baked rig for THAT player - sync the "
                + "pack again while they are online, or set vehicles.seat-rig: false in config.yml "
                + "if you would rather riders stayed themselves.");
    }

    private void warnIfFrozen() {
        if (warnedFrozen) {
            return;
        }
        warnedFrozen = true;
        log.warning("A vehicle was told to move and did not. If this server sets "
                + "armor-stands-tick: false in paper-world-defaults.yml, that is why - "
                + "the chassis is a stand, and every vehicle will sit still.");
    }

    /** Called when the plugin unloads. Takes every derived entity with it. */
    public void clear() {
        for (Ride ride : live.values()) {
            ride.evictAll(VehicleExitEvent.Cause.SHUTDOWN);
            ride.despawnParts();
        }
        live.clear();
        riders.clear();
        // After the evictions, which reveal their own riders through `undress`.
        // This is the backstop for anybody the eviction could not reach — their
        // chunk had gone, their Ride was already dropped — because a player
        // left invisible by a plugin that is no longer running has nothing at
        // all to put them back.
        concealed.clear();
        stop();
    }

    // ------------------------------------------------------------------
    // One vehicle in the world
    // ------------------------------------------------------------------

    /**
     * A chassis with its seats, its model and where it is going.
     *
     * <p>Holds the authoritative position rather than reading it off the
     * chassis: everything else is pushed at this, so nothing that shoves an
     * entity around can accumulate into drift.
     */
    private final class Ride {

        private final VehicleInfo info;
        private final UUID chassisId;
        private final World world;

        /** Where the vehicle actually is, which is not necessarily where its entities are yet. */
        private Location at;
        private VehiclePhysics.State state;

        /** One per seat, in the pack's order. Index IS the seat number. */
        private final List<UUID> mounts = new ArrayList<>();
        private final List<UUID> hitboxes = new ArrayList<>();
        private final List<UUID> occupants = new ArrayList<>();

        /**
         * How far below its mount each seat's occupant actually ended up, in
         * blocks, measured — or null until a rider has been aboard for a tick.
         *
         * <p><strong>This is what makes the seat height right on a version
         * nobody has measured.</strong> Where a passenger sits over its mount
         * is vanilla's rule, changed between versions before, and every figure
         * in {@link MountOffset} is one somebody measured by hand. The engine
         * has a better instrument: the rider. Every tick the mount is exactly
         * where the previous tick put it, and the rider is exactly where
         * vanilla put them over it, so the difference between the two IS the
         * rule on this server. From the second tick aboard the mount is aimed
         * with that number, and the guess in {@link MountOffset} only ever
         * decides the first.
         */
        private final List<Double> mountDrop = new ArrayList<>();

        /**
         * The vehicle's own body, as one or more Interaction boxes.
         *
         * <p>Tiled along the forward axis because an Interaction's footprint is
         * SQUARE — its width applies to both horizontal axes — so a bus needs
         * several of them to be bus-shaped. See {@link VehicleHitbox}.
         */
        private final List<UUID> body = new ArrayList<>();

        private UUID modelId;

        /**
         * Its animated model, or null when this vehicle's art is one still
         * display.
         *
         * <p>Exactly one of this and {@link #modelId} is ever set. Which one
         * is decided once, at spawn, by whether the model has a rig with
         * anything that moves in it — so a vehicle that gains an animation in
         * studio becomes animated on the next chunk load, and one that loses
         * its last keyframe goes quietly back to a single display.
         */
        private RigCarrier.CarriedRig rig;

        /** Rig bone names before already-detached ones are hidden. */
        private List<String> supportedParts = List.of();

        /**
         * What the rig is playing, so a state change is noticed rather than
         * re-asked twenty times a second.
         *
         * <p>{@code Placement.play} already ignores a repeat of a one-shot
         * that is mid-play, but a LOOP would be restarted every tick — which
         * is an animation that never gets past its first frame. This is the
         * memo that stops that.
         */
        private String playing;

        /**
         * Whether {@link #playing} is a name the model actually has.
         *
         * <p>Guards the re-assert below from becoming a lookup per tick for a
         * vehicle whose pack names an animation that has since been renamed.
         */
        private boolean playable;

        /** Its own age in ticks, which is what a particle interval counts against. */
        private long age;

        /**
         * The placed models close enough to matter, as of this tick.
         *
         * <p>Refreshed once at the top of {@link #tick} and then read by every
         * question the world is asked — see {@link ModelObstacles} for why the
         * gathering and the asking are separated. Empty for a vehicle with no
         * furniture near it, which is nearly all of them, and every read below
         * short-circuits on that.
         */
        private ModelObstacles nearbyModels = ModelObstacles.NONE;

        /**
         * Where this vehicle has got to in the sound it is playing.
         *
         * <p>Per vehicle rather than shared, unlike {@link #particles}: what
         * this holds is a playhead, and two cars on one server are two engines
         * at two points in the same file.
         */
        private final VehicleSounds noise = new VehicleSounds();

        /**
         * Whether the last full tick found this vehicle empty and going
         * nowhere.
         *
         * <p>Latched rather than asked, because asking is the expensive part:
         * knowing whether a vehicle would move needs the block reads and the
         * physics step this is there to skip. So the answer from the last full
         * tick stands until the next one — see {@link #PARKED_POLL_TICKS}.
         */
        private boolean parked;

        /**
         * What it was doing when it parked.
         *
         * <p>Kept so the cheap path can still drive the animation and the
         * emitters: a parked vehicle is in {@code idle} (and, for a boat,
         * {@code submerged}), and those are states a pack maps things to.
         */
        private Set<VehicleState> parkedStates = Set.of();

        /**
         * What each occupant is currently wearing, or null for their own body.
         *
         * <p>Per OCCUPANT rather than per vehicle: two seats wear different
         * things in the same state, which is most of why a seat carries its own
         * map. A key with a null value is meaningful — it says "we have already
         * asked for nothing", which is what stops a seat whose state maps to
         * nothing being re-asked every tick.
         */
        private final Map<UUID, String> worn = new java.util.HashMap<>();

        /**
         * The waiting room in front of {@code worn}, one per occupant, because
         * two seats change state independently. See {@link StateSettle} — the
         * vehicle's own animation waits out the same window with the same gate.
         */
        private final Map<UUID, StateSettle> settling = new java.util.HashMap<>();

        /**
         * Who is actually wearing something right now, as opposed to who has
         * been asked to.
         *
         * <p><strong>The two are not the same and the difference is a rider
         * stuck as themselves for the rest of the journey.</strong>
         * {@link #worn} is written BEFORE the call so that a refusal is not
         * retried twenty times a second — which is right, and which also means
         * it keeps saying "wearing the drive pose" after anything outside this
         * vehicle takes the rig off: {@code /emote stop}, a death, a plugin
         * ending the session. Nothing changes state afterwards, so the memo
         * never disagrees with itself and the rig never comes back.
         *
         * <p>This is the other half: only somebody whose {@code wear} actually
         * STARTED is in here, so a rig that has gone missing can be re-asked
         * without turning a permanent refusal into a lookup per tick.
         */
        private final Set<UUID> dressed = new java.util.HashSet<>();

        /**
         * Whether the driver has already been told this hull is out of water.
         *
         * <p>Latched rather than sent per tick, and cleared when it floats
         * again, so beaching a boat says one line and refloating it re-arms
         * the warning for the next time.
         */
        private boolean toldBeached;

        /** How many ticks in a row a move was asked for and nothing happened. */
        private int stuck;

        /**
         * Whether it answers its driver — {@link Vehicle#setEnabled}.
         *
         * <p>Read off the chassis when the Ride is built and written back
         * on every change, so this field is a cache of {@link #disabledKey}
         * and never the other way round.
         */
        private boolean enabled;

        /**
         * A plugin's cap on the top speed, or NaN for none —
         * {@link Vehicle#setSpeedLimit}. Not persisted, on purpose.
         */
        private double speedLimit = Double.NaN;

        /**
         * A plugin's handling penalty and standing lean — {@link Vehicle#setHandling}
         * and {@link Vehicle#setPosture}. Not persisted either: what caused
         * them is, so whatever imposed them re-imposes them on the next tick
         * after a reload rather than the engine remembering a number it cannot
         * explain.
         */
        private double speedFactor = 1;
        private double turnFactor = 1;
        private double posturePitch;
        private double postureRoll;

        /**
         * A plugin's rate of descent, or NaN for none —
         * {@link Vehicle#setDescent}. Not persisted, for the same reason.
         */
        private double descent = Double.NaN;

        /**
         * {@link #info} with the speed limit and the descent applied, or
         * {@code info} itself when there is neither.
         *
         * <p>What the physics is given. Everything else — seats, hitbox, the
         * name — reads {@code info}, because a limit changes none of those.
         * Rebuilt only when the limit changes, so the per-tick cost of having
         * one is nothing.
         */
        private VehicleInfo driven;

        /**
         * What the last full tick said it was doing, so a change is noticed
         * — {@link VehicleStateEvent} fires on the change and never on the
         * tick.
         */
        private Set<VehicleState> lastStates = Set.of();

        /**
         * The tick until which the speedometer keeps off the action bar,
         * because something worth reading was just written there. See
         * {@link #hush}.
         */
        private long hushUntil;

        /**
         * What the driver asked for on the last full tick, for
         * {@link Vehicle#input}: a plugin reads the same demand the physics
         * did rather than asking the player again a tick apart.
         */
        private VehiclePhysics.Demand lastDemand = VehiclePhysics.Demand.idle(0);

        /**
         * The step this tick took, waiting for {@link #settle} — or null for a
         * vehicle that has not taken one this tick, which is every parked one
         * and any whose chassis has gone.
         *
         * <p>A handoff between two halves of one tick, not state: it is written
         * at the end of {@link #tick} and consumed at the top of {@link #settle},
         * and nothing between those two points may read it except by knowing
         * that. See {@link VehicleRuntime#tick()} for what runs in the gap.
         */
        private VehiclePhysics.Step pending;

        /** What the world was doing during {@link #pending}. Same handoff. */
        private VehiclePhysics.Surroundings pendingAround;

        /** Whether a collision moved this vehicle after its own step. See {@link #settle}. */
        private boolean bumped;

        /**
         * How far into its animation a speed-linked vehicle is, in seconds of
         * that animation - advanced by how far the vehicle TRAVELLED this
         * tick rather than by the tick itself. See
         * {@link VehicleInfo#animationFollowsSpeed}.
         *
         * <p>Kept across a change of state on purpose: `moving` and
         * `reversing` are the same wheel at the same angle, so a cycle that
         * carried on from where the last one was is one less thing for the
         * crossfade to hide.
         */
        private double animationPhase;

        /**
         * The animation a plugin asked for with {@code Vehicle.perform}, or
         * null while the state table has the rig.
         *
         * <p>Deliberately NOT written to the chassis. A trick is a condition
         * of the moment in the same way a speed limit is, and a bike that came
         * back from a restart still holding a wheelie would be one nobody
         * could get out of.
         */
        private String performed;

        /**
         * The wall this vehicle is riding, or null. Held across ticks rather
         * than re-decided from scratch each one: STARTING a ride asks a lot
         * (fast enough, off the ground, shallow enough) and CONTINUING one
         * asks little (still fast, wall still there), which is what makes a
         * ride something you commit to rather than something that flickers on
         * and off along a bumpy wall.
         */
        private VehiclePhysics.Wall wall;

        /** Whether it was off the ground last tick, so a landing can be noticed. */
        private boolean wasAirborne;

        /** Where it was last tick, for working out which way a landing was travelling. */
        private Location lastAt;

        /**
         * How long the driver has held sneak, ticks, while the engine is
         * holding them in their seat. See {@link #SNEAK_TAP_TICKS}.
         */
        private int sneakTicks;



        /**
         * Emotes a plugin has put on occupants over their seat's states —
         * {@link Vehicle#dress}. Consulted by {@link #dressOccupants} ahead
         * of the state table, and dropped when they get out.
         */
        private final Map<UUID, String> dressOverrides = new java.util.HashMap<>();

        /**
         * Facings a plugin has given occupants instead of their seat's yaw —
         * {@link Vehicle#turnOccupant}. Read wherever a seat's yaw is, and
         * dropped when they get out.
         */
        private final Map<UUID, Double> yawOverrides = new java.util.HashMap<>();

        /**
         * Emote variants a plugin has given occupants - {@link
         * Vehicle#dressVariant}. Applied to whatever the seat's state table
         * names, and dropped when they get out.
         */
        private final Map<UUID, String> variants = new java.util.HashMap<>();

        /**
         * Occupants a plugin has asked to keep in their seats through the
         * sneak key — {@link Vehicle#holdOccupant}. Consulted by the dismount
         * listener, and only while this vehicle is moving; see there.
         */
        private final java.util.Set<UUID> held = new java.util.HashSet<>();

        /** The way seat {@code index}'s occupant faces: their override, or the seat's yaw. */
        private double seatYaw(int index) {
            VehicleSeat seat = info.seats().get(index);
            UUID occupant = index < occupants.size() ? occupants.get(index) : null;
            Double turned = occupant == null ? null : yawOverrides.get(occupant);
            return turned != null ? turned : seat.yaw();
        }

        /**
         * Whether the last speedometer write was a moving one, so a vehicle
         * that has just stopped writes its zero once and then leaves the bar
         * alone.
         */
        private boolean showedSpeed;

        Ride(VehicleInfo info, Entity chassis) {
            // Chassis saved by builds before entity-tracked vehicle audio are
            // still marked silent in NBT. Repair them as they are adopted so
            // existing vehicles become valid sound sources too.
            enableChassisSounds(chassis);
            this.info = info;
            this.driven = info;
            this.chassisId = chassis.getUniqueId();
            this.world = chassis.getWorld();
            this.enabled = !chassis.getPersistentDataContainer().has(disabledKey, PersistentDataType.BYTE);
            // `at` is a POSITION and carries no rotation at all — the heading
            // lives in `state` and the pitch is nobody's. A chassis is spawned
            // at the player's own location, which brings their pitch with it,
            // and an ItemDisplay honours pitch: a vehicle parked by somebody
            // looking at the ground sat tilted until the first move replaced
            // `at` with a fresh Location (which is why it "fixed itself" as
            // soon as anybody drove it).
            Location spawned = chassis.getLocation();
            this.at = new Location(spawned.getWorld(), spawned.getX(), spawned.getY(), spawned.getZ());
            this.state = VehiclePhysics.State.still(spawned.getYaw());
            for (int i = 0; i < info.seats().size(); i++) {
                mounts.add(null);
                hitboxes.add(null);
                occupants.add(null);
                mountDrop.add(null);
            }
        }

        private Entity chassis() {
            return plugin.getServer().getEntity(chassisId);
        }

        // --- parts ----------------------------------------------------

        /**
         * Spawns the seats, their hitboxes and the model.
         *
         * <p>All of them {@code setPersistent(false)}: they are derived from
         * the chassis and the pack, so saving them means a chunk load makes a
         * second set while the first is still there, invisible and permanent.
         * That is the 0.46.1 audit's finding, written down again because the
         * shape recurs.
         */
        void spawnParts() {
            for (int i = 0; i < info.seats().size(); i++) {
                Location seat = seatLocation(i);
                Entity mount = spawnMount(i);
                mounts.set(i, mount.getUniqueId());
                parts.put(mount.getUniqueId(), chassisId);

                Interaction hitbox = world.spawn(seat, Interaction.class, box -> {
                    box.setInteractionWidth(0.6f);
                    box.setInteractionHeight(0.8f);
                    box.setResponsive(true);
                    box.setPersistent(false);
                });
                hitboxes.set(i, hitbox.getUniqueId());
                parts.put(hitbox.getUniqueId(), chassisId);
            }

            VehicleHitbox box = info.hitbox();
            for (int tile = 0; tile < box.tiles(); tile++) {
                Interaction part = world.spawn(bodyLocation(tile), Interaction.class, b -> {
                    b.setInteractionWidth((float) box.width());
                    b.setInteractionHeight((float) box.height());
                    b.setResponsive(true);
                    b.setPersistent(false);
                });
                body.add(part.getUniqueId());
                parts.put(part.getUniqueId(), chassisId);
            }

            refreshArt();
        }

        /** Whether anybody is in any seat. */
        private boolean occupied() {
            for (UUID id : occupants) {
                if (id != null) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Draws the art, or takes it away, according to whether it should be
         * seen right now: always, unless the vehicle is WORN and somebody is
         * in it - see {@link VehicleInfo#worn()}. Idempotent, and called from
         * the three places the answer can change: the parts being spawned,
         * somebody getting in, somebody getting out.
         *
         * <p>Taking it away is a despawn rather than an invisible item, so
         * that an animated model's rig goes with it and nothing keeps posing
         * displays nobody can see; spawning it again restarts its clock,
         * which for a thing that was just stepped out of is the right frame
         * to start on.
         */
        private void refreshArt() {
            boolean hidden = info.worn() && occupied();
            boolean drawn = modelId != null || rig != null;
            if (hidden && drawn) {
                removeEntity(modelId);
                modelId = null;
                if (rig != null) {
                    rig.despawn();
                    rig = null;
                    playing = null;
                    playable = false;
                }
            } else if (!hidden && !drawn) {
                // An animated model first: a vehicle whose art moves is several
                // displays the animator retimes, and a still one is a single
                // display. Same branch, and for the same reason, as
                // ModelPlacementListener's — which is why it is a question about
                // the MODEL rather than about the vehicle.
                if (!spawnRig()) {
                    art().ifPresent(this::spawnModel);
                }
            }
        }

        /**
         * Spawns the split model rig, if this vehicle has one.
         *
         * @return whether it did, so the caller knows not to spawn a still
         *         display as well — two models on one vehicle is one model
         *         and a ghost
         */
        private boolean spawnRig() {
            String id = artId();
            if (rigs == null || id == null || !rigs.hasRig(id)) {
                return false;
            }
            rig = rigs.carry(modelAnchor(), id, modelYaw(), carry, this::partStack,
                    (float) info.scale()).orElse(null);
            if (rig != null) {
                supportedParts = rig.bones();
                for (String detached : detachedParts()) {
                    rig.hide(detached);
                }
            }
            return rig != null;
        }

        /**
         * Which model this vehicle's art IS, by whichever name it was
         * delivered under.
         *
         * <p>The rig store is keyed by model id, and both front doors put the
         * model's own id in it — studio's {@code carrier} string IS the model
         * id, and an authored vehicle's {@code model:} is the item whose model
         * it wears. So this is one lookup key from two spellings, which is the
         * same fork {@link #art()} makes one line further down.
         */
        private String artId() {
            Optional<String> carrier = info.carrier();
            if (carrier.isPresent()) {
                return carrier.get();
            }
            return info.model().map(ContentId::toString).orElse(null);
        }

        /**
         * What one part of the rig renders as.
         *
         * <p>The same two ways of naming art as {@link #art()}: paper wearing
         * a string for a pushed pack, the vehicle's own item wearing the
         * part's model for an authored one.
         */
        private ItemStack partStack(String partItem) {
            if (info.carrier().isPresent()) {
                return StudioCarrier.item(tags, partItem);
            }
            ItemStack stack = info.model().flatMap(items::create).orElse(null);
            if (stack == null) {
                return null;
            }
            stack.setAmount(1);
            // Through the service rather than setItemModel, because how a
            // model is addressed is a version fork — see Items.wearModel.
            ContentId.parse(partItem).ifPresent(model -> items.wearModel(stack, model));
            return stack;
        }

        /**
         * The entity seat {@code index}'s occupant rides.
         *
         * <p>A display, holding nothing, on every server that can teleport a
         * ridden entity: it has no box, no physics and no tick of its own,
         * its position goes to clients every tick, and it glides between
         * them on the same window as the model — see the class note. The
         * seat's clickable box is the {@code Interaction} beside it, so a
         * mount nothing can click is no loss.
         *
         * <p>A small armour stand WITH gravity on a server that has refused
         * (see {@link PassengerTeleport#exact}). Gravity is what gives a
         * stand physics; without it the velocity the fallback arm sets is
         * dropped on the floor, which is the bug this whole arrangement
         * replaced. Nothing else about it is the display's equal — its
         * position reaches clients every third tick — but a rider carried
         * approximately beats one left in the road.
         */
        private Entity spawnMount(int index) {
            boolean stand = !seatMover.exact();
            Location where = mountLocation(index, stand);
            if (!stand) {
                ItemDisplay mount = world.spawn(where, ItemDisplay.class, d -> d.setPersistent(false));
                carry.carry(mount);
                return mount;
            }
            return world.spawn(where, ArmorStand.class, s -> {
                s.setMarker(false);
                s.setSmall(true);
                s.setVisible(false);
                s.setGravity(true);
                // A stand has a bounding box, which is the price of physics —
                // and an invisible quarter-block box that other players walk
                // into is a bug. Nothing should ever collide with a seat.
                s.setCollidable(false);
                s.setInvulnerable(true);
                s.setSilent(true);
                s.setPersistent(false);
            });
        }

        /**
         * Swaps seat {@code index}'s display for a stand, keeping its rider.
         *
         * <p>Reached once per server, ever: the first time a ridden display
         * refuses to teleport, which latches {@link PassengerTeleport} onto
         * the fallback arm so every mount spawned afterwards is a stand
         * already. Removing the display ejects its rider, and that ejection
         * must not read as them getting out — {@link #reseating} is the
         * guard {@code left} checks.
         */
        private Entity reseat(int index, UUID occupant) {
            UUID oldId = mounts.get(index);
            Entity old = oldId == null ? null : plugin.getServer().getEntity(oldId);
            Entity mount = spawnMount(index);
            mounts.set(index, mount.getUniqueId());
            parts.put(mount.getUniqueId(), chassisId);
            if (oldId != null) {
                parts.remove(oldId);
            }
            Player player = plugin.getServer().getPlayer(occupant);
            reseating = occupant;
            try {
                if (old != null) {
                    old.remove();
                }
                if (player != null && !mount.addPassenger(player)) {
                    // Left standing beside it, which from their side is a
                    // dismount they did not ask for.
                    vacate(occupant, VehicleExitEvent.Cause.DISMOUNTED);
                }
            } finally {
                reseating = null;
            }
            return mount;
        }

        /** Where the model sits: half a block up, so its base is on the chassis. */
        private Location modelAnchor() {
            // Plus the ride height: the body sits at the mean of its wheels,
            // sprung, while the position stays on the highest of them. See
            // VehiclePhysics.State.lift.
            return at.clone().add(0, MODEL_LIFT + state.lift(), 0);
        }

        /**
         * The body's attitude as the rotation an item display applies before
         * its own yaw.
         *
         * <p>In the model's frame, where the front is -z, the right-hand side
         * is +x and up is +y: nose-up is a positive turn about x, and
         * right-side-down is a negative turn about z. Roll is applied first
         * and pitch on top of it, which is the order
         * {@code VehiclePhysics.bodyOffset} uses for the seats — the two have
         * to agree or a rider slides off their seat as the body tilts.
         */
        private Matrix4f attitude() {
            return new Matrix4f()
                    .rotateX((float) Math.toRadians(state.pitch()))
                    .rotateZ((float) -Math.toRadians(state.roll()));
        }

        /**
         * Whether the body is tilted enough to be worth telling the client.
         * A tenth of a degree is under what a display can show.
         */
        private boolean tilted() {
            return Math.abs(state.pitch()) > 0.1 || Math.abs(state.roll()) > 0.1;
        }

        /**
         * Which way the model is drawn.
         *
         * <p>The same value for a rig as for a still display, deliberately.
         * {@code RigPlacementListener} passes ONE yaw to both arms of its own
         * branch — the still display's entity yaw and the rig's baked pose use
         * the same number — so a vehicle that did otherwise would have its
         * animated art facing a different way from its still art for no reason
         * anybody could find. {@link #MODEL_YAW_OFFSET} is the whole of the
         * difference from the vehicle's heading.
         */
        private float modelYaw() {
            return (float) state.yaw() + MODEL_YAW_OFFSET;
        }

        /** Where body tile {@code index} sits, turned with the vehicle. */
        private Location bodyLocation(int index) {
            double[] offset =
                    VehiclePhysics.seatOffset(state.yaw(), 0, info.hitbox().tileOffset(index));
            return new Location(world, at.getX() + offset[0], at.getY(), at.getZ() + offset[1]);
        }

        /**
         * The stack the model display holds.
         *
         * <p>Two ways in, and neither is a branch on where the vehicle came
         * from — it is a branch on how its art was NAMED. A pack built here
         * ships the model beside an item, so the item is the handle; a pack
         * built by Studio ships a zip with no plugin behind it, so its models
         * borrow paper wearing a {@code custom_model_data} string. Exactly the
         * fork {@code RigSpawn} already describes for a rig's parts, and the
         * only thing that differs between an authored and a pushed vehicle.
         */
        private Optional<ItemStack> art() {
            Optional<String> carrier = info.carrier();
            if (carrier.isPresent()) {
                return Optional.of(StudioCarrier.item(tags, carrier.get()));
            }
            return info.model().flatMap(items::create);
        }

        private void spawnModel(ItemStack item) {
            ItemDisplay display = world.spawn(at.clone().add(0, MODEL_LIFT, 0), ItemDisplay.class, d -> {
                d.setItemStack(item);
                // NONE for the same reason a placed model uses it: every other
                // transform applies the model's own display block, and a
                // generated model's `fixed` is scale 0.5 with a translation.
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(Display.Billboard.FIXED);
                d.setPersistent(false);
                // The same call a placed model of the same size uses, and
                // MODEL_LIFT above is deliberately NOT multiplied by it:
                // `scaledTransformation` already carries the 0.5 * (scale - 1)
                // that keeps a grown model's floor where an ungrown one's was.
                // Scaling the lift as well would raise the vehicle off the road
                // by half its own height.
                if (info.scale() != 1) {
                    d.setTransformation(RigMath.scaledTransformation((float) info.scale()));
                }
            });
            carry.carry(display);
            modelId = display.getUniqueId();
            parts.put(modelId, chassisId);
        }

        void despawnParts() {
            for (UUID id : mounts) {
                removeEntity(id);
            }
            for (UUID id : hitboxes) {
                removeEntity(id);
            }
            for (UUID id : body) {
                removeEntity(id);
            }
            body.clear();
            removeEntity(modelId);
            if (rig != null) {
                rig.despawn();
                rig = null;
                playing = null;
                playable = false;
            }
        }

        private void removeEntity(UUID id) {
            if (id == null) {
                return;
            }
            parts.remove(id);
            Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }

        // --- seats ----------------------------------------------------

        int seatIndexOfHitbox(UUID hitbox) {
            return hitboxes.indexOf(hitbox);
        }

        boolean isBody(UUID entity) {
            return body.contains(entity);
        }

        /**
         * The first seat nobody is in, in the pack's order — so the driver
         * seat is filled first and the first person into a vehicle is the one
         * driving it.
         *
         * <p>This is what clicking the BODY does, and it is the reason the
         * body is worth having beyond being a thing to aim at. The seat
         * markers are small and, on anything the size of a cart, overlap each
         * other — so which one a click resolves to is a matter of where the
         * crosshair happened to land, and "whoever got in first is driving"
         * was not reliably true. Clicking a seat still picks that seat.
         *
         * @return the seat index, or -1 when the vehicle is full
         */
        int firstFreeSeat() {
            for (int i = 0; i < occupants.size(); i++) {
                if (occupants.get(i) == null) {
                    return i;
                }
            }
            return -1;
        }

        UUID occupant(int index) {
            return occupants.get(index);
        }

        /**
         * Who is in which seat, by index, as a snapshot.
         *
         * <p>Taken before a rebuild so the same people can be put back in the
         * same seats — see {@link #replace}. A copy rather than the live list,
         * which {@code evictAll} is about to empty.
         */
        List<UUID> seating() {
            return new ArrayList<>(occupants);
        }

        boolean mount(int index, Player player) {
            Entity mount = plugin.getServer().getEntity(mounts.get(index));
            if (mount == null || !mount.addPassenger(player)) {
                return false;
            }
            occupants.set(index, player.getUniqueId());
            // The cheap tick is latched from the LAST full one, so somebody
            // getting into a parked vehicle would otherwise wait up to
            // PARKED_POLL_TICKS before it noticed them.
            parked = false;
            refreshArt();
            return true;
        }

        /**
         * Takes somebody's seat back, whichever way they left it.
         *
         * <p><strong>The one place every way out goes through</strong> — a
         * dismount, a quit, an eviction, a plugin's eject — and therefore the
         * one place {@link VehicleExitEvent} fires. Idempotent: somebody who
         * is not in a seat here is nothing to do, which is what lets
         * {@link #evictAll} call it after an {@code eject} whose dismount
         * event may or may not have already reached {@code left}.
         */
        void vacate(UUID player, VehicleExitEvent.Cause cause) {
            int index = -1;
            for (int i = 0; i < occupants.size(); i++) {
                if (player.equals(occupants.get(i))) {
                    occupants.set(i, null);
                    index = i;
                }
            }
            riders.remove(player);
            controls.forget(player);
            dressOverrides.remove(player);
            yawOverrides.remove(player);
            undress(player);
            if (index < 0) {
                return;
            }
            // A worn vehicle's art comes back the moment its last occupant
            // is out - before the exit event, so a listener that removes the
            // vehicle on a dismount finds a whole one to remove.
            refreshArt();
            Player who = plugin.getServer().getPlayer(player);
            if (who != null) {
                // After the seat is free and they are themselves again: the
                // event is not cancellable, so it reports what is already so.
                plugin.getServer().getPluginManager().callEvent(
                        new VehicleExitEvent(who, handle(chassisId), index, cause));
            }
        }

        /**
         * Everybody out, for {@code cause}.
         *
         * <p>{@code eject} fires the dismount event synchronously on a server
         * that has one, and that reaches {@code left} — which is why
         * {@link #leaving} is set around it, so that listener says the right
         * cause rather than DISMOUNTED. The {@code vacate} after it is for the
         * server that has no such event, or a mount that was already gone;
         * on the ordinary path it finds the seat free and does nothing.
         */
        void evictAll(VehicleExitEvent.Cause cause) {
            VehicleExitEvent.Cause before = leaving;
            leaving = cause;
            try {
                for (int i = 0; i < occupants.size(); i++) {
                    UUID id = occupants.get(i);
                    if (id == null) {
                        continue;
                    }
                    Entity mount = plugin.getServer().getEntity(mounts.get(i));
                    if (mount != null) {
                        mount.eject();
                    }
                    vacate(id, cause);
                }
            } finally {
                leaving = before;
            }
        }

        /** {@link Vehicle#eject}: one person out. */
        boolean eject(UUID player) {
            int index = occupants.indexOf(player);
            if (index < 0) {
                return false;
            }
            VehicleExitEvent.Cause before = leaving;
            leaving = VehicleExitEvent.Cause.EJECTED;
            try {
                Entity mount = plugin.getServer().getEntity(mounts.get(index));
                if (mount != null) {
                    mount.eject();
                }
                vacate(player, VehicleExitEvent.Cause.EJECTED);
            } finally {
                leaving = before;
            }
            return true;
        }

        // --- what a plugin can change ---------------------------------

        boolean enabled() {
            return enabled;
        }

        /**
         * {@link Vehicle#setEnabled}. The chassis is written first, so the
         * flag survives whatever happens to this Ride.
         */
        void enable(boolean on) {
            Entity chassis = chassis();
            if (chassis != null) {
                if (on) {
                    chassis.getPersistentDataContainer().remove(disabledKey);
                } else {
                    chassis.getPersistentDataContainer().set(disabledKey, PersistentDataType.BYTE, (byte) 1);
                }
            }
            enabled = on;
            // A click-arm throttle left where it was would fire the moment
            // the vehicle came back on; a driver expects to have to press
            // something. The key arm keeps nothing and `take` costs nothing.
            resetThrottle();
            parked = false;
        }

        OptionalDouble speedLimit() {
            return Double.isNaN(speedLimit) ? OptionalDouble.empty() : OptionalDouble.of(speedLimit);
        }

        /** {@link Vehicle#setSpeedLimit}. */
        void limitSpeed(double blocksPerSecond) {
            speedLimit = !Double.isFinite(blocksPerSecond) || blocksPerSecond <= 0
                    ? Double.NaN
                    : blocksPerSecond;
            redrive();
            parked = false;
        }

        OptionalDouble descent() {
            return Double.isNaN(descent) ? OptionalDouble.empty() : OptionalDouble.of(descent);
        }

        /**
         * {@link Vehicle#setDescent}. Only an air vehicle reads its flight,
         * so on anything else this is remembered and does nothing, which is
         * what the interface promises.
         */
        void descend(double blocksPerSecond) {
            descent = !Double.isFinite(blocksPerSecond) || blocksPerSecond < 0
                    ? Double.NaN
                    : blocksPerSecond;
            redrive();
        }

        /**
         * Rebuilds {@link #driven} from {@link #info} and whatever a plugin
         * has imposed on it. The one place that does, so the two overrides
         * cannot forget each other: clearing the speed limit used to hand
         * the physics {@code info} itself, which would have dropped the
         * descent with it.
         */
        private void redrive() {
            VehicleInfo wanted = info;
            if (!Double.isNaN(speedLimit)) {
                wanted = wanted.withSpeed(Math.min(speedLimit, info.speed()));
            }
            // After the speed limit, so a damaged vehicle is a fraction of
            // whatever it was allowed to do rather than of what its definition
            // said — the two overrides compose instead of racing.
            if (speedFactor < 1) {
                wanted = wanted.withSpeed(wanted.speed() * speedFactor);
            }
            if (turnFactor < 1) {
                wanted = wanted.withTurnSpeed(wanted.turnSpeed() * turnFactor);
            }
            if (!Double.isNaN(descent)) {
                wanted = wanted.withFlight(wanted.flight().withDescent(descent));
            }
            driven = wanted;
        }

        /**
         * How much of the bodywork's angle the RIDER takes, degrees.
         *
         * <p>Not all of it. Somebody on a thing that leans stays more upright
         * than the thing does - they counter-balance, which is most of what
         * riding something is - so the first {@link #RIDER_LEAN_SLACK} degrees
         * are theirs to absorb and everything past that they go over with. A
         * car cornering at fourteen degrees leaves its driver at six, which
         * reads as somebody sitting in a car; a board rolled eighty onto a
         * wall leaves its rider at seventy-two, which reads as a wall ride.
         * Handing over the whole angle made every ordinary corner look like a
         * crash.
         */
        private static double ridden(double degrees) {
            double past = Math.abs(degrees) - RIDER_LEAN_SLACK;
            return past <= 0 ? 0 : Math.copySign(past * RIDER_LEAN_SHARE, degrees);
        }

        /** {@link Vehicle#wallRiding}. */
        boolean wallRiding() {
            return wall != null;
        }

        /**
         * {@link Vehicle#perform}. Blank is null, so a plugin reading an
         * animation name out of a config cannot accidentally ask for "".
         */
        void perform(String animation) {
            String wanted = animation == null || animation.isBlank() ? null : animation.trim();
            if (Objects.equals(wanted, performed)) {
                return;
            }
            performed = wanted;
            // Nothing is played from here: `animate` runs every tick and is
            // the one place that decides what the rig is doing, so setting the
            // field is the whole change. Playing it here as well would be a
            // second answer to the same question, and the two would disagree
            // on the tick a state changed.
        }

        /** {@link Vehicle#performing}. */
        Optional<String> performing() {
            return Optional.ofNullable(performed);
        }

        /**
         * {@code named} in this occupant's variant, if they have one and it
         * exists.
         *
         * <p>Falls back to the plain emote rather than refusing, which is what
         * makes a partial set of variants a sensible thing to ship: the engine
         * asks the emote store whether the variant is there, and plays what is.
         */
        private String variantOf(UUID occupant, String named) {
            String variant = variants.get(occupant);
            if (named == null || variant == null || emotes == null) {
                return named;
            }
            String wanted = named + variant;
            return emotes.info(wanted).isPresent() ? wanted : named;
        }

        /** {@link Vehicle#dressVariant}. */
        void dressVariant(UUID occupant, String variant) {
            if (!occupants.contains(occupant)) {
                return;
            }
            if (variant == null || variant.isBlank()) {
                variants.remove(occupant);
            } else {
                variants.put(occupant, variant.startsWith("_") ? variant : "_" + variant);
            }
        }

        /** {@link Vehicle#holdOccupant}. */
        void holdOccupant(UUID occupant, boolean hold) {
            if (!occupants.contains(occupant)) {
                return;
            }
            if (hold) {
                held.add(occupant);
            } else {
                held.remove(occupant);
            }
        }

        /**
         * Whether a sneak dismount by {@code occupant} should be refused: they
         * are held AND this vehicle is moving. The speed test is what keeps
         * the promise in {@link Vehicle#holdOccupant} that nobody is ever
         * stuck — a stopped vehicle always lets go, whatever any plugin
         * thinks.
         */
        boolean holds(UUID occupant) {
            return held.contains(occupant) && state().groundSpeed() >= HOLD_RELEASE_SPEED;
        }

        /** {@link Vehicle#turnOccupant}. */
        void turnOccupant(UUID occupant, Double yaw) {
            if (!occupants.contains(occupant)) {
                return;
            }
            if (yaw == null || !Double.isFinite(yaw)) {
                yawOverrides.remove(occupant);
            } else {
                yawOverrides.put(occupant, VehiclePhysics.wrap360(yaw));
            }
            parked = false;
        }

        /**
         * Throws the rider off, if this vehicle bails and that landing was bad
         * enough.
         *
         * <p>Measured off where the vehicle actually WENT rather than off its
         * velocity: a landing is where the last tick put it against where this
         * one did, and the direction of that is what the vehicle was doing
         * irrespective of what its wheels were pointing at. That difference is
         * the whole trick - a board landed sideways is one whose heading and
         * whose travel disagree.
         *
         * <p>The window has a far edge for a reason: landing straight
         * backwards is riding away fakie, which is a trick and not a fall.
         * See {@link VehicleBail}.
         */
        private void bailed(Set<VehicleState> states) {
            boolean airborne = states.contains(VehicleState.AIRBORNE);
            Location was = lastAt;
            boolean landed = wasAirborne && !airborne && wall == null;
            wasAirborne = airborne;
            lastAt = at.clone();
            VehicleBail rules = info.bail();
            if (!landed || rules == null || was == null || was.getWorld() != world) {
                return;
            }
            Player rider = driver();
            if (rider == null) {
                return;
            }
            double dx = at.getX() - was.getX();
            double dz = at.getZ() - was.getZ();
            double travelled = Math.hypot(dx, dz) * 20;
            if (travelled < rules.minSpeed()) {
                return;
            }
            // Minecraft's yaw: 0 faces +z and increases clockwise, so the way
            // it was travelling is atan2(-dx, dz).
            double travelYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double off = Math.abs(VehiclePhysics.wrap180(travelYaw - state.yaw()));
            if (off < rules.from() || off > rules.to()) {
                return;
            }
            VehicleBailEvent event =
                    new VehicleBailEvent(rider, handle(chassisId), off, travelled);
            plugin.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }
            evictAll(VehicleExitEvent.Cause.DISMOUNTED);
            halt();
            if (rules.damage() > 0) {
                rider.damage(rules.damage());
            }
        }

        /**
         * A tap of sneak by a held rider still gets them out.
         *
         * <p>The engine refuses their dismount so the key can mean something
         * else (see {@link Vehicle#holdOccupant}), and that refusal took the
         * ordinary way OFF with it. So the two are told apart here rather than
         * in every plugin that uses the door: the key is counted while it is
         * down, and a release inside {@link #SNEAK_TAP_TICKS} is an exit the
         * engine performs itself.
         */
        private void sneakTap() {
            Player driver = driver();
            if (driver == null || !this.held.contains(driver.getUniqueId())) {
                sneakTicks = 0;
                return;
            }
            if (lastDemand.sneak()) {
                sneakTicks++;
                return;
            }
            int pressed = sneakTicks;
            sneakTicks = 0;
            if (pressed > 0 && pressed <= SNEAK_TAP_TICKS) {
                evictAll(VehicleExitEvent.Cause.DISMOUNTED);
            }
        }

        /** {@link Vehicle#input}: the last demand, as the keys a plugin can read. */
        VehicleInput input() {
            if (driver() == null) {
                return VehicleInput.NONE;
            }
            VehiclePhysics.Demand d = lastDemand;
            return new VehicleInput(d.steersByKeys(), d.throttle(), d.steer(),
                    d.throttle() > 0, d.throttle() < 0, d.steer() < 0, d.steer() > 0,
                    d.braking() || d.lift() > 0, d.sprint(), d.sneak());
        }

        /** {@link Vehicle#nudge}. */
        void nudge(double blocksPerSecond) {
            state = state.nudged(blocksPerSecond);
            parked = false;
        }

        /** {@link Vehicle#spin}. */
        void spin(double degreesPerSecond) {
            state = state.spun(degreesPerSecond);
            parked = false;
        }

        Vector velocity() {
            double[] horizontal = VehiclePhysics.worldVelocity(state.yaw(), state.speed(), state.slip());
            return new Vector(horizontal[0], state.verticalSpeed(), horizontal[1]);
        }

        void applyImpulse(Vector deltaVelocity, double spinDelta) {
            if (deltaVelocity == null || !Double.isFinite(deltaVelocity.getX())
                    || !Double.isFinite(deltaVelocity.getY()) || !Double.isFinite(deltaVelocity.getZ())
                    || !Double.isFinite(spinDelta)) {
                return;
            }
            Vector delta = deltaVelocity.clone();
            double magnitude = delta.length();
            if (magnitude > 40) delta.multiply(40 / magnitude);
            double[] horizontal = VehiclePhysics.worldVelocity(state.yaw(), state.speed(), state.slip());
            double spin = Math.max(-720, Math.min(720, state.yawRate() + spinDelta));
            state = state.impacted(horizontal[0] + delta.getX(),
                    Math.max(-40, Math.min(40, state.verticalSpeed() + delta.getY())),
                    horizontal[1] + delta.getZ(), spin);
            bumped = true;
            parked = false;
        }

        /**
         * A bone's pivot as a body-frame offset in blocks.
         *
         * <p>The same conversion the seats and emitters use — x mirrored, y
         * straight up, z forward from the model's centre — because a part and a
         * seat are the same question asked about the same model, and a second
         * derivation is a second chance to get the mirroring wrong on the axis
         * nobody checks.
         */
        Vector partOffset(String name) {
            float[] pivot = rig == null || name == null ? null : rig.pivotOf(name);
            if (pivot == null || pivot.length < 3) return null;
            for (float value : pivot) {
                if (!Float.isFinite(value)) return null;
            }
            double scale = info.scale();
            return new Vector(
                    -(pivot[0] - 8) / 16.0 * scale,
                    pivot[1] / 16.0 * scale,
                    (pivot[2] - 8) / 16.0 * scale);
        }

        void setPosture(double pitch, double roll) {
            // Bounded so a plugin cannot park a vehicle on its roof and strand
            // whoever is sitting in it: the rider absorbs some of the body's
            // lean, and past a right angle that stops meaning anything.
            posturePitch = Double.isFinite(pitch) ? Math.max(-60, Math.min(60, pitch)) : 0;
            postureRoll = Double.isFinite(roll) ? Math.max(-60, Math.min(60, roll)) : 0;
        }

        void setHandling(double speed, double turn) {
            double wantedSpeed = Double.isFinite(speed) ? Math.max(0.05, Math.min(1, speed)) : 1;
            double wantedTurn = Double.isFinite(turn) ? Math.max(0.05, Math.min(1, turn)) : 1;
            if (wantedSpeed == speedFactor && wantedTurn == turnFactor) return;
            speedFactor = wantedSpeed;
            turnFactor = wantedTurn;
            redrive();
        }

        Set<String> detachedParts() {
            Entity chassis = chassis();
            String encoded = chassis == null ? null : chassis.getPersistentDataContainer()
                    .get(detachedPartsKey, PersistentDataType.STRING);
            if (encoded == null || encoded.isBlank()) return Set.of();
            Set<String> names = new LinkedHashSet<>();
            for (String name : encoded.split("\\n")) {
                if (!name.isBlank()) names.add(name);
            }
            return Set.copyOf(names);
        }

        boolean detachPart(String name, Vector velocity, double spin, long despawnTicks) {
            if (name == null || name.isBlank() || name.indexOf('\n') >= 0 || rig == null
                    || !supportedParts.contains(name) || detachedParts().contains(name)) {
                return false;
            }
            List<ItemDisplay> displays = rig.detach(name);
            if (displays.isEmpty()) return false;
            Set<String> detached = new LinkedHashSet<>(detachedParts());
            detached.add(name);
            Entity chassis = chassis();
            if (chassis != null) {
                chassis.getPersistentDataContainer().set(detachedPartsKey, PersistentDataType.STRING,
                        String.join("\n", detached));
            }
            for (ItemDisplay display : displays) {
                throwDebris(display, velocity, spin, despawnTicks);
            }
            return true;
        }

        /** {@link Vehicle#dress}: forgotten with the seat, and re-dressed on the next tick. */
        void dressAs(UUID occupant, String emoteId) {
            if (!occupants.contains(occupant)) {
                return;
            }
            if (emoteId == null || emoteId.isEmpty()) {
                dressOverrides.remove(occupant);
            } else {
                dressOverrides.put(occupant, emoteId);
            }
            parked = false;
        }

        /** {@link Vehicle#stop}: dead, this tick, throttle and all. */
        void halt() {
            state = state.stopped();
            resetThrottle();
        }

        private void resetThrottle() {
            UUID id = occupants.isEmpty() ? null : occupants.get(0);
            if (id != null) {
                controls.forget(id);
                controls.take(id);
            }
        }

        /** Where it is, facing its heading — {@link Vehicle#location}. */
        Location position() {
            Location where = at.clone();
            where.setYaw((float) state.yaw());
            where.setPitch(0);
            return where;
        }

        VehiclePhysics.State state() {
            return state;
        }

        Set<VehicleState> states() {
            return lastStates;
        }

        /**
         * Where seat {@code index}'s occupant STANDS, right now: their feet,
         * facing the seat's way.
         *
         * <p>Side and forward, turned with the vehicle — the same reading
         * {@code place: seat:} uses, so a bench seats people along itself
         * however the vehicle is parked.
         *
         * <p>SITTING puts the seat's point under their backside, STANDING
         * under their feet. The pose is what decides that, which is why it is
         * not merely cosmetic — {@link #SEATED_POSE} is the hip height between
         * the two, and it is most of a block rather than a nudge. Studio's
         * editor hangs its figure by the same rule, so this point is the one
         * the editor drew, and it is the point the worn rig is pinned to
         * ({@code Emotes.anchor}) with nothing of vanilla's in between.
         */
        Location seatLocation(int index) {
            VehicleSeat seat = info.seats().get(index);
            double yaw = state.yaw();
            // The basis is VehiclePhysics' and is tested there. It was inline
            // here once, with `right` pointing left.
            //
            // Scaled by the vehicle's own scale, because a seat's offset is
            // measured against the ART: on a bus drawn twice as big the driver
            // is twice as far forward and twice as high, or they sit in the
            // middle of the saloon a metre inside the floor.
            //
            // What is NOT scaled is the two corrections either side of it.
            // {@link #SEATED_POSE} is a hip height on a PLAYER, and a player is
            // the same size in a big vehicle as in a small one; `seatOffset` is
            // a server's own calibration nudge and belongs to the server rather
            // than to the model.
            double scale = info.scale();
            double drop = seat.pose() == VehicleSeat.Pose.SITTING ? SEATED_POSE : 0;
            // On the BODY, not on the position: the body is pitched and rolled
            // about the same point the model is drawn around and sits at the
            // model's ride height, and a seat that stayed level while the
            // bodywork under it went nose-up on a kerb would leave its rider
            // hanging in the air in front of the windscreen.
            double[] offset = VehiclePhysics.bodyOffset(yaw, state.pitch(), state.roll(), MODEL_LIFT,
                    seat.x() * scale, seat.y() * scale - drop + seatOffset, seat.z() * scale + seatForward);
            Location location = new Location(world,
                    at.getX() + offset[0],
                    at.getY() + state.lift() + offset[1],
                    at.getZ() + offset[2]);
            location.setYaw((float) VehiclePhysics.wrap360(yaw + seatYaw(index)));
            return location;
        }

        /**
         * Where a seat's MOUNT goes: above the occupant's feet by however far
         * vanilla will seat them below it, and without the seat's rotation.
         *
         * <p>A passenger's body is dragged round by its vehicle's yaw, so a
         * mount that turned with the vehicle spun the rider's body every time
         * the car did — they could not look where they liked while being
         * carried. The mount therefore stays at yaw zero for ever and the
         * seat's own yaw is spent once, on the teleport that aims somebody as
         * they sit down. Which is all a seat yaw was ever meant to do; see
         * {@link VehicleSeat#yaw()}.
         *
         * @param stand whether the mount is (or will be) the fallback stand
         *              rather than a display — the two seat a rider at
         *              different heights, see {@link MountOffset}. Only the
         *              guess: a measured drop ({@link #mountDrop}) wins
         */
        Location mountLocation(int index, boolean stand) {
            Location seat = seatLocation(index);
            Double measured = mountDrop.get(index);
            seat.add(0, measured != null ? measured : stand ? standMountOffset : displayMountOffset, 0);
            seat.setYaw(0);
            seat.setPitch(0);
            return seat;
        }

        // --- moving ---------------------------------------------------
        // --- moving ---------------------------------------------------

        void tick() {
            pending = null;
            bumped = false;
            Entity chassis = chassis();
            if (chassis == null || !chassis.isValid()) {
                // The chunk unloaded, or somebody removed it. Drop everything
                // keyed to it — the 0.46.1 audit's other finding was two maps
                // that only ever grew because a chunk unload is not an
                // untrack.
                evictAll(VehicleExitEvent.Cause.UNLOADED);
                despawnParts();
                live.remove(chassisId);
                return;
            }

            age++;

            // Nobody in it and going nowhere: the art keeps running and
            // nothing else does. See PARKED_POLL_TICKS — both calls below
            // return immediately for a vehicle with no rig and no emitters,
            // which is most of them, so a parked car park really does cost
            // nothing.
            if (parked && age % PARKED_POLL_TICKS != 0) {
                animate(parkedStates);
                particles.emit(world, at, state.yaw(), info, parkedStates, age);
                // A parked vehicle still hums if its pack said it does, on the
                // same argument as its animation and its emitters: idle is a
                // state, and a moored boat that stopped lapping the moment
                // everybody got out would be a vehicle that only exists while
                // somebody is watching. It reaches here every PARKED_POLL_TICKS,
                // so a repeat can be up to half a second late — see
                // VehicleSounds, which is measured in whole seconds.
                noise.play(sounds, chassis, info, parkedStates, age);
                return;
            }

            // Once, ahead of every question the world is asked this tick. See
            // ModelObstacles: the gathering is a chunk scan and the questions
            // are arithmetic, so the scan is what must not be repeated.
            nearbyModels = obstacles.around(world, at, modelReach(), modelHeight());

            Player driver = driver();
            // A disabled vehicle is driven by nobody, whoever is in the seat:
            // idle keeps the heading and coasts to a stop, which is what
            // running out of fuel looks like. See Vehicle.setEnabled.
            VehiclePhysics.Demand demand = driver == null || !enabled
                    ? VehiclePhysics.Demand.idle(state.yaw())
                    : controls.read(driver, info);
            lastDemand = demand;

            VehiclePhysics.Surroundings around = surroundings();
            // `driven` rather than `info`: the same vehicle with a plugin's
            // speed limit applied, or `info` itself when there is none.
            VehiclePhysics.Step step = VehiclePhysics.step(driven, state, demand, around, DT);
            state = step.state();
            if (step.moves()) {
                // Asked before the world is: a listener sees where the
                // vehicle is trying to go, and a refusal is a wall it cannot
                // see — stopped dead, and no longer falling either, so a
                // vehicle held at a boundary in mid-air does not bank up a
                // terminal velocity to spend the moment it is let through.
                Location from = position();
                Location to = new Location(world, at.getX() + step.dx(), at.getY() + step.dy(),
                        at.getZ() + step.dz(), (float) state.yaw(), 0);
                VehicleMoveEvent moving = new VehicleMoveEvent(handle(chassisId), from, to);
                plugin.getServer().getPluginManager().callEvent(moving);
                if (moving.isCancelled()) {
                    state = state.stopped().landed();
                } else {
                    apply(step);
                    shoveAside(step);
                }
            }
            // Everything from here on reads the position this vehicle finished
            // at, and it has not finished until the collision pass has run.
            // See VehicleRuntime.tick for the three passes and why they are
            // three.
            pending = step;
            pendingAround = around;
        }

        /**
         * The second half of a tick: the entities are put where the vehicle
         * ended up, and everything that reports on where that is fires.
         *
         * <p>Split from {@link #tick} so the collision pass can sit between
         * them. Does nothing for a vehicle that took the parked shortcut or
         * whose chassis went away, which is what {@code pending} being null
         * means.
         */
        void settle() {
            VehiclePhysics.Step step = pending;
            if (step == null) {
                // Shunted while parked: it took no step of its own, so nothing
                // below applies — but it HAS moved, and its entities are still
                // standing where it was. Placing them is the whole of what a
                // parked vehicle owes this pass.
                if (bumped) {
                    Entity chassis = chassis();
                    if (chassis != null && chassis.isValid()) {
                        place(chassis);
                    }
                }
                return;
            }
            pending = null;
            Entity chassis = chassis();
            if (chassis == null || !chassis.isValid()) {
                return;
            }
            Player driver = driver();
            place(chassis);

            // On the change and never on the tick — a listener that wants
            // every tick has VehicleMoveEvent.
            if (!step.states().equals(lastStates)) {
                Set<VehicleState> previous = lastStates;
                lastStates = step.states();
                plugin.getServer().getPluginManager().callEvent(
                        new VehicleStateEvent(handle(chassisId), lastStates, previous));
            }

            // AFTER the move, so both read the position the vehicle actually
            // ended up at rather than the one it was asked to go to — a
            // vehicle stopped by a wall should not throw its exhaust inside
            // the wall.
            sneakTap();
            bailed(step.states());
            animate(step.states());
            dressOccupants(step.states());
            particles.emit(world, at, state.yaw(), info, step.states(), age);
            // The chassis is the source, so the client keeps the note attached
            // while the vehicle moves and turns instead of leaving each loop
            // behind at the coordinate where it began.
            noise.play(sounds, chassis, info, step.states(), age);
            sayIfBeached(driver, pendingAround);
            showSpeed(driver);

            // Occupied is never parked, whether or not it is moving: a rider's
            // rig is aimed every tick, and half a second of a driver's body
            // pointing where the vehicle used to point is a visible thing.
            //
            // Nor is anything that has just been hit. A shunted car has the
            // speed to roll away from where it was pushed, and latching it as
            // parked on the strength of a step taken BEFORE the impact is a
            // vehicle that takes the shove and then declines to move.
            parked = !step.moves() && !bumped && empty();
            parkedStates = step.states();
        }

        /** Whether every seat is free. */
        private boolean empty() {
            for (UUID occupant : occupants) {
                if (occupant != null) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Puts each occupant's body into whatever their seat asks for.
         *
         * <p>An occupant animation is an EMOTE — see {@link VehicleSeat#animations()}
         * — worn through {@link ai.resourcepack.engine.api.Emotes#wear}, which
         * is the movement-set machinery with the movement taken out. So a
         * driver leaning on the door and a driver hauling the wheel round are
         * authored in the emote editor like anything else, and this only
         * decides which one is on.
         *
         * <p>Only on a CHANGE, for the same reason {@link #animate} is: the
         * swap restarts the emote's clock, so asking every tick is a cycle
         * stuck on its first frame. {@code worn} remembers per seat rather than
         * per vehicle because two seats in one vehicle wear different things in
         * the same state — which is most of the point.
         */
        private void dressOccupants(Set<VehicleState> states) {
            if (emotes == null) {
                return;
            }
            // A state this seat left blank is the player's own body, NOT a
            // fall-through to the next state — see VehicleSeat.animations. A
            // fall-through would leave a driver hauling an imaginary wheel
            // round while the car sat still.
            //
            // Which is why this is `current` and not `choose`. It said the
            // above in a comment and then called the fall-through anyway, and
            // that gap was invisible until IDLE became a floor: a seat whose
            // emote was mapped to `submerged` used to reach it whenever the
            // boat was moored, so the mapping people actually write worked by
            // accident and stopped when the accident was fixed.

            for (int i = 0; i < occupants.size(); i++) {
                UUID id = occupants.get(i);
                VehicleSeat seat = info.seats().get(i);
                // A hidden seat wears nothing, whatever it mapped: there is no
                // point spawning a rig for somebody nobody can see, and doing
                // it anyway would hang a set of displays inside the bodywork
                // for every occupant. See VehicleSeat.hidden.
                if (id == null || seat.hidden() || (seat.animations().isEmpty() && !seatRig)) {
                    continue;
                }
                Player player = plugin.getServer().getPlayer(id);
                if (player == null) {
                    continue;
                }
                // Which way their BODY points, every tick and before the
                // change-only check below — because unlike what they are
                // wearing, this changes whenever the vehicle turns.
                //
                // The seat's own facing, exactly as `seatLocation` computes it
                // for the teleport that aims somebody as they sit down. That
                // teleport is spent once and their camera is theirs afterwards
                // (see `mountLocation`, which pins the mount at yaw zero for
                // that reason) — so without this the rig followed the mouse and
                // a driver sat sideways in a kayak that was going straight on.
                // Their head still turns wherever they like; only the rig is
                // held square to the seat, which is what vanilla does with a
                // real body in a boat.
                //
                // The SAME number as that teleport, with nothing added: a rig
                // faces the way a player at that yaw would. See the note where
                // MODEL_YAW_OFFSET is declared for the half turn that was here
                // and why it went.
                emotes.face(player, (float) VehiclePhysics.wrap360(this.state.yaw() + seatYaw(i)));
                // And whether their cape is drawn, on the same every-tick
                // footing as their facing rather than once when they sit down:
                // a rig can be re-put-on under this class (a state change, a
                // group taking it away and giving it back), and a cape that
                // was decided once would come back with it. The call is a
                // no-op when nothing changed and for the riders — most of
                // them — who have no cape at all.
                emotes.cape(player, info.capes());
                // Resolved per SEAT, because the answer depends on what this
                // seat mapped: turning outranks travelling now, and a seat that
                // maps `moving` and not `turning` must not lose its occupant's
                // pose on every corner. See VehicleState.forSeat.
                VehicleState state = VehicleState.forSeat(states, seat.animations()).orElse(null);
                String named = state == null ? null : seat.animations().get(state);
                // A plugin's override beats the seat's own table for as long
                // as it stands — see Vehicle.dress. Treated as a named emote
                // so it is worn over the stance and its refusal is reported.
                String override = dressOverrides.get(id);
                if (override != null) {
                    named = override;
                }
                // And this rider's own variant of it, where one exists: a
                // stance, a handedness, a team. Per emote rather than per
                // rider, so a set that mirrors the two states worth mirroring
                // and leaves the rest alone works — see Vehicle.dressVariant.
                named = variantOf(id, named);
                String wanted = named != null ? named : fallbackStance(seat);
                // A rig that was ON and is not any more is re-asked whatever
                // the memo says: something outside this vehicle took it off,
                // and the memo cannot see that. Only somebody we actually
                // dressed is ever re-asked, which is what stops a permanent
                // refusal becoming a lookup per tick — see `dressed`.
                boolean lost = dressed.contains(id) && !emotes.isEmoting(id);
                if (Objects.equals(wanted, worn.get(id)) && !lost) {
                    // Settled back onto what is already on: whatever was being
                    // waited out never happened, so stop waiting for it.
                    settling.remove(id);
                    continue;
                }
                if (!lost && worn.containsKey(id) && !settled(id, wanted)) {
                    continue;
                }
                settling.remove(id);
                // Recorded before the call rather than after, so a refusal —
                // this player is mid-emote of their own, the pack has no rig
                // for them — is not retried twenty times a second.
                worn.put(id, wanted);
                // The seat's own stance UNDER whatever the pack named, so an
                // emote that only moves the arms keeps its occupant seated. It
                // was the alternative to the stance rather than a base for it,
                // which meant naming ANY emote for a seat stood its rider up:
                // the emote's animators replaced the lot, the legs fell back to
                // rest, and a driver hauling a wheel round did it standing.
                //
                // Null when the seat named nothing — `wanted` is already the
                // stance in that case, and merging it under itself is work for
                // no difference.
                String under = named != null ? fallbackStance(seat) : null;
                EmoteResult result = emotes.wear(player, wanted, under);
                if (wanted != null && result != null && result.started()) {
                    dressed.add(id);
                } else {
                    dressed.remove(id);
                }
                // `cape` above ran before a changed state could put a new rig
                // on. Ask once more after the wear so a newly spawned cape is
                // hidden in this same tick rather than flashing for one.
                emotes.cape(player, info.capes());
                sayIfRefused(player, named, result);
            }
        }

        /**
         * What an occupant wears when the seat named nothing for this state.
         *
         * <p><strong>The engine's own stance, not their own body</strong> — the
         * point of a vehicle seat is that somebody is sitting IN something, and
         * a vanilla passenger is drawn standing up to their waist in the hull
         * at whatever angle the game feels like. So the rig goes on either way
         * and the seat's {@code pose} picks which stance, which is the same
         * thing that already decides what the seat's own position means.
         *
         * <p>Null when {@code vehicles.seat-rig} is off, which is the whole of
         * that switch: an occupant nobody dressed goes back to being an
         * ordinary player. A seat that NAMES an emote is unaffected by it — the
         * pack asked for something specific and gets it.
         */
        /**
         * Whether {@code wanted} has been what this seat is asking for for long
         * enough to act on. See {@link StateSettle}, which is the rule and the
         * reasoning — a state the vehicle only passes through is not a state
         * its occupant should be posed in.
         *
         * <p>Sitting down is never delayed — the caller only asks once
         * something is already worn, so the first dressing is immediate.
         */
        private boolean settled(UUID id, String wanted) {
            return settling.computeIfAbsent(id, k -> new StateSettle()).settled(wanted, age);
        }

        private String fallbackStance(VehicleSeat seat) {
            if (!seatRig) {
                return null;
            }
            return seat.pose() == VehicleSeat.Pose.SITTING
                    ? ai.resourcepack.engine.api.Emotes.BUILT_IN_SITTING
                    : ai.resourcepack.engine.api.Emotes.BUILT_IN_STANDING;
        }

        /**
         * Records why an occupant's body did not change, when it did not.
         *
         * <p>Every way {@code wear} can refuse is a real, fixable state — the
         * pack carries no rig for this player, the emote id no longer exists,
         * they are mid-emote of their own — and every one of them looks
         * identical from the seat: nothing happened. Throwing the reason away
         * was why "I am still just me in the seat" had no answer.
         *
         * <p><strong>It goes to the console and not to the rider.</strong> It
         * was a chat line, and chat is the wrong place for it twice over: the
         * rider can act on none of these — every remedy belongs to whoever owns
         * the pack — and it fires on a state CHANGE, so a boat crossing in and
         * out of a mapping while somebody manoeuvres it repeats the same
         * sentence at them. A server owner debugging a vehicle is not the
         * person sitting in it, and this is the line they need.
         *
         * <p>Once per rider per reason, which {@code worn} does not cover on
         * its own: that stops the retry within one state, and a seat whose
         * states genuinely alternate would still write a line each way round.
         */
        private void sayIfRefused(Player player, String wanted, EmoteResult result) {
            if (result == null || result.started()) {
                return;
            }
            if (wanted == null) {
                warnIfNoSeatRigs(player, result);
                return;
            }
            if (!warnedRefusals.add(player.getUniqueId() + "/" + wanted + "/" + result.reason())) {
                return;
            }
            String why;
            switch (result.reason()) {
                case NO_RIG_FOR_PLAYER:
                    why = "this pack carries no rig for them - sync it again while they are online.";
                    break;
                case NO_RIGS_IN_PACK:
                case INCOMPLETE_EMOTE_DATA:
                case NO_EMOTES:
                    why = "this pack arrived without its emote rigs - sync it again.";
                    break;
                case UNKNOWN_EMOTE:
                    why = "this pack no longer has an emote called '" + wanted + "'.";
                    break;
                case ALREADY_EMOTING:
                    why = "they are in an emote of their own, which wins.";
                    break;
                case IN_SPECTATOR:
                    why = "they are in spectator.";
                    break;
                default:
                    why = "it was refused: " + result.reason().name().toLowerCase(Locale.ROOT) + ".";
                    break;
            }
            log.warning("Vehicle " + info.id() + ": " + player.getName()
                    + " is riding as themselves because " + why);
        }

        /** Gives an occupant their own body back as they get out. */
        private void undress(UUID id) {
            String had = worn.remove(id);
            dressed.remove(id);
            // The waiting room goes with it. This is the one method every way
            // out of a seat goes through, which is the whole reason the hide
            // below lives here — and a map keyed on occupants that is only ever
            // added to is the 0.46.1 audit's finding all over again.
            settling.remove(id);
            // A hidden seat's occupant, put back on everybody's screen. Here
            // rather than beside `sit`'s hide because this is the one method
            // every way out of a seat goes through — a dismount, a quit, a
            // reload, a chunk unload — and the hide was one method with one
            // caller against four.
            //
            // Somebody the emote system is hiding is left hidden: both use this
            // plugin as the key, so revealing them would take a running emote's
            // body out of hiding while its rig was still on.
            concealed.show(id, emotes != null && emotes.isEmoting(id));
            if (emotes == null || had == null) {
                return;
            }
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                emotes.stop(player);
            }
        }

        /**
         * Plays whatever this vehicle's state asks for.
         *
         * <p>Only on a CHANGE. {@code Placement.play} ignores a repeated
         * one-shot on its own, but a looping animation asked for again is
         * rewound — so calling this unconditionally would be a drive cycle
         * stuck on its first frame for ever.
         *
         * <p>A state the pack did not configure falls through to the next one
         * it did rather than stopping; see {@link VehicleState#choose}. So the
         * only thing that stops a vehicle animating is a pack that configured
         * nothing at all, which is a vehicle that never started.
         *
         * <p><strong>Straight across, and the smoothing is not here.</strong>
         * A change is handed to the rig animator the moment it happens, and
         * the animator crossfades a carried rig from the pose it is showing to
         * the new cycle — in the space of each bone's own angles, about its
         * own pivot, so a wheel stays on its axle throughout — and joins the
         * new cycle at the point nearest that pose, so a wheel keeps its
         * phase. Six earlier arrangements of this method tried to make the
         * join smooth from THIS side, with waits, a rest-pose waypoint and
         * cycles allowed to come round; the design record in AGENTS.md has
         * all of them and why each was wrong. What they had in common was
         * leaving the interpolation to something that tweens composed
         * transforms, which cannot be made smooth from here or anywhere.
         *
         * <p>Nor is there a settle wait in front of it any more. Braking from
         * forwards into reverse crosses IDLE for two or three ticks; a fade
         * that has set off towards idle simply re-aims at reversing from
         * wherever it has got to, and three ticks of a fade towards an engine
         * ticking over is nothing anybody can see. {@link StateSettle} stays
         * for the SEATS, whose swap is a different mechanism with a real
         * flash to hide.
         */
        private void animate(Set<VehicleState> states) {
            if (rig == null) {
                return;
            }
            // A plugin's `perform` outranks the state table, the way `dress`
            // outranks a seat's. Asked FIRST rather than folded into the
            // table, so a vehicle with no `animations:` at all can still be
            // given one from outside - which is most of what makes this
            // usable by an addon whose tricks the pack knows nothing about.
            String wanted = performed != null ? performed
                    : info.animations().isEmpty() ? null
                    : VehicleState.choose(states, info.animations()).orElse(null);
            if (wanted == null && playing == null) {
                // The ordinary case for a vehicle with no animations: nothing
                // to start and nothing to stop, before any lookup is done.
                return;
            }
            Optional<Placement> found = rig.placement();
            if (found.isEmpty()) {
                // The rig has been broken or its chunk went. Forgetting what
                // we thought was playing means the next tick that finds it
                // again starts cleanly rather than believing a stale answer.
                playing = null;
                playable = false;
                return;
            }
            Placement placement = found.get();

            // A wheel turns because the vehicle moved, not because time
            // passed. The clock is driven from here rather than left to run at
            // the animation's own rate, which is what had a pushed skateboard
            // spinning its wheels at one speed from a crawl to a tuck.
            //
            // A PERFORMED animation is exempt: that link is there so a wheel
            // turns because the vehicle moved, and a trick is not a wheel. A
            // barspin slowing to a crawl because the rider was braking into it
            // would be the mechanism showing through. The phase is left where
            // it is rather than reset, so the wheels pick up where they were
            // the moment the trick ends.
            boolean seekWanted = false;
            if (performed == null && info.animationFollowsSpeed() && wanted != null) {
                double top = Math.max(0.1, info.speed());
                double rate = Math.min(4, Math.abs(state().groundSpeed()) / top);
                animationPhase += rate / 20.0;
                // Applied after the play() below on the tick a state changes,
                // so a cycle that has just started is moved to the phase this
                // vehicle is at rather than left at zero.
                seekWanted = true;
            }

            if (!Objects.equals(wanted, playing)) {
                playing = wanted;
                if (wanted == null) {
                    placement.stop();
                    playable = false;
                } else {
                    // Restarted rather than resumed — though for a carried rig
                    // swapping one loop for another the animator picks the
                    // frame, see RigAnimator.startTickFor. The answer is
                    // recorded even when it FAILS: a pack naming an animation
                    // the model no longer has would otherwise be a lookup per
                    // tick for ever.
                    playable = placement.play(wanted, true);
                }
                if (seekWanted && playable) {
                    placement.seek(animationPhase);
                }
                return;
            }

            // <strong>Nothing changed, and that is when this matters.</strong>
            // A vehicle state is a condition that HOLDS, so what it names
            // has to run for as long as it holds — but an animation
            // authored as a one-shot runs out after its own length and
            // hands the rig back to rest. With one animation mapped to
            // every state that is a rowing cycle which plays once on spawn
            // and never again, because no state change ever comes along to
            // restart it.
            //
            // So it is re-asked the moment it has fallen idle, which loops
            // it whatever its own end mode says. `playing()` is what the
            // placement is actually doing rather than what it was last
            // told, so a HOLD or a genuine loop never trips this.
            if (playable && playing != null && placement.playing().isEmpty()) {
                placement.play(playing, true);
            }

            // The ordinary tick for a speed-linked vehicle: the cycle is
            // already the right one and only its playhead moves, by however
            // far this vehicle travelled. Standing still, the phase does not
            // move and neither do the wheels.
            if (seekWanted && playable) {
                placement.seek(animationPhase);
            }
        }



        /**
         * Tells the driver once that this hull is out of water.
         *
         * <p>Because the alternative is a boat that has quietly become eight
         * times slower with nothing on screen to say why, which reads as lag
         * or as a broken vehicle rather than as a rule. See
         * {@link VehiclePhysics#BEACHED_FRACTION} — it can still crawl, and
         * the message is what makes that legible as "get back in the water"
         * instead of "this is broken now".
         */
        /** Ticks a hull has to sit on land before it is told so. */
        private static final int BEACHED_SAY_AFTER = 10;
        private int beachedTicks;

        private void sayIfBeached(Player driver, VehiclePhysics.Surroundings around) {
            if (!VehiclePhysics.beached(info, around)) {
                toldBeached = false;
                beachedTicks = 0;
                return;
            }
            // Out of the water AND on something, for a moment. A hull in the
            // air is not beached - a surfboard leaving the top of a wave is
            // out of the water for a dozen ticks and back in it - and a hull
            // bumping over a rock at the edge of a pool is not either.
            if (!around.supported()) {
                return;
            }
            if (++beachedTicks < BEACHED_SAY_AFTER || toldBeached || driver == null) {
                return;
            }
            toldBeached = true;
            hush();
            // Overhead, like the seat line, and for the same reason: this is a
            // condition somebody is in for a few seconds, not a fact worth a
            // permanent line in their chat log. Beaching a hull and refloating
            // it half a dozen times while placing a dock is six lines of chat
            // for something the boat itself is already showing you.
            overhead(driver, nameOf(info) + " is out of the water - steer back in");
        }

        /**
         * The wall this vehicle is riding this tick, or null.
         *
         * <p>Two questions, and they are deliberately not the same one.
         *
         * <p><strong>Starting</strong> a ride takes everything at once: the
         * vehicle says it can ({@code wall-ride:}), it is off the ground -
         * you jump ONTO a wall, you do not drive into one - it is going at
         * least {@link VehiclePhysics#WALL_RIDE_MIN_SPEED}, there is a wall
         * beside it both low down and high up, and it is travelling within
         * {@link VehiclePhysics#WALL_RIDE_MAX_ANGLE} of along that wall rather
         * than into it. Going straight at a wall is a crash, and this is the
         * line between the two.
         *
         * <p><strong>Continuing</strong> one takes almost nothing: the wall is
         * still there and the speed has not run out. Re-asking the whole
         * question every tick would drop the ride at the first pillar, the
         * first doorway and the first tick the aligned heading no longer
         * counted as "an approach" - so a ride ends for the two reasons a
         * rider can feel, and not for bookkeeping.
         *
         * <p>The side is which way the vehicle leans; the yaw is the line of
         * the wall nearest the way it was already going, which is what the
         * physics straightens it onto.
         */
        private VehiclePhysics.Wall wallFor(boolean supported) {
            if (!info.wallRide()) {
                return null;
            }
            // HOLDING SPACE IS THE WHOLE ENTRY CONDITION, and everything else
            // about starting a ride went away when it arrived.
            //
            // Angles and speeds were the first two answers and both were
            // wrong, for the same reason: a rider cannot see the number they
            // are failing, so a near miss and a broken feature look identical
            // - and what they were actually experiencing was the collision
            // resolver doing its job, sliding them off a wall they were trying
            // to stick to. There is nothing to guess at once the rider says
            // what they want. Hold space at a wall: you ride it. Let go: you
            // come off it, with a kick.
            boolean asking = lastDemand.lift() > 0;
            VehiclePhysics.Wall beside = wallBeside();

            if (wall != null) {
                if (!asking) {
                    // Letting go is how you get off, and it throws you clear
                    // rather than dropping you - which is what a kick off a
                    // wall is. State.kicked, because a nudge is along the
                    // heading and the heading is along the wall.
                    state = state.kicked(VehiclePhysics.JUMP_SPEED,
                            -wall.side() * VehiclePhysics.WALL_RIDE_KICK);
                    return null;
                }
                // Still holding: the ride lasts while there is wall beside
                // them and they are still moving along it.
                return beside != null && Math.abs(state.groundSpeed()) >= WALL_RIDE_END_SPEED
                        ? new VehiclePhysics.Wall(beside.side(), beside.yaw(), climbOn(wall))
                        : null;
            }
            if (!asking || supported || beside == null) {
                return null;
            }
            if (Math.abs(state.groundSpeed()) < VehiclePhysics.WALL_RIDE_MIN_SPEED) {
                // Silently. It used to say so, back when a wall ride was a
                // thing you had to be told you had nearly done; now that
                // holding the key IS the whole entry condition, the only way
                // to fail is to be barely moving, and a rider who is barely
                // moving can see that.
                return null;
            }
            return beside;
        }

        /**
         * Where the nose is pointed up the wall this tick, in degrees.
         *
         * <p>The steering turns it, at {@link VehiclePhysics#WALL_RIDE_TURN} a
         * second, and letting go eases it back to a shallow descent - gravity,
         * said as an angle. Steering TOWARD the wall climbs, which is the way
         * round a rider expects: you lean into the thing you are riding to go
         * up it.
         */
        private double climbOn(VehiclePhysics.Wall riding) {
            double climb = riding.climb();
            double steer = lastDemand.steer() * riding.side();
            double seconds = 1 / 20.0;
            if (steer != 0) {
                return climb + steer * VehiclePhysics.WALL_RIDE_TURN * seconds;
            }
            return climb + (VehiclePhysics.WALL_RIDE_REST_CLIMB - climb)
                    * VehiclePhysics.WALL_RIDE_SETTLE * seconds;
        }

        /**
         * The wall this vehicle is up against, whichever way it is pointing,
         * or null.
         *
         * <p><strong>The four world directions, not the vehicle's own
         * sides.</strong> Probing along the vehicle's right hand is right only
         * when the vehicle is already parallel to the wall - which it is not
         * when it has just flown into one at an angle and is being slid along
         * it, and that is exactly the moment a rider is asking for a wall
         * ride. Aimed forty degrees into a wall, "the vehicle's right" points
         * into open air, so the probe found nothing and the mechanic looked
         * broken from the only approach anybody actually makes.
         *
         * <p>Either sample being solid is enough - level with the vehicle or
         * half a block under it - so a wall found on the way up, on the way
         * down, or scraping along is the same wall.
         */
        private VehiclePhysics.Wall wallBeside() {
            double[] right = VehiclePhysics.right(state.yaw());
            double heading = state.yaw();
            VehiclePhysics.Wall best = null;
            double bestOff = Double.MAX_VALUE;
            for (double[] direction : WALL_DIRECTIONS) {
                double x = at.getX() + direction[0] * WALL_REACH;
                double z = at.getZ() + direction[1] * WALL_REACH;
                if (!solidFor(x, at.getY() + WALL_HIGH, z) && !solidFor(x, at.getY() + WALL_LOW, z)) {
                    continue;
                }
                // A face reached by stepping in x is a wall running along z,
                // and the other way round.
                double line = direction[0] != 0 ? 0.0 : 90.0;
                double one = Math.abs(VehiclePhysics.wrap180(line - heading));
                double other = Math.abs(VehiclePhysics.wrap180(line + 180 - heading));
                double off = Math.min(one, other);
                if (off >= bestOff) {
                    continue;
                }
                // Which of the rider's sides it is on, so the body knows which
                // way to roll over.
                double dot = right[0] * direction[0] + right[1] * direction[1];
                bestOff = off;
                best = new VehiclePhysics.Wall(dot >= 0 ? 1 : -1, one <= other ? line : line + 180);
            }
            return best;
        }

        /** Solid to a vehicle: a block, or a placed model it collides with. */
        private boolean solidFor(double x, double y, double z) {
            return BlockSurfaces.solidAt(world, x, y, z) || nearbyModels.solidAt(x, y, z);
        }

        private Player driver() {
            UUID id = occupants.isEmpty() ? null : occupants.get(0);
            return id == null ? null : plugin.getServer().getPlayer(id);
        }

        /**
         * What the world is doing under the vehicle.
         *
         * <p>A handful of block reads, and no more: this runs for every
         * occupied vehicle every tick, and a ray trace here would be the most
         * expensive thing in the plugin.
         *
         * <p><strong>Supported is a HEIGHT question, not a material one.</strong>
         * It used to ask whether the block a sixth of a block under the centre
         * was solid, which is a different question with the same answer on
         * flat ground and a wrong one everywhere else: a snow layer is not a
         * solid material, so a vehicle resting on top of one was told every
         * tick that it was falling — {@code AIRBORNE}, gravity, and a landing
         * that put it straight back. And the footprint is asked rather than
         * the centre, because {@link #apply} lands the vehicle on the
         * footprint: with the two disagreeing, a vehicle with a wheel on a
         * ledge fell and landed alternately for as long as it sat there.
         */
        private VehiclePhysics.Surroundings surroundings() {
            Block here = world.getBlockAt(at);
            boolean supported = !Double.isNaN(surfaceUnder(at.getX(), at.getY(), at.getZ(),
                    info.hitbox(), at.getY() - SUPPORT_REACH, at.getY()));
            boolean water = here.getType() == Material.WATER;
            wall = wallFor(supported);
            // The wheels only matter on land: a hull sits on the water however
            // the bed under it is shaped, and an aircraft's wheels are up.
            double[] wheels = info.medium() == VehicleMedium.LAND && supported ? wheelHeights() : null;
            if (!water) {
                return new VehiclePhysics.Surroundings(supported, false, 0, wheels, wall);
            }
            // Fully under is a full block of push; otherwise the hull settles
            // with its base a little below the top of the WATER in the block
            // it is in, which is what floating at the surface looks like.
            //
            // The top of the water, not of the block: flowing water is drawn
            // at its level - seven eighths for the block beside a source,
            // one eighth at the end of its run - and a hull that floated a
            // full block up in a puddle an eighth deep would be standing in
            // the air. A river's edge is the everyday case; a wave built out
            // of levels (the surfing addon's wave pool) is the one that made
            // it matter, because there the whole surface is levels.
            boolean deep = here.getRelative(0, 1, 0).getType() == Material.WATER;
            double submersion = deep ? 1 : Math.max(-1, Math.min(1, (here.getY() + waterTop(here)) - at.getY()));
            return new VehiclePhysics.Surroundings(supported, true, submersion, wheels, wall);
        }

        /**
         * How far up its block a water block's surface is, where a hull's
         * base settles: a shade under the eight ninths a source is drawn at,
         * and for flowing water the height its level is drawn at, by the
         * same shade. Falling water (level 8 and up) is a full column.
         */
        private static double waterTop(Block water) {
            org.bukkit.block.data.BlockData data = water.getBlockData();
            if (!(data instanceof org.bukkit.block.data.Levelled)) {
                return WATER_SURFACE;
            }
            int level = ((org.bukkit.block.data.Levelled) data).getLevel();
            if (level <= 0 || level >= 8) {
                return WATER_SURFACE;
            }
            return Math.max(0.05, (8 - level) / 9.0 - (8 / 9.0 - WATER_SURFACE));
        }

        /**
         * The height of the ground under each wheel, relative to the position:
         * front-left, front-right, rear-left, rear-right, NaN over nothing.
         *
         * <p>This is what the body's pitch and roll are aimed at, and it is
         * the whole of how a car goes nose-up onto a kerb and leans on a
         * slab road instead of hopping up a block dead level. Four points a
         * wheel's width in from the box's corners, three block reads each at
         * most — the one the position is in, and two below it, since the
         * position sits on the HIGHEST wheel and the others may be a block or
         * two lower. A surface above the position within a shade counts too,
         * for a wheel that has just come onto something the corner samples
         * missed.
         */
        private double[] wheelHeights() {
            VehicleHitbox box = info.hitbox();
            double half = VehiclePhysics.wheelbase(box) / 2;
            double side = VehiclePhysics.track(box) / 2;
            double[] heights = new double[4];
            int wheel = 0;
            for (double forward : new double[] {half, -half}) {
                for (double right : new double[] {-side, side}) {
                    double[] offset = VehiclePhysics.seatOffset(state.yaw(), right, forward);
                    heights[wheel++] = wheelHeight(at.getX() + offset[0], at.getZ() + offset[1]);
                }
            }
            return heights;
        }

        private double wheelHeight(double x, double z) {
            double y = at.getY();
            // The models first, and only the ones in the same band the blocks
            // are read over: a wheel resting on a platform is on the platform,
            // and reading the floor under it instead would tip the body into a
            // corner it is nowhere near. Highest wins for the same reason the
            // block loop counts down from the top.
            double onModel = nearbyModels.topAt(x, z, y - 1.6, y + 0.6 + BlockSurfaces.EPSILON);
            int highest = (int) Math.floor(y + 0.5);
            int lowest = (int) Math.floor(y - 1.6);
            for (int level = highest; level >= lowest; level--) {
                double top = BlockSurfaces.top(world.getBlockAt(new Location(world, x, level, z)), x, z);
                if (!Double.isNaN(top) && top <= y + 0.6 + BlockSurfaces.EPSILON) {
                    return (Double.isNaN(onModel) || top > onModel ? top : onModel) - y;
                }
            }
            return Double.isNaN(onModel) ? Double.NaN : onModel - y;
        }

        /** Commits a step, refusing whatever the world will not allow. */
        private void apply(VehiclePhysics.Step step) {
            VehiclePhysics.State beforeImpact = state;
            Vector worldNormal = null;
            boolean landedHard = false;
            VehicleHitbox box = info.hitbox();
            double nextY = at.getY() + step.dy();
            if (step.dy() < 0) {
                // <strong>On the surface, at whatever height the surface is.</strong>
                // This used to land the vehicle at the top of the BLOCK it was
                // falling into — {@code floor(y) + 1} — which is right for the
                // full cube it assumed and wrong for every partial block in
                // the game: a car on a slab road sat half a block in the air,
                // a boat in a shallow stream rode over the top of it, and a
                // vehicle on a path or a farmland field hovered a sixteenth
                // above everything a player was walking on.
                double top = surfaceUnder(at.getX(), nextY, at.getZ(), box, nextY, at.getY());
                if (!Double.isNaN(top)) {
                    nextY = top;
                    landedHard = Math.abs(beforeImpact.verticalSpeed()) > 0.5;
                    state = state.landed();
                }
            }

            double nextX = at.getX() + step.dx();
            double nextZ = at.getZ() + step.dz();
            if (step.dx() != 0 || step.dz() != 0) {
                // The WHOLE FOOTPRINT at the destination, not one point a nose
                // length ahead. A point check is a vehicle whose bodywork goes
                // through a wall its centre line misses — which is most walls,
                // for anything wider than a block.
                // Each of the three is only asked when the one before it left
                // the question open, so a vehicle driving down an empty road —
                // which is nearly all of them, nearly all the time — still
                // costs exactly one `blocked` call.
                boolean into = blocked(nextX, nextY, nextZ, box, state.yaw());
                // How high the thing in the way actually stands, so a slab
                // raises the vehicle by half a block and a kerb by a whole
                // one. NaN when nothing within a step is above the base, which
                // is a wall rather than a step.
                double climb = into && info.medium() == VehicleMedium.LAND
                        ? obstructionAhead(nextX, nextY, nextZ, box, state.yaw())
                        : Double.NaN;
                boolean canStep = !Double.isNaN(climb)
                        && !blocked(nextX, climb, nextZ, box, state.yaw());
                // Asked only when something is in the way and no kerb explains
                // it: is the vehicle in fact already inside something? See
                // VehiclePhysics.resolve — this is what lets a vehicle that
                // ended up in a wall drive back out of it.
                boolean stuck = into && !canStep
                        && blocked(at.getX(), nextY, at.getZ(), box, state.yaw());

                switch (VehiclePhysics.resolve(into, canStep, stuck)) {
                    case STEP_UP -> {
                        // The position takes the step at once; the body does
                        // not, and springs up after it — and the bump costs
                        // some speed. See VehiclePhysics.State.stepped.
                        state = state.stepped(climb - nextY);
                        nextY = climb;
                    }
                    case STOP -> {
                        // <strong>Along the wall, if either axis is clear.</strong>
                        // Stopping dead was honest and read as hitting an
                        // invisible wall every time a wing brushed a building.
                        // Each world axis is tried on its own — two more box
                        // checks, only on a tick that has already hit something
                        // — and whichever is free keeps most of its velocity
                        // (VehiclePhysics.WALL_SLIDE_KEEP); the one that is not
                        // loses all of it. Both blocked, or a corner where both
                        // are free but together are not, is a head-on stop.
                        boolean alongX = step.dx() != 0 && !blocked(nextX, nextY, at.getZ(), box, state.yaw());
                        boolean alongZ = step.dz() != 0 && !blocked(at.getX(), nextY, nextZ, box, state.yaw());
                        if (alongX && alongZ) {
                            // A corner clipped: keep the bigger component.
                            alongZ = Math.abs(step.dz()) > Math.abs(step.dx());
                            alongX = !alongZ;
                        }
                        if (alongX) {
                            nextZ = at.getZ();
                            worldNormal = new Vector(0, 0, Math.signum(step.dz()));
                            state = state.deflected(false, true);
                        } else if (alongZ) {
                            nextX = at.getX();
                            worldNormal = new Vector(Math.signum(step.dx()), 0, 0);
                            state = state.deflected(true, false);
                        } else {
                            nextX = at.getX();
                            nextZ = at.getZ();
                            worldNormal = new Vector(step.dx(), 0, step.dz());
                            if (worldNormal.lengthSquared() > 1e-9) worldNormal.normalize();
                            state = state.stopped();
                        }
                    }
                    // Either nothing was in the way, or the vehicle is already
                    // in something and refusing would only trap it.
                    case MOVE -> { }
                }
            }

            at = new Location(world, nextX, nextY, nextZ);
            if (worldNormal != null) {
                fireWorldImpact(beforeImpact, state, worldNormal, VehicleImpactEvent.Cause.WORLD,
                        new Location(world, nextX, nextY + box.height() / 2, nextZ));
            }
            if (landedHard) {
                fireWorldImpact(beforeImpact, state, new Vector(0, -1, 0),
                        VehicleImpactEvent.Cause.LANDING, new Location(world, nextX, nextY, nextZ));
            }
        }

        /** Publishes one authoritative world collision after the solver has changed the state. */
        private void fireWorldImpact(VehiclePhysics.State before, VehiclePhysics.State after, Vector normal,
                                     VehicleImpactEvent.Cause cause, Location point) {
            double[] oldHorizontal = VehiclePhysics.worldVelocity(before.yaw(), before.speed(), before.slip());
            double[] newHorizontal = VehiclePhysics.worldVelocity(after.yaw(), after.speed(), after.slip());
            Vector oldVelocity = new Vector(oldHorizontal[0], before.verticalSpeed(), oldHorizontal[1]);
            Vector newVelocity = new Vector(newHorizontal[0], after.verticalSpeed(), newHorizontal[1]);
            Vector change = newVelocity.clone().subtract(oldVelocity);
            double closing = Math.max(0, oldVelocity.dot(normal));
            if (cause == VehicleImpactEvent.Cause.LANDING) closing = Math.abs(before.verticalSpeed());
            if (closing <= 1e-6 && change.lengthSquared() <= 1e-6) return;
            VehicleImpactArea area = cause == VehicleImpactEvent.Cause.LANDING
                    ? VehicleImpactArea.UNDERSIDE
                    : impactArea(body(), normal.getX(), normal.getZ());
            VehicleImpactEvent.Outcome outcome = new VehicleImpactEvent.Outcome(
                    handle(chassisId), oldVelocity, change, normal, closing,
                    Math.max(0.1, info.weight()) * change.length(),
                    after.yawRate() - before.yawRate(), area);
            plugin.getServer().getPluginManager().callEvent(
                    new VehicleImpactEvent(cause, point, outcome, null));
        }

        /**
         * Says where seat {@code index}'s occupant actually is, against where
         * this class just put them. Silent unless {@code vehicles.debug-seats}.
         *
         * <p><strong>The gap is the whole point, and it is the one number
         * nothing else in here can see.</strong> Everything upstream is
         * derived: {@code seatLocation} is a pure function of the chassis, the
         * yaw and the seat's own numbers, so it cannot drift — which means a
         * rider who is visibly out of their seat is a divergence between that
         * answer and where the world ended up putting them, and no amount of
         * reading this file will show it.
         *
         * <p>Split into ALONG and ACROSS rather than one distance, because the
         * two have different causes: along is the vanilla passenger rule that
         * {@code seat-forward} exists to close, and across is not supposed to
         * happen at all — a seat's lateral offset is rotated by the same yaw as
         * everything else on the vehicle.
         *
         * <p>Once a second per seat. A line per tick per occupant is a full bus
         * writing 160 lines a second, which is a log nobody reads.
         */
        private void reportSeat(int index, Player rider, Entity mount) {
            if (!debugSeats || rider == null || age % 20 != 0) {
                return;
            }
            Location want = seatLocation(index);
            Location got = rider.getLocation();
            double dx = got.getX() - want.getX();
            double dz = got.getZ() - want.getZ();
            // Into the vehicle's own frame, so the two numbers mean something
            // whichever way it is pointing.
            double heading = Math.toRadians(state.yaw());
            double along = -(dx * Math.sin(heading)) + dz * Math.cos(heading);
            double across = dx * Math.cos(heading) + dz * Math.sin(heading);
            VehicleSeat seat = info.seats().get(index);
            plugin.getLogger().info(String.format(
                    "[seat %d %s] across %+.3f along %+.3f up %+.3f | mount %+.3f | yaw %.1f",
                    index, seat.isDriver() ? "driver" : "passenger",
                    across, along, got.getY() - want.getY(),
                    mount == null ? 0 : mount.getLocation().getY() - mountLocation(index,
                            mount instanceof ArmorStand).getY(),
                    state.yaw()));
        }

        /**
         * Whether the vehicle's box would be inside a solid block there.
         *
         * <p>Sampled at the four corners of the footprint and at its centre,
         * at every block height the box spans. Sampling rather than a true
         * swept volume: a real sweep is a much bigger piece of work and this
         * runs for every moving vehicle every tick, and the failure of
         * sampling — squeezing a corner through a one-block pillar at very
         * high speed — is a great deal less visible than the failure it
         * replaced, which was driving through the side of a house.
         *
         * <p>The bottom of the box is deliberately not sampled: a vehicle
         * rests ON the ground, so its own floor is always in the block it is
         * standing on.
         *
         * <p><strong>Against the block's real collision shape, not its
         * material.</strong> Asking whether a material is solid says a slab
         * fills its whole block, which made a vehicle standing on a slab road
         * permanently inside a wall as far as this was concerned — and a
         * vehicle already inside something is allowed to keep moving (see
         * {@link VehiclePhysics#resolve}), so on that road it drove through
         * buildings.
         */
        private boolean blocked(double x, double y, double z, VehicleHitbox box, double yaw) {
            for (double dy = 0.2; dy < box.height(); dy += 1) {
                for (double[] offset : footprint(box, yaw)) {
                    if (BlockSurfaces.solidAt(world, x + offset[0], y + dy, z + offset[1])) {
                        return true;
                    }
                }
            }
            // The placed models on the same terms, so everything downstream —
            // the wall slide, the step up, the already-inside case — works on
            // them without knowing there is a second kind of world.
            //
            // <strong>Sampled much more finely, because a model is not a
            // block.</strong> Five points are right for blocks: the thinnest
            // thing in the way is a whole cube, so some corner always lands in
            // it. A model fence rail is a couple of pixels thick and would pass
            // clean between two corners of anything wider than it. The extra
            // points cost nothing on the vehicles that matter, because this
            // runs only when something is actually nearby — which, for a
            // vehicle on an empty road, is never.
            if (nearbyModels.isEmpty()) {
                return false;
            }
            for (double dy = 0.2; dy < box.height(); dy += MODEL_SAMPLE_STEP) {
                for (double[] offset : modelFootprint(box, yaw)) {
                    if (nearbyModels.solidAt(x + offset[0], y + dy, z + offset[1])) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The vehicle's outline, sampled finely enough to catch thin art.
         *
         * <p>The PERIMETER rather than the whole area, which is the cheap half
         * of the argument and also the correct one: anything crossing the
         * footprint has to cross its edge to get there, so walking the edge
         * finds every wall, rail and post that a vehicle could be driving
         * into. The centre is kept as well, for the one case an edge walk
         * misses — a vehicle standing entirely over a single narrow thing.
         */
        private double[][] modelFootprint(VehicleHitbox box, double yaw) {
            double halfWidth = box.width() / 2;
            double halfLength = box.length() / 2;
            int across = (int) Math.ceil(box.width() / MODEL_SAMPLE_STEP);
            int along = (int) Math.ceil(box.length() / MODEL_SAMPLE_STEP);
            List<double[]> points = new ArrayList<>((across + along) * 2 + 1);
            for (int i = 0; i <= across; i++) {
                double right = -halfWidth + box.width() * i / (double) across;
                points.add(VehiclePhysics.seatOffset(yaw, right, -halfLength));
                points.add(VehiclePhysics.seatOffset(yaw, right, halfLength));
            }
            // The corners are already in from the pass above, so the sides run
            // between them rather than repeating them.
            for (int i = 1; i < along; i++) {
                double forward = -halfLength + box.length() * i / (double) along;
                points.add(VehiclePhysics.seatOffset(yaw, -halfWidth, forward));
                points.add(VehiclePhysics.seatOffset(yaw, halfWidth, forward));
            }
            points.add(VehiclePhysics.seatOffset(yaw, 0, 0));
            return points.toArray(new double[0][]);
        }

        /**
         * How far out {@link ModelObstacles} looks, in blocks.
         *
         * <p>The footprint's own half diagonal, a step's worth of travel, and
         * then {@link #MODEL_SEARCH_MARGIN} — because a placed model is found
         * by its ANCHOR and reaches well past it. A four-block statue is
         * anchored in one block and hangs over the three around it, so a search
         * bounded by the vehicle would drive into the half of it that is not
         * over its own anchor.
         */
        private double modelReach() {
            VehicleHitbox box = info.hitbox();
            return Math.hypot(box.width(), box.length()) / 2 + 1.5 + MODEL_SEARCH_MARGIN;
        }

        /**
         * How far up and down it looks.
         *
         * <p>Down as far as {@link #wheelHeight} reads and up past the top of
         * the vehicle, since a model taller than the box is still something to
         * hit — plus the same margin, for the same reason.
         */
        private double modelHeight() {
            return info.hitbox().height() + 2 + MODEL_SEARCH_MARGIN;
        }

        /**
         * The height of the surface the footprint would rest on at {@code y},
         * or NaN for nothing within {@code floor} and {@code ceiling}.
         *
         * <p>The highest of the five, so a vehicle with one corner on a kerb
         * sits on the kerb rather than sinking the rest of itself to the road.
         */
        private double surfaceUnder(double x, double y, double z, VehicleHitbox box,
                                    double floor, double ceiling) {
            double best = Double.NaN;
            for (double[] offset : footprint(box, state.yaw())) {
                double top = BlockSurfaces.resting(world, x + offset[0], y, z + offset[1],
                        floor, ceiling);
                if (!Double.isNaN(top) && (Double.isNaN(best) || top > best)) {
                    best = top;
                }
            }
            // <strong>A model a vehicle can be stopped by is one it can also
            // stand on, and that pairing is not optional.</strong> Without it a
            // car that stepped up onto a low platform found nothing holding it
            // there, fell back through, was stepped up again on the next tick,
            // and bounced for as long as it sat on the thing — the same failure
            // a snow layer used to cause, for the same reason.
            double onModel = modelTop(x, z, box, state.yaw(),
                    floor - BlockSurfaces.EPSILON, ceiling + BlockSurfaces.EPSILON);
            return Double.isNaN(onModel) || (!Double.isNaN(best) && best > onModel) ? best : onModel;
        }

        /**
         * The highest placed-model surface anywhere under the vehicle, within a
         * band.
         *
         * <p><strong>Over the SAME points {@link #blocked} tests, and that is
         * the whole reason this is its own method.</strong> The two used
         * different sample sets — five corners here, the dense perimeter there
         * — and a staircase is exactly where that shows: the coarse pass
         * measured a step at one height, the step-up moved the vehicle there,
         * and the dense pass immediately found a step edge between two corners
         * that was higher still. So every stair was "something in the way that
         * is not a step", and vehicles stopped dead at the bottom of models
         * they could obviously have driven up.
         *
         * <p>An answer about what a vehicle can climb has to be measured the
         * same way as the answer about whether it fits once it has.
         */
        private double modelTop(double x, double z, VehicleHitbox box, double yaw,
                                double floor, double ceiling) {
            if (nearbyModels.isEmpty()) {
                return Double.NaN;
            }
            double best = Double.NaN;
            for (double[] offset : modelFootprint(box, yaw)) {
                double top = nearbyModels.topAt(x + offset[0], z + offset[1], floor, ceiling);
                if (!Double.isNaN(top) && (Double.isNaN(best) || top > best)) {
                    best = top;
                }
            }
            return best;
        }

        /**
         * The height of whatever is in the way at the destination, or NaN when
         * nothing a vehicle could climb is.
         *
         * <p>Only asked when the destination is blocked, so the extra reads
         * stay off the road ahead of every vehicle that is not driving into
         * anything.
         */
        private double obstructionAhead(double x, double y, double z, VehicleHitbox box, double yaw) {
            double best = Double.NaN;
            for (double[] offset : footprint(box, yaw)) {
                double top = BlockSurfaces.obstruction(world, x + offset[0], z + offset[1],
                        y, MAX_STEP_UP);
                if (!Double.isNaN(top) && (Double.isNaN(best) || top > best)) {
                    best = top;
                }
            }
            // A low model is a kerb, not a wall — which is what makes the
            // collidable default cost a rug or a manhole cover nothing: a
            // vehicle drives onto it and over it rather than stopping dead at
            // something an ankle high. Measured over the dense sample set, so
            // this and `blocked` cannot disagree about a stair — see modelTop.
            double onModel = modelTop(x, z, box, yaw,
                    y + BlockSurfaces.EPSILON, y + MAX_STEP_UP + BlockSurfaces.EPSILON);
            return Double.isNaN(onModel) || (!Double.isNaN(best) && best > onModel) ? best : onModel;
        }

        /**
         * The five points the world is sampled at, as world x/z offsets from
         * the vehicle's centre: its four corners and the middle.
         *
         * <p>Shared by everything that asks the world a question, because the
         * three of them disagreeing about where the vehicle is would be a
         * vehicle held up by ground it is not standing on.
         */
        private double[][] footprint(VehicleHitbox box, double yaw) {
            double halfWidth = box.width() / 2;
            double halfLength = box.length() / 2;
            double[][] points = new double[5][];
            for (int corner = 0; corner < 5; corner++) {
                double right = corner == 4 ? 0 : (corner < 2 ? -halfWidth : halfWidth);
                double forward = corner == 4 ? 0 : (corner % 2 == 0 ? -halfLength : halfLength);
                points[corner] = VehiclePhysics.seatOffset(yaw, right, forward);
            }
            return points;
        }

        /**
         * Shoves anything standing where the vehicle is going.
         *
         * <p><strong>This is what "the hitbox collides" actually means here,
         * and it is not a bounding box.</strong> A Bukkit plugin cannot give
         * an entity a bounding box of its own size — a box comes from the
         * entity TYPE — so a vehicle-shaped solid is not something that can be
         * asked for. What can be done is the effect: anybody inside the box
         * gets pushed out of it, away from the centre and along the way the
         * vehicle is going, so a car shoves you aside instead of passing
         * through you.
         *
         * <p>Occupants are exempt, obviously. So are other vehicles' parts,
         * which are ours and are already where they should be.
         */
        private void shoveAside(VehiclePhysics.Step step) {
            VehicleHitbox box = info.hitbox();
            double reach = Math.max(box.width(), box.length()) / 2 + 0.5;
            for (Entity nearby : world.getNearbyEntities(at, reach, box.height(), reach)) {
                if (!(nearby instanceof LivingEntity) || occupants.contains(nearby.getUniqueId())) {
                    continue;
                }
                if (nearby instanceof Player && !pushPlayers) {
                    continue;
                }
                double dx = nearby.getLocation().getX() - at.getX();
                double dz = nearby.getLocation().getZ() - at.getZ();
                double away = Math.hypot(dx, dz);
                // Straight through the middle has no direction to be pushed
                // out in, so it gets the vehicle's own — normalised and scaled
                // like the ordinary case rather than taken raw. It was taken
                // raw and then had the vehicle's motion added on top, so
                // somebody standing exactly on the centre line got that motion
                // twice while everybody a hand's width to the side got a
                // measured shove.
                double along = Math.hypot(step.dx(), step.dz());
                Vector push = away < 0.05
                        ? (along < 1e-6
                                ? new Vector()
                                : new Vector(step.dx() / along, 0, step.dz() / along).multiply(SHOVE))
                        : new Vector(dx / away, 0, dz / away).multiply(SHOVE);
                nearby.setVelocity(nearby.getVelocity().add(push.add(new Vector(step.dx(), 0, step.dz()))));
            }
        }

        // --- hitting other vehicles ------------------------------------

        /** Whether this vehicle is somewhere another one could hit it. */
        boolean collidable() {
            return world != null && at != null && at.getWorld() != null;
        }

        /**
         * Whether it took a full tick this pass, which is the whole test for
         * whether it is worth asking what it is touching. See
         * {@link VehicleRuntime#impacts()}.
         *
         * <p>Deliberately not "did it MOVE". Two vehicles standing still inside
         * each other — spawned there, or left there by an impact that ended
         * with both stopped — would then have nothing to separate them, and
         * "stuck inside another vehicle" is the one failure of this whole pass
         * that a player cannot drive out of. A vehicle that took the parked
         * shortcut is still excluded, which is what keeps the car park free.
         */
        boolean ticked() {
            return pending != null;
        }

        /**
         * How far to look for something to have hit, in blocks.
         *
         * <p>This box's own half diagonal plus the WIDEST vehicle the pack
         * defines, because a neighbour is found by where its centre is and a
         * long one reaches a good way past it. Derived from the catalogue
         * rather than from the format's maximum, so a server whose biggest
         * vehicle is a go-kart searches a go-kart's worth of world; see
         * {@link VehicleRuntime#widest}.
         *
         * <p>Floored at this vehicle's own size, so two of the same thing can
         * always find each other whatever the catalogue says. Which is not
         * paranoia: a vehicle outlives the definition it was adopted with — a
         * reload can drop its type entirely — and a reach that came out shorter
         * than the vehicle is a pair parked nose to nose that never touches.
         */
        double impactReach() {
            VehicleHitbox box = info.hitbox();
            double mine = Math.hypot(box.width(), box.length()) / 2;
            return mine + Math.max(widest, mine) + 0.5;
        }

        /**
         * Hits {@code other}, if the two are in fact overlapping.
         *
         * <p>Height first, and on its own: two boxes whose footprints cross are
         * not touching if one is on a bridge over the other, and the flat
         * arithmetic in {@link VehicleImpacts} has no way to know that. A
         * vehicle parked exactly on another's roof shares a plane and no
         * volume, which is why this wants real overlap rather than contact.
         */
        void collideWith(Ride other) {
            if (!world.equals(other.world)) {
                return;
            }
            double base = Math.max(at.getY(), other.at.getY());
            double top = Math.min(at.getY() + info.hitbox().height(),
                    other.at.getY() + other.info.hitbox().height());
            if (top - base <= VERTICAL_TOUCH) {
                return;
            }
            VehicleImpacts.Body mine = body();
            VehicleImpacts.Body theirs = other.body();
            VehicleImpacts.Contact contact = VehicleImpacts.contact(mine, theirs);
            if (contact == null) {
                return;
            }
            VehicleImpacts.Exchange exchange = VehicleImpacts.resolve(mine, theirs, contact);
            bump(exchange.a());
            other.bump(exchange.b());
            if (exchange.closingSpeed() > 1e-6 && exchange.normalImpulse() > 0) {
                Vector normal = new Vector(contact.nx(), 0, contact.nz());
                Location point = new Location(world, contact.px(), base + (top - base) / 2, contact.pz());
                VehicleImpactEvent.Outcome first = new VehicleImpactEvent.Outcome(
                        handle(chassisId), new Vector(mine.vx(), 0, mine.vz()),
                        new Vector(exchange.a().dvx(), 0, exchange.a().dvz()), normal,
                        exchange.closingSpeed(), exchange.normalImpulse(), exchange.a().dspin(),
                        impactArea(mine, contact.nx(), contact.nz()));
                VehicleImpactEvent.Outcome second = new VehicleImpactEvent.Outcome(
                        handle(other.chassisId), new Vector(theirs.vx(), 0, theirs.vz()),
                        new Vector(exchange.b().dvx(), 0, exchange.b().dvz()), normal.clone().multiply(-1),
                        exchange.closingSpeed(), exchange.normalImpulse(), exchange.b().dspin(),
                        impactArea(theirs, -contact.nx(), -contact.nz()));
                plugin.getServer().getPluginManager().callEvent(
                        new VehicleImpactEvent(VehicleImpactEvent.Cause.VEHICLE, point, first, second));
            }
        }

        /** Which side of {@code body} faces a world-space contact normal. */
        private VehicleImpactArea impactArea(VehicleImpacts.Body body, double nx, double nz) {
            double[] forward = VehiclePhysics.forward(body.yaw());
            double along = nx * forward[0] + nz * forward[1];
            if (Math.abs(along) >= 0.55) {
                return along > 0 ? VehicleImpactArea.FRONT : VehicleImpactArea.REAR;
            }
            double[] right = VehiclePhysics.right(body.yaw());
            double across = nx * right[0] + nz * right[1];
            return across >= 0 ? VehicleImpactArea.RIGHT : VehicleImpactArea.LEFT;
        }

        /** This vehicle as the impulse solver wants it. See {@link VehicleImpacts.Body}. */
        private VehicleImpacts.Body body() {
            double[] velocity = VehiclePhysics.worldVelocity(state.yaw(), state.speed(), state.slip());
            VehicleHitbox box = info.hitbox();
            // The pack's weight, straight through: only the RATIO between two
            // vehicles is ever read, so the 1-100 the format states is as good
            // a unit as any. The floor is for a pack that wrote a zero.
            return new VehicleImpacts.Body(at.getX(), at.getZ(), state.yaw(),
                    box.width(), box.length(), velocity[0], velocity[1], state.yawRate(),
                    Math.max(0.1, info.weight()));
        }

        /**
         * Takes this vehicle's half of a collision.
         *
         * <p>The velocity and the spin are the physics model's own state and go
         * straight in. <strong>The separation has to ask the world first</strong>
         * — it is a teleport, and the whole point of it is to move a vehicle
         * somewhere it was not going, which for a car already squeezed against
         * a building is into the building. A refused shove is not shared out
         * again: the other vehicle has already been given its own half, and
         * handing it the rest would mean a car pinned against a wall shoving
         * back harder than one in the open. What it costs is that two vehicles
         * jammed in an alley stay overlapped until one of them drives out,
         * which is also what would happen to two cars.
         */
        private void bump(VehicleImpacts.Impulse impulse) {
            if (impulse == null || !impulse.any()) {
                return;
            }
            double[] velocity = VehiclePhysics.worldVelocity(state.yaw(), state.speed(), state.slip());
            state = state.impacted(velocity[0] + impulse.dvx(), velocity[1] + impulse.dvz(),
                    state.yawRate() + impulse.dspin());
            if (impulse.pushX() != 0 || impulse.pushZ() != 0) {
                double nextX = at.getX() + impulse.pushX();
                double nextZ = at.getZ() + impulse.pushZ();
                if (!blocked(nextX, at.getY(), nextZ, info.hitbox(), state.yaw())) {
                    at = new Location(world, nextX, at.getY(), nextZ);
                }
            }
            // Whatever this vehicle was doing, it is doing something else now.
            // A parked one has to come back to the full tick to spend the speed
            // it has just been given; see settle.
            bumped = true;
            parked = false;
        }

        /**
         * Pushes every entity at where it should be.
         *
         * <p>Teleport, for everything, every tick. Each is a correction toward
         * the authoritative position rather than a blind delta, so an entity
         * that missed a tick catches up rather than falling permanently
         * behind. The occupant's worn rig is pinned here too, in the same
         * pass as the model it has to sit in — a rig moved from the emote
         * system's own tick was a tick adrift whenever that tick ran first,
         * which at speed is half a block.
         */
        /**
         * Writes the body's attitude into a single-display model.
         *
         * <p>The rotation is composed ahead of the scale so it happens about
         * the display's own origin — the anchor, half a block up — rather
         * than about wherever the scale has moved the model's base to; the
         * same order the animator uses for a rig, since the two have to look
         * the same. Re-sent only when it has changed, and glided over the
         * same window as the teleport that carries the position, so the
         * body tilts as smoothly as it moves.
         */
        private void tiltModel(ItemDisplay display) {
            Matrix4f m = tilted() ? attitude() : new Matrix4f();
            if (info.scale() != 1) {
                m.scaleLocal((float) info.scale());
                m.translateLocal(0f, 0.5f * ((float) info.scale() - 1f), 0f);
            }
            Transformation next = RigMath.toTransformation(m);
            if (next.equals(display.getTransformation())) {
                return;
            }
            // The toggle re-arms the client's interpolation from the pose it
            // is showing; see RigAnimator for why a plain zero does not.
            display.setInterpolationDelay(1);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(MODEL_GLIDE_TICKS);
            display.setTransformation(next);
        }

        /**
         * Keeps the speedometer off the action bar for a moment, because
         * something a driver should read has just been written there.
         */
        void hush() {
            hushUntil = age + HUSH_TICKS;
        }

        void showStatus(String text) {
            if (text == null || text.isBlank()) return;
            for (UUID id : occupants) {
                Player player = id == null ? null : plugin.getServer().getPlayer(id);
                if (player != null) overhead(player, text);
            }
            hush();
        }

        /**
         * The driver's speed, above their hotbar.
         *
         * <p>Ground speed rather than speed along the heading, in kilometres
         * an hour, because a block is a metre and a drift is still moving.
         * Only while moving: a parked car's dial reading zero is the action
         * bar being occupied for nothing, and it would paint over whatever
         * anything else wanted to say there.
         *
         * <p>Two switches, and they are different questions. The server's
         * {@code vehicles.speedometer} is "does this server want speed
         * readouts"; {@link VehicleInfo#speedometer()} is "does THIS vehicle
         * have a dashboard", which a skateboard does not. Either one off is
         * off, and off means nothing is written — the readout does not move to
         * the chat or a boss bar, it stops existing, and the action bar goes
         * back to whatever else wants it.
         */
        private void showSpeed(Player driver) {
            if (!speedometer || !info.speedometer() || driver == null
                    || age < hushUntil || age % SPEEDOMETER_TICKS != 0) {
                return;
            }
            double ground = state.groundSpeed();
            if (ground < VehiclePhysics.MOVING_THRESHOLD && !showedSpeed) {
                return;
            }
            showedSpeed = ground >= VehiclePhysics.MOVING_THRESHOLD;
            String colour = state.sliding() ? "&e" : "&7";
            overhead(driver, colour + Math.round(ground * KMH) + " &8km/h");
        }

        private void place(Entity chassis) {
            boolean asked = at.toVector().distanceSquared(chassis.getLocation().toVector()) > 1e-8;
            // The chassis never carries a passenger — the model, the mounts
            // and the hitboxes are all moved by us rather than riding it — so
            // a plain teleport always works and is exact.
            chassis.teleport(at);

            for (int i = 0; i < mounts.size(); i++) {
                Entity mount = plugin.getServer().getEntity(mounts.get(i));
                if (mount == null) {
                    continue;
                }
                UUID occupant = occupants.get(i);
                Player rider = occupant == null ? null : plugin.getServer().getPlayer(occupant);
                // Measured before the mount moves: the rider is where vanilla
                // put them over the mount as it stands, and the mount has not
                // moved since. See mountDrop.
                if (rider != null && mount.getUniqueId().equals(
                        rider.getVehicle() == null ? null : rider.getVehicle().getUniqueId())) {
                    double drop = mount.getLocation().getY() - rider.getLocation().getY();
                    if (Double.isFinite(drop) && Math.abs(drop) < 4) {
                        mountDrop.set(i, drop);
                    }
                }
                Location target = mountLocation(i, mount instanceof ArmorStand);
                if (occupant == null) {
                    // Nobody aboard, so nothing refuses a plain teleport.
                    mount.teleport(target);
                } else if (!seatMover.move(mount, target)) {
                    if (mount instanceof ArmorStand) {
                        // The fallback arm. A stand with gravity has physics,
                        // and physics applies exactly the velocity it is
                        // given before it applies friction to what is left —
                        // so a correction to the target lands there, short of
                        // a wall. Approximate only in what the client sees.
                        mount.setVelocity(target.toVector().subtract(mount.getLocation().toVector()));
                    } else {
                        reseat(i, occupant);
                    }
                }
                if (rider != null && emotes != null) {
                    emotes.anchor(rider, seatLocation(i));
                    // ONE CLOCK. The occupant's emote and the bodywork's
                    // animation were written together — a paddle stroke and
                    // the arms on the paddle, a wheel and the hands turning
                    // it — and they only stay together if they are posed at
                    // the same time. They start on the same tick, but a
                    // restart, a loop wrapping a tick apart or a flicker
                    // between two states pulls them apart, and a rower whose
                    // hands leave the paddle reads as two animations rather
                    // than one. So the rig's own playhead is handed to the
                    // rider every tick; see Emotes.seek. A vehicle with no
                    // rig, or one playing nothing, leaves the rider on their
                    // own clock.
                    if (rig != null) {
                        // Not while the vehicle's own clock runs on DISTANCE
                        // (animation-follows-speed): that clock is a wheel's,
                        // and a rider's legs are not a function of how far the
                        // thing has rolled. Slaving them to it plays a push in
                        // slow motion at walking pace.
                        if (!info.animationFollowsSpeed()) {
                            rig.placement().flatMap(Placement::playhead)
                                    .ifPresent(at -> emotes.seek(rider, at));
                        }
                        // The rider goes over with the bodywork. The vehicle's
                        // pitch and roll are about ITS axes and the rider may
                        // be sitting side-on to them, so they are turned into
                        // the rider's own frame by the seat's yaw first — for a
                        // skater square across the board, the board's roll onto
                        // a wall IS the rider tipping onto their face.
                        // The MODEL'S angles, not the state's: `attitude()`
                        // draws the bodywork with rotateZ(-roll), and a rider
                        // leaned from the raw number is a rider tipping the
                        // opposite way to the thing they are standing on. It
                        // does not show at the fourteen degrees a car corners
                        // at. It shows completely at eighty, as a wall rider
                        // lying INTO the wall with their board between them
                        // and the open air.
                        double seatRadians = Math.toRadians(seatYaw(i));
                        double cos = Math.cos(seatRadians);
                        double sin = Math.sin(seatRadians);
                        // On a wall the rider goes over with the board, all
                        // of it: they are lying on the thing. Everywhere else
                        // they are balancing on it, and take a fraction of a
                        // corner's lean at most - see ridden().
                        double drawnPitch = state.pitch();
                        double drawnRoll = -state.roll();
                        double leanPitch = wall != null ? drawnPitch : ridden(drawnPitch);
                        double leanRoll = wall != null ? drawnRoll : ridden(drawnRoll);
                        emotes.lean(rider,
                                (float) (leanPitch * cos + leanRoll * sin),
                                (float) (leanRoll * cos - leanPitch * sin));
                    }
                }
                reportSeat(i, rider, mount);

                // <strong>Outside the branch, which is where it was not.</strong>
                // The empty-seat arm used to `continue`, so a free seat's
                // hitbox was placed once when the parts were spawned and never
                // moved again — drive away and the only thing you could click
                // to get in was a box hanging in the air where the vehicle had
                // been parked. The body hitbox covered it up: clicking that
                // still seats you, in the FIRST free seat, so what it looked
                // like was "you can never pick which seat you get", not "the
                // seat hitboxes are somewhere else".
                //
                // At the occupant's feet rather than at the mount, which sits
                // most of a block above them: the box is what somebody clicks
                // to get in, and it should be where the seat is drawn.
                Entity hitbox = plugin.getServer().getEntity(hitboxes.get(i));
                if (hitbox != null) {
                    Location box = seatLocation(i);
                    box.setYaw(0);
                    box.setPitch(0);
                    hitbox.teleport(box);
                }
            }

            for (int tile = 0; tile < body.size(); tile++) {
                Entity part = plugin.getServer().getEntity(body.get(tile));
                if (part != null) {
                    part.teleport(bodyLocation(tile));
                }
            }

            if (rig != null) {
                // Every part to the SAME point, which is the rig invariant —
                // a part's offset from the anchor lives in its transformation
                // matrix, not in its position. The yaw goes to the rig's yaw
                // host and turns the whole thing; see RigCarrier. The pitch
                // and roll go into every part's matrix, ahead of its
                // animation, so a spinning wheel spins on a tilted car.
                rig.moveTo(modelAnchor(), modelYaw());
                // The standing lean is ADDED to the computed attitude rather
                // than replacing it, so a vehicle resting on a broken axle
                // still squats over bumps and leans into corners — around the
                // angle it now sits at.
                rig.tilt((float) (state.pitch() + posturePitch), (float) (state.roll() + postureRoll));
            }

            Entity model = modelId == null ? null : plugin.getServer().getEntity(modelId);
            if (model != null) {
                Location where = modelAnchor();
                where.setYaw(modelYaw());
                // The ENTITY never pitches. The body's attitude is in the
                // display's transformation instead, where it can roll as well
                // as pitch and where the client interpolates it; an entity
                // pitch would be a vehicle lying on its side with no way to
                // lean it. This is the line that keeps the two apart.
                where.setPitch(0);
                model.teleport(where);
                if (model instanceof ItemDisplay display) {
                    tiltModel(display);
                }
            }

            // The stuck check: asked to move, and the chassis did not. Three
            // ticks rather than one, because a single tick can legitimately
            // round to nothing. A teleport that does not arrive is a much
            // rarer thing than the velocity a mount once watched, so this is now
            // a guard against a world refusing the move rather than against a
            // server that has switched armour stands off ticking.
            if (asked && chassis.getLocation().distanceSquared(at) > 1e-4) {
                stuck++;
                if (stuck > 3) {
                    warnIfFrozen();
                    stuck = 0;
                }
            } else {
                stuck = 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // The handle a plugin holds
    // ------------------------------------------------------------------

    /**
     * {@link Vehicle}, over a chassis id.
     *
     * <p>Resolves the {@link Ride} on every call rather than holding one,
     * because a Ride is rebuilt on every chunk load and a handle is
     * something a plugin keeps in a field. A chassis with no Ride — parked
     * in a chunk that has just loaded and not yet been adopted, or whose
     * pack has gone — still answers what it can from the entity: where it
     * is, its data, and it can be switched off, which is written to the
     * chassis and picked up when the Ride is built.
     *
     * <p>Equal to any other handle on the same chassis, so a plugin can key
     * a map by it — though {@link #uniqueId} is the better key, being the
     * thing this is equal by.
     */
    private final class Handle implements Vehicle {

        private final UUID chassisId;

        Handle(UUID chassisId) {
            this.chassisId = chassisId;
        }

        private Entity chassisOrNull() {
            Entity entity = plugin.getServer().getEntity(chassisId);
            return entity != null && entity.isValid() && idOf(entity).isPresent() ? entity : null;
        }

        private Ride ride() {
            return live.get(chassisId);
        }

        @Override
        public ContentId id() {
            Ride ride = ride();
            if (ride != null) {
                return ride.info.id();
            }
            Entity chassis = plugin.getServer().getEntity(chassisId);
            return idOf(chassis).orElse(null);
        }

        @Override
        public VehicleInfo info() {
            Ride ride = ride();
            if (ride != null) {
                return ride.info;
            }
            ContentId id = id();
            return id == null ? null : catalogue.get(id);
        }

        @Override
        public UUID uniqueId() {
            return chassisId;
        }

        @Override
        public Entity chassis() {
            return chassisOrNull();
        }

        @Override
        public boolean isValid() {
            return chassisOrNull() != null;
        }

        @Override
        public Location location() {
            Ride ride = ride();
            if (ride != null) {
                return ride.position();
            }
            Entity chassis = chassisOrNull();
            return chassis == null ? null : chassis.getLocation();
        }

        @Override
        public double heading() {
            Ride ride = ride();
            if (ride != null) {
                return ride.state().yaw();
            }
            Entity chassis = chassisOrNull();
            return chassis == null ? 0 : VehiclePhysics.wrap360(chassis.getLocation().getYaw());
        }

        @Override
        public double speed() {
            Ride ride = ride();
            return ride == null ? 0 : ride.state().speed();
        }

        @Override
        public double verticalSpeed() {
            Ride ride = ride();
            return ride == null ? 0 : ride.state().verticalSpeed();
        }

        @Override
        public Set<VehicleState> states() {
            Ride ride = ride();
            return ride == null ? Set.of() : ride.states();
        }

        @Override
        public Optional<Player> driver() {
            return occupant(0);
        }

        @Override
        public List<Player> occupants() {
            Ride ride = ride();
            List<Player> aboard = new ArrayList<>();
            if (ride == null) {
                return aboard;
            }
            for (UUID id : ride.seating()) {
                Player player = id == null ? null : plugin.getServer().getPlayer(id);
                if (player != null) {
                    aboard.add(player);
                }
            }
            return aboard;
        }

        @Override
        public Optional<Player> occupant(int index) {
            Ride ride = ride();
            if (ride == null || index < 0 || index >= ride.info.seats().size()) {
                return Optional.empty();
            }
            UUID id = ride.occupant(index);
            return id == null ? Optional.empty() : Optional.ofNullable(plugin.getServer().getPlayer(id));
        }

        @Override
        public OptionalInt seatOf(Player player) {
            Ride ride = ride();
            if (ride == null || player == null) {
                return OptionalInt.empty();
            }
            int index = ride.seating().indexOf(player.getUniqueId());
            return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
        }

        @Override
        public boolean seat(Player player) {
            Ride ride = ride();
            if (ride == null || player == null) {
                return false;
            }
            int index = ride.firstFreeSeat();
            return index >= 0 && sit(player, ride, index);
        }

        @Override
        public boolean seat(Player player, int index) {
            Ride ride = ride();
            return ride != null && player != null && sit(player, ride, index);
        }

        @Override
        public boolean eject(Player player) {
            Ride ride = ride();
            return ride != null && player != null && ride.eject(player.getUniqueId());
        }

        @Override
        public void ejectAll() {
            Ride ride = ride();
            if (ride != null) {
                ride.evictAll(VehicleExitEvent.Cause.EJECTED);
            }
        }

        @Override
        public boolean isEnabled() {
            Ride ride = ride();
            if (ride != null) {
                return ride.enabled();
            }
            Entity chassis = chassisOrNull();
            return chassis == null
                    || !chassis.getPersistentDataContainer().has(disabledKey, PersistentDataType.BYTE);
        }

        @Override
        public void setEnabled(boolean enabled) {
            Ride ride = ride();
            if (ride != null) {
                ride.enable(enabled);
                return;
            }
            // No Ride to tell: the chassis remembers, and the Ride built from
            // it later reads the flag in its constructor.
            Entity chassis = chassisOrNull();
            if (chassis == null) {
                return;
            }
            if (enabled) {
                chassis.getPersistentDataContainer().remove(disabledKey);
            } else {
                chassis.getPersistentDataContainer().set(disabledKey, PersistentDataType.BYTE, (byte) 1);
            }
        }

        @Override
        public OptionalDouble speedLimit() {
            Ride ride = ride();
            return ride == null ? OptionalDouble.empty() : ride.speedLimit();
        }

        @Override
        public void setSpeedLimit(double blocksPerSecond) {
            Ride ride = ride();
            if (ride != null) {
                ride.limitSpeed(blocksPerSecond);
            }
        }

        @Override
        public OptionalDouble descent() {
            Ride ride = ride();
            return ride == null ? OptionalDouble.empty() : ride.descent();
        }

        @Override
        public void setDescent(double blocksPerSecond) {
            Ride ride = ride();
            if (ride != null) {
                ride.descend(blocksPerSecond);
            }
        }

        @Override
        public VehicleInput input() {
            Ride ride = ride();
            return ride == null ? VehicleInput.NONE : ride.input();
        }

        @Override
        public void nudge(double blocksPerSecond) {
            Ride ride = ride();
            if (ride != null) {
                ride.nudge(blocksPerSecond);
            }
        }

        @Override
        public void spin(double degreesPerSecond) {
            Ride ride = ride();
            if (ride != null) {
                ride.spin(degreesPerSecond);
            }
        }

        @Override
        public void dress(Player occupant, String emoteId) {
            Ride ride = ride();
            if (ride != null && occupant != null) {
                ride.dressAs(occupant.getUniqueId(), emoteId);
            }
        }

        @Override
        public void undress(Player occupant) {
            dress(occupant, null);
        }

        @Override
        public void turnOccupant(Player occupant, Double yaw) {
            Ride ride = ride();
            if (ride != null && occupant != null) {
                ride.turnOccupant(occupant.getUniqueId(), yaw);
            }
        }

        @Override
        public void dressVariant(Player occupant, String variant) {
            Ride ride = ride();
            if (ride != null && occupant != null) {
                ride.dressVariant(occupant.getUniqueId(), variant);
            }
        }

        @Override
        public void holdOccupant(Player occupant, boolean hold) {
            Ride ride = ride();
            if (ride != null && occupant != null) {
                ride.holdOccupant(occupant.getUniqueId(), hold);
            }
        }

        @Override
        public double groundSpeed() {
            Ride ride = ride();
            return ride == null ? 0 : ride.state().groundSpeed();
        }

        @Override
        public Vector velocity() {
            Ride ride = ride();
            return ride == null ? new Vector() : ride.velocity();
        }

        @Override
        public void applyImpulse(Vector deltaVelocity, double spinDelta) {
            Ride ride = ride();
            if (ride != null) ride.applyImpulse(deltaVelocity, spinDelta);
        }

        @Override
        public List<String> parts() {
            Ride ride = ride();
            return ride == null ? List.of() : List.copyOf(ride.supportedParts);
        }

        @Override
        public Vector partOffset(String part) {
            Ride ride = ride();
            return ride == null ? null : ride.partOffset(part);
        }

        @Override
        public void setPosture(double pitch, double roll) {
            Ride ride = ride();
            if (ride != null) ride.setPosture(pitch, roll);
        }

        @Override
        public void setHandling(double speedFactor, double turnFactor) {
            Ride ride = ride();
            if (ride != null) ride.setHandling(speedFactor, turnFactor);
        }

        @Override
        public Set<String> detachedParts() {
            Ride ride = ride();
            if (ride != null) return ride.detachedParts();
            Entity chassis = chassisOrNull();
            String encoded = chassis == null ? null : chassis.getPersistentDataContainer()
                    .get(detachedPartsKey, PersistentDataType.STRING);
            if (encoded == null || encoded.isBlank()) return Set.of();
            Set<String> names = new LinkedHashSet<>();
            for (String name : encoded.split("\\n")) if (!name.isBlank()) names.add(name);
            return Set.copyOf(names);
        }

        @Override
        public boolean detachPart(String part, Vector velocity, double spin, long despawnTicks) {
            Ride ride = ride();
            return ride != null && ride.detachPart(part, velocity, spin, despawnTicks);
        }

        @Override
        public void showStatus(String legacyText) {
            Ride ride = ride();
            if (ride != null) ride.showStatus(legacyText);
        }

        @Override
        public boolean wallRiding() {
            Ride ride = ride();
            return ride != null && ride.wallRiding();
        }

        @Override
        public void perform(String animation) {
            Ride ride = ride();
            if (ride != null) {
                ride.perform(animation);
            }
        }

        @Override
        public Optional<String> performing() {
            Ride ride = ride();
            return ride == null ? Optional.empty() : ride.performing();
        }

        @Override
        public void stop() {
            Ride ride = ride();
            if (ride != null) {
                ride.halt();
            }
        }

        @Override
        public boolean remove() {
            Entity chassis = chassisOrNull();
            return chassis != null && VehicleRuntime.this.remove(chassis);
        }

        @Override
        public PersistentDataContainer data() {
            Entity chassis = chassisOrNull();
            return chassis == null ? null : chassis.getPersistentDataContainer();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Handle && ((Handle) other).chassisId.equals(chassisId);
        }

        @Override
        public int hashCode() {
            return chassisId.hashCode();
        }

        @Override
        public String toString() {
            return "Vehicle[" + id() + " " + chassisId + "]";
        }
    }

    /** What to call a vehicle in a message. */
    public String nameOf(VehicleInfo info) {
        return info.name()
                .map(name -> ChatColor.translateAlternateColorCodes('&', name))
                .orElseGet(() -> info.id().toString());
    }
}
