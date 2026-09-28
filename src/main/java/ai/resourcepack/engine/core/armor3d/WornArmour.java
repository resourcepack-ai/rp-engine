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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
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
 * <h2>Three decisions, each of which is a way this could have been wrong</h2>
 *
 * <p><b>The displays are not passengers of the player.</b> A passenger would
 * be carried by the client at exactly the player's drawn position, which is
 * what anything glued to a body wants — and CraftBukkit refuses to teleport an
 * entity that is being ridden. That would be every other plugin's
 * {@code /spawn}, {@code /home} and warp failing, silently, for anybody wearing
 * a chestplate. So they are teleported every tick instead, and given the same
 * three ticks to glide over that a client gives the player they are glued to
 * ({@link DisplayLatency#TRACKED_ENTITY_TICKS}): behind by the same amount is
 * together.
 *
 * <p><b>The pose is sent every {@link #POSE_PERIOD} ticks and interpolated over
 * exactly that.</b> Re-sent every tick with a longer glide, the client starts
 * each tween from wherever the last one had got to, which is a low-pass filter
 * on the walk cycle: a quarter of every arm swing disappears and the rest is
 * late. Sampled at a fixed period and tweened over the same period, the swing
 * arrives whole and exactly one period late — which is about how late the
 * watching client draws the body itself, off positions it lerps over three.
 *
 * <p><b>The wearer does not see their own.</b> In first person the game draws
 * none of your body, and a torso of armour floating under the camera — with
 * pauldrons at head height — is the whole of what they would get. The emote
 * system reached the same conclusion for the same reason ({@code
 * hideFromWearer}). {@code /rp armor self} turns it back on for the session,
 * which is how anybody checks their own set in F5.
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
     * How far ahead a swing is sampled, in ticks.
     *
     * <p>The watching client starts the swing the moment the packet lands;
     * everything this sends arrives a period late. Sampling the swing that
     * far ahead puts the pauldron where the arm is when the pose is shown,
     * which is {@code ArmSwing.progress}'s {@code +1} for the same reason.
     */
    private static final float SWING_LEAD = POSE_PERIOD;

    /** Past this in one tick, a move is a teleport and is not glided. */
    private static final double SNAP_DISTANCE = 8;

    /** The culling box a display is given: a player and their reach, from the feet up. */
    private static final float CULL_WIDTH = 2.5f;
    private static final float CULL_HEIGHT = 2.5f;

    private final Plugin plugin;
    private final Armor3dItems items;
    private final Supplier<Map<String, Armor3dSet>> sets;
    private final DisplayCarry glide;
    private final DisplayCarry snap;
    private final boolean available;

    private final Map<UUID, Wearer> wearers = new HashMap<>();
    /** Who asked to see their own. Session-only, and kept when they take it off and put it back on. */
    private final Set<UUID> showingSelf = new HashSet<>();
    private long tick;
    private int taskId = -1;

    public WornArmour(Plugin plugin, Compatibility compatibility, Armor3dItems items,
                      Supplier<Map<String, Armor3dSet>> sets, boolean available) {
        this.plugin = plugin;
        this.items = items;
        this.sets = sets;
        this.glide = DisplayCarry.forServer(compatibility, DisplayLatency.TRACKED_ENTITY_TICKS);
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
     * Toggles whether {@code player} sees their own 3D armour.
     *
     * @return whether they now do
     */
    public boolean toggleSelf(Player player) {
        boolean now = showingSelf.add(player.getUniqueId()) || !showingSelf.remove(player.getUniqueId());
        Wearer wearer = wearers.get(player.getUniqueId());
        if (wearer != null) {
            for (ItemDisplay display : wearer.displays.values()) {
                if (now) {
                    player.showEntity(plugin, display);
                } else {
                    player.hideEntity(plugin, display);
                }
            }
        }
        return now;
    }

    public boolean showsSelf(Player player) {
        return showingSelf.contains(player.getUniqueId());
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
                Map<String, Armor3dSet.Part> wanted = wanted(player, known);
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
            wearer.sync(player);
            if (pose || wearer.poseNow) {
                wearer.pose(player, false);
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
    private Map<String, Armor3dSet.Part> wanted(Player player, Map<String, Armor3dSet> known) {
        if (known.isEmpty() || player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return Map.of();
        }
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return Map.of();
        }
        Map<String, Armor3dSet.Part> wanted = new LinkedHashMap<>();
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
                            wanted.put(entry.getKey().id() + "/" + piece.wire() + "/" + i, parts.get(i));
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
        wearer.pose(event.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Wearer wearer = wearers.remove(event.getPlayer().getUniqueId());
        if (wearer != null) {
            wearer.clear();
        }
        showingSelf.remove(event.getPlayer().getUniqueId());
    }

    // ---- one wearer -----------------------------------------------------------

    private final class Wearer {

        final UUID id;
        Map<String, Armor3dSet.Part> wanted = Map.of();
        final Map<String, ItemDisplay> displays = new HashMap<>();
        final Map<String, Armor3dSet.Part> shown = new HashMap<>();
        /** Who this wearer's displays are currently hidden from because they cannot see the wearer. */
        final Set<UUID> hiddenFrom = new HashSet<>();

        /** {@code {position, speed}} — see {@link WornPose#walk}. */
        final float[] walk = new float[2];
        float bodyYaw;
        double lastX;
        double lastZ;
        Location lastPlaced;
        boolean started;
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
            if (!started) {
                lastX = at.getX();
                lastZ = at.getZ();
                started = true;
            }
            double dx = at.getX() - lastX;
            double dz = at.getZ() - lastZ;
            lastX = at.getX();
            lastZ = at.getZ();
            if (dx * dx + dz * dz > SNAP_DISTANCE * SNAP_DISTANCE) {
                // Teleported: no stride, and the body faces where they look.
                dx = 0;
                dz = 0;
                bodyYaw = at.getYaw();
            }
            Entity vehicle = player.getVehicle();
            if (vehicle != null) {
                // A rider's legs belong to the seat and their body to what they
                // sit in: a boat or a horse sets both on the client.
                WornPose.walk(walk, 0);
                if (vehicle instanceof Boat || vehicle instanceof LivingEntity) {
                    bodyYaw = vehicle.getLocation().getYaw();
                } else {
                    bodyYaw = WornPose.bodyYaw(bodyYaw, at.getYaw(), 0, 0, swinging());
                }
                return;
            }
            WornPose.walk(walk, Math.sqrt(dx * dx + dz * dz));
            bodyYaw = WornPose.bodyYaw(bodyYaw, at.getYaw(), dx, dz, swinging());
        }

        boolean swinging() {
            return tick - swingStart < SWING_TICKS;
        }

        /** Spawns what is missing, removes what is no longer worn, and carries the rest. */
        void sync(Player player) {
            World world = player.getWorld();
            for (Map.Entry<String, ItemDisplay> entry : List.copyOf(displays.entrySet())) {
                ItemDisplay display = entry.getValue();
                Armor3dSet.Part part = wanted.get(entry.getKey());
                if (part == null || !display.isValid() || display.getWorld() != world
                        || !part.model().equals(shown.get(entry.getKey()).model())) {
                    display.remove();
                    displays.remove(entry.getKey());
                    shown.remove(entry.getKey());
                }
            }
            Location feet = feet(player);
            boolean jumped = lastPlaced == null || lastPlaced.getWorld() != world
                    || lastPlaced.distanceSquared(feet) > SNAP_DISTANCE * SNAP_DISTANCE;
            for (Map.Entry<String, Armor3dSet.Part> entry : wanted.entrySet()) {
                if (displays.containsKey(entry.getKey())) {
                    continue;
                }
                displays.put(entry.getKey(), spawn(player, feet, entry.getValue()));
                shown.put(entry.getKey(), entry.getValue());
                poseNow = true;
            }
            for (ItemDisplay display : displays.values()) {
                if (jumped) {
                    snap.carry(display);
                    display.teleport(feet);
                    glide.carry(display);
                } else {
                    display.teleport(feet);
                }
            }
            lastPlaced = feet;
        }

        private ItemDisplay spawn(Player player, Location feet, Armor3dSet.Part part) {
            ItemDisplay display = feet.getWorld().spawn(feet, ItemDisplay.class, d -> {
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
            glide.carry(display);
            if (!showingSelf.contains(player.getUniqueId())) {
                player.hideEntity(plugin, display);
            }
            for (UUID viewer : hiddenFrom) {
                Player other = Bukkit.getPlayer(viewer);
                if (other != null) {
                    other.hideEntity(plugin, display);
                }
            }
            return display;
        }

        /**
         * Sends every part's pose.
         *
         * @param immediate a swing: tween over the whole period from the pose
         *                  on screen, rather than waiting for the period
         */
        void pose(Player player, boolean immediate) {
            poseNow = false;
            if (displays.isEmpty()) {
                return;
            }
            // The poses the game turns the whole body for, and the armour is
            // simply put away for: swimming, crawling, gliding, sleeping, a
            // riptide spin. Chasing them is a second model of the renderer for
            // moments nobody stands still in.
            org.bukkit.entity.Pose stance = player.getPose();
            boolean hidden = stance != Pose.STANDING && stance != Pose.SNEAKING;
            WornPose.State state = new WornPose.State();
            state.walkPosition = walk[0];
            state.walkSpeed = walk[1];
            state.age = player.getTicksLived();
            state.headYaw = WornPose.wrap(player.getLocation().getYaw() - bodyYaw);
            state.headPitch = player.getLocation().getPitch();
            state.crouching = stance == Pose.SNEAKING;
            state.riding = player.getVehicle() != null;
            state.leftHanded = player.getMainHand() == MainHand.LEFT;
            armPoses(player, state);
            float swing = (tick - swingStart + SWING_LEAD) / SWING_TICKS;
            if (swing > 0f && swing < 1f) {
                state.attackTime = swing;
                state.attackLeft = swingLeft;
            }
            WornPose.Pose pose = WornPose.pose(state);

            for (Map.Entry<String, ItemDisplay> entry : displays.entrySet()) {
                Armor3dSet.Part part = shown.get(entry.getKey());
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
                display.setInterpolationDuration(POSE_PERIOD);
                display.setTransformation(next);
            }
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
         * Hides the armour from anybody who cannot see the person wearing it.
         *
         * <p>A vanished moderator in 3D armour would otherwise be a suit
         * walking round on its own — and so would anybody an emote has
         * swapped for a rig, which hides them the same way.
         */
        void mirrorVisibility(Player wearer) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(wearer)) {
                    continue;
                }
                boolean sees = viewer.canSee(wearer);
                boolean hidden = hiddenFrom.contains(viewer.getUniqueId());
                if (sees == !hidden) {
                    continue;
                }
                for (ItemDisplay display : displays.values()) {
                    if (sees) {
                        viewer.showEntity(plugin, display);
                    } else {
                        viewer.hideEntity(plugin, display);
                    }
                }
                if (sees) {
                    hiddenFrom.remove(viewer.getUniqueId());
                } else {
                    hiddenFrom.add(viewer.getUniqueId());
                }
            }
            hiddenFrom.removeIf(viewer -> Bukkit.getPlayer(viewer) == null);
        }

        void clear() {
            for (ItemDisplay display : displays.values()) {
                display.remove();
            }
            displays.clear();
            shown.clear();
        }
    }

    /** Where the displays stand: the player's feet, turned to nothing — the transform holds every turn. */
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

    /** The ids of every set a player could be given, for the commands. */
    public List<String> ids() {
        return new ArrayList<>(sets.get().keySet());
    }
}
