package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.core.version.Compatibility;

import org.bukkit.entity.Display;

/**
 * Whether a display entity glides to a new position or simply appears at it.
 *
 * <p>A moved display jumps by default: the client is told where it now is and
 * puts it there. {@code setTeleportDuration} asks the client to take a few
 * ticks getting there instead, which is the difference between a walking emote
 * gliding and a walking emote strobing.
 *
 * <p>The method arrived in 1.20.2, three releases after the display entities
 * it applies to, so the engine's floor and this are not the same line. On a
 * server below it the answer is to do nothing — the rig still moves, still
 * arrives in the right place, and looks worse on the way.
 *
 * @see Feature#SMOOTH_RIG_MOVEMENT
 */
public interface DisplayCarry {

    /** Sets a display up to be moved, or does nothing where that is not a thing. */
    void carry(Display display);

    /**
     * The arm for this server.
     *
     * @param ticks how long the client should take to cover a move
     */
    static DisplayCarry forServer(Compatibility compatibility, int ticks) {
        return compatibility.has(Feature.SMOOTH_RIG_MOVEMENT)
                ? new Interpolated(ticks)
                : new Immediate();
    }

    /**
     * 1.20.2 and up.
     *
     * <p>Its own class rather than a lambda so the reference to a method that
     * does not exist on older servers sits somewhere those servers never load.
     */
    final class Interpolated implements DisplayCarry {

        private final int ticks;

        Interpolated(int ticks) {
            this.ticks = ticks;
        }

        @Override
        public void carry(Display display) {
            display.setTeleportDuration(ticks);
        }
    }

    /** Below 1.20.2: nothing to set, and the rig snaps. */
    final class Immediate implements DisplayCarry {

        @Override
        public void carry(Display display) {
        }
    }
}
