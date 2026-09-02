package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.Placement;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.api.event.ModelSeatEvent;
import ai.resourcepack.engine.core.Chat;
import ai.resourcepack.engine.core.model.DisplayCarry;
import ai.resourcepack.engine.core.model.DisplayLatency;
import ai.resourcepack.engine.core.model.MountOffset;
import ai.resourcepack.engine.core.model.RigCarrier;
import ai.resourcepack.engine.core.model.RigTags;
import ai.resourcepack.engine.core.version.Compatibility;
import org.bukkit.ChatColor;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Vehicles: a model people ride.
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
 * <p><strong>Anything carrying a passenger is moved by velocity, never by
 * teleport.</strong> This is not a preference. CraftBukkit refuses
 * {@code Entity#teleport} on an entity that is being ridden, so the obvious
 * implementation — teleport each seat to where it should be — silently does
 * nothing the moment somebody sits in it, on exactly the versions this engine
 * promises to run on. Paper has a flag for it and Spigot does not, and the
 * engine supports both.
 *
 * <p>Seat mounts are <strong>marker</strong> armour stands, which have no
 * bounding box, so velocity moves them exactly rather than approximately —
 * there is nothing for them to collide with. Collision is decided here, in
 * block reads, before the move is committed.
 *
 * <p>The one thing that can stop this working is a server that has switched
 * armour stands off ticking ({@code armor-stands-tick: false} in Paper's
 * config). A stand that does not tick does not apply velocity, so a vehicle
 * would sit still with no error anywhere — {@link #warnIfFrozen} watches for
 * exactly that and says so once, because the alternative is a support ticket
 * that reads "vehicles do not work" with nothing to go on.
 *
 * <h2>What is derived and therefore never saved</h2>
 *
 * <p>Only the chassis persists. The seats, their hitboxes and the model are
 * rebuilt whenever a chassis is picked up again — the lesson of the 0.46.1
 * audit, where saving derived entities meant a chunk load spawned a second set
 * and orphaned the first, invisibly and for ever.
 */
public final class Vehicles implements Listener {

    /** Seconds per tick, so the physics constants can be quoted per second. */
    private static final double DT = 1 / 20.0;

    /**
     * How long the client is asked to take gliding the model to each new
     * position.
     *
     * <p><strong>This matches vanilla's own entity interpolation on purpose,
     * and that is the whole point of it.</strong> A client lerps an ordinary
     * entity's position over about three ticks, and a rider is drawn wherever
     * their mount is — so the rider is three ticks behind. The model is a
     * Display and lags by whatever this says instead. Set them to different
     * numbers and the two are behind by different amounts, which is a rider
     * sliding out of their seat the faster the vehicle goes.
     *
     * <p>The lag itself is not what looks wrong. Everything being three ticks
     * behind is invisible — there is nothing on screen to compare it against.
     * Everything being behind by DIFFERENT amounts is what reads as the
     * player being left behind, and this is the one number that fixes it.
     *
     * <p>Which is why this is NOT {@code DisplayLatency.glideTicks(1)}, the
     * rule the emote rig uses. That rule minimises lag against the world and
     * is right there; here the requirement is to match a rider exactly, and
     * being a tick tighter than them is the bug rather than an improvement.
     *
     * <p>If they still separate, {@link DisplayLatency#TRACKED_ENTITY_TICKS}
     * is the dial.
     */
    private static final int MODEL_GLIDE_TICKS = DisplayLatency.TRACKED_ENTITY_TICKS;

    /** How far a seated player is drawn above their own position. See {@code Seats}. */
    private static final double SEATED_POSE = 0.3;

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
     * <p>A Blockbench model — which is every model either front door produces
     * — is built facing {@code -z}, and a Minecraft entity at yaw 0 faces
     * {@code +z}. So the display is turned half a turn from the vehicle's own
     * heading, or the car drives backwards down the road.
     *
     * <p><strong>This is the one number here that was found in a client
     * rather than derived</strong>, and it is a named constant so that it is
     * the one thing to change if a model ever faces the wrong way again.
     * Nothing else depends on it: seat positions are in the vehicle's frame,
     * not the model's, so turning the art does not move anybody.
     */
    private static final float MODEL_YAW_OFFSET = 180;

    /** How far ahead a solid block stops the vehicle, in blocks. */
    private static final double NOSE = 0.6;

    /** A vehicle can climb this in one step, like a player. */
    private static final double STEP_UP = 1.0;

    /** How hard a vehicle shoves somebody out of its way, blocks per tick. */
    private static final double SHOVE = 0.35;

    /**
     * How far an occupied seat may drift before it is teleported back.
     *
     * <p><strong>This number is the whole high-speed fix, so it is worth
     * knowing what it trades.</strong> Teleporting a mount somebody is riding
     * sends that player a position packet they must acknowledge before the
     * server will accept their movement again. At twenty of those a second
     * the acknowledgements cannot keep up with the round trip, so corrections
     * queue and the rider falls further behind the faster the vehicle goes —
     * which is exactly what "the server can't keep up" looks like.
     *
     * <p>So an occupied mount is moved by VELOCITY, which costs the rider no
     * acknowledgement at all, and is teleported only when it has drifted
     * further than this. Half a block is under one tick of travel at 12
     * blocks a second, so an accurate mount is never teleported and a stuck
     * one is corrected immediately.
     *
     * <p>It degrades safely, which is the point: if velocity turns out not to
     * move a mount on some server, the drift exceeds this every tick and
     * every tick teleports — exactly the behaviour this replaced, no worse.
     */
    private static final double SEAT_DRIFT = 0.5;

    private final Plugin plugin;
    private final Items items;
    private final Logger log;
    private final VehicleControls controls;
    private final DisplayCarry carry;
    private final double mountOffset;

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

    /** Marks a chassis as ours, and says which vehicle it is. */
    private final NamespacedKey idKey;

    private volatile Map<ContentId, VehicleInfo> catalogue = Map.of();

    /** Chassis uuid -> what is going on with it. Only occupied vehicles are in here. */
    private final Map<UUID, Ride> live = new ConcurrentHashMap<>();

    /** Player uuid -> the chassis they are on, so a quit or a dismount can find it. */
    private final Map<UUID, UUID> riders = new ConcurrentHashMap<>();

    /** Said once, however many vehicles are stuck. */
    private volatile boolean warnedFrozen;

    /**
     * Added to every vehicle seat, from config.yml.
     *
     * <p>The same escape hatch {@code models.seat-offset} is for furniture, and
     * here for a better reason: a vehicle seat's height is arithmetic over a
     * vanilla attachment point that a plugin cannot read
     * ({@link MountOffset#SMALL_STAND_ATTACHMENT}), so it is derived rather
     * than measured and can be a little out on a version nobody has checked.
     * Rather than have an owner wait for a release to fix a rider sitting an
     * inch high, they nudge every seat on the server and type /rp reload.
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

    public Vehicles(Plugin plugin, Items items, Compatibility compatibility, RigCarrier rigs,
                    ai.resourcepack.engine.api.Emotes emotes) {
        this.emotes = emotes;
        this.plugin = plugin;
        this.items = items;
        this.log = plugin.getLogger();
        this.rigs = rigs;
        this.particles = new VehicleParticles(plugin.getLogger());
        this.controls = VehicleControls.forServer(compatibility, plugin.getLogger());
        this.carry = DisplayCarry.forServer(compatibility, MODEL_GLIDE_TICKS);
        // forVehicleSeat, not forServer: these mounts are small stands
        // rather than markers, and a small stand has an attachment point of
        // its own that the marker figure does not account for. Using the
        // marker one sat every rider about three quarters of a block high.
        this.mountOffset = MountOffset.forVehicleSeat(compatibility);
        this.tags = RigTags.forServer(compatibility, plugin);
        this.seatMover = PassengerTeleport.forServer();
        this.idKey = new NamespacedKey(plugin, "vehicle");
    }

    /** The control arm, so the plugin can register it and report it. */
    public VehicleControls controls() {
        return controls;
    }

    /**
     * Adopts {@code vehicles.seat-offset} and {@code vehicles.push-players}.
     * Called on enable and on every reload.
     */
    public void configure(double seatOffset, double seatForward, boolean pushPlayers) {
        this.seatOffset = seatOffset;
        this.seatForward = seatForward;
        this.pushPlayers = pushPlayers;
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
     * <p>Rebuilding ejects whoever is in one. That is the honest cost and it
     * beats the alternative: a passenger sitting in a seat that no longer
     * exists, on a vehicle whose handling numbers nobody can account for.
     */
    public void replace(Map<ContentId, VehicleInfo> loaded) {
        this.catalogue = loaded == null ? Map.of() : Map.copyOf(loaded);
        for (UUID chassisId : List.copyOf(live.keySet())) {
            Ride ride = live.remove(chassisId);
            if (ride == null) {
                continue;
            }
            ride.evictAll();
            ride.despawnParts();
        }
        // Not re-adopted here: adoptLoaded() runs straight after every call to
        // this, from the one place that knows the whole load finished. Doing it
        // twice would spawn a set of parts and immediately drop them.
    }

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
            stand.setSilent(true);
            // The one thing here that IS saved: a parked vehicle has to still
            // be there tomorrow. Everything hanging off it is rebuilt.
            stand.setPersistent(true);
        });
        chassis.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        return Optional.of(chassis);
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
            ride.evictAll();
            ride.despawnParts();
        }
        chassis.remove();
        return true;
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
    }

    private void sit(Player player, Ride ride, int index) {
        if (riders.containsKey(player.getUniqueId()) || player.isInsideVehicle()) {
            return;
        }
        if (ride.occupant(index) != null) {
            return;
        }
        Location at = ride.seatLocation(index);

        // The same event a chair and a seat bone fire, deliberately: a server
        // that refuses seating means all of them, and inventing a second
        // cancellable event would make "may this player sit here" two
        // questions with two answers.
        ModelSeatEvent asked = new ModelSeatEvent(player, at);
        plugin.getServer().getPluginManager().callEvent(asked);
        if (asked.isCancelled()) {
            return;
        }

        // Teleported before mounting, which is what aims them: a seat's yaw
        // says which way the occupant faces, and once they are a passenger
        // their look is their own. So this points a rear bench backwards as
        // somebody sits down and never fights them afterwards.
        player.teleport(at);
        if (!ride.mount(index, player)) {
            return;
        }
        riders.put(player.getUniqueId(), ride.chassisId);
        VehicleSeat seat = ride.info.seats().get(index);
        if (seat.isDriver()) {
            controls.take(player.getUniqueId());
        }
        // Said out loud, because who is driving was a mystery worth solving
        // from the outside: on a small vehicle the seat markers overlap, and
        // somebody who meant to drive and got a passenger seat had nothing to
        // tell them so. The controls come with it, since they differ by
        // server and nobody reads a startup report.
        Chat.send(player, seat.isDriver()
                ? "You are driving " + nameOf(ride.info) + ". " + controls.describe() + "."
                : "You are riding in " + nameOf(ride.info) + ", "
                        + seatName(ride.info, seat).toLowerCase(Locale.ROOT) + ".");
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
        owner.getServer().getPluginManager().registerEvent(
                (Class<? extends Event>) found, this, EventPriority.MONITOR,
                (listener, event) -> {
                    if (event instanceof EntityEvent
                            && ((EntityEvent) event).getEntity() instanceof Player) {
                        left((Player) ((EntityEvent) event).getEntity());
                    }
                },
                owner);
    }

    /** Somebody got off, or logged out. */
    private void left(Player player) {
        UUID chassis = riders.remove(player.getUniqueId());
        controls.forget(player.getUniqueId());
        if (chassis == null) {
            return;
        }
        Ride ride = live.get(chassis);
        if (ride != null) {
            ride.vacate(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        left(event.getPlayer());
    }

    // ------------------------------------------------------------------
    // The tick
    // ------------------------------------------------------------------

    /** Starts the driving loop. Called once, on enable. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /**
     * One tick of every vehicle somebody is in.
     *
     * <p>A parked vehicle is not ticked at all — it has no driver, so it has
     * nowhere to go, and a server with a hundred cars in a car park should pay
     * for none of them. That is also why the seats are only rebuilt when a
     * chunk loads: this loop never touches them.
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
    }

    /**
     * Says once that armour stands are not ticking, which is the one server
     * setting that stops all of this working.
     *
     * <p>Detected rather than read out of a config file, because the setting
     * is Paper's and the engine runs on Spigot too. What it looks like from
     * here is a stand that was given a velocity and did not move.
     */
    private void warnIfFrozen() {
        if (warnedFrozen) {
            return;
        }
        warnedFrozen = true;
        log.warning("A vehicle was told to move and did not. If this server sets "
                + "armor-stands-tick: false in paper-world-defaults.yml, that is why - "
                + "a stand that does not tick never applies the velocity it is given, "
                + "and every vehicle will sit still.");
    }

    /** Called when the plugin unloads. Takes every derived entity with it. */
    public void clear() {
        for (Ride ride : live.values()) {
            ride.evictAll();
            ride.despawnParts();
        }
        live.clear();
        riders.clear();
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

        /** Its own age in ticks, which is what a particle interval counts against. */
        private long age;

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
         * What the vehicle moved last tick, so a ridden mount can be aimed
         * where its seat WILL be rather than where it is.
         *
         * <p><strong>This is the whole of the rider sitting too far back.</strong>
         * Velocity is not a position: it is applied by the entity's own tick,
         * which runs after ours, so a mount told to cover the gap to its seat
         * arrives there at the end of the tick — by which time the vehicle has
         * moved on again. The rider therefore trails by exactly one tick of
         * travel, permanently, and the faster the vehicle the further back
         * they sit: at 12 blocks a second that is 0.6 of a block, which is
         * most of a seat.
         *
         * <p>The model has no such lag because it is teleported, which is why
         * the two visibly disagreed rather than both being late together.
         */
        private Vector carriedBy = new Vector();

        Ride(VehicleInfo info, Entity chassis) {
            this.info = info;
            this.chassisId = chassis.getUniqueId();
            this.world = chassis.getWorld();
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
                ArmorStand mount = world.spawn(seat, ArmorStand.class, stand -> {
                    // NOT a marker, and small. A marker is the right shape for
                    // a chair — no box, nothing to collide with — but a marker
                    // is also excluded from a great deal of vanilla's entity
                    // ticking, and an entity that does not tick never applies
                    // the velocity it is given. Velocity is what carries a
                    // rider at speed without a teleport per tick (see
                    // SEAT_DRIFT), so the mount has to be something that
                    // moves. Small keeps its box to a quarter block, which is
                    // the least that can snag on the bodywork around it.
                    stand.setMarker(false);
                    stand.setSmall(true);
                    stand.setVisible(false);
                    stand.setGravity(false);
                    // A non-marker stand has a bounding box, which is the price
                    // of it ticking — and an invisible quarter-block box that
                    // other players walk into is a bug, not a feature. Nothing
                    // should ever collide with a seat.
                    stand.setCollidable(false);
                    stand.setInvulnerable(true);
                    stand.setSilent(true);
                    stand.setPersistent(false);
                });
                mounts.set(i, mount.getUniqueId());

                Interaction hitbox = world.spawn(seat, Interaction.class, box -> {
                    box.setInteractionWidth(0.6f);
                    box.setInteractionHeight(0.8f);
                    box.setResponsive(true);
                    box.setPersistent(false);
                });
                hitboxes.set(i, hitbox.getUniqueId());
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
            }

            // An animated model first: a vehicle whose art moves is several
            // displays the animator retimes, and a still one is a single
            // display. Same branch, and for the same reason, as
            // ModelPlacementListener's — which is why it is a question about
            // the MODEL rather than about the vehicle.
            if (!spawnRig()) {
                art().ifPresent(this::spawnModel);
            }
        }

        /**
         * Spawns the animated model, if this vehicle has one.
         *
         * @return whether it did, so the caller knows not to spawn a still
         *         display as well — two models on one vehicle is one model
         *         and a ghost
         */
        private boolean spawnRig() {
            String id = artId();
            if (rigs == null || id == null || !rigs.animates(id)) {
                return false;
            }
            rig = rigs.carry(modelAnchor(), id, modelYaw(), carry, this::partStack).orElse(null);
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

        /** Where the model sits: half a block up, so its base is on the chassis. */
        private Location modelAnchor() {
            return at.clone().add(0, MODEL_LIFT, 0);
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
            });
            carry.carry(display);
            modelId = display.getUniqueId();
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
            }
        }

        private void removeEntity(UUID id) {
            if (id == null) {
                return;
            }
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

        boolean mount(int index, Player player) {
            Entity mount = plugin.getServer().getEntity(mounts.get(index));
            if (mount == null || !mount.addPassenger(player)) {
                return false;
            }
            occupants.set(index, player.getUniqueId());
            return true;
        }

        void vacate(UUID player) {
            for (int i = 0; i < occupants.size(); i++) {
                if (player.equals(occupants.get(i))) {
                    occupants.set(i, null);
                }
            }
            undress(player);
        }

        void evictAll() {
            for (int i = 0; i < occupants.size(); i++) {
                UUID id = occupants.get(i);
                if (id == null) {
                    continue;
                }
                Entity mount = plugin.getServer().getEntity(mounts.get(i));
                if (mount != null) {
                    mount.eject();
                }
                occupants.set(i, null);
                undress(id);
                riders.remove(id);
                controls.forget(id);
            }
        }

        /**
         * Where seat {@code index} is in the world, right now.
         *
         * <p>Side and forward, turned with the vehicle — the same reading
         * {@code place: seat:} uses, so a bench seats people along itself
         * however the vehicle is parked.
         */
        Location seatLocation(int index) {
            VehicleSeat seat = info.seats().get(index);
            double yaw = state.yaw();
            // The basis is VehiclePhysics' and is tested there. It was inline
            // here once, with `right` pointing left.
            double[] offset = VehiclePhysics.seatOffset(yaw, seat.x(), seat.z() + seatForward);
            double x = at.getX() + offset[0];
            double z = at.getZ() + offset[1];

            // SITTING puts the point under their backside, STANDING under
            // their feet. The pose is what decides that, which is why it is
            // not merely cosmetic. SEATED_POSE is the third of a block the
            // game draws a riding player's hips above their own position.
            double lift = (seat.pose() == VehicleSeat.Pose.SITTING
                    ? mountOffset - SEATED_POSE
                    : mountOffset) + seatOffset;

            Location location = new Location(world, x, at.getY() + seat.y() + lift, z);
            location.setYaw((float) VehiclePhysics.wrap360(yaw + seat.yaw()));
            return location;
        }

        /**
         * Where a seat's MOUNT goes, which is the seat without its rotation.
         *
         * <p>A passenger's body is dragged round by its vehicle's yaw, so a
         * mount that turned with the vehicle spun the rider's body every time
         * the car did — they could not look where they liked while being
         * carried. The mount therefore stays at yaw zero for ever and the
         * seat's own yaw is spent once, on the teleport that aims somebody as
         * they sit down. Which is all a seat yaw was ever meant to do; see
         * {@link VehicleSeat#yaw()}.
         */
        Location mountLocation(int index) {
            Location seat = seatLocation(index);
            seat.setYaw(0);
            seat.setPitch(0);
            return seat;
        }

        // --- moving ---------------------------------------------------

        void tick() {
            Entity chassis = chassis();
            if (chassis == null || !chassis.isValid()) {
                // The chunk unloaded, or somebody removed it. Drop everything
                // keyed to it — the 0.46.1 audit's other finding was two maps
                // that only ever grew because a chunk unload is not an
                // untrack.
                evictAll();
                despawnParts();
                live.remove(chassisId);
                return;
            }

            age++;
            Player driver = driver();
            VehiclePhysics.Demand demand = driver == null
                    ? VehiclePhysics.Demand.idle(state.yaw())
                    : controls.read(driver, info);

            VehiclePhysics.Surroundings around = surroundings();
            VehiclePhysics.Step step = VehiclePhysics.step(info, state, demand, around, DT);
            state = step.state();
            carriedBy = new Vector(step.dx(), step.dy(), step.dz());
            if (step.moves()) {
                apply(step);
                shoveAside(step);
            }
            place(chassis);

            // AFTER the move, so both read the position the vehicle actually
            // ended up at rather than the one it was asked to go to — a
            // vehicle stopped by a wall should not throw its exhaust inside
            // the wall.
            animate(step.states());
            dressOccupants(step.states());
            particles.emit(world, at, state.yaw(), info, step.states(), age);
            sayIfBeached(driver, around);
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
            for (int i = 0; i < occupants.size(); i++) {
                UUID id = occupants.get(i);
                VehicleSeat seat = info.seats().get(i);
                if (id == null || seat.animations().isEmpty()) {
                    continue;
                }
                Player player = plugin.getServer().getPlayer(id);
                if (player == null) {
                    continue;
                }
                // A state this seat left blank is the player's own body, NOT a
                // fall-through to the next state — see VehicleSeat.animations.
                // A fall-through would leave a driver hauling an imaginary
                // wheel round while the car sat still.
                String wanted = VehicleState.choose(states, seat.animations()).orElse(null);
                if (Objects.equals(wanted, worn.get(id))) {
                    continue;
                }
                // Recorded before the call rather than after, so a refusal —
                // this player is mid-emote of their own, the pack has no rig
                // for them — is not retried twenty times a second.
                worn.put(id, wanted);
                emotes.wear(player, wanted);
            }
        }

        /** Gives an occupant their own body back as they get out. */
        private void undress(UUID id) {
            String had = worn.remove(id);
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
         */
        private void animate(Set<VehicleState> states) {
            if (rig == null || info.animations().isEmpty()) {
                return;
            }
            String wanted = VehicleState.choose(states, info.animations()).orElse(null);
            if (Objects.equals(wanted, playing)) {
                return;
            }
            Optional<Placement> placement = rig.placement();
            if (placement.isEmpty()) {
                // The rig has been broken or its chunk went. Forgetting what
                // we thought was playing means the next tick that finds it
                // again starts cleanly rather than believing a stale answer.
                playing = null;
                return;
            }
            if (wanted == null) {
                placement.get().stop();
            } else if (!placement.get().play(wanted)) {
                // The model has no animation by that name — a pack naming one
                // that was since renamed in the editor. Left unrecorded so the
                // next genuine change is still tried, and silent because this
                // is a per-tick path.
                return;
            }
            playing = wanted;
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
        private void sayIfBeached(Player driver, VehiclePhysics.Surroundings around) {
            if (!VehiclePhysics.beached(info, around)) {
                toldBeached = false;
                return;
            }
            if (toldBeached || driver == null) {
                return;
            }
            toldBeached = true;
            Chat.send(driver, nameOf(info) + " is out of the water, so it can barely move. "
                    + "Steer back to the water.");
        }

        private Player driver() {
            UUID id = occupants.isEmpty() ? null : occupants.get(0);
            return id == null ? null : plugin.getServer().getPlayer(id);
        }

        /**
         * What the world is doing under the vehicle.
         *
         * <p>Three block reads, and no more: this runs for every occupied
         * vehicle every tick, and a ray trace here would be the most expensive
         * thing in the plugin.
         */
        private VehiclePhysics.Surroundings surroundings() {
            Block under = world.getBlockAt(at.clone().add(0, -0.15, 0));
            Block here = world.getBlockAt(at);
            boolean supported = under.getType().isSolid();
            boolean water = here.getType() == Material.WATER;
            if (!water) {
                return new VehiclePhysics.Surroundings(supported, false, 0);
            }
            // Fully under is a full block of push; otherwise the hull settles
            // with its base a little below the top of the block it is in,
            // which is what floating at the surface looks like.
            boolean deep = here.getRelative(0, 1, 0).getType() == Material.WATER;
            double submersion = deep ? 1 : Math.max(-1, Math.min(1, (here.getY() + 0.85) - at.getY()));
            return new VehiclePhysics.Surroundings(supported, true, submersion);
        }

        /** Commits a step, refusing whatever the world will not allow. */
        private void apply(VehiclePhysics.Step step) {
            VehicleHitbox box = info.hitbox();
            double nextY = at.getY() + step.dy();
            if (step.dy() < 0 && solidUnder(at.getX(), nextY, at.getZ(), box)) {
                nextY = Math.floor(nextY - 0.05) + 1;
                state = state.landed();
            }

            double nextX = at.getX() + step.dx();
            double nextZ = at.getZ() + step.dz();
            if (step.dx() != 0 || step.dz() != 0) {
                // The WHOLE FOOTPRINT at the destination, not one point a nose
                // length ahead. A point check is a vehicle whose bodywork goes
                // through a wall its centre line misses — which is most walls,
                // for anything wider than a block.
                if (blocked(nextX, nextY, nextZ, box, state.yaw())) {
                    boolean canStep = info.medium() == VehicleMedium.LAND
                            && !blocked(nextX, nextY + STEP_UP, nextZ, box, state.yaw());
                    if (canStep) {
                        nextY += STEP_UP;
                    } else {
                        // Stopped dead rather than sliding along the wall.
                        // Sliding is what a player expects and is a much
                        // bigger piece of work; stopping is honest and is what
                        // a vehicle hitting a building should do.
                        nextX = at.getX();
                        nextZ = at.getZ();
                        state = state.stopped();
                    }
                }
            }

            at = new Location(world, nextX, nextY, nextZ);
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
         */
        private boolean blocked(double x, double y, double z, VehicleHitbox box, double yaw) {
            double halfWidth = box.width() / 2;
            double halfLength = box.length() / 2;
            for (double dy = 0.2; dy < box.height(); dy += 1) {
                for (int corner = 0; corner < 5; corner++) {
                    double right = corner == 4 ? 0 : (corner < 2 ? -halfWidth : halfWidth);
                    double forward = corner == 4 ? 0 : (corner % 2 == 0 ? -halfLength : halfLength);
                    double[] offset = VehiclePhysics.seatOffset(yaw, right, forward);
                    if (world.getBlockAt(new Location(world, x + offset[0], y + dy, z + offset[1]))
                            .getType().isSolid()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Whether anything solid is under the footprint at {@code y}. */
        private boolean solidUnder(double x, double y, double z, VehicleHitbox box) {
            double halfWidth = box.width() / 2;
            double halfLength = box.length() / 2;
            for (int corner = 0; corner < 5; corner++) {
                double right = corner == 4 ? 0 : (corner < 2 ? -halfWidth : halfWidth);
                double forward = corner == 4 ? 0 : (corner % 2 == 0 ? -halfLength : halfLength);
                double[] offset = VehiclePhysics.seatOffset(state.yaw(), right, forward);
                if (world.getBlockAt(new Location(world, x + offset[0], y - 0.05, z + offset[1]))
                        .getType().isSolid()) {
                    return true;
                }
            }
            return false;
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
                // in, so it gets the vehicle's own.
                Vector push = away < 0.05
                        ? new Vector(step.dx(), 0, step.dz())
                        : new Vector(dx / away, 0, dz / away).multiply(SHOVE);
                nearby.setVelocity(nearby.getVelocity().add(push.add(new Vector(step.dx(), 0, step.dz()))));
            }
        }

        /**
         * Pushes every entity at where it should be.
         *
         * <p>Velocity for anything with a passenger, teleport for anything
         * without — see the class note. Both are corrections toward the
         * authoritative position rather than blind deltas, so an entity that
         * missed a tick catches up rather than falling permanently behind.
         */
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
                Location target = mountLocation(i);
                if (occupants.get(i) == null) {
                    // Nobody to send a packet to, so exactness is free.
                    mount.teleport(target);
                    continue;
                }
                // Somebody is on it. Velocity first — see SEAT_DRIFT — and a
                // teleport only to correct real drift, because every teleport
                // costs the rider a round trip they have to acknowledge.
                //
                // Aimed one tick AHEAD of the seat, because velocity is applied
                // by the mount's own tick after this one: aiming at where the
                // seat is now lands the rider there just as the vehicle leaves,
                // which is the constant backwards offset. See carriedBy.
                Vector wanted = target.toVector().add(carriedBy)
                        .subtract(mount.getLocation().toVector());
                if (wanted.lengthSquared() > SEAT_DRIFT * SEAT_DRIFT) {
                    if (!seatMover.move(mount, target)) {
                        mount.setVelocity(wanted);
                    }
                } else {
                    mount.setVelocity(wanted);
                }
                Entity hitbox = plugin.getServer().getEntity(hitboxes.get(i));
                if (hitbox != null) {
                    hitbox.teleport(target);
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
                // host and turns the whole thing; see RigCarrier.
                rig.moveTo(modelAnchor(), modelYaw());
            }

            Entity model = modelId == null ? null : plugin.getServer().getEntity(modelId);
            if (model != null) {
                Location where = modelAnchor();
                where.setYaw(modelYaw());
                // Stated rather than inherited. `at` carries no pitch now, but
                // a display that ever acquires one is a vehicle lying on its
                // side, and this is the line that makes that impossible.
                where.setPitch(0);
                model.teleport(where);
            }

            // The stuck check: asked to move, and the chassis did not. Three
            // ticks rather than one, because a single tick can legitimately
            // round to nothing. A teleport that does not arrive is a much
            // rarer thing than the velocity this used to watch, so this is now
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

    /** What to call a vehicle in a message. */
    public String nameOf(VehicleInfo info) {
        return info.name()
                .map(name -> ChatColor.translateAlternateColorCodes('&', name))
                .orElseGet(() -> info.id().toString());
    }
}
