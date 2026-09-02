package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.event.ModelSeatEvent;
import ai.resourcepack.engine.core.Chat;
import ai.resourcepack.engine.core.model.DisplayCarry;
import ai.resourcepack.engine.core.model.MountOffset;
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
import java.util.Optional;
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
     * <p>One tick would be exact and stutters whenever a tick is late; three
     * is visibly behind. Two is one tick of lead, which is what keeps a
     * vehicle looking smooth on a server that is not perfectly on time.
     */
    private static final int MODEL_GLIDE_TICKS = 2;

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

    private final Plugin plugin;
    private final Items items;
    private final Logger log;
    private final VehicleControls controls;
    private final DisplayCarry carry;
    private final double mountOffset;

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

    public Vehicles(Plugin plugin, Items items, Compatibility compatibility) {
        this.plugin = plugin;
        this.items = items;
        this.log = plugin.getLogger();
        this.controls = VehicleControls.forServer(compatibility);
        this.carry = DisplayCarry.forServer(compatibility, MODEL_GLIDE_TICKS);
        this.mountOffset = MountOffset.forServer(compatibility);
        this.tags = RigTags.forServer(compatibility, plugin);
        this.seatMover = PassengerTeleport.forServer();
        this.idKey = new NamespacedKey(plugin, "vehicle");
    }

    /** The control arm, so the plugin can register it and report it. */
    public VehicleControls controls() {
        return controls;
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, VehicleInfo> loaded) {
        this.catalogue = loaded == null ? Map.of() : Map.copyOf(loaded);
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

        /** How many ticks in a row a move was asked for and nothing happened. */
        private int stuck;

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
                    stand.setMarker(true);
                    stand.setVisible(false);
                    stand.setGravity(false);
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

            art().ifPresent(this::spawnModel);
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
            double[] offset = VehiclePhysics.seatOffset(yaw, seat.x(), seat.z());
            double x = at.getX() + offset[0];
            double z = at.getZ() + offset[1];

            // SITTING puts the point under their backside, STANDING under
            // their feet. The pose is what decides that, which is why it is
            // not merely cosmetic. SEATED_POSE is the third of a block the
            // game draws a riding player's hips above their own position.
            double lift = seat.pose() == VehicleSeat.Pose.SITTING
                    ? mountOffset - SEATED_POSE
                    : mountOffset;

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

            Player driver = driver();
            VehiclePhysics.Demand demand = driver == null
                    ? VehiclePhysics.Demand.idle(state.yaw())
                    : controls.read(driver, info);

            VehiclePhysics.Step step = VehiclePhysics.step(info, state, demand, surroundings(), DT);
            state = step.state();
            if (step.moves()) {
                apply(step);
            }
            place(chassis);
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
            double nextY = at.getY() + step.dy();
            if (step.dy() < 0) {
                Block landing = world.getBlockAt(new Location(world, at.getX(), nextY - 0.05, at.getZ()));
                if (landing.getType().isSolid()) {
                    nextY = landing.getY() + 1;
                    state = state.landed();
                }
            }

            double nextX = at.getX() + step.dx();
            double nextZ = at.getZ() + step.dz();
            if (step.dx() != 0 || step.dz() != 0) {
                double length = Math.hypot(step.dx(), step.dz());
                // A nose length ahead of where it will be, so a vehicle stops
                // at a wall rather than with its bonnet inside it.
                double aheadX = nextX + step.dx() / length * NOSE;
                double aheadZ = nextZ + step.dz() / length * NOSE;
                Block ahead = world.getBlockAt(new Location(world, aheadX, nextY + 0.1, aheadZ));
                if (ahead.getType().isSolid()) {
                    boolean canStep = info.medium() == VehicleMedium.LAND
                            && !ahead.getRelative(0, 1, 0).getType().isSolid();
                    if (canStep) {
                        nextY = Math.max(nextY, ahead.getY() + STEP_UP);
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
                    mount.teleport(target);
                } else if (!seatMover.move(mount, target)) {
                    // Neither teleport arm worked on this server. Lossy, and
                    // the rider will trail — but trailing beats standing still.
                    mount.setVelocity(target.toVector().subtract(mount.getLocation().toVector()));
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

            Entity model = modelId == null ? null : plugin.getServer().getEntity(modelId);
            if (model != null) {
                Location where = at.clone().add(0, MODEL_LIFT, 0);
                where.setYaw((float) state.yaw() + MODEL_YAW_OFFSET);
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
