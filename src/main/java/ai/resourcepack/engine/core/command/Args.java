package ai.resourcepack.engine.core.command;

/**
 * Numbers a human typed into a chat box.
 *
 * <p>Neither of these throws. A command argument is not a parse of a config
 * file: somebody who types {@code /rp models sixteen} wants a list of models,
 * not a stack trace, so the value is clamped to something sane and the command
 * runs.
 */
final class Args {

    /** Scanning a whole world is not a command, so a radius is bounded. */
    static final double MIN_RADIUS = 1;
    static final double MAX_RADIUS = 128;
    static final double DEFAULT_RADIUS = 16;

    private Args() {
    }

    /** A radius, clamped to something a server can answer instantly. */
    static double radius(String text) {
        try {
            return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, Double.parseDouble(text.trim())));
        } catch (NumberFormatException | NullPointerException e) {
            return DEFAULT_RADIUS;
        }
    }

    /** An item count. Anything unparseable is one, never a crash. */
    static int amount(String text) {
        try {
            return Math.max(1, Math.min(99, Integer.parseInt(text.trim())));
        } catch (NumberFormatException | NullPointerException e) {
            return 1;
        }
    }
}
