package ai.resourcepack.engine.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * A movement state that drives a STANCE — an emote a player wears.
 *
 * <p>An ordinary emote is a moment: it plays where somebody stands and ends
 * the first step they take. A stance is the other kind. The player is hidden
 * behind their own rig and stays that way, in whatever game mode they were
 * already in, free to walk, jump and fight while the rig is carried along with
 * them; the animation's clock runs only while they are in one of the states
 * the pack author named, and rests at the first frame otherwise. It ends when
 * they take it off, die or log out — never because they moved.
 *
 * <p><b>Exactly one of these holds at a time</b>, resolved per tick from what
 * the player is doing: being off the ground wins over everything, then
 * crouching over sprinting and sprinting over walking, so a crouch-walk is
 * {@link #SNEAK_MOVE} and never also {@link #WALK}. An emote cannot be
 * half-playing.
 *
 * <p>These states are exhaustive over what a body is doing rather than a list
 * somebody started, which is why the wire names are stable: they are what
 * studio writes into an emote's {@code triggers} array.
 *
 * <p><b>Ported verbatim from the engine this one replaced</b>, wire names and
 * all. The names are a contract with manifests that already exist, so this is
 * a place to copy rather than to improve: a rename here is a stance that
 * silently stops being worn.
 *
 * <p><b>{@link #JUMP} is opt-in in a way the others are not.</b> A stance
 * that does not name it treats the air as whatever the body is otherwise doing
 * — which is exactly what every stance authored before it existed already did,
 * deliberately: one authored for standing still should keep playing while its
 * wearer falls down a shaft rather than freezing mid-air. A stance that DOES
 * name it gets the air as a state of its own. A movement group is different
 * again and always resolves it, because a group answers every state explicitly
 * and "the player's own animation" is one of the answers.
 *
 * <h2>{@link #SNEAK} is an umbrella, and is never resolved</h2>
 *
 * Crouching used to be ONE state covering both standing still and crouch-
 * walking, which meant a set could not have a crouched idle and a crouch-walk
 * cycle — the two things a crouching body actually does. {@link #SNEAK_IDLE}
 * and {@link #SNEAK_MOVE} split it, and the old name is kept as what a pack
 * built before the split says: {@link #fallback()} is how a resolved state
 * finds it, so a manifest naming {@code sneak} goes on playing that one emote
 * in both. Nothing ever resolves TO {@code SNEAK} — a state that meant "either
 * of two things" cannot drive a clock that has room for one.
 */
public enum EmoteTrigger {

    /** Standing still. */
    IDLE("idle"),
    /** Moving under their own power, not sprinting. */
    WALK("walk"),
    /** Sprinting. */
    SPRINT("sprint"),
    /**
     * Crouching, moving or not — the state a pack named before the split.
     *
     * <p>Never the answer {@code stanceState} gives; see the class note. It
     * exists so a manifest that named it keeps working, through
     * {@link #fallback()}.
     */
    SNEAK("sneak"),
    /** Crouching and standing still. */
    SNEAK_IDLE("sneak_idle"),
    /** Crouching and moving — a crouch-walk. */
    SNEAK_MOVE("sneak_move"),
    /** Off the ground — jumping, or falling. See the note above. */
    JUMP("jump");

    private final String wire;

    EmoteTrigger(String wire) {
        this.wire = wire;
    }

    /**
     * The broader state an older pack would have named instead, or null.
     *
     * <p>Only the two crouching states have one, and it is {@link #SNEAK}. A
     * lookup asks for the exact state first and falls back to this, so a pack
     * built before the split wears its one crouch emote in both — and a pack
     * built after it never reaches the fallback at all.
     */
    public EmoteTrigger fallback() {
        return this == SNEAK_IDLE || this == SNEAK_MOVE ? SNEAK : null;
    }

    /**
     * The states this name actually stands for, as things that can hold.
     *
     * <p>The other direction from {@link #fallback()}, and it is what anybody
     * ASKING about an emote wants: {@link #SNEAK} covers both crouching states,
     * so a pack that named it is worn while crouch-walking even though nothing
     * ever resolves to the name it used. Reported as-named, a caller checking
     * whether a set covers {@link #SNEAK_MOVE} would be told no about a set
     * that plainly does.
     *
     * <p>Every other state covers itself and nothing else, so a caller can walk
     * this unconditionally.
     */
    public Set<EmoteTrigger> covers() {
        return this == SNEAK ? EnumSet.of(SNEAK_IDLE, SNEAK_MOVE) : EnumSet.of(this);
    }

    /** The name studio writes into a manifest. Lowercase, and stable. */
    public String wireName() {
        return wire;
    }

    /**
     * Which state holds, given what a body is doing.
     *
     * <p>Exactly one, resolved per tick. Being off the ground wins over
     * everything, then crouching over sprinting and sprinting over walking, so
     * a crouch-walk is {@link #SNEAK_MOVE} and never also {@link #WALK}. An
     * emote cannot be half-playing.
     */
    public static EmoteTrigger of(boolean sneaking, boolean sprinting, boolean moving, boolean airborne) {
        if (airborne) {
            return JUMP;
        }
        if (sneaking) {
            return moving ? SNEAK_MOVE : SNEAK_IDLE;
        }
        if (!moving) {
            return IDLE;
        }
        return sprinting ? SPRINT : WALK;
    }

    /**
     * The trigger a manifest's string names, or null for one this jar has
     * never heard of.
     *
     * <p><b>Null rather than an exception, and the caller drops it.</b> A newer
     * studio may name a state this jar has no idea how to detect, and refusing
     * to load the emote at all — or worse, loading a stance whose condition can
     * never hold — is a worse answer than playing the states it does
     * understand. See {@code EmoteStore}'s note on what happens when dropping
     * leaves nothing.
     */
    public static EmoteTrigger of(String wireName) {
        if (wireName == null) return null;
        for (EmoteTrigger trigger : values()) {
            if (trigger.wire.equals(wireName)) return trigger;
        }
        return null;
    }
}
