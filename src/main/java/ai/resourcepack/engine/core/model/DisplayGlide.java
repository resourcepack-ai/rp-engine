package ai.resourcepack.engine.core.model;

import org.bukkit.entity.Display;

/**
 * A display's teleport duration, set where the server has it (1.20.2 and up)
 * and skipped below, for code that is handed a display by somebody else and
 * has no {@link DisplayCarry} of its own: {@code Models.move}, puppets.
 *
 * <p>Listed in {@code version-arms.txt}: it names a method newer than the
 * floor, and only calls it once a reflective look has found it, so an older
 * server loads the class and never reaches the call.
 */
public final class DisplayGlide {

    private static final boolean AVAILABLE = available();

    private DisplayGlide() {
    }

    private static boolean available() {
        try {
            Display.class.getMethod("setTeleportDuration", int.class);
            return true;
        } catch (NoSuchMethodException | RuntimeException e) {
            return false;
        }
    }

    /** Whether a moved display can glide on this server at all. */
    public static boolean supported() {
        return AVAILABLE;
    }

    /** Sets how many ticks a moved display takes to arrive, 0 to 59, if the server can. */
    public static void set(Display display, int ticks) {
        if (!AVAILABLE || display == null) {
            return;
        }
        int clamped = Math.max(0, Math.min(59, ticks));
        if (display.getTeleportDuration() != clamped) {
            display.setTeleportDuration(clamped);
        }
    }
}
