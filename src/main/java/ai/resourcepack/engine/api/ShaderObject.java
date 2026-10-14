package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Vector;

/**
 * One 3D shader object standing in a world, and everything that drives it.
 *
 * <p>Four things can change, and each one is something the CLIENT animates
 * smoothly rather than a series of jumps:
 * <ul>
 *   <li><b>State</b>: which of the object's named looks it shows. Entering a
 *   state starts that state's clock, so a one-shot (an explosion, a portal
 *   opening) plays once from the start, in step with the game's own time, and
 *   blends out of the state before it if its code says so.</li>
 *   <li><b>Position</b>, <b>turn</b> (yaw, pitch, roll) and <b>size</b>: moved
 *   over a number of ticks with an {@link Easing}. The object is drawn in its
 *   own frame, so turning it turns the whole picture.</li>
 * </ul>
 * And {@link #play} runs a sequence of all of these that a content file wrote.
 *
 * <p><b>Main thread only.</b> A handle stays valid while the entity does; every
 * method on a removed one does nothing and answers false.
 */
public interface ShaderObject {

    /** What it is. */
    ContentId id();

    /** What it is, in full: its states and sequences. */
    ShaderObjectInfo info();

    /** The display entity that draws it. Moving it yourself is allowed; this handle reads it back. */
    ItemDisplay entity();

    /** Whether it is still in the world. */
    boolean isValid();

    /** Its middle. */
    Location location();

    // ---- state ----------------------------------------------------------------

    /** The state it is in, by name (or number, when the code does not name it). */
    String state();

    /**
     * Enters a state, by name or number, and starts its clock. Entering the
     * state it is already in does nothing; {@link #replay} starts it again.
     *
     * @return false for a state the object does not have
     */
    boolean state(String state);

    /** Starts the current state's clock again: a one-shot plays again from its start. */
    void replay();

    /** Shows the current state as long finished (its settled look), without changing it. */
    void settle();

    /** 0..15: a number the code may use so two copies need not look identical. */
    int seed();

    void seed(int seed);

    // ---- motion ---------------------------------------------------------------

    /** Its diameter in blocks. */
    double size();

    /** Its turn, as yaw, pitch and roll in degrees. Yaw turns like an entity's: 90 faces west. */
    float[] rotation();

    /** Moves its middle to {@code to} over {@code ticks}. 0 is at once. The world must be the same. */
    void moveTo(Location to, int ticks, Easing easing);

    /** Moves it by {@code by} blocks over {@code ticks}. */
    void moveBy(Vector by, int ticks, Easing easing);

    /** Turns it to a yaw, pitch and roll (degrees) over {@code ticks}, the long way round if that is what the numbers say. */
    void turnTo(float yaw, float pitch, float roll, int ticks, Easing easing);

    /** Changes its diameter over {@code ticks}. */
    void resize(double size, int ticks, Easing easing);

    /** Whether a move, turn or resize is still running. */
    boolean isMoving();

    // ---- sequences ------------------------------------------------------------

    /**
     * Runs a sequence its content file wrote, from the start, replacing any
     * running. Moves already running carry on unless the sequence moves the
     * same thing.
     *
     * @return false for a sequence it does not have
     */
    boolean play(String sequence);

    /** The sequence running, or null. */
    String playing();

    /** Stops the sequence and every move, turn and resize, where they are. */
    void stop();

    /** Takes it out of the world. */
    void remove();
}
