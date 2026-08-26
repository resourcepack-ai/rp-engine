package ai.resourcepack.engine.core.sync;

/**
 * What a pairing reference can look like.
 *
 * <p>Two shapes, both fixed by {@code the pairing service's own spec}: an eight-digit pairing
 * code from the panel, or a 32-hex Minecraft UUID for the permalink flow. They
 * do not collide, which is why one protocol carries both.
 *
 * <p><strong>This is a shape check and nothing more.</strong> Nothing here can
 * tell whether a well-formed code was ever issued: the far end silently ignores
 * one it does not know, and no reply comes back to say so. So the most that can
 * honestly be said after claiming one is that we are waiting — which is why the
 * command does not say "synced".
 */
public final class SyncCodes {

    private SyncCodes() {
    }

    /** Whether {@code ref} could be a pairing code or a player uuid. */
    public static boolean isValid(String ref) {
        return isCode(ref) || isUuid(ref);
    }

    /** An eight-digit pairing code, as the panel issues. */
    public static boolean isCode(String ref) {
        if (ref == null || ref.length() != 8) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            if (!Character.isDigit(ref.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** A 32-hex uuid with the dashes taken out, as the permalink flow uses. */
    public static boolean isUuid(String ref) {
        if (ref == null || ref.length() != 32) {
            return false;
        }
        for (int i = 0; i < 32; i++) {
            if (Character.digit(ref.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
