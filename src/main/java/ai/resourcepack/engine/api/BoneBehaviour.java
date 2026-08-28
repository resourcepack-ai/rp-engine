package ai.resourcepack.engine.api;

import java.util.Locale;

/**
 * What a bone does besides being drawn, taken from its name.
 *
 * <p><strong>The prefixes are ModelEngine's, deliberately.</strong> This is the
 * one place in the whole engine where copying a competitor's convention is
 * obviously right: every rig a server owner already has, and every rig they can
 * buy, names its bones this way. Inventing our own spelling would mean a rig
 * that works there has to be re-authored to work here, for no gain to anybody
 * — and the convention is a naming scheme rather than anything ModelEngine
 * owns.
 *
 * <p>A bone with no recognised prefix is {@link #NONE}, which is nearly all of
 * them. A model does not need any of this to work.
 *
 * <p>What is deliberately NOT here: the procedural ones (segments, tails) and
 * the item-display slots. Those are animation the engine would be inventing
 * rather than positions it can read off a rig, and half-doing them is worse
 * than not having them — a tail that lags wrongly reads as broken.
 */
public enum BoneBehaviour {

    /** Drawn and nothing else. Almost every bone. */
    NONE(""),

    /**
     * Turns to look where its host is looking.
     *
     * <p>{@code h_head}. Only means anything on a model worn by an entity: a
     * placed statue has nothing to look with.
     */
    HEAD("h_"),

    /**
     * As {@link #HEAD}, and every bone under it inherits it.
     *
     * <p>{@code hi_head}, for a head that has a jaw and eyes hanging off it.
     */
    HEAD_INHERITED("hi_"),

    /**
     * Where a driver sits, and steers from.
     *
     * <p>{@code mount}. One per model — a second is ignored, because "which
     * one is driving" has no answer worth guessing at.
     */
    DRIVER("mount"),

    /**
     * Where a passenger sits.
     *
     * <p>{@code p_seat1}. As many as the model has, and none of them steer.
     */
    SEAT("p_"),

    /**
     * A hitbox of its own, so this part of the model can be hit.
     *
     * <p>{@code b_wing}. Damage to it is damage to whatever the model belongs
     * to — the point is that a dragon is hit on the wing you aimed at rather
     * than in a rectangle around the whole animal.
     */
    HITBOX("b_"),

    /**
     * As {@link #HITBOX}, and turns with the bone.
     *
     * <p>{@code ob_wing}. Costs more and matters on anything that rotates far
     * from where it started.
     */
    HITBOX_ORIENTED("ob_"),

    /**
     * Where a name floats.
     *
     * <p>{@code tag_name}. Above the model rather than above the entity's own
     * head, which on a big model is somewhere inside its knee.
     */
    NAMETAG("tag_");

    private final String prefix;

    BoneBehaviour(String prefix) {
        this.prefix = prefix;
    }

    /** The bone-name prefix that turns this on. */
    public String prefix() {
        return prefix;
    }

    /** Whether this one only means something on a model worn by an entity. */
    public boolean needsAHost() {
        return this == HEAD || this == HEAD_INHERITED || this == DRIVER || this == SEAT;
    }

    /** Whether it puts a hitbox in the world. */
    public boolean isHitbox() {
        return this == HITBOX || this == HITBOX_ORIENTED;
    }

    /** Whether it follows the host's gaze. */
    public boolean isHead() {
        return this == HEAD || this == HEAD_INHERITED;
    }

    /**
     * What a bone called {@code name} does.
     *
     * <p>Longest prefix first, because {@code hi_} starts with neither
     * {@code h_} nor anything else here but {@code ob_} contains {@code b_} —
     * and a wing called {@code ob_wing} matched as a plain hitbox would stop
     * turning with the bone with nothing to say why.
     */
    public static BoneBehaviour of(String name) {
        if (name == null || name.isEmpty()) {
            return NONE;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // The exact-match one, which is a whole name rather than a prefix.
        if (lower.equals(DRIVER.prefix)) {
            return DRIVER;
        }
        BoneBehaviour found = NONE;
        for (BoneBehaviour behaviour : values()) {
            if (behaviour == NONE || behaviour == DRIVER) {
                continue;
            }
            if (lower.startsWith(behaviour.prefix)
                    && behaviour.prefix.length() > found.prefix.length()) {
                found = behaviour;
            }
        }
        return found;
    }
}
