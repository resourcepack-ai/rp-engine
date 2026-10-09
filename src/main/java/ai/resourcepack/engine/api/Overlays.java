package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/**
 * The overlays this server holds, and how to put one on somebody's screen.
 *
 * <p>An overlay is a picture drawn over the world: a run of characters the
 * engine keeps on the action bar for as long as it is shown. Some are a single
 * baked image; some are shapes the pack's own core shader draws as maths. From
 * out here they are the same thing, deliberately — {@link #show} takes an id
 * and the engine works out which kind it is and what that kind needs.
 *
 * <h2>Showing one is not a one-shot</h2>
 *
 * The action bar fades on its own after a couple of seconds, so an overlay is
 * <em>held</em> rather than sent: {@link #show} adds it to what that player is
 * wearing and the engine redraws it until something takes it off. That is why
 * there is a {@link #hide} rather than a duration — a caller that wants one for
 * five seconds schedules the hide, and a caller that wants one until a fight
 * ends hides it when the fight ends.
 *
 * <h2>Live values</h2>
 *
 * {@link #set} is how a value on screen changes. An overlay's text may contain
 * {@code {name}} placeholders, and setting one re-renders that player's line on
 * the next tick — so a mana bar is {@code set(player, "mana", "42")} from
 * wherever your plugin already knows the number.
 *
 * <p><b>What a live value cannot do is move a SHAPE.</b> A shader object's
 * geometry is compiled into the pack, and a core shader has no channel through
 * which a server can push a number at it — there is no uniform to set and no
 * packet that would carry one. Text is the exception, and only because the text
 * never enters the shader: the server builds the string and the client draws it
 * with the font it already has. If a radius needs to follow a stat, that is the
 * font-glyph kind of overlay, not this one.
 *
 * <p>Main thread only, like the rest of the API that touches a player.
 */
public interface Overlays {

    /** Every overlay id this server holds, sorted. */
    Collection<ContentId> ids();

    /** What the pack said an overlay is, or empty if there is no such overlay. */
    Optional<OverlayInfo> info(ContentId id);

    /** As {@link #info(ContentId)}, from the text form of an id. */
    Optional<OverlayInfo> info(String id);

    /**
     * Shows an overlay to a player, and keeps showing it.
     *
     * <p>Showing one they are already wearing is not an error and not a
     * restart: it is how a caller makes sure it is up without having to track
     * whether it already was.
     *
     * @return false if there is no such overlay, or the player is offline
     */
    boolean show(Player viewer, ContentId id);

    /** As {@link #show(Player, ContentId)}, from the text form of an id. */
    boolean show(Player viewer, String id);

    /** Takes one overlay off. Quiet if they were not wearing it. */
    boolean hide(Player viewer, ContentId id);

    /** As {@link #hide(Player, ContentId)}, from the text form of an id. */
    boolean hide(Player viewer, String id);

    /** Takes every overlay off this player. */
    void hideAll(Player viewer);

    /** Whether this player is currently wearing that overlay. */
    boolean isShowing(Player viewer, ContentId id);

    /** Every overlay this player is wearing, in the order they were shown. */
    Collection<ContentId> showing(Player viewer);

    /**
     * Sets a value this player's overlays can print.
     *
     * <p>Per player, not per overlay: a number like "mana" means the same thing
     * to every overlay that prints it, and keeping one set of values per player
     * is what stops two overlays disagreeing about it. The change is drawn on
     * the next redraw rather than immediately, so setting several in a row
     * costs one draw rather than one each.
     *
     * <p>A null value removes it, and an unset placeholder renders as empty
     * rather than as the literal {@code {name}} — a HUD with a gap in it is
     * easier to read than one with a brace in it.
     */
    void set(Player viewer, String name, String value);

    /** What {@link #set} last put there, if anything. */
    Optional<String> value(Player viewer, String name);

    /**
     * How far {@code text} moves the cursor, in pixels, as the engine measures
     * an overlay's text.
     *
     * <p>The same measurement that places a shader object's runs: vanilla's
     * glyph widths, every icon in the pack at its real size, and the spaces
     * {@link #space} makes. So a plugin that lays words and icons out in a
     * value (a kill feed: a name, then a weapon, then a name) can line them up
     * against what the engine will do with them. Formatting codes measure as
     * nothing, and bold as a pixel more per character.
     */
    int width(String text);

    /**
     * Characters that move the cursor {@code pixels} to the right (or, when
     * negative, to the left) and draw nothing.
     *
     * <p>The default font's own space and the engine's negative spaces, so it
     * works in any text drawn in the default font, an overlay's value included,
     * and {@link #width} measures it exactly. Zero is the empty string.
     */
    String space(int pixels);
}
