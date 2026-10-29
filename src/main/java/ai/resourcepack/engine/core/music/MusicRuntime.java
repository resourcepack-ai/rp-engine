package ai.resourcepack.engine.core.music;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Music;
import ai.resourcepack.engine.api.MusicInfo;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.core.hook.WorldGuardHook;
import ai.resourcepack.engine.core.sound.SoundsImpl;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Plays music. Internal; {@link Music} is what a plugin sees.
 *
 * <p>Every half second it works out what each online player should be hearing
 * and hands that to their {@link Playback}, which says what to stop and start.
 * Half a second is how late a track can start after the one before it, which
 * a {@code gap:} of a second or so hides, and how late music changes when
 * somebody walks into a place: quick enough not to notice, and cheap.
 */
public final class MusicRuntime implements Music, Listener {

    private static final long PERIOD = 10;

    /** How often Minecraft's own music is told to stop while ours plays: every ten seconds. */
    private static final long VANILLA_SWEEP = 200;

    /**
     * Minecraft's music events, for when the registry cannot be listed.
     * Stopping a name the client does not have does nothing, so this can be
     * generous across versions.
     */
    private static final List<String> VANILLA_FALLBACK = List.of(
            "music.creative", "music.credits", "music.dragon", "music.end", "music.game", "music.menu",
            "music.under_water", "music.nether.basalt_deltas", "music.nether.crimson_forest",
            "music.nether.nether_wastes", "music.nether.soul_sand_valley", "music.nether.warped_forest",
            "music.overworld.deep_dark", "music.overworld.dripstone_caves", "music.overworld.grove",
            "music.overworld.jagged_peaks", "music.overworld.lush_caves", "music.overworld.swamp",
            "music.overworld.forest", "music.overworld.old_growth_taiga", "music.overworld.meadow",
            "music.overworld.cherry_grove", "music.overworld.frozen_peaks", "music.overworld.snowy_slopes",
            "music.overworld.stony_peaks", "music.overworld.flower_forest", "music.overworld.desert",
            "music.overworld.badlands", "music.overworld.jungle", "music.overworld.sparse_jungle",
            "music.overworld.bamboo_jungle");

    private final Plugin plugin;
    private final SoundsImpl sounds;
    private final Random random = new Random();

    private volatile Map<ContentId, MusicInfo> music = Map.of();
    private Map<ContentId, MusicPlace> places = Map.of();
    private boolean anyRegions;

    private final Map<UUID, Playback> playing = new HashMap<>();
    /** What a command or plugin started, which beats where the player is. */
    private final Map<UUID, ContentId> started = new HashMap<>();
    /** The place music a player stopped, which stays quiet until they leave it. */
    private final Map<UUID, ContentId> hushed = new HashMap<>();

    private List<String> vanillaMusic;
    private BukkitTask task;
    private long now;

    public MusicRuntime(Plugin plugin, SoundsImpl sounds) {
        this.plugin = plugin;
        this.sounds = sounds;
    }

    /** Replaces the music, as a reload does. Everything playing starts again from the new definitions. */
    public void replace(MusicDefinitions.Result loaded) {
        for (Map.Entry<UUID, Playback> entry : playing.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                apply(player, null, entry.getValue().silence());
            }
        }
        playing.clear();
        music = loaded == null ? Map.of() : loaded.music();
        places = loaded == null ? Map.of() : loaded.places();
        anyRegions = places.values().stream().anyMatch(place -> !place.regions().isEmpty())
                && Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
        started.values().removeIf(id -> !music.containsKey(id));
        hushed.clear();
        if (task == null && !music.isEmpty()) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
        }
    }

    /** Stops every track and the timer, for shutdown. */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        replace(null);
    }

    @Override
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(music.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    @Override
    public Optional<MusicInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(music.get(id));
    }

    @Override
    public boolean play(Player player, ContentId id) {
        MusicInfo info = music.get(id);
        if (player == null || info == null) {
            return false;
        }
        started.put(player.getUniqueId(), id);
        // Again from the top, even if it was already playing: that is what
        // somebody asking for it means.
        Playback playback = playback(player);
        apply(player, null, playback.silence());
        apply(player, info, playback.advance(info, now, this::length, random));
        return true;
    }

    @Override
    public void stop(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        // Started music ends and the place's comes back, which is what a boss
        // fight ending wants. With nothing started, it is the place's music
        // being asked to stop, and it stays stopped until they leave.
        if (started.remove(id) == null) {
            MusicInfo here = placeMusic(player);
            if (here != null) {
                hushed.put(id, here.id());
            }
        }
        Playback playback = playing.get(id);
        if (playback != null) {
            apply(player, null, playback.silence());
        }
    }

    @Override
    public boolean skip(Player player) {
        Playback playback = player == null ? null : playing.get(player.getUniqueId());
        if (playback == null || playback.music() == null) {
            return false;
        }
        apply(player, playback.music(), playback.skip(now, this::length, random));
        return true;
    }

    @Override
    public Optional<ContentId> playing(Player player) {
        Playback playback = player == null ? null : playing.get(player.getUniqueId());
        return playback == null || playback.music() == null
                ? Optional.empty() : Optional.of(playback.music().id());
    }

    @Override
    public Optional<ContentId> track(Player player) {
        Playback playback = player == null ? null : playing.get(player.getUniqueId());
        return playback == null ? Optional.empty() : Optional.ofNullable(playback.track());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        playing.remove(id);
        started.remove(id);
        hushed.remove(id);
    }

    private void tick() {
        now += PERIOD;
        if (music.isEmpty() && playing.isEmpty()) {
            return;
        }
        boolean sweep = now % VANILLA_SWEEP == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            MusicInfo wanted = wanted(player);
            Playback playback = playing.get(player.getUniqueId());
            if (wanted == null && playback == null) {
                continue;
            }
            playback = playback(player);
            apply(player, wanted, playback.advance(wanted, now, this::length, random));
            if (playback.music() == null) {
                playing.remove(player.getUniqueId());
            } else if (sweep && !wanted.vanilla() && playback.track() != null) {
                hushVanilla(player);
            }
        }
    }

    /** What a player should hear now: what was started for them, or their place's music. */
    private MusicInfo wanted(Player player) {
        UUID id = player.getUniqueId();
        ContentId forced = started.get(id);
        if (forced != null) {
            return music.get(forced);
        }
        MusicInfo here = placeMusic(player);
        ContentId quiet = hushed.get(id);
        if (quiet != null) {
            if (here != null && here.id().equals(quiet)) {
                return null;
            }
            hushed.remove(id);
        }
        return here;
    }

    /** The music of the place a player is in, the highest priority first, then by id. */
    private MusicInfo placeMusic(Player player) {
        Location at = player.getLocation();
        if (at.getWorld() == null) {
            return null;
        }
        String world = at.getWorld().getName();
        Set<String> regions = anyRegions ? WorldGuardHook.regionsAt(at) : Set.of();
        MusicInfo best = null;
        for (Map.Entry<ContentId, MusicPlace> entry : places.entrySet()) {
            MusicInfo candidate = music.get(entry.getKey());
            if (candidate == null || !entry.getValue().contains(world, at.getX(), at.getY(), at.getZ(), regions)) {
                continue;
            }
            if (best == null || candidate.priority() > best.priority()
                    || (candidate.priority() == best.priority() && candidate.id().compareTo(best.id()) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private Playback playback(Player player) {
        return playing.computeIfAbsent(player.getUniqueId(), key -> new Playback());
    }

    /** A track's length for {@link Playback}: negative when the server has no such sound. */
    private double length(ContentId id) {
        return sounds.info(id).map(SoundInfo::length).orElse(-1d);
    }

    private void apply(Player player, MusicInfo music, Playback.Step step) {
        if (step.stop() != null) {
            sounds.stop(player, step.stop());
        }
        if (step.play() != null) {
            if (music != null && !music.vanilla()) {
                hushVanilla(player);
            }
            sounds.playFollowing(player, step.play());
        }
    }

    /**
     * Stops Minecraft's own music for one player, by name. Not by category:
     * that would stop ours too, which is very likely in the music category.
     * The client starts its own again on a timer of its own, so this is done
     * as each track starts and every ten seconds after.
     */
    private void hushVanilla(Player player) {
        for (String name : vanillaMusic()) {
            player.stopSound(name);
        }
    }

    private List<String> vanillaMusic() {
        if (vanillaMusic != null) {
            return vanillaMusic;
        }
        List<String> found = new ArrayList<>();
        try {
            // As Keyed rather than Sound: Sound is an enum on the oldest
            // servers this runs on and an interface on the newest.
            for (Object sound : org.bukkit.Registry.SOUNDS) {
                NamespacedKey key = ((Keyed) sound).getKey();
                if (NamespacedKey.MINECRAFT.equals(key.getNamespace()) && key.getKey().startsWith("music.")) {
                    found.add(key.getKey());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            found.clear();
        }
        vanillaMusic = found.isEmpty() ? VANILLA_FALLBACK : List.copyOf(found);
        return vanillaMusic;
    }
}
