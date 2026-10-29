package ai.resourcepack.engine.core.music;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.MusicInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/**
 * One player's music: which piece, where in it, and when the next track is
 * due. Free of Bukkit; {@link #advance} says what to stop and what to start
 * and the runtime does it.
 *
 * <p>Time is in server ticks, counted by the runtime. A track is due when the
 * one before it has run its length and the gap after it, which is the only way
 * to follow one sound with another in a game whose sounds are one-shots.
 */
final class Playback {

    /** "Never": the track playing has no length, or the playlist has ended. */
    static final long NEVER = Long.MAX_VALUE;

    /** What one step asks of the runtime. Either may be null. */
    record Step(ContentId stop, ContentId play) {
        static final Step NOTHING = new Step(null, null);
    }

    private MusicInfo music;
    private List<ContentId> order = List.of();
    private int position;
    private ContentId track;
    private long due = NEVER;

    MusicInfo music() {
        return music;
    }

    ContentId track() {
        return track;
    }

    /**
     * The step at tick {@code now} for a player who should be hearing
     * {@code wanted} (null for silence).
     *
     * @param length a track's length in seconds; 0 or less when unknown, and
     *               negative when the server has no such sound at all
     */
    Step advance(MusicInfo wanted, long now, ToDoubleFunction<ContentId> length, Random random) {
        if (wanted == null) {
            return silence();
        }
        if (music != wanted) {
            // A different piece, or the same id from a reload (which is a new
            // object, and may have different tracks): start it over.
            ContentId stopping = track;
            music = wanted;
            order = shuffled(wanted, random, null);
            position = -1;
            track = null;
            due = now;
            Step next = next(now, length, random);
            return new Step(stopping, next.play());
        }
        if (now < due) {
            return Step.NOTHING;
        }
        return next(now, length, random);
    }

    /** Straight on to the next track, as {@code /rp music skip} asks. */
    Step skip(long now, ToDoubleFunction<ContentId> length, Random random) {
        if (music == null) {
            return Step.NOTHING;
        }
        ContentId stopping = track;
        Step next = next(now, length, random);
        return new Step(stopping, next.play());
    }

    /** Stops whatever is playing and forgets the piece. */
    Step silence() {
        ContentId stopping = track;
        music = null;
        order = List.of();
        track = null;
        due = NEVER;
        return stopping == null ? Step.NOTHING : new Step(stopping, null);
    }

    /**
     * The next playable track. Tracks the server has no sound for are passed
     * over, and a whole round of them ends the music rather than spinning.
     */
    private Step next(long now, ToDoubleFunction<ContentId> length, Random random) {
        for (int tried = 0; tried <= order.size(); tried++) {
            position++;
            if (position >= order.size()) {
                if (!music.loop()) {
                    track = null;
                    due = NEVER;
                    return Step.NOTHING;
                }
                order = shuffled(music, random, track);
                position = 0;
            }
            ContentId candidate = order.get(position);
            double seconds = length.applyAsDouble(candidate);
            if (seconds < 0) {
                continue;
            }
            track = candidate;
            due = seconds > 0 ? now + ticks(seconds) + ticks(music.gap()) : NEVER;
            return new Step(null, candidate);
        }
        track = null;
        due = NEVER;
        return Step.NOTHING;
    }

    /**
     * The order for one time round. Shuffled when asked, and never starting
     * with the track that just ended, which is what a shuffle that repeats a
     * song back to back sounds like.
     */
    private static List<ContentId> shuffled(MusicInfo music, Random random, ContentId last) {
        if (!music.shuffle() || music.tracks().size() < 2) {
            return music.tracks();
        }
        List<ContentId> out = new ArrayList<>(music.tracks());
        Collections.shuffle(out, random);
        if (out.get(0).equals(last)) {
            Collections.swap(out, 0, 1 + random.nextInt(out.size() - 1));
        }
        return List.copyOf(out);
    }

    private static long ticks(double seconds) {
        return Math.max(0, Math.round(seconds * 20));
    }
}
