package ai.resourcepack.engine.core.vfx;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Vfx;
import ai.resourcepack.engine.api.VfxInfo;
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
 */
public final class VfxRuntime implements Vfx {

    /** On every display, so a crash's leftovers can be found; they are not persistent anyway. */
    public static final String TAG = "rpengine_vfx";
    /** Effects playing at once. Each is up to a few hundred entities, so the oldest gives way. */
    static final int MAX_PLAYING = 32;
    /** How far a sound and a hidden display's viewers are looked for. */
    private static final double HEARING = 64;
    private static final Quaternionf NONE = new Quaternionf();

    private final Plugin plugin;
    private final VfxCatalogue catalogue;
    private final boolean supported;
    /** Whether a player's own pack carries a Studio effect; content-folder effects are everybody's. */
    private final BiPredicate<Player, ContentId> holds;
    private final List<Play> playing = new ArrayList<>();
    private final Map<String, ItemStack> items = new HashMap<>();
    private BukkitTask task;

    public VfxRuntime(Plugin plugin, VfxCatalogue catalogue, boolean supported, BiPredicate<Player, ContentId> holds) {
        this.plugin = plugin;
        this.catalogue = catalogue;
        this.supported = supported;
        this.holds = holds;
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
                if (!play.step()) {
                    play.end();
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("VFX " + play.entry.info().id() + " stopped: " + e);
                play.end();
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
        if (!supported || at == null || at.getWorld() == null) {
            return Optional.empty();
        }
        return catalogue.entry(id).map(entry -> start(entry, at));
    }

    @Override
    public Optional<VfxPlay> play(ContentId id, Player caster) {
        return caster == null ? Optional.empty() : play(id, caster.getLocation());
    }

    /** Plays an entry already looked up (by {@code /rp cast}). */
    public Optional<VfxPlay> play(VfxEntry entry, Location at) {
        if (!supported || entry == null || at == null || at.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.of(start(entry, at));
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

    private Play start(VfxEntry entry, Location at) {
        while (playing.size() >= MAX_PLAYING) {
            playing.get(0).end();
        }
        Play play = new Play(entry, at);
        playing.add(play);
        return play;
    }

    private ItemStack item(String model, int level) {
        return items.computeIfAbsent(model + "#" + level, k -> VfxDisplays.item(model, level)).clone();
    }

    /** One effect playing at one place. */
    private final class Play implements VfxPlay {
        final VfxEntry entry;
        final Location origin;
        final World world;
        final Quaternionf frame;
        final VfxPlayer player;
        final boolean shared;
        /** Who may see and hear it: everybody near for a content-folder effect, the holders of the push for a Studio one. */
        final Set<UUID> viewers = new HashSet<>();
        final Map<String, Sprite> live = new HashMap<>();
        final Set<String> sounds = new LinkedHashSet<>();
        final Set<UUID> heard = new HashSet<>();
        boolean ended;

        Play(VfxEntry entry, Location at) {
            this.entry = entry;
            this.origin = at.clone();
            this.world = at.getWorld();
            this.frame = new Quaternionf().rotationY((float) Math.toRadians(-at.getYaw()));
            this.player = new VfxPlayer(entry.recipe());
            this.shared = entry.info().source() == VfxInfo.Source.CONTENT;
            if (!shared) {
                for (Player p : world.getPlayers()) {
                    if (p.getLocation().distanceSquared(origin) <= HEARING * HEARING && holds.test(p, entry.info().id())) {
                        viewers.add(p.getUniqueId());
                    }
                }
            }
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

        /** Draws the next tick; false once the timeline is over. */
        boolean step() {
            if (ended || player.done() || !world.isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
                return false;
            }
            VfxPlayer.Frame frame = player.step();
            Set<String> seen = new HashSet<>();
            for (VfxPlayer.Instance inst : frame.instances()) {
                seen.add(inst.id());
                Sprite s = live.get(inst.id());
                if (s == null) {
                    s = new Sprite(inst);
                    live.put(inst.id(), s);
                } else {
                    s.pose(inst, 2);
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
            for (VfxPlayer.SoundEvent s : frame.sounds()) {
                sound(s);
            }
            return true;
        }

        private void sound(VfxPlayer.SoundEvent s) {
            Location at = loc(s.at());
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(at) > HEARING * HEARING || !(shared || viewers.contains(p.getUniqueId()))) {
                    continue;
                }
                p.playSound(at, s.sound(), SoundCategory.PLAYERS, (float) s.volume(), (float) s.pitch());
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

        Vector3f world(double[] local) {
            return frame.transform(new Vector3f((float) local[0], (float) local[1], (float) local[2]));
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

            Sprite(VfxPlayer.Instance inst) {
                String paletteId = inst.palette() != null ? VfxPainter.paletteId(inst.palette(), entry.recipe().palettes) : null;
                this.model = VfxPack.itemModel(inst.sprite(), paletteId);
                this.billboard = inst.billboard();
                this.level = inst.level();
                boolean glows = !VfxData.get().spriteOf(inst.sprite()).shade();
                Location spawn = origin.clone();
                spawn.setYaw(0);
                spawn.setPitch(0);
                if (billboard) {
                    spawn = loc(inst.pos());
                    spawn.setYaw(0);
                    spawn.setPitch(0);
                }
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
                Quaternionf local = new Quaternionf((float) inst.rot()[0], (float) inst.rot()[1], (float) inst.rot()[2], (float) inst.rot()[3]);
                Vector3f scale = new Vector3f((float) inst.scale()[0], (float) inst.scale()[1], (float) inst.scale()[2]);
                if (billboard) {
                    return new Transformation(new Vector3f(), local, scale, NONE);
                }
                return new Transformation(world(inst.pos()), new Quaternionf(frame).mul(local), scale, NONE);
            }

            void pose(VfxPlayer.Instance inst, int interpolation) {
                if (!display.isValid()) {
                    return;
                }
                if (inst.level() != level) {
                    level = inst.level();
                    display.setItemStack(item(model, level));
                }
                if (billboard) {
                    Location to = loc(inst.pos());
                    to.setYaw(0);
                    to.setPitch(0);
                    display.teleport(to);
                }
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(interpolation);
                display.setTransformation(transformation(inst));
            }

            void remove() {
                display.remove();
            }
        }
    }
}
