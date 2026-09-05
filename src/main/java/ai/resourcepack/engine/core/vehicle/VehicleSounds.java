package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.api.Sounds;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleState;

import org.bukkit.entity.Entity;

import java.util.Optional;
import java.util.Set;

/**
 * A vehicle's engine note, and everything else it makes a noise about.
 *
 * <p>One of these per vehicle, because what it holds is where that vehicle has
 * got to in the sound it is playing.
 *
 * <h2>Minecraft has no looping sound, and that is the whole design</h2>
 *
 * <p>A sound event is a one-shot. Everything in this game that plays
 * continuously is a server re-playing a short file on a timer, so a running
 * engine here is the same: play it, wait exactly as long as the file runs,
 * play it again. That is why {@link SoundInfo#length()} exists — without the
 * length there is no timer that is not a guess, and a guess is either a gap or
 * an overlap, both of which are more noticeable than either would sound
 * written down.
 *
 * <p><strong>A sound with no length plays ONCE per state.</strong> Not on a
 * default interval: a two-second engine looped every half second is four
 * engines, and silence is a better wrong answer than that. It is also the
 * honest one — nobody told us how long the file is.
 *
 * <h2>One state at a time</h2>
 *
 * <p>Read exactly like the animation map — the highest active state that has a
 * sound wins, and a blank state falls through to the next. An emitter fires on
 * any of its states because two particle effects at once is a busy vehicle;
 * two engine notes at once is a broken one.
 *
 * <h2>The chassis is the source</h2>
 *
 * <p>Each repeat is played from the vehicle's chassis entity rather than from
 * the coordinate where the repeat began. The client can therefore keep the
 * positional sound on the vehicle while it drives and turns instead of fading
 * or panning toward a point the vehicle already left behind.
 */
final class VehicleSounds {

    /** What is playing, or null when the vehicle is in no state that sounds. */
    private String playing;

    /**
     * The tick the current sound is due to be played again on.
     *
     * <p>{@link Long#MAX_VALUE} is "never" — a sound with no length, which
     * has already had its one play.
     */
    private long nextTick;

    /**
     * One tick's worth of noise.
     *
     * @param source the vehicle's chassis, which the sound follows
     * @param age   the vehicle's own tick counter, which is what the repeat is
     *              measured in. A parked vehicle only reaches here every few
     *              ticks, so a repeat can be that late — inaudible against a
     *              loop measured in seconds, and worth knowing before shorter
     *              ones are allowed
     */
    void play(Sounds sounds, Entity source, VehicleInfo info, Set<VehicleState> states, long age) {
        if (sounds == null || source == null || info.sounds().isEmpty()) {
            // Not just an optimisation: `playing` must not be cleared for a
            // vehicle whose map is empty, or nothing else here would ever run.
            return;
        }
        String wanted = VehicleState.choose(states, info.sounds()).orElse(null);
        if (wanted == null) {
            playing = null;
            return;
        }
        if (!wanted.equals(playing)) {
            // A change is heard immediately. The old sound keeps playing to
            // its end on every client that started it — there is no way to
            // stop one that names no source — which is why a state change
            // mid-loop overlaps for a moment rather than cutting.
            playing = wanted;
            nextTick = age;
        }
        if (age < nextTick) {
            return;
        }
        Optional<ContentId> id = ContentId.parse(wanted);
        if (id.isEmpty() || !sounds.playFrom(source, id.get())) {
            // No such sound, or a name that is not an id at all. Silent and
            // never retried for as long as this state holds: this runs twenty
            // times a second, and a lookup per tick for a sound that does not
            // exist is the shape of thing that costs a server its tick rate.
            // The vehicle loader already warned about the shape; a sound that
            // simply has not been pushed yet is not an error worth a line.
            nextTick = Long.MAX_VALUE;
            return;
        }
        double length = sounds.info(id.get()).map(SoundInfo::length).orElse(0d);
        nextTick = length > 0 ? age + Math.max(1, Math.round(length * 20)) : Long.MAX_VALUE;
    }

    /**
     * Forgets what was playing, so the next tick starts it again.
     *
     * <p>Called when a vehicle's sound could have gone stale under it — a
     * reload, a push that replaced the sound — rather than on the ordinary
     * path, where the state change is what restarts it.
     */
    void forget() {
        playing = null;
    }
}
