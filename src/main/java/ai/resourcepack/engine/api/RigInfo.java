package ai.resourcepack.engine.api;

import java.util.List;

/**
 * What a studio model is, without placing one.
 *
 * <p>Called {@code ModelInfo} in {@code the previous engine}. Renamed here
 * because this engine already has a {@code ModelInfo}, and the two describe
 * genuinely different things: that one is how a content pack's model gets put
 * down, and this one is what a rig can play.
 */
/*
 * Original doc:
 * What a model is, without placing one: whether it animates, and what it can
 * play.
 *
 * <p>The twin of {@link EmoteInfo}, and it exists for the same reason - enough
 * to build a menu out of, in one call rather than three. Obtained from
 * {@link Models#info}.
 */
public final class RigInfo {

    private final String id;
    private final boolean animated;
    private final List<String> animations;

    public RigInfo(String id, boolean animated, List<String> animations) {
        this.id = id;
        this.animated = animated;
        this.animations = animations == null ? List.of() : List.copyOf(animations);
    }

    /** Its id, as the panel names it. This is what every other call here takes. */
    public String id() {
        return id;
    }

    /**
     * Whether it places as an animated rig rather than one still display.
     *
     * <p>A model can have animations listed and still answer false: a rig with
     * no moving parts has nothing to animate, and asking one to play reports
     * failure rather than pretending.
     */
    public boolean animated() {
        return animated;
    }

    /**
     * Its animation names, in the order the editor lists them.
     *
     * <p>Free text, and nothing makes them unique - where a model has two of
     * the same name, {@link Placement#play} takes the first.
     */
    public List<String> animations() {
        return animations;
    }

    @Override
    public String toString() {
        return "RigInfo(" + id + (animated ? ", animated " : ", still ") + animations + ")";
    }
}
