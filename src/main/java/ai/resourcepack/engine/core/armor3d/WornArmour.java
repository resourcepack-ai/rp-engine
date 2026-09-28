package ai.resourcepack.engine.core.armor3d;

import ai.resourcepack.engine.core.model.DisplayCarry;
import ai.resourcepack.engine.core.model.DisplayLatency;
import ai.resourcepack.engine.core.version.Compatibility;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 3D armour on the people wearing it.
 *
 * <p>Every tick: who has a piece on, and a display per part of it standing at
 * their feet and posed onto the limb it belongs to. The helmet is not here —
 * the game draws it (see {@link Armor3dSet}).
 *
 * <h2>Two copies, because there are two audiences with opposite clocks</h2>
 *
 * <p><b>Everybody else</b> sees the player at positions the server relayed,
 * lerped over three ticks. The SHARED copy is teleported to the same positions
 * and given the same three ticks to glide over
 * ({@link DisplayLatency#TRACKED_ENTITY_TICKS}): behind by the same amount is
 * together. Its pose is sampled now, sent every {@link #POSE_PERIOD} ticks and
 * tweened over exactly that — a longer tween restarted every tick low-passes the
 * walk cycle and a quarter of every arm swing disappears.
 *
 * <p><b>The wearer</b> sees their own body where their client PREDICTS it,
 * the instant they press a key. Anything the server moves reaches them a whole
 * round trip later, plus its glide: at walking pace that was most of a block
 * behind, which is the lag people saw in F5. So the wearer is never shown the
 * shared copy. When they ask to see their own ({@code /rp armor self}) they get
 * a second, OWN copy that only they can see, placed and posed as far AHEAD as
 * their ping says they are — position extrapolated along their velocity, the
 * stride advanced by its speed, the body turn run forward — so it arrives where
 * their body is by then. Starting, stopping and turning are where a prediction
 * is wrong, and it is wrong there for about a round trip and then settles.
 *
 * <p><b>Why not passengers of the player</b>, which a client would carry at
 * exactly its own drawn position with no lag at all: Paper refuses to teleport
 * a player who has passengers to another world, before any event a plugin could
 * answer, so every cross-world {@code /spawn} and portal-plugin warp would fail
 * for anybody wearing a chestplate. Spigot refuses same-world teleports too.
 *
 * <p>The own copy is off by default, for {@code hideFromWearer}'s reason: in
 * first person the game draws none of your body, so it would be a torso of
 * armour under the camera and nothing else.
 */
public final class WornArmour implements Listener {

    /** How often a pose is sent, and how long each one is tweened over. See the class note. */
    static final int POSE_PERIOD = 2;

    /** How often equipment is re-read. A piece put on appears within this. */
    private static final int EQUIPMENT_PERIOD = 4;

    /** How often who-can-see-whom is re-checked. */
    private static final int VISIBILITY_PERIOD = 10;

    /** Ticks a swing takes; {@code LivingEntity.getCurrentSwingDuration} with no haste. */
    private static final float SWING_TICKS = 6f;

    /**
     * How far ahead the SHARED copy samples a swing, in ticks.
     *
     * <p>A watching client starts the swing the moment the packet lands;
     * everything this sends arrives a period late. {@code ArmSwing.progress}'s
     * {@code +1}, for the same reason.
     */
    private static final float SWING_LEAD = POSE_PERIOD;

    /** How long the own copy glides over a move. Short: it is led, not trailed. */
    private static final int OWN_GLIDE_TICKS = 2;

    /** The most the own copy is ever placed ahead of the body, in blocks. */
    private static final double MAX_LEAD = 1.5;

    /** The most a round trip is believed, in ticks — a lag spike is not a velocity. */
    private static final double MAX_PING_TICKS = 8;

    /** How much of the way to the new lead each tick moves: {@code EmoteStance.LEAD_SMOOTHING}. */
    private static final double LEAD_SMOOTHING = 0.5;

    /** Past this in one tick, a move is a teleport and is not glided or led. */
    private static final double SNAP_DISTANCE = 8;

    /** The culling box a display is given: a player and their reach, from the feet up. */
    private static final float CULL_WIDTH = 2.5f;
    private static final float CULL_HEIGHT = 2.5f;

    private final Plugin plugin;
    private final Armor3dItems items;
    private final Supplier<Map<String, Armor3dSet>> sets;
    private final DisplayCarry glide;
    private final DisplayCarry ownGlide;
    private final DisplayCarry snap;
    private final boolean available;

    /** A player's client protocol — see {@link Armor3dSet#drawnBy}. */
    private final java.util.function.ToIntFunction<Player> protocolOf;
    /** Looked up once per session: a connection does not change version. */
    private final Map<UUID, Integer> protocols = new HashMap<>();

    private final Map<UUID, Wearer> wearers = new HashMap<>();
    /** Who asked to see their own. Session-only, and kept when they take it off and put it back on. */
    private final Set<UUID> showingSelf = new HashSet<>();
    private long tick;
    private int taskId = -1;

    public WornArmour(Plugin plugin, Compatibility compatibility, Armor3dItems items,
                      Supplier<Map<String, Armor3dSet>> sets, boolean available,
                      java.util.function.ToIntFunction<Player> protocolOf) {
        this.plugin = plugin;
        this.protocolOf = protocolOf;
        this.items = items;
        this.sets = sets;
        this.glide = DisplayCarry.forServer(compatibility, DisplayLatency.TRACKED_ENTITY_TICKS);
        this.ownGlide = DisplayCarry.forServer(compatibility, OWN_GLIDE_TICKS);
        this.snap = DisplayCarry.forServer(compatibility, 0);
        this.available = available;
    }

    public void start() {
        if (!available || taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1).getTaskId();
    }

    /** Takes every display down. Nothing here is persistent, so this is tidiness, not safety. */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        for (Wearer wearer : wearers.values()) {
            wearer.clear();
        }
        wearers.clear();
    }

    /**
     * Toggles whether {@code player} sees their own 3D armour. The next tick
     * builds or takes down their own copy.
     *
     * @return whether they now do
     */
    public boolean toggleSelf(Player player) {
        UUID id = player.getUniqueId();
        return showingSelf.add(id) || !showingSelf.remove(id);
    }

    public boolean showsSelf(Player player) {
        return showingSelf.contains(player.getUniqueId());
    }

    /** Whether {@code player}'s own client draws {@code set} — see {@link Armor3dSet#drawnBy}. */
    public boolean drawsItself(Player player, Armor3dSet set) {
        return set.drawnBy(protocol(player));
    }

    // ---- the loop -------------------------------------------------------------

    private void tick() {
        tick++;
        Map<String, Armor3dSet> known = sets.get();
        boolean readEquipment = tick % EQUIPMENT_PERIOD == 0;
        boolean pose = tick % POSE_PERIOD == 0;
        boolean visibility = tick % VISIBILITY_PERIOD == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Wearer wearer = wearers.get(player.getUniqueId());
            if (readEquipment) {
                Map<String, Placed> wanted = wanted(player, known);
                if (wanted.isEmpty()) {
                    if (wearer != null) {
                        wearer.clear();
                        wearers.remove(player.getUniqueId());
                    }
                    continue;
                }
                if (wearer == null) {
                    wearer = new Wearer(player);
                    wearers.put(player.getUniqueId(), wearer);
                }
                wearer.wanted = wanted;
            }
            if (wearer == null) {
                continue;
            }
            wearer.step(player);
            wearer.shared.sync(player, wearer.wanted, feet(player), false);
            // Their own copy is only the parts their client does not already
            // draw: a set their shaders draw is on their body with no lag at
            // all, and nothing here could improve on that.
            Map<String, Placed> ownWanted = new LinkedHashMap<>();
            int ownProtocol = protocol(player);
            for (Map.Entry<String, Placed> entry : wearer.wanted.entrySet()) {
                if (!entry.getValue().set().drawnBy(ownProtocol)) {
                    ownWanted.put(entry.getKey(), entry.getValue());
                }
            }
            boolean own = showingSelf.contains(player.getUniqueId()) && !ownWanted.isEmpty();
            if (own) {
                if (wearer.own == null) {
                    wearer.own = new Copy(true);
                }
                wearer.own.sync(player, ownWanted, feet(player).add(wearer.lead), wearer.jumped);
            } else if (wearer.own != null) {
                wearer.own.clear();
                wearer.own = null;
            }
            if (pose || wearer.poseNow) {
                wearer.pose(player);
            }
            if (visibility) {
                wearer.mirrorVisibility(player);
            }
        }
        // Gone without a quit event: a reload, a kick mid-tick.
        if (readEquipment) {
            wearers.entrySet().removeIf(entry -> {
                Player online = Bukkit.getPlayer(entry.getKey());
                if (online != null && online.isOnline()) {
                    return false;
                }
                entry.getValue().clear();
                return true;
            });
        }
    }

    /**
     * Every part {@code player} should be wearing right now, keyed so the same
     * part keeps the same display from one read to the next.
     *
     * <p>Empty for anybody dead or in spectator: the game draws neither of them
     * with armour on.
     */
    private Map<String, Placed> wanted(Player player, Map<String, Armor3dSet> known) {
        if (known.isEmpty() || player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return Map.of();
        }
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return Map.of();
        }
        Map<String, Placed> wanted = new LinkedHashMap<>();
        for (Armor3dSet.Piece piece : Armor3dSet.Piece.values()) {
            ItemStack stack = equipment.getItem(piece.slot());
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            items.read(stack)
                    // Only in its own slot. A chestplate forced onto a head by
                    // command is a paper hat, not a chestplate.
                    .filter(worn -> worn.piece() == piece)
                    .map(worn -> known.get(worn.setId()))
                    .flatMap(set -> set.piece(piece).map(worn -> Map.entry(set, worn)))
                    .ifPresent(entry -> {
                        List<Armor3dSet.Part> parts = entry.getValue().parts();
                        for (int i = 0; i < parts.size(); i++) {
                            wanted.put(entry.getKey().id() + "/" + piece.wire() + "/" + i,
                                    new Placed(entry.getKey(), parts.get(i)));
                        }
                    });
        }
        return wanted;
    }

    // ---- events ---------------------------------------------------------------

    /**
     * A swing, posed in the same call.
     *
     * <p>Not on the next tick: a poll finds a swing up to a tick late, and the
     * whole swing is six. {@code ArmSwing} makes the same argument for a rig.
     * The off hand is told apart by name, because the constant for it is newer
     * than this engine's floor.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        Wearer wearer = wearers.get(event.getPlayer().getUniqueId());
        if (wearer == null) {
            return;
        }
        String type = event.getAnimationType().name();
        boolean offHand = type.equals("OFF_ARM_SWING");
        if (!offHand && !type.equals("ARM_SWING")) {
            return;
        }
        boolean mainLeft = event.getPlayer().getMainHand() == MainHand.LEFT;
        wearer.swingStart = tick;
        wearer.swingLeft = mainLeft != offHand;
        wearer.pose(event.getPlayer());
    }

    /**
     * Somebody new: nobody's own copy is theirs to see. A display is visible
     * by default, so the hide made when it spawned covered only the players
     * online then.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joiner = event.getPlayer();
        for (Wearer wearer : wearers.values()) {
            if (wearer.id.equals(joiner.getUniqueId())) {
                continue;
            }
            if (wearer.own != null) {
                for (ItemDisplay display : wearer.own.displays.values()) {
                    joiner.hideEntity(plugin, display);
                }
            }
            // And the shared copy by the ordinary rule, now rather than at the
            // next visibility pass: a joiner whose client draws the set must
            // not see a second suit for half a second.
            Player worn = Bukkit.getPlayer(wearer.id);
            if (worn != null) {
                for (Map.Entry<String, ItemDisplay> entry : wearer.shared.displays.entrySet()) {
                    Placed placed = wearer.shared.shown.get(entry.getKey());
                    if (placed != null) {
                        wearer.apply(joiner, worn, entry.getValue(), placed.set());
                    }
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Wearer wearer = wearers.remove(event.getPlayer().getUniqueId());
        if (wearer != null) {
            wearer.clear();
        }
        showingSelf.remove(event.getPlayer().getUniqueId());
        protocols.remove(event.getPlayer().getUniqueId());
    }

    // ---- one wearer -----------------------------------------------------------

    private final class Wearer {

        final UUID id;
        Map<String, Placed> wanted = Map.of();
        /** What everybody else sees. Never shown to the wearer. */
        final Copy shared = new Copy(false);
        /** What the wearer sees of themselves, when they asked to. Only ever shown to them. */
        Copy own;
        /** "viewer|display" for every shared display currently hidden from a viewer. */
        final Set<String> hidden = new HashSet<>();

        /** {@code {position, speed}} — see {@link WornPose#walk}. */
        final float[] walk = new float[2];
        float bodyYaw;
        Location last;
        /** Last tick's movement, which the own copy's body turn is run forward on. */
        double dx;
        double dz;
        /** How far ahead of the body the own copy stands, blocks — see the class note. */
        Vector lead = new Vector();
        /** Whether this tick's move was a teleport, which nothing may glide across. */
        boolean jumped = true;
        long swingStart = Long.MIN_VALUE / 2;
        boolean swingLeft;
        boolean poseNow = true;

        Wearer(Player player) {
            this.id = player.getUniqueId();
            this.bodyYaw = player.getLocation().getYaw();
        }

        /** The client's own bookkeeping for this player, one tick on. */
        void step(Player player) {
            Location at = player.getLocation();
            jumped = last == null || last.getWorld() != at.getWorld()
                    || last.distanceSquared(at) > SNAP_DISTANCE * SNAP_DISTANCE;
            Vector moved = jumped ? new Vector() : at.toVector().subtract(last.toVector());
            last = at;
            dx = moved.getX();
            dz = moved.getZ();
            if (jumped) {
                // Teleported: no stride, the body faces where they look, and
                // nothing is predicted across the jump.
                bodyYaw = at.getYaw();
                lead = new Vector();
            }
            Entity vehicle = player.getVehicle();
            if (vehicle != null) {
                // A rider's legs belong to the seat and their body to what they
                // sit in: a boat or a horse sets both on the client.
                WornPose.walk(walk, 0);
                bodyYaw = vehicle instanceof Boat || vehicle instanceof LivingEntity
                        ? vehicle.getLocation().getYaw()
                        : WornPose.bodyYaw(bodyYaw, at.getYaw(), 0, 0, swinging(0));
                dx = 0;
                dz = 0;
            } else {
                WornPose.walk(walk, Math.sqrt(dx * dx + dz * dz));
                bodyYaw = WornPose.bodyYaw(bodyYaw, at.getYaw(), dx, dz, swinging(0));
            }
            if (!jumped) {
                // The own copy's lead: where this body will be one round trip
                // and one glide from now, chased so a single late movement
                // packet is not a lunge. EmoteStance.leadFor's shape.
                Vector wanted = moved.clone().multiply(ownLeadTicks(player));
                if (wanted.length() > MAX_LEAD) {
                    wanted.multiply(MAX_LEAD / wanted.length());
                }
                lead.add(wanted.subtract(lead).multiply(LEAD_SMOOTHING));
                if (lead.lengthSquared() < 1e-4) {
                    lead = new Vector();
                }
            }
        }

        /** Whether an arm is mid-swing {@code ahead} ticks from now. */
        boolean swinging(double ahead) {
            return tick + ahead - swingStart < SWING_TICKS;
        }

        /**
         * How far ahead the own copy is placed, in ticks: the round trip, plus
         * the glide it is given, less the half tick a frame is interpolated
         * behind. {@code getPing} is the round trip already.
         */
        double ownLeadTicks(Player player) {
            return Math.min(MAX_PING_TICKS, Math.max(0, player.getPing()) / 50.0) + OWN_GLIDE_TICKS - 0.5;
        }

        /** Sends every part's pose, to both copies. */
        void pose(Player player) {
            poseNow = false;
            org.bukkit.entity.Pose stance = player.getPose();
            // The poses the game turns the whole body for, and the armour is
            // simply put away for: swimming, crawling, gliding, sleeping, a
            // riptide spin. Chasing them is a second model of the renderer for
            // moments nobody stands still in.
            boolean hidden = stance != Pose.STANDING && stance != Pose.SNEAKING;
            shared.pose(player, state(player, stance, 0, SWING_LEAD), bodyYaw, hidden);
            if (own != null) {
                double ahead = Math.min(MAX_PING_TICKS, Math.max(0, player.getPing()) / 50.0) + POSE_PERIOD;
                // The body turn, run forward on the movement it is making now:
                // the wearer's client has already turned it.
                float yaw = bodyYaw;
                if (player.getVehicle() == null) {
                    for (int i = 0; i < Math.round(ahead); i++) {
                        yaw = WornPose.bodyYaw(yaw, player.getLocation().getYaw(), dx, dz, swinging(i));
                    }
                }
                own.pose(player, state(player, stance, (float) ahead, (float) ahead), yaw, hidden);
            }
        }

        /**
         * The pose inputs, sampled {@code ahead} ticks in the future — the
         * stride advanced at its current speed — with a swing sampled
         * {@code swingAhead} ticks on.
         */
        private WornPose.State state(Player player, org.bukkit.entity.Pose stance, float ahead, float swingAhead) {
            WornPose.State state = new WornPose.State();
            state.walkPosition = walk[0] + walk[1] * ahead;
            state.walkSpeed = walk[1];
            state.age = player.getTicksLived() + ahead;
            state.headYaw = WornPose.wrap(player.getLocation().getYaw() - bodyYaw);
            state.headPitch = player.getLocation().getPitch();
            state.crouching = stance == Pose.SNEAKING;
            state.riding = player.getVehicle() != null;
            state.leftHanded = player.getMainHand() == MainHand.LEFT;
            armPoses(player, state);
            float swing = (tick - swingStart + swingAhead) / SWING_TICKS;
            if (swing > 0f && swing < 1f) {
                state.attackTime = swing;
                state.attackLeft = swingLeft;
            }
            return state;
        }

        /** What each hand is doing, for the arm poses vanilla draws. */
        private void armPoses(Player player, WornPose.State state) {
            ItemStack main = player.getInventory().getItemInMainHand();
            ItemStack off = player.getInventory().getItemInOffHand();
            WornPose.ArmPose mainPose = holding(main) ? WornPose.ArmPose.ITEM : WornPose.ArmPose.EMPTY;
            WornPose.ArmPose offPose = holding(off) ? WornPose.ArmPose.ITEM : WornPose.ArmPose.EMPTY;
            if (player.isBlocking()) {
                if (off.getType() == Material.SHIELD) {
                    offPose = WornPose.ArmPose.BLOCK;
                } else {
                    mainPose = WornPose.ArmPose.BLOCK;
                }
            } else if (player.isHandRaised() && main.getType() == Material.BOW) {
                mainPose = WornPose.ArmPose.BOW;
            }
            if (state.leftHanded) {
                state.leftArm = mainPose;
                state.rightArm = offPose;
            } else {
                state.rightArm = mainPose;
                state.leftArm = offPose;
            }
        }

        private boolean holding(ItemStack stack) {
            return stack != null && stack.getType() != Material.AIR;
        }

        /**
         * Who sees which of the shared displays, brought up to date.
         *
         * <p>Two reasons to hide one from a viewer, both per viewer: they cannot
         * see the wearer (a vanished moderator in 3D armour would otherwise be a
         * suit walking round on its own, and so would anybody an emote has
         * swapped for a rig), or their client draws the set itself — see
         * {@link Armor3dSet#drawnBy}.
         */
        void mirrorVisibility(Player wearer) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(wearer)) {
                    continue;
                }
                for (Map.Entry<String, ItemDisplay> entry : shared.displays.entrySet()) {
                    Placed placed = shared.shown.get(entry.getKey());
                    if (placed != null) {
                        apply(viewer, wearer, entry.getValue(), placed.set());
                    }
                }
            }
            hidden.removeIf(key -> Bukkit.getPlayer(UUID.fromString(key.substring(0, key.indexOf('|')))) == null);
        }

        /** One viewer, one display: shown or hidden as {@link #shows} says. */
        void apply(Player viewer, Player wearer, ItemDisplay display, Armor3dSet set) {
            String key = viewer.getUniqueId() + "|" + display.getUniqueId();
            boolean show = shows(viewer, wearer, set);
            if (show && hidden.remove(key)) {
                viewer.showEntity(plugin, display);
            } else if (!show && hidden.add(key)) {
                viewer.hideEntity(plugin, display);
            }
        }

        void clear() {
            shared.clear();
            if (own != null) {
                own.clear();
                own = null;
            }
        }
    }

    /** One copy of a wearer's displays: the shared one, or their own. */
    private final class Copy {

        final boolean own;
        final Map<String, ItemDisplay> displays = new HashMap<>();
        final Map<String, Placed> shown = new HashMap<>();
        boolean fresh;

        Copy(boolean own) {
            this.own = own;
        }

        /** Spawns what is missing, removes what is no longer worn, and carries the rest to {@code at}. */
        void sync(Player player, Map<String, Placed> wanted, Location at, boolean jumped) {
            World world = player.getWorld();
            boolean moved = jumped;
            for (Map.Entry<String, ItemDisplay> entry : List.copyOf(displays.entrySet())) {
                ItemDisplay display = entry.getValue();
                Placed part = wanted.get(entry.getKey());
                if (part == null || !display.isValid() || display.getWorld() != world
                        || !part.part().model().equals(shown.get(entry.getKey()).part().model())) {
                    display.remove();
                    displays.remove(entry.getKey());
                    shown.remove(entry.getKey());
                }
            }
            for (Map.Entry<String, Placed> entry : wanted.entrySet()) {
                if (displays.containsKey(entry.getKey())) {
                    continue;
                }
                displays.put(entry.getKey(), spawn(player, at, entry.getValue()));
                shown.put(entry.getKey(), entry.getValue());
                fresh = true;
            }
            DisplayCarry carry = own ? ownGlide : glide;
            for (ItemDisplay display : displays.values()) {
                if (moved || display.getLocation().distanceSquared(at) > SNAP_DISTANCE * SNAP_DISTANCE) {
                    snap.carry(display);
                    display.teleport(at);
                    carry.carry(display);
                } else {
                    display.teleport(at);
                }
            }
        }

        private ItemDisplay spawn(Player player, Location at, Placed placed) {
            Armor3dSet.Part part = placed.part();
            ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(items.art(part.model()));
                // NONE: the model is built in its own frame, not an item's.
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(Display.Billboard.FIXED);
                d.setPersistent(false);
                // A display is culled by a box at its position, and this one
                // stands at the feet: without a box the size of a player, the
                // armour vanishes whenever the feet leave the screen.
                d.setDisplayWidth(CULL_WIDTH);
                d.setDisplayHeight(CULL_HEIGHT);
                // Nothing until the first pose lands, rather than a chestplate
                // at the feet for a tick.
                d.setTransformation(collapsed());
            });
            (own ? ownGlide : glide).carry(display);
            if (own) {
                // Only the wearer: everybody else has the shared copy.
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (!other.equals(player)) {
                        other.hideEntity(plugin, display);
                    }
                }
            } else {
                player.hideEntity(plugin, display);
                Wearer wearer = wearers.get(player.getUniqueId());
                if (wearer != null) {
                    for (Player viewer : Bukkit.getOnlinePlayers()) {
                        if (!viewer.equals(player)) {
                            wearer.apply(viewer, player, display, placed.set());
                        }
                    }
                }
            }
            return display;
        }

        void pose(Player player, WornPose.State state, float bodyYaw, boolean hidden) {
            if (displays.isEmpty()) {
                return;
            }
            WornPose.Pose pose = WornPose.pose(state);
            for (Map.Entry<String, ItemDisplay> entry : displays.entrySet()) {
                Placed placed = shown.get(entry.getKey());
                Armor3dSet.Part part = placed == null ? null : placed.part();
                WornPose.Limb limb = part == null ? null : WornPose.limbOf(part.bone());
                Transformation next;
                if (limb == null || hidden) {
                    next = collapsed();
                } else {
                    Vector3f translation = new Vector3f();
                    Quaternionf rotation = WornPose.place(pose, limb, part.anchor(), part.rotation(), bodyYaw,
                            state.crouching, translation);
                    float size = WornPose.PLAYER_SCALE / (part.scale() > 0 ? part.scale() : 1f);
                    next = new Transformation(translation, rotation, new Vector3f(size, size, size), new Quaternionf());
                }
                ItemDisplay display = entry.getValue();
                if (next.equals(display.getTransformation())) {
                    continue;
                }
                // Toggled, not set: an unchanged delay is dropped from the
                // metadata packet, and a tween then runs against a start tick
                // long past and snaps. RigAnimator carries the long version.
                display.setInterpolationDelay(1);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(fresh ? 0 : POSE_PERIOD);
                display.setTransformation(next);
            }
            fresh = false;
        }

        void clear() {
            for (ItemDisplay display : displays.values()) {
                display.remove();
            }
            displays.clear();
            shown.clear();
        }
    }

    /** Where the shared copy stands: the player's feet, turned to nothing — the transform holds every turn. */
    private static Location feet(Player player) {
        Location at = player.getLocation().clone();
        at.setYaw(0f);
        at.setPitch(0f);
        return at;
    }

    /** Too small to see, and never zero — a zero scale is a NaN quaternion for ever after. */
    private static Transformation collapsed() {
        float tiny = 0.0001f;
        return new Transformation(new Vector3f(0f, 1f, 0f), new Quaternionf(),
                new Vector3f(tiny, tiny, tiny), new Quaternionf());
    }

    /** One display's part, and the set it is a part of. */
    private record Placed(Armor3dSet set, Armor3dSet.Part part) {
    }

    /** {@code player}'s protocol, cached for the session. */
    private int protocol(Player player) {
        return protocols.computeIfAbsent(player.getUniqueId(), id -> protocolOf.applyAsInt(player));
    }

    /**
     * Whether {@code viewer} should see a display of {@code set} on
     * {@code wearer}: not if they cannot see the wearer at all, and not if
     * their own client draws the set — then the displays would be a second
     * suit standing in the first.
     */
    private boolean shows(Player viewer, Player wearer, Armor3dSet set) {
        return viewer.canSee(wearer) && !set.drawnBy(protocol(viewer));
    }

    /** The ids of every set a player could be given, for the commands. */
    public List<String> ids() {
        return new ArrayList<>(sets.get().keySet());
    }
}
