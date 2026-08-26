package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Interaction;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

/**
 * One model standing in the world, and the handle you play its animations on.
 * Obtained from {@link Models}; never constructed directly.
 *
 * <p>A handle is a view of entities, not a snapshot - the rig it names can be
 * broken by a player at any moment, after which {@link #play} returns false
 * rather than throwing. Holding one across ticks is fine; it just stops
 * working when the rig does, which {@link #isValid()} answers.
 *
 * <p><b>Two handles on the same rig are equal and hash alike</b>, so they can
 * be kept in a Set or compared directly. Every lookup returns a fresh object,
 * so identity comparison ({@code ==}) is never what you want.
 *
 * <p><b>Main thread only</b>, like everything in {@link Models}.
 *
 * <p>Extends the original {@code ModelPlacement} interface, so a plugin
 * written against the 1.x plugin API keeps compiling and keeps working.
 *
 * <p>That supertype is gone here. It existed so a plugin compiled against the
 * 1.x jar kept working, and nothing has ever compiled against this engine's
 * 1.x — there isn't one.
 */
public interface Placement {

    /** The model this is, as the panel names it. */
    String modelId();

    /** Where the rig stands. */
    Location location();

    /**
     * The Interaction entity that anchors the rig - what a player clicks, and
     * a stable identity for this placement across ticks.
     *
     * <p>For your own state on a specific statue, prefer {@link #data()} with
     * a key of your own: see the namespacing note there.
     */
    Interaction hitbox();

    /**
     * This model's animation names, in editor order. Shorthand for
     * {@link Models#animationsOf}.
     */
    List<String> animations();

    /**
     * What this placement is playing right now, which includes an idle loop it
     * fell back to on its own. Empty when it's standing at rest.
     */
    Optional<String> playing();

    /**
     * Plays a named animation on every moving part of this rig, on one clock,
     * whatever triggers that animation claims - including none.
     *
     * <p>Follows the same rule a trigger does: while a one-shot is already
     * playing, asking for it again is ignored rather than restarting or
     * layering it, and false comes back. Use {@link #play(String, boolean)}
     * when you mean "start it over".
     *
     * @return false if the name matches no animation of this model, if the rig
     *         has no moving parts (a model that places as one still display
     *         can't animate), if the rig has been broken, or if that one-shot
     *         is mid-play. Never throws for any of them - a placement
     *         disappearing under a caller is ordinary.
     */
    boolean play(String animation);

    /**
     * As {@link #play(String)}, but {@code restart} rewinds an animation that
     * is already running instead of leaving it alone.
     */
    boolean play(String animation, boolean restart);

    /**
     * Stops what's playing. The rig returns to its idle loop if it has one,
     * otherwise to its rest pose - the same place a one-shot goes when it
     * reaches its end.
     *
     * @return whether anything was actually playing.
     */
    boolean stop();

    /** Whether the rig is still standing. False once anything has removed it. */
    boolean isValid();

    /**
     * Removes this placement.
     *
     * <p>Fires {@link ai.resourcepack.api.event.ModelBreakEvent} first and does
     * nothing if a listener cancels it.
     *
     * @param dropItem whether to drop the item that places this model back into
     *                 the world, the way breaking it by hand would.
     * @return false if it was already gone, or a listener refused.
     */
    boolean remove(boolean dropItem);

    /**
     * Persistent storage of your own, on this specific statue.
     *
     * <p>The container is the hitbox entity's, so it is saved with the chunk
     * and survives restarts exactly as the rig does - and it is gone when the
     * rig is.
     *
     * <p>It is shared with the animator, and the namespace is what keeps you
     * apart: every key this library writes is in the host plugin's namespace,
     * so a key of your own can never collide with one of ours as long as you
     * make it with your own plugin. {@link #key} is that, spelled out.
     *
     * <pre>{@code
     * NamespacedKey owner = Placement.key(this, "owner");
     * statue.data().set(owner, PersistentDataType.STRING, player.getName());
     * }</pre>
     */
    PersistentDataContainer data();

    /** A key in your plugin's namespace, for use with {@link #data()}. */
    static NamespacedKey key(Plugin plugin, String name) {
        return new NamespacedKey(plugin, name);
    }
}
