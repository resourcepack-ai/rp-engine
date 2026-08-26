package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

/**
 * How a model should be placed by {@link Models#place}.
 *
 * <p>Immutable and built by chaining; {@link #defaults()} is a fine argument
 * on its own.
 *
 * <pre>{@code
 * rpai.models().place(spot, "wizard_statue",
 *     PlaceOptions.defaults().animation("Idle").scale(2f).placer(player));
 * }</pre>
 */
public final class PlaceOptions {

    private static final PlaceOptions DEFAULTS = new PlaceOptions(null, 1f, null);

    private final String animation;
    private final float scale;
    private final Player placer;

    private PlaceOptions(String animation, float scale, Player placer) {
        this.animation = animation;
        this.scale = scale;
        this.placer = placer;
    }

    /** No animation choice, natural size, nobody credited. */
    public static PlaceOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Which animation this placement plays where several claim one trigger.
     *
     * <p>Optional, and it is a preference rather than a command: the choice
     * wins for any trigger it claims, and a trigger it doesn't claim still
     * falls back to the first animation in model order that does. So a statue
     * can be told "play B on right-click" and still loop A.
     *
     * <p>Stored on the entities, so it survives a restart and two copies of
     * one model can stand side by side playing different animations.
     */
    public PlaceOptions animation(String animation) {
        return new PlaceOptions(animation, scale, placer);
    }

    /**
     * Size multiplier, clamped to the range the panel offers (0.125 - 8).
     * The hitbox grows with it, so a big statue is punchable where it looks.
     */
    public PlaceOptions scale(float scale) {
        return new PlaceOptions(animation, scale, placer);
    }

    /**
     * Who is placing it, if anybody.
     *
     * <p>Supplying one is what makes the placement announceable: with a placer,
     * {@link ai.resourcepack.presence.ModelPlaceEvent} fires and anything on
     * the server can refuse it. Without one nothing is fired at all, because
     * that event is a {@code PlayerEvent} and inventing a player to fill it
     * would hand every listener a lie. A placement your own plugin makes on
     * its own behalf is your decision to make, so pass a placer whenever the
     * placement is really somebody's.
     */
    public PlaceOptions placer(Player placer) {
        return new PlaceOptions(animation, scale, placer);
    }

    /** The chosen animation, or null. */
    public String animation() {
        return animation;
    }

    /** The size multiplier. 1 unless set. */
    public float scale() {
        return scale;
    }

    /** Who is placing it, or null. */
    public Player placer() {
        return placer;
    }
}
