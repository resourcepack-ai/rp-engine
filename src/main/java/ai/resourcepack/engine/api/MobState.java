package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a custom mob is doing, as the words its animations are mapped by.
 *
 * <p><strong>The declaration order is the precedence, highest first.</strong>
 * Four of these are ONE-SHOTS ({@link #DEATH}, {@link #SPAWN}, {@link #ATTACK},
 * {@link #HURT}): something happened, the animation plays to its end and the
 * mob goes back to what it was doing. A one-shot is cut short only by a
 * higher one starting — a mob hit while it is mid-bite keeps biting, a mob
 * killed mid-bite falls over. The other three are LOOPS, chosen from how fast
 * the mob is going.
 *
 * <p><strong>A blank loop falls through and a blank one-shot does not
 * play.</strong> A mob with no {@code run} animation walks when it runs, one
 * with no {@code walk} idles, and one with no {@code idle} stands in its rest
 * pose — the loops are one question ("how is it moving") answered at three
 * levels of detail, so a less detailed answer is still an answer. A one-shot is
 * an event, and a hurt with nothing drawn for it is simply a mob that does not
 * flinch.
 *
 * <p>Studio's mob editor offers exactly these under the same names and in this
 * order. There is no shared type between a Worker and a jar, so the agreement
 * is by hand: change one here and change {@code MOB_STATES} in Studio in the
 * same breath, order included.
 */
public enum MobState {

    /** It died. The model stays for the animation (at most five seconds), then goes. */
    DEATH,

    /** It was just spawned. Once. */
    SPAWN,

    /** It hit something, or fired at it. */
    ATTACK,

    /** It took damage. */
    HURT,

    /** Moving fast, or chasing what it is fighting. */
    RUN,

    /** Moving. */
    WALK,

    /** Standing. */
    IDLE;

    /** The name an author writes, lowercased. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this plays once and hands back, rather than looping. */
    public boolean oneShot() {
        return ordinal() <= HURT.ordinal();
    }

    /** {@code walk}, {@code WALK} or {@code " Walk "}; empty for anything else. */
    public static Optional<MobState> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The loop a mob moving like this plays, given what it has animations
     * for: the most detailed of run, walk and idle that it has and that is not
     * more than it is doing. Empty is its rest pose.
     *
     * <p>Falling through only ever goes DOWN: a standing mob with only a walk
     * animation stands still rather than walking on the spot.
     */
    public static Optional<MobState> loop(boolean running, boolean moving, Map<MobState, String> mapped) {
        MobState from = running ? RUN : moving ? WALK : IDLE;
        for (MobState state : values()) {
            if (state.ordinal() < from.ordinal()) {
                continue;
            }
            String animation = mapped == null ? null : mapped.get(state);
            if (animation != null && !animation.isEmpty()) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
