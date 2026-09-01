package ai.resourcepack.engine.core.sync;

import java.util.UUID;

/**
 * What a pairing reference can look like.
 *
 * <p>Two shapes, both fixed by the pairing service: an eight-digit pairing
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

    /**
     * The player a uuid ref names, or {@code null} if it is not one.
     *
     * <p>The wire writes a uuid as 32 hex with the dashes taken out, so the
     * dashes go back in before Java will read it.
     */
    public static UUID uuidOf(String ref) {
        if (!isUuid(ref)) {
            return null;
        }
        return UUID.fromString(ref.substring(0, 8) + "-" + ref.substring(8, 12) + "-"
                + ref.substring(12, 16) + "-" + ref.substring(16, 20) + "-" + ref.substring(20));
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
