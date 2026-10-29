package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Objects;

/**
 * A piece of music: a playlist of the pack's own sounds, and where it plays.
 *
 * <p>Minecraft has no music of a server's choosing. Its own soundtrack is
 * picked by the client from the biome, and a sound event is a one-shot, so
 * what plays here is the server starting each track as the one before it ends.
 * That is why a track needs a length: the engine reads it off the file, or the
 * sound's {@code length:} says it.
 */
public final class MusicInfo {

    private final ContentId id;
    private final List<ContentId> tracks;
    private final boolean shuffle;
    private final boolean loop;
    private final double gap;
    private final boolean vanilla;
    private final int priority;
    private final boolean placed;

    private MusicInfo(ContentId id, List<ContentId> tracks, boolean shuffle, boolean loop, double gap,
                      boolean vanilla, int priority, boolean placed) {
        this.id = id;
        this.tracks = tracks;
        this.shuffle = shuffle;
        this.loop = loop;
        this.gap = gap;
        this.vanilla = vanilla;
        this.priority = priority;
        this.placed = placed;
    }

    /** Engine internal; built by the music loader. */
    public static MusicInfo of(ContentId id, List<ContentId> tracks, boolean shuffle, boolean loop, double gap,
                               boolean vanilla, int priority, boolean placed) {
        return new MusicInfo(Objects.requireNonNull(id, "id"), List.copyOf(tracks), shuffle, loop,
                Math.max(0, gap), vanilla, priority, placed);
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /** The sounds it plays, in the order written. */
    public List<ContentId> tracks() {
        return tracks;
    }

    /** Whether the order is shuffled, afresh each time round. */
    public boolean shuffle() {
        return shuffle;
    }

    /** Whether it starts again after the last track, rather than falling silent. */
    public boolean loop() {
        return loop;
    }

    /** Seconds of silence between one track and the next. */
    public double gap() {
        return gap;
    }

    /**
     * Whether Minecraft's own music may play over it. False by default: the
     * game's soundtrack starting halfway through somebody's lobby theme is two
     * pieces of music at once.
     */
    public boolean vanilla() {
        return vanilla;
    }

    /** Which wins where two places overlap: the higher. */
    public int priority() {
        return priority;
    }

    /**
     * Whether it plays by itself somewhere (a world, a WorldGuard region or an
     * area). Music without a place plays only when something starts it.
     */
    public boolean placed() {
        return placed;
    }

    @Override
    public String toString() {
        return id + " (" + tracks.size() + " tracks)";
    }
}
