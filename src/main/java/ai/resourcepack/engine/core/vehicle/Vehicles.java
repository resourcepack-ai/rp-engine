package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.EmoteResult;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.Placement;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import ai.resourcepack.engine.api.event.ModelSeatEvent;
import ai.resourcepack.engine.core.Chat;
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
public final class Vehicles implements Listener {

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

    /**
     * How far an OCCUPANT'S RIG is turned from the way their seat points.
     *
     * <p>The same half turn as {@link #MODEL_YAW_OFFSET} and, like it, found in
     * a client rather than derived — a rig faced backwards down the road until
     * it was put in. Kept as its own constant precisely because it is a
     * different fact about a different piece of art: that one is the vehicle's
     * bodywork, this is the baked player rig, and the day one of them is
     * rebuilt facing the other way the other must not move with it.
     *
     * <p>It is needed at all because the value handed over is a real heading —
     * {@code state.yaw() + seat.yaw()} is the same number the teleport that
     * aims somebody as they sit down uses, and that one is correct without any
     * offset. A player and a rig read a yaw differently, and this is the whole
     * of the difference.
     */
    private static final float SEAT_RIG_YAW_OFFSET = 180;

    /** How far ahead a solid block stops the vehicle, in blocks. */
    private static final double NOSE = 0.6;

    /** A vehicle can climb this in one step, like a player. */
    private static final double STEP_UP = 1.0;

    /** How hard a vehicle shoves somebody out of its way, blocks per tick. */
    private static final double SHOVE = 0.35;

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
        this.displayMountOffset = MountOffset.forServer(compatibility);
        this.standMountOffset = MountOffset.forVehicleSeat(compatibility);
        this.tags = RigTags.forServer(compatibility, plugin);
        this.seatMover = PassengerTeleport.forServer();
        this.concealed = new HiddenRiders(plugin);
        this.idKey = new NamespacedKey(plugin, "vehicle");
    }

    /** The control arm, so the plugin can register it and report it. */
    public VehicleControls controls() {
        return controls;
    }

    /**
     * Adopts {@code vehicles.seat-offset}, {@code vehicles.push-players} and
     * {@code vehicles.seat-rig}. Called on enable and on every reload.
     */
    public void configure(double seatOffset, double seatForward, boolean pushPlayers,
                          boolean seatRig) {
        this.seatOffset = seatOffset;
        this.seatForward = seatForward;
        this.pushPlayers = pushPlayers;
        this.seatRig = seatRig;
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
        reseat.clear();
        for (UUID chassisId : List.copyOf(live.keySet())) {
            Ride ride = live.remove(chassisId);
            if (ride == null) {
                continue;
            }
            reseat.put(chassisId, ride.seating());
            ride.evictAll();
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
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return;
        }
        List<String> strings = tags.read(item);
        if (strings.isEmpty() || strings.get(0) == null) {
            return;
        }
        String carrier = strings.get(0);
        VehicleInfo info = null;
        for (VehicleInfo candidate : catalogue.values()) {
            if (candidate.carrier().filter(carrier::equals).isPresent()) {
                info = candidate;
                break;
            }
        }
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

    private void sit(Player player, Ride ride, int index) {
        sit(player, ride, index, false);
    }

    /**
     * @param quiet whether to skip the line that says which seat this is —
     *              true when the engine is putting somebody back where they
     *              already were, which is not news
     */
    private void sit(Player player, Ride ride, int index, boolean quiet) {
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
        if (!quiet) {
            overhead(player, seat.isDriver()
                    ? "Driving " + nameOf(ride.info) + " - " + controls.describe()
                    : "Riding in " + nameOf(ride.info) + " - "
                            + seatName(ride.info, seat).toLowerCase(Locale.ROOT));
        }
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
            ride.vacate(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        left(event.getPlayer());
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
            ride.evictAll();
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
            Entity old = plugin.getServer().getEntity(mounts.get(index));
            Entity mount = spawnMount(index);
            mounts.set(index, mount.getUniqueId());
            Player player = plugin.getServer().getPlayer(occupant);
            reseating = occupant;
            try {
                if (old != null) {
                    old.remove();
                }
                if (player != null && !mount.addPassenger(player)) {
                    occupants.set(index, null);
                    riders.remove(occupant);
                    controls.forget(occupant);
                    undress(occupant);
                }
            } finally {
                reseating = null;
            }
            return mount;
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
            double[] offset = VehiclePhysics.seatOffset(yaw, seat.x(), seat.z() + seatForward);
            double drop = seat.pose() == VehicleSeat.Pose.SITTING ? SEATED_POSE : 0;
            Location location = new Location(world,
                    at.getX() + offset[0],
                    at.getY() + seat.y() - drop + seatOffset,
                    at.getZ() + offset[1]);
            location.setYaw((float) VehiclePhysics.wrap360(yaw + seat.yaw()));
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

            // Nobody in it and going nowhere: the art keeps running and
            // nothing else does. See PARKED_POLL_TICKS — both calls below
            // return immediately for a vehicle with no rig and no emitters,
            // which is most of them, so a parked car park really does cost
            // nothing.
            if (parked && age % PARKED_POLL_TICKS != 0) {
                animate(parkedStates);
                particles.emit(world, at, state.yaw(), info, parkedStates, age);
                return;
            }

            Player driver = driver();
            VehiclePhysics.Demand demand = driver == null
                    ? VehiclePhysics.Demand.idle(state.yaw())
                    : controls.read(driver, info);

            VehiclePhysics.Surroundings around = surroundings();
            VehiclePhysics.Step step = VehiclePhysics.step(info, state, demand, around, DT);
            state = step.state();
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

            // Occupied is never parked, whether or not it is moving: a rider's
            // rig is aimed every tick, and half a second of a driver's body
            // pointing where the vehicle used to point is a visible thing.
            parked = !step.moves() && empty();
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
                // Plus the half turn a rig reads a yaw by — see
                // SEAT_RIG_YAW_OFFSET. Without it the rider faced backwards
                // down the road, which is the same symptom the bodywork had.
                emotes.face(player, (float) VehiclePhysics.wrap360(
                        this.state.yaw() + seat.yaw() + SEAT_RIG_YAW_OFFSET));
                // Resolved per SEAT, because the answer depends on what this
                // seat mapped: turning outranks travelling now, and a seat that
                // maps `moving` and not `turning` must not lose its occupant's
                // pose on every corner. See VehicleState.forSeat.
                VehicleState state = VehicleState.forSeat(states, seat.animations()).orElse(null);
                String named = state == null ? null : seat.animations().get(state);
                String wanted = named != null ? named : fallbackStance(seat);
                // A rig that was ON and is not any more is re-asked whatever
                // the memo says: something outside this vehicle took it off,
                // and the memo cannot see that. Only somebody we actually
                // dressed is ever re-asked, which is what stops a permanent
                // refusal becoming a lookup per tick — see `dressed`.
                boolean lost = dressed.contains(id) && !emotes.isEmoting(id);
                if (Objects.equals(wanted, worn.get(id)) && !lost) {
                    continue;
                }
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
         */
        private void animate(Set<VehicleState> states) {
            if (rig == null || info.animations().isEmpty()) {
                return;
            }
            String wanted = VehicleState.choose(states, info.animations()).orElse(null);
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

            if (!Objects.equals(wanted, playing)) {
                playing = wanted;
                if (wanted == null) {
                    placement.stop();
                    playable = false;
                } else {
                    // Restarted rather than resumed: a state change is a cut,
                    // and joining a cycle halfway through because the last
                    // state happened to have run for two seconds is a limp.
                    //
                    // The answer is recorded even when it FAILS — a pack naming
                    // an animation the model no longer has — because this runs
                    // twenty times a second and retrying a name that cannot
                    // work is a lookup per tick for ever.
                    playable = placement.play(wanted, true);
                }
                return;
            }

            // <strong>Nothing changed, and that is when this matters.</strong>
            // A vehicle state is a condition that HOLDS, so what it names has
            // to run for as long as it holds — but an animation authored as a
            // one-shot runs out after its own length and hands the rig back to
            // the model's idle loop. With one animation mapped to every state
            // that is a rowing cycle which plays once on spawn and never again,
            // because no state change ever comes along to restart it.
            //
            // So it is re-asked the moment it has fallen idle, which loops it
            // whatever its own end mode says. `playing()` is what the placement
            // is actually doing rather than what it was last told, so a HOLD or
            // a genuine loop never trips this.
            if (playable && wanted != null && placement.playing().isEmpty()) {
                placement.play(wanted, true);
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
        private void sayIfBeached(Player driver, VehiclePhysics.Surroundings around) {
            if (!VehiclePhysics.beached(info, around)) {
                toldBeached = false;
                return;
            }
            if (toldBeached || driver == null) {
                return;
            }
            toldBeached = true;
            // Overhead, like the seat line, and for the same reason: this is a
            // condition somebody is in for a few seconds, not a fact worth a
            // permanent line in their chat log. Beaching a hull and refloating
            // it half a dozen times while placing a dock is six lines of chat
            // for something the boat itself is already showing you.
            overhead(driver, nameOf(info) + " is out of the water - steer back in");
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
                }

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

    /** What to call a vehicle in a message. */
    public String nameOf(VehicleInfo info) {
        return info.name()
                .map(name -> ChatColor.translateAlternateColorCodes('&', name))
                .orElseGet(() -> info.id().toString());
    }
}
