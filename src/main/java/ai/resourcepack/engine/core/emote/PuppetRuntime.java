package ai.resourcepack.engine.core.emote;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.Keyframe;
import ai.resourcepack.engine.api.Puppet;
import ai.resourcepack.engine.api.PuppetAnimation;
import ai.resourcepack.engine.api.PuppetOptions;
import ai.resourcepack.engine.api.Puppets;
import ai.resourcepack.engine.core.Regions;
import ai.resourcepack.engine.core.animation.RigMath;
import ai.resourcepack.engine.core.model.DisplayGlide;
import ai.resourcepack.engine.core.version.Spawning;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * {@link Puppets}: player rigs owned by a plugin rather than by a player.
 *
 * <p><b>Everything here is the emote director's arithmetic with the player
 * taken out</b>, and it lives in this package so it can reach the same pieces:
 * the bone tables and rigs of {@link EmoteStore}, the bone items
 * ({@link EmoteDirector#boneItem}), the scale and the base height a rig stands
 * at, the hand socket ({@link HeldItem}) and {@link RigMath}'s composition. The
 * director itself is not reused because nearly all of its four thousand lines
 * are about the player: hiding them, following them, giving them back. A puppet
 * has none of that to do.
 *
 * <p>What a puppet does not do that an emote does: props, a cape, a shadow and
 * an arm swing. A plugin that wants a prop holds it ({@link Puppet#hold}); the
 * others are cosmetic and can follow.
 *
 * <p>One global timer poses every puppet each tick; on Folia each puppet's pass
 * runs on the region that holds its feet.
 */
public final class PuppetRuntime implements Puppets {

    /** Ticks a pose takes to arrive on the client, the emote director's number. */
    private static final int POSE_TICKS = EmoteDirector.INTERPOLATION_TICKS;

    /** How high a name floats over the feet, in blocks. */
    private static final double NAME_HEIGHT = 2.05;

    private final Plugin plugin;
    private final EmoteStore store;
    private final SkinCache skins;
    private final BooleanSupplier available;
    private final Runnable rebake;
    private final NamespacedKey partKey;
    private final Map<UUID, Body> all = new ConcurrentHashMap<>();
    private Regions.Fanout<UUID> passes;
    private Tasks.Handle task;
    private long ticks;

    public PuppetRuntime(Plugin plugin, EmoteStore store, SkinCache skins, BooleanSupplier available, Runnable rebake) {
        this.plugin = plugin;
        this.store = store;
        this.skins = skins;
        this.available = available;
        this.rebake = rebake;
        this.partKey = new NamespacedKey(plugin, "puppet_part");
    }

    public void start() {
        passes = new Regions.Fanout<>(plugin);
        task = Tasks.globalTimer(plugin, this::tick, 1, 1);
        // A reload leaves the last run's parts standing with nothing to pose them.
        Regions.loaded(plugin, Display.class, display -> {
            if (display.getPersistentDataContainer().has(partKey, PersistentDataType.BYTE)) {
                display.remove();
            }
        });
    }

    /** Takes every puppet down. Skipped on Folia's shutdown, where the world has already stopped. */
    public void stop(boolean worldStopped) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!worldStopped) {
            for (Body body : new ArrayList<>(all.values())) {
                body.remove();
            }
        }
        all.clear();
        if (passes != null) {
            passes.clear();
        }
    }

    // ---- Puppets ----------------------------------------------------------

    @Override
    public boolean available() {
        return available.getAsBoolean();
    }

    @Override
    public Optional<String> skin(String name, byte[] png, boolean slim) {
        return Optional.ofNullable(skins == null ? null : skins.pin(name, png, slim));
    }

    @Override
    public String skinOf(UUID player) {
        return player == null ? null : SkinCache.keyOf(player);
    }

    @Override
    public boolean ready(String skinKey) {
        return store.hasRigFor(skinKey);
    }

    @Override
    public void bake() {
        Regions.global(plugin, rebake);
    }

    @Override
    public Optional<Puppet> spawn(Location feet, PuppetOptions options) {
        if (feet == null || feet.getWorld() == null || !available()) {
            return Optional.empty();
        }
        PuppetOptions o = options == null ? PuppetOptions.DEFAULT : options;
        EmoteStore.PlayerRig rig = store.rigForKey(o.skin());
        if (rig == null) {
            return Optional.empty();
        }
        Body body = new Body(o, rig, feet);
        all.put(body.id, body);
        return Optional.of(body);
    }

    @Override
    public Collection<Puppet> all() {
        return Collections.unmodifiableCollection(new ArrayList<Puppet>(all.values()));
    }

    // ---- the timer --------------------------------------------------------

    private void tick() {
        ticks++;
        for (Body body : all.values()) {
            passes.at(body.id, body.feet, id -> {
                Body b = all.get(id);
                if (b == null) {
                    return;
                }
                try {
                    b.pose(POSE_TICKS);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("A puppet failed to pose and was removed: " + e);
                    b.remove();
                }
            });
        }
    }

    // ---- one puppet -------------------------------------------------------

    /** What a puppet is playing: an emote's keyframes, or ones handed in. */
    private static final class Clip {
        final Map<String, Map<String, List<Keyframe>>> animators;
        final Map<String, List<Keyframe>> root;
        final double length;
        final boolean loop;

        Clip(Map<String, Map<String, List<Keyframe>>> animators, Map<String, List<Keyframe>> root,
             double length, boolean loop) {
            this.animators = animators == null ? Collections.emptyMap() : animators;
            this.root = root;
            this.length = Math.max(0, length);
            this.loop = loop;
        }

        static final Clip REST = new Clip(Collections.emptyMap(), null, 0, false);

        double timeAt(double elapsed) {
            if (length <= 0) {
                return 0;
            }
            return loop ? ((elapsed % length) + length) % length : Math.max(0, Math.min(elapsed, length));
        }
    }

    private final class Body implements Puppet {
        final UUID id = UUID.randomUUID();
        final String skinKey;
        final Set<UUID> audience;
        final boolean slim;
        final List<EmoteStore.Bone> bones;
        final List<ItemDisplay> parts = new ArrayList<>();
        volatile Location feet;
        float yaw;
        int glide = 1;
        Clip clip = Clip.REST;
        double clockStart;
        long clockTick;
        double speed = 1;
        float[][] fadeFrom;
        long fadeStart;
        int fadeTicks;
        Float lookYaw;
        Float lookPitch;
        ItemDisplay mainHand;
        ItemDisplay offHand;
        TextDisplay nameTag;
        boolean removed;

        Body(PuppetOptions o, EmoteStore.PlayerRig rig, Location at) {
            this.skinKey = o.skin();
            this.audience = o.audience() == null ? null : new LinkedHashSet<>(o.audience());
            String variant = o.slim() != null
                    ? (o.slim() ? SkinModel.SLIM : SkinModel.WIDE)
                    : (rig.variant != null ? rig.variant : SkinModel.WIDE);
            this.slim = SkinModel.SLIM.equals(variant);
            boolean jointed = store.usesJointed(rig);
            List<EmoteStore.Bone> table = store.bonesFor(rig);
            // The cape is a bone a puppet does not draw: it has no physics to hang
            // from here, and a still cape reads as a board strapped to the back.
            List<EmoteStore.Bone> kept = new ArrayList<>();
            for (EmoteStore.Bone bone : table) {
                if (bone != null && !EmoteStore.CAPE_BONE.equalsIgnoreCase(bone.key)) {
                    kept.add(bone);
                }
            }
            this.bones = kept;
            this.feet = at.clone();
            this.yaw = at.getYaw();
            Location base = base(feet);
            for (int i = 0; i < bones.size(); i++) {
                EmoteStore.Bone bone = bones.get(i);
                ItemStack item = EmoteDirector.boneItem(EmoteStore.boneItemId(rig, bone.key, variant, jointed));
                parts.add(spawnDisplay(base, ItemDisplay.class, d -> {
                    d.setItemStack(item);
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                }));
            }
            name(o.name());
            pose(0);
        }

        // ---- spawning ------------------------------------------------------

        private <T extends Display> T spawnDisplay(Location at, Class<T> type, java.util.function.Consumer<T> dress) {
            World world = at.getWorld();
            java.util.function.Supplier<T> make = () -> Spawning.spawn(world, at, type, d -> {
                dress.accept(d);
                d.setPersistent(false);
                d.getPersistentDataContainer().set(partKey, PersistentDataType.BYTE, (byte) 1);
                DisplayGlide.set(d, glide);
            });
            return audience == null ? make.get() : Spawning.forAudience(onlineAudience(), make);
        }

        private List<Player> onlineAudience() {
            List<Player> out = new ArrayList<>();
            for (UUID viewer : audience) {
                Player p = Bukkit.getPlayer(viewer);
                if (p != null) {
                    out.add(p);
                }
            }
            return out;
        }

        private Location base(Location at) {
            Location base = at.clone().add(0, EmoteDirector.RIG_BASE_Y, 0);
            base.setYaw(0);
            base.setPitch(0);
            return base;
        }

        private List<Display> everything() {
            List<Display> out = new ArrayList<>(parts.size() + 3);
            for (ItemDisplay part : parts) {
                if (part != null) {
                    out.add(part);
                }
            }
            if (mainHand != null) out.add(mainHand);
            if (offHand != null) out.add(offHand);
            if (nameTag != null) out.add(nameTag);
            return out;
        }

        // ---- Puppet ----------------------------------------------------------

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public boolean isValid() {
            return !removed;
        }

        @Override
        public Location location() {
            Location at = feet.clone();
            at.setYaw(yaw);
            at.setPitch(0);
            return at;
        }

        @Override
        public String skin() {
            return skinKey;
        }

        @Override
        public void move(Location to, int glideTicks) {
            if (removed || to == null || to.getWorld() == null) {
                return;
            }
            this.glide = Math.max(0, Math.min(59, glideTicks));
            boolean turned = to.getYaw() != yaw;
            this.feet = to.clone();
            this.yaw = to.getYaw();
            Location base = base(feet);
            for (Display d : everything()) {
                DisplayGlide.set(d, glide);
                if (d == nameTag) {
                    Location over = feet.clone().add(0, NAME_HEIGHT, 0);
                    over.setYaw(0);
                    over.setPitch(0);
                    Regions.move(d, over);
                } else {
                    Regions.move(d, base);
                }
            }
            // A turn is in every part's transformation, so it is sent now rather
            // than on the next pass, gliding over the same window as the move.
            if (turned) {
                pose(Math.max(1, glide));
            }
        }

        @Override
        public boolean play(String emote, double fromSeconds, double speed, Boolean loop, int blendTicks) {
            if (emote == null) {
                return false;
            }
            EmoteStore.Emote found = store.find(emote);
            if (found == null) {
                return false;
            }
            start(new Clip(found.animators, found.root, found.length, loop != null ? loop : found.loop),
                    fromSeconds, speed, blendTicks);
            return true;
        }

        @Override
        public void play(PuppetAnimation animation, double fromSeconds, double speed, int blendTicks) {
            if (animation == null) {
                rest(blendTicks);
                return;
            }
            start(new Clip(animation.bones(), animation.root(), animation.length(), animation.loop()),
                    fromSeconds, speed, blendTicks);
        }

        @Override
        public void rest(int blendTicks) {
            start(Clip.REST, 0, 1, blendTicks);
        }

        private void start(Clip next, double from, double rate, int blendTicks) {
            if (removed) {
                return;
            }
            if (blendTicks > 0) {
                fadeFrom = sample(clip, time());
                fadeStart = ticks;
                fadeTicks = blendTicks;
            } else {
                fadeFrom = null;
            }
            this.clip = next;
            this.speed = Double.isFinite(rate) && rate > 0 ? rate : 1;
            this.clockStart = Double.isFinite(from) ? from : 0;
            this.clockTick = ticks;
        }

        @Override
        public void seek(double seconds) {
            this.clockStart = Double.isFinite(seconds) ? seconds : 0;
            this.clockTick = ticks;
        }

        @Override
        public double playhead() {
            return clip.timeAt(elapsed());
        }

        private double elapsed() {
            return clockStart + (ticks - clockTick) / 20.0 * speed;
        }

        private double time() {
            return clip.timeAt(elapsed());
        }

        @Override
        public void look(Float yaw, Float pitch) {
            this.lookYaw = yaw;
            this.lookPitch = pitch;
        }

        @Override
        public void hold(boolean off, ItemStack item) {
            if (removed) {
                return;
            }
            boolean empty = item == null || item.getType() == Material.AIR;
            ItemDisplay current = off ? offHand : mainHand;
            if (empty) {
                if (current != null) {
                    current.remove();
                }
                if (off) offHand = null; else mainHand = null;
                return;
            }
            if (current != null && current.isValid()) {
                current.setItemStack(item.clone());
                return;
            }
            ItemDisplay made = spawnDisplay(base(feet), ItemDisplay.class, d -> {
                d.setItemStack(item.clone());
                d.setItemDisplayTransform(off
                        ? ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND
                        : ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
            });
            if (off) offHand = made; else mainHand = made;
            pose(0);
        }

        @Override
        public void name(String name) {
            if (removed) {
                return;
            }
            if (name == null || name.isEmpty()) {
                if (nameTag != null) {
                    nameTag.remove();
                    nameTag = null;
                }
                return;
            }
            if (nameTag != null && nameTag.isValid()) {
                nameTag.setText(name);
                return;
            }
            Location over = feet.clone().add(0, NAME_HEIGHT, 0);
            over.setYaw(0);
            over.setPitch(0);
            nameTag = spawnDisplay(over, TextDisplay.class, d -> {
                d.setText(name);
                d.setBillboard(Display.Billboard.CENTER);
                d.setSeeThrough(false);
                d.setDefaultBackground(true);
            });
        }

        @Override
        public void show(Player player) {
            if (player == null || removed) {
                return;
            }
            if (audience != null) {
                audience.add(player.getUniqueId());
            }
            for (Entity e : everything()) {
                player.showEntity(plugin, e);
            }
        }

        @Override
        public void hide(Player player) {
            if (player == null || removed) {
                return;
            }
            if (audience != null) {
                audience.remove(player.getUniqueId());
            }
            for (Entity e : everything()) {
                player.hideEntity(plugin, e);
            }
        }

        @Override
        public void remove() {
            if (removed) {
                return;
            }
            removed = true;
            all.remove(id);
            for (Display d : everything()) {
                if (d.isValid()) {
                    d.remove();
                }
            }
            parts.clear();
            mainHand = null;
            offHand = null;
            nameTag = null;
        }

        // ---- posing ----------------------------------------------------------

        /** Every bone's nine values at {@code t}, root last: the director's samplePose. */
        private float[][] sample(Clip c, double t) {
            int last = bones.size();
            float[][] values = new float[last + 1][];
            for (int i = 0; i < last; i++) {
                EmoteStore.Bone bone = bones.get(i);
                values[i] = RigMath.sampleStep(c.animators.get(bone.key), t, 1f);
            }
            values[last] = c.root == null || c.root.isEmpty() ? null : RigMath.sampleStep(c.root, t, 1f);
            return values;
        }

        void pose(int interpolation) {
            if (removed) {
                return;
            }
            float[][] values = sample(clip, time());
            if (fadeFrom != null) {
                long into = ticks - fadeStart;
                if (fadeTicks <= 0 || into >= fadeTicks) {
                    fadeFrom = null;
                } else {
                    float s = Math.max(0f, into / (float) fadeTicks);
                    values = RigMath.lerpProgram(fadeFrom, values, values.length, s);
                }
            }
            int last = bones.size();
            Matrix4f root = new Matrix4f();
            float[] rootPivot = store.rootPivot();
            if (values[last] != null && rootPivot != null) {
                RigMath.composeStep(root, rootPivot, values[last]);
            }
            Map<String, Matrix4f> composed = new HashMap<>();
            for (int i = 0; i < bones.size(); i++) {
                EmoteStore.Bone bone = bones.get(i);
                Matrix4f parent = bone.parent != null ? composed.get(bone.parent) : null;
                Matrix4f animation = new Matrix4f(parent != null ? parent : root);
                RigMath.composeStep(animation, bone.pivot, values[i]);
                if ((lookYaw != null || lookPitch != null) && "head".equalsIgnoreCase(bone.key)) {
                    // Minecraft's angles into the rig's frame: the rig faces -z,
                    // so looking down is a negative turn about x and turning
                    // right is a negative turn about y.
                    float pitch = lookPitch == null ? 0f : -lookPitch;
                    float turn = lookYaw == null ? 0f : -lookYaw;
                    RigMath.composeStep(animation, bone.pivot,
                            new float[] {pitch, turn, 0f, 0f, 0f, 0f, 1f, 1f, 1f});
                }
                composed.put(bone.key, animation);
                ItemDisplay display = i < parts.size() ? parts.get(i) : null;
                if (display == null || !display.isValid()) {
                    continue;
                }
                apply(display, bodyFrame(animation), interpolation);
            }
            poseHand(mainHand, HeldItem.MAIN_HAND_ATTACH, false, composed, interpolation);
            poseHand(offHand, HeldItem.OFF_HAND_ATTACH, true, composed, interpolation);
        }

        private Matrix4f bodyFrame(Matrix4f animation) {
            Matrix4f m = new Matrix4f();
            if (yaw != 0f) {
                m.rotateY((float) Math.toRadians(-yaw));
            }
            m.scale(EmoteDirector.PLAYER_SCALE);
            m.mul(RigMath.toItemDisplaySpace(animation));
            return m;
        }

        private void poseHand(ItemDisplay display, String attach, boolean off,
                              Map<String, Matrix4f> composed, int interpolation) {
            if (display == null || !display.isValid()) {
                return;
            }
            Matrix4f chain = composed.get(EmoteStore.attachEndBone(attach));
            if (chain == null) {
                for (Map.Entry<String, Matrix4f> e : composed.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(attach)) {
                        chain = e.getValue();
                        break;
                    }
                }
            }
            if (chain == null) {
                return;
            }
            Matrix4f m = new Matrix4f(chain);
            HeldItem.applyTo(m, off, slim);
            Matrix4f out = new Matrix4f();
            if (yaw != 0f) {
                out.rotateY((float) Math.toRadians(-yaw));
            }
            out.scale(EmoteDirector.PLAYER_SCALE);
            out.mul(RigMath.toItemDisplaySpace(m));
            HeldItem.orient(out);
            apply(display, out, interpolation);
        }

        private void apply(ItemDisplay display, Matrix4f m, int interpolation) {
            Transformation next = RigMath.toTransformation(m);
            if (next.equals(display.getTransformation())) {
                return;
            }
            // Toggling re-arms the client's interpolation, as the director does.
            display.setInterpolationDelay(1);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(interpolation);
            display.setTransformation(next);
        }
    }
}
