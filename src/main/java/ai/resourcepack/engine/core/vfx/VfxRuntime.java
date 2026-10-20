package ai.resourcepack.engine.core.vfx;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Vfx;
import ai.resourcepack.engine.api.VfxInfo;
import ai.resourcepack.engine.api.VfxOptions;
import ai.resourcepack.engine.api.VfxPlay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * Plays VFX in the world, and is the API's {@link Vfx}.
 *
 * <p>Each tick of each effect is one {@link VfxPlayer#step}: a list of sprite
 * instances, each kept as one item display by its id, so a sprite that lives
 * across ticks is one entity moved by its interpolated transformation and the
 * client smooths it. A fixed sprite stays at the cast point and is moved by its
 * transformation; a billboard cannot be, because its transformation is applied
 * after it turns to face the camera, so it is teleported instead, with a
 * teleport duration so that interpolates too. Read off the workbench POC that
 * these numbers were tuned in, in game.
 *
 * <p>A Studio effect's sprites are only in the packs of the players holding
 * that push, so its displays are hidden from everybody else rather than drawn
 * as missing-model cubes. Its sounds are played to the same players, and every
 * sound an effect started is stopped when it ends: some sound events (an
 * elytra's wind) run for many seconds past the effect.
 *
 * <p>How a cast plays is its {@link VfxOptions}: a loop goes round the
 * recipe's loop section (the whole effect without one) until a time, a count,
 * a condition or a stop ends it, and then plays out its fade or cuts.
 */
public final class VfxRuntime implements Vfx {

    /** On every display, so a crash's leftovers can be found; they are not persistent anyway. */
    public static final String TAG = "rpengine_vfx";
    /** {@code vfx.max-sprites} when config.yml does not say. */
    public static final int DEFAULT_MAX_SPRITES = 4000;
    /** How far a sound and a hidden display's viewers are looked for. */
    private static final double HEARING = 64;
    private static final Quaternionf NONE = new Quaternionf();

    private final Plugin plugin;
    private final VfxCatalogue catalogue;
    private final boolean supported;
    /** Whether a player's own pack carries a Studio effect; content-folder effects are everybody's. */
    private final BiPredicate<Player, ContentId> holds;
    /** A placeholder's value for a player, for a loop's condition. */
    private final BiFunction<Player, String, Optional<String>> lookup;
    private final List<Play> playing = new ArrayList<>();
    private final Map<String, ItemStack> items = new HashMap<>();
    private BukkitTask task;
    /** Sprites alive at once across every effect; 0 is no limit. */
    private volatile int maxSprites = DEFAULT_MAX_SPRITES;

    public VfxRuntime(Plugin plugin, VfxCatalogue catalogue, boolean supported, BiPredicate<Player, ContentId> holds,
                      BiFunction<Player, String, Optional<String>> lookup) {
        this.plugin = plugin;
        this.catalogue = catalogue;
        this.supported = supported;
        this.holds = holds;
        this.lookup = lookup;
    }

    /** {@code vfx.max-sprites}: how many sprites may be alive at once, across every effect. 0 or less is no limit. */
    public void maxSprites(int max) {
        maxSprites = Math.max(0, max);
    }

    public int maxSprites() {
        return maxSprites;
    }

    /** Whether this server can play VFX at all (1.21.4+). */
    public boolean supported() {
        return supported;
    }

    public void start() {
        if (!supported || task != null) {
            return;
        }
        // Leftovers of a crash mid-effect; nothing here is persistent, so this is belt and braces.
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(TAG)) {
                    e.remove();
                }
            }
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        stopAll();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (playing.isEmpty()) {
            return;
        }
        for (Play play : new ArrayList<>(playing)) {
            try {
                if (!play.tick()) {
                    play.end();
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("VFX " + play.entry.info().id() + " stopped: " + e);
                play.end();
            }
        }
        // Over the budget, the oldest give way. The newest is never ended for
        // it, so a cast always plays, even one bigger than the whole budget.
        int max = maxSprites;
        if (max > 0) {
            int alive = 0;
            for (Play play : playing) {
                alive += play.live.size();
            }
            while (alive > max && playing.size() > 1) {
                Play oldest = playing.get(0);
                alive -= oldest.live.size();
                oldest.end();
            }
        }
    }

    // ---- the API

    @Override
    public List<ContentId> ids() {
        return catalogue.ids();
    }

    @Override
    public Optional<VfxInfo> info(ContentId id) {
        return catalogue.info(id);
    }

    @Override
    public Optional<VfxInfo> find(String name) {
        return catalogue.find(name).map(VfxEntry::info);
    }

    @Override
    public Optional<VfxPlay> play(ContentId id, Location at) {
        return play(id, at, null, VfxOptions.DEFAULT);
    }

    @Override
    public Optional<VfxPlay> play(ContentId id, Player caster) {
        return caster == null ? Optional.empty() : play(id, caster.getLocation(), caster, VfxOptions.DEFAULT);
    }

    @Override
    public Optional<VfxPlay> play(ContentId id, Location at, Player caster, VfxOptions options) {
        return catalogue.entry(id).flatMap(entry -> play(entry, at, caster, options));
    }

    @Override
    public Optional<VfxPlay> play(ContentId id, Player caster, VfxOptions options) {
        return caster == null ? Optional.empty() : play(id, caster.getLocation(), caster, options);
    }

    /** Plays an entry already looked up (by {@code /rp cast}). */
    public Optional<VfxPlay> play(VfxEntry entry, Location at, Player caster, VfxOptions options) {
        if (!supported || entry == null || at == null || at.getWorld() == null) {
            return Optional.empty();
        }
        Play play = new Play(entry, at, caster, options == null ? VfxOptions.DEFAULT : options);
        playing.add(play);
        return Optional.of(play);
    }

    @Override
    public List<VfxPlay> playing() {
        return List.copyOf(playing);
    }

    @Override
    public int stopAll() {
        int n = playing.size();
        for (Play play : new ArrayList<>(playing)) {
            play.end();
        }
        return n;
    }

    @Override
    public int stop(String tag) {
        if (tag == null) {
            return 0;
        }
        int n = 0;
        for (Play play : new ArrayList<>(playing)) {
            if (play.options.tag() != null && play.options.tag().equalsIgnoreCase(tag.trim())) {
                play.finish();
                n++;
            }
        }
        return n;
    }

    @Override
    public int stop(Player caster) {
        if (caster == null) {
            return 0;
        }
        int n = 0;
        for (Play play : new ArrayList<>(playing)) {
            if (caster.getUniqueId().equals(play.casterId)) {
                play.finish();
                n++;
            }
        }
        return n;
    }

    private ItemStack item(String model, int level) {
        return items.computeIfAbsent(model + "#" + level, k -> VfxDisplays.item(model, level)).clone();
    }

    /** One effect playing. */
    private final class Play implements VfxPlay {
        final VfxEntry entry;
        final VfxOptions options;
        final UUID casterId;
        final World world;
        final boolean shared;
        final int[] section;
        /** Where it plays, and which way: moved every tick while it follows something. */
        Location origin;
        Quaternionf frame;
        VfxPlayer player;
        /** Who may see and hear it. Null for everybody near, as a content-folder effect is. */
        final Set<UUID> viewers;
        final Map<String, Sprite> live = new HashMap<>();
        final Set<String> sounds = new LinkedHashSet<>();
        final Set<UUID> heard = new HashSet<>();
        int delay;
        long age;
        int passes;
        double clock;
        /** Told to stop looping: it plays out (FINISH) or ends at once (CUT). */
        boolean stopping;
        boolean ended;

        Play(VfxEntry entry, Location at, Player caster, VfxOptions options) {
            this.entry = entry;
            this.options = options;
            this.casterId = caster == null ? null : caster.getUniqueId();
            this.world = at.getWorld();
            this.section = entry.recipe().loopSection();
            this.delay = options.delay();
            this.player = new VfxPlayer(entry.recipe(), options.seed());
            place(at);
            boolean everyone = entry.info().source() == VfxInfo.Source.CONTENT
                    && options.viewers() == VfxOptions.Viewers.EVERYONE;
            this.shared = everyone;
            if (everyone) {
                this.viewers = null;
            } else {
                this.viewers = new HashSet<>();
                if (options.viewers() == VfxOptions.Viewers.CASTER) {
                    if (caster != null && (entry.info().source() == VfxInfo.Source.CONTENT
                            || holds.test(caster, entry.info().id()))) {
                        viewers.add(caster.getUniqueId());
                    }
                } else {
                    for (Player p : world.getPlayers()) {
                        if (p.getLocation().distanceSquared(origin) <= HEARING * HEARING && holds.test(p, entry.info().id())) {
                            viewers.add(p.getUniqueId());
                        }
                    }
                }
            }
        }

        /** The cast point and frame for a place, with the options' turn and offset applied. */
        private void place(Location at) {
            float yaw = (float) (at.getYaw() - options.turn());
            Quaternionf f = new Quaternionf().rotationY((float) Math.toRadians(-yaw));
            double[] off = options.offset();
            Vector3f o = f.transform(new Vector3f((float) off[0], (float) off[1], (float) off[2]));
            Location l = at.clone().add(o.x, o.y, o.z);
            l.setYaw(0);
            l.setPitch(0);
            this.origin = l;
            this.frame = f;
        }

        @Override
        public ContentId id() {
            return entry.info().id();
        }

        @Override
        public Location location() {
            return origin.clone();
        }

        @Override
        public boolean running() {
            return !ended;
        }

        @Override
        public void stop() {
            end();
        }

        @Override
        public void finish() {
            if (ended) {
                return;
            }
            // Only a loop has a fade to play out to; a one-shot stopped is stopped.
            if (!options.loop() || options.end() == VfxOptions.End.CUT) {
                end();
            } else {
                stopping = true;
            }
        }

        @Override
        public VfxOptions options() {
            return options;
        }

        @Override
        public long age() {
            return age;
        }

        @Override
        public int passes() {
            return passes;
        }

        private Player caster() {
            return casterId == null ? null : Bukkit.getPlayer(casterId);
        }

        /** Whether the loop should end now, by its time, its count or its condition. */
        private boolean loopIsOver() {
            if (options.loopTicks() > 0 && age >= options.loopTicks()) {
                return true;
            }
            if (options.loopTimes() > 0 && passes >= options.loopTimes()) {
                return true;
            }
            if (age % options.checkEvery() != 0) {
                return false;
            }
            Player caster = caster();
            if (options.until() != null && test(options.until(), caster)) {
                return true;
            }
            if (options.whileTrue() != null && !test(options.whileTrue(), caster)) {
                return true;
            }
            if (options.stopWhen() != null) {
                try {
                    return options.stopWhen().test(this);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("A VFX's stopWhen check threw, so the loop ended: " + e);
                    return true;
                }
            }
            return false;
        }

        private boolean test(String condition, Player caster) {
            return ai.resourcepack.engine.core.dialog.DialogConditions.test(condition,
                    name -> lookup.apply(caster, name),
                    permission -> caster != null && caster.hasPermission(permission));
        }

        /** One server tick: false once it is over. */
        boolean tick() {
            if (ended) {
                return false;
            }
            if (delay > 0) {
                delay--;
                return true;
            }
            Entity follow = options.follow();
            if (follow != null) {
                if (!follow.isValid() || follow.getWorld() != world) {
                    return false;
                }
                place(follow.getLocation());
            }
            if (!world.isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
                return false;
            }
            age++;
            if (options.loop() && !stopping && loopIsOver()) {
                if (options.end() == VfxOptions.End.CUT) {
                    return false;
                }
                stopping = true;
            }
            // Speed: so many recipe ticks this server tick, a fraction carried.
            clock += options.speed();
            int steps = (int) Math.floor(clock);
            clock -= steps;
            if (steps == 0) {
                if (follow != null) {
                    for (Sprite s : live.values()) {
                        s.follow();
                    }
                }
                return true;
            }
            VfxPlayer.Frame last = null;
            List<VfxPlayer.SoundEvent> heardNow = new ArrayList<>();
            for (int i = 0; i < steps; i++) {
                if (!advance()) {
                    break;
                }
                last = player.step();
                heardNow.addAll(last.sounds());
            }
            if (last == null) {
                return false;
            }
            draw(last, Math.max(2, (int) Math.ceil(2 / options.speed())));
            if (options.sounds()) {
                for (VfxPlayer.SoundEvent s : heardNow) {
                    sound(s);
                }
            }
            return true;
        }

        /** Gets the player ready for its next step, going round the loop; false once there is nothing left to play. */
        private boolean advance() {
            boolean looping = options.loop() && !stopping;
            if (section != null && looping && player.tick() == section[1]) {
                passes++;
                if (options.loopTimes() > 0 && passes >= options.loopTimes()) {
                    stopping = true;
                } else {
                    player.jumpTo(section[0]);
                }
            }
            if (player.done()) {
                if (section == null && looping) {
                    passes++;
                    if (options.loopTimes() > 0 && passes >= options.loopTimes()) {
                        return false;
                    }
                    player = new VfxPlayer(entry.recipe(), options.vary() ? options.seed() + passes : options.seed());
                    return true;
                }
                return false;
            }
            return true;
        }

        private void draw(VfxPlayer.Frame frame, int interpolation) {
            Set<String> seen = new HashSet<>();
            for (VfxPlayer.Instance inst : frame.instances()) {
                seen.add(inst.id());
                Sprite s = live.get(inst.id());
                if (s == null) {
                    s = new Sprite(inst);
                    live.put(inst.id(), s);
                } else {
                    s.pose(inst, interpolation);
                }
            }
            Iterator<Map.Entry<String, Sprite>> it = live.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Sprite> e = it.next();
                if (!seen.contains(e.getKey())) {
                    e.getValue().remove();
                    it.remove();
                }
            }
        }

        private boolean sees(Player p) {
            return viewers == null || viewers.contains(p.getUniqueId());
        }

        private void sound(VfxPlayer.SoundEvent s) {
            Location at = loc(s.at());
            float volume = (float) (s.volume() * options.volume());
            if (volume <= 0) {
                return;
            }
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(at) > HEARING * HEARING || !sees(p)) {
                    continue;
                }
                p.playSound(at, s.sound(), SoundCategory.PLAYERS, volume, (float) s.pitch());
                heard.add(p.getUniqueId());
            }
            sounds.add(s.sound());
        }

        void end() {
            if (ended) {
                return;
            }
            ended = true;
            for (Sprite s : live.values()) {
                s.remove();
            }
            live.clear();
            for (UUID id : heard) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    for (String key : sounds) {
                        p.stopSound(key, SoundCategory.PLAYERS);
                    }
                }
            }
            playing.remove(this);
        }

        /** A point in the effect's frame, scaled, as an offset in the world. */
        Vector3f world(double[] local) {
            double k = options.scale();
            return frame.transform(new Vector3f((float) (local[0] * k), (float) (local[1] * k), (float) (local[2] * k)));
        }

        Location loc(double[] local) {
            Vector3f w = world(local);
            return origin.clone().add(w.x, w.y, w.z);
        }

        /** One sprite of this effect: an item display showing its picture. */
        final class Sprite {
            final ItemDisplay display;
            final String model;
            final boolean billboard;
            int level;
            double[] pos;

            Sprite(VfxPlayer.Instance inst) {
                String paletteId = inst.palette() != null ? VfxPainter.paletteId(inst.palette(), entry.recipe().palettes) : null;
                this.model = VfxPack.itemModel(inst.sprite(), paletteId);
                this.billboard = inst.billboard();
                this.level = inst.level();
                this.pos = inst.pos();
                boolean glows = !VfxData.get().spriteOf(inst.sprite()).shade();
                Location spawn = billboard ? loc(inst.pos()) : origin.clone();
                spawn.setYaw(0);
                spawn.setPitch(0);
                Transformation first = transformation(inst);
                this.display = world.spawn(spawn, ItemDisplay.class, d -> {
                    d.setItemStack(item(model, inst.level()));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setBillboard(billboard ? Display.Billboard.CENTER : Display.Billboard.FIXED);
                    if (glows) {
                        d.setBrightness(new Display.Brightness(15, 15));
                    }
                    d.setTransformation(first);
                    d.setShadowRadius(0);
                    d.setViewRange(4f);
                    d.setPersistent(false);
                    VfxDisplays.glide(d, 2);
                    d.addScoreboardTag(TAG);
                    if (!shared) {
                        d.setVisibleByDefault(false);
                    }
                });
                if (!shared) {
                    for (UUID id : viewers) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            p.showEntity(plugin, display);
                        }
                    }
                }
            }

            private Transformation transformation(VfxPlayer.Instance inst) {
                double k = options.scale();
                Quaternionf local = new Quaternionf((float) inst.rot()[0], (float) inst.rot()[1], (float) inst.rot()[2], (float) inst.rot()[3]);
                Vector3f scale = new Vector3f((float) (inst.scale()[0] * k), (float) (inst.scale()[1] * k), (float) (inst.scale()[2] * k));
                if (billboard) {
                    return new Transformation(new Vector3f(), local, scale, NONE);
                }
                return new Transformation(world(inst.pos()), new Quaternionf(frame).mul(local), scale, NONE);
            }

            void pose(VfxPlayer.Instance inst, int interpolation) {
                if (!display.isValid()) {
                    return;
                }
                pos = inst.pos();
                if (inst.level() != level) {
                    level = inst.level();
                    display.setItemStack(item(model, level));
                }
                if (billboard) {
                    display.teleport(loc(inst.pos()));
                } else if (options.follow() != null) {
                    display.teleport(origin);
                }
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(interpolation);
                display.setTransformation(transformation(inst));
            }

            /** Keeps up with what it follows on a tick with no new frame (a slowed effect). */
            void follow() {
                if (!display.isValid()) {
                    return;
                }
                display.teleport(billboard ? loc(pos) : origin);
            }

            void remove() {
                display.remove();
            }
        }
    }
}
