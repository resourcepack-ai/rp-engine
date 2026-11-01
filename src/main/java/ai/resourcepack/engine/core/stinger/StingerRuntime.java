package ai.resourcepack.engine.core.stinger;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.StingerInfo;
import ai.resourcepack.engine.api.StingerPlay;
import ai.resourcepack.engine.api.Stingers;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.logging.Level;

/**
 * Plays stingers by sending titles; the pack's text shader does the drawing.
 *
 * <p>Three steps per play, each on the player's own thread
 * ({@link Tasks#onLater}, so Folia is fine): the IN title (fading in over the
 * in ticks, held long), then at the end of the fade the screen is covered and
 * the caller's callback runs, then the OUT title (shown at full alpha and
 * fading out over the out ticks: a fresh title restarts the client's fade).
 * While it holds, the in title is sent again every {@link #REFRESH_TICKS} with
 * no fade, so a long or open-ended hold never runs past the title's stay, and
 * a client that dropped its title gets it back already covered.
 */
public final class StingerRuntime implements Stingers, Listener {

    /** How often a held cover is sent again. */
    static final int REFRESH_TICKS = 40;
    /** How long each of those sends holds for: comfortably past the next one. */
    static final int REFRESH_STAY = REFRESH_TICKS * 3;

    private final Plugin plugin;
    private final StingerCatalogue catalogue;
    private final BiPredicate<Player, ContentId> holds;
    private final Map<UUID, Play> playing = new ConcurrentHashMap<>();

    /**
     * @param holds whether a player's pack holds a stinger: a stinger's glyph
     *              and shader are only in the pack it came in
     */
    public StingerRuntime(Plugin plugin, StingerCatalogue catalogue, BiPredicate<Player, ContentId> holds) {
        this.plugin = plugin;
        this.catalogue = catalogue;
        this.holds = holds;
    }

    @Override
    public List<ContentId> ids() {
        return catalogue.ids();
    }

    @Override
    public Optional<StingerInfo> info(ContentId id) {
        return catalogue.entry(id).map(StingerEntry::info);
    }

    @Override
    public Optional<StingerInfo> find(String name) {
        return catalogue.find(name).map(StingerEntry::info);
    }

    @Override
    public boolean canPlay(Player player, ContentId id) {
        return player != null && id != null && catalogue.entry(id).isPresent()
                && (holds == null || holds.test(player, id));
    }

    @Override
    public Optional<StingerPlay> play(Player player, ContentId id, Runnable whenCovered) {
        return catalogue.entry(id).flatMap(entry -> start(player, entry, entry.info().holdTicks(), whenCovered));
    }

    @Override
    public Optional<StingerPlay> play(Player player, ContentId id, int holdTicks, Runnable whenCovered) {
        return catalogue.entry(id).flatMap(entry -> start(player, entry, Math.max(0, holdTicks), whenCovered));
    }

    @Override
    public Optional<StingerPlay> cover(Player player, ContentId id, Runnable whenCovered) {
        return catalogue.entry(id).flatMap(entry -> start(player, entry, -1, whenCovered));
    }

    @Override
    public Optional<StingerPlay> playing(Player player) {
        return player == null ? Optional.empty() : Optional.ofNullable(playing.get(player.getUniqueId()));
    }

    @Override
    public boolean stop(Player player) {
        Play play = player == null ? null : playing.get(player.getUniqueId());
        if (play == null) {
            return false;
        }
        play.cancel();
        return true;
    }

    /** Ends every play quietly, for the plugin going away. */
    public void stopAll() {
        for (Play play : List.copyOf(playing.values())) {
            play.end(false);
        }
    }

    /**
     * Starts a play.
     *
     * @param holdTicks how long it holds once covered, or -1 to hold until released
     */
    private Optional<StingerPlay> start(Player player, StingerEntry entry, int holdTicks, Runnable whenCovered) {
        if (player == null || !player.isOnline() || !canPlay(player, entry.info().id())) {
            return Optional.empty();
        }
        Play play = new Play(player, entry, holdTicks, whenCovered);
        Play old = playing.put(player.getUniqueId(), play);
        if (old != null) {
            // Its titles are about to be overwritten by this one's.
            old.end(false);
        }
        Tasks.now(plugin, player, play::begin);
        return Optional.of(play);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Play play = playing.get(event.getPlayer().getUniqueId());
        if (play != null) {
            play.end(false);
        }
    }

    private enum Phase { STARTING, IN, HOLD, OUT, DONE }

    private final class Play implements StingerPlay {
        private final Player player;
        private final StingerEntry entry;
        private final int holdTicks;
        private final Runnable whenCovered;
        private final String inTitle;
        private final String outTitle;

        private Phase phase = Phase.STARTING;
        private boolean releaseAsked;
        private Tasks.Handle step = Tasks.Handle.NONE;
        private Tasks.Handle refresh = Tasks.Handle.NONE;

        Play(Player player, StingerEntry entry, int holdTicks, Runnable whenCovered) {
            this.player = player;
            this.entry = entry;
            this.holdTicks = holdTicks;
            this.whenCovered = whenCovered;
            this.inTitle = StingerTitles.title(entry.inColor(), entry.glyph());
            this.outTitle = StingerTitles.title(entry.outColor(), entry.glyph());
        }

        @Override
        public ContentId id() {
            return entry.info().id();
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public synchronized boolean isCovered() {
            return phase == Phase.HOLD;
        }

        @Override
        public synchronized boolean isFinished() {
            return phase == Phase.DONE;
        }

        @Override
        public void release() {
            synchronized (this) {
                if (phase == Phase.STARTING || phase == Phase.IN) {
                    releaseAsked = true;
                    return;
                }
                if (phase != Phase.HOLD) {
                    return;
                }
            }
            Tasks.now(plugin, player, this::leave);
        }

        @Override
        public void cancel() {
            end(true);
        }

        /** Ends it; {@code clear} also takes the title off the player's screen. */
        void end(boolean clear) {
            synchronized (this) {
                if (phase == Phase.DONE) {
                    return;
                }
                phase = Phase.DONE;
                step.cancel();
                refresh.cancel();
            }
            playing.remove(player.getUniqueId(), this);
            if (clear && player.isOnline()) {
                Tasks.now(plugin, player, player::resetTitle);
            }
        }

        void begin() {
            synchronized (this) {
                if (phase != Phase.STARTING) {
                    return;
                }
                phase = Phase.IN;
            }
            StingerInfo info = entry.info();
            int stay = holdTicks < 0 ? REFRESH_STAY : holdTicks + REFRESH_STAY;
            player.sendTitle(inTitle, "", info.inTicks(), stay, 0);
            info.soundIn().ifPresent(this::sound);
            schedule(this::covered, info.inTicks());
        }

        private void covered() {
            synchronized (this) {
                if (phase != Phase.IN) {
                    return;
                }
                phase = Phase.HOLD;
            }
            if (whenCovered != null) {
                try {
                    whenCovered.run();
                } catch (Throwable e) {
                    plugin.getLogger().log(Level.WARNING, "A stinger's covered callback (" + id() + ") threw", e);
                }
            }
            boolean leaveNow;
            synchronized (this) {
                if (phase != Phase.HOLD) {
                    return; // cancelled or replaced from inside the callback
                }
                leaveNow = releaseAsked || holdTicks == 0;
                if (!leaveNow) {
                    refresh = Tasks.onTimer(plugin, player, this::resend, REFRESH_TICKS, REFRESH_TICKS);
                    if (holdTicks > 0) {
                        step = Tasks.onLater(plugin, player, this::leave, holdTicks);
                    }
                }
            }
            if (leaveNow) {
                leave();
            }
        }

        private void resend() {
            synchronized (this) {
                if (phase != Phase.HOLD) {
                    return;
                }
            }
            player.sendTitle(inTitle, "", 0, REFRESH_STAY, 0);
        }

        private void leave() {
            synchronized (this) {
                if (phase != Phase.HOLD) {
                    return;
                }
                phase = Phase.OUT;
                step.cancel();
                refresh.cancel();
            }
            StingerInfo info = entry.info();
            player.sendTitle(outTitle, "", 0, 0, info.outTicks());
            info.soundOut().ifPresent(this::sound);
            schedule(this::finished, info.outTicks());
        }

        private void finished() {
            synchronized (this) {
                if (phase != Phase.OUT) {
                    return;
                }
                phase = Phase.DONE;
            }
            playing.remove(player.getUniqueId(), this);
        }

        private void schedule(Runnable next, int ticks) {
            synchronized (this) {
                if (phase == Phase.DONE) {
                    return;
                }
                step = Tasks.onLater(plugin, player, next, () -> end(false), Math.max(1, ticks));
            }
        }

        private void sound(String sound) {
            try {
                player.playSound(player.getLocation(), StingerTitles.soundKey(sound), SoundCategory.MASTER, 1f, 1f);
            } catch (IllegalArgumentException ignored) {
                // Not an id the server accepts; the stinger plays silent.
            }
        }
    }
}
