package ai.resourcepack.engine.core.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Which paths this plugin will read from and write to.
 *
 * <p><strong>A security boundary, not tidiness.</strong> A path in a pull came
 * off an HTTP response, and it is about to be resolved against a folder on
 * somebody's server. Studio checks the same rule at its own end; this is the
 * near end of the same fence, and both refuse rather than sanitize — a path
 * that needed cleaning is a path the other side should not have sent.
 *
 * <p>The check is deliberately whitelist-shaped and deliberately paranoid
 * about Windows, because a server owner on Windows is the ordinary case here:
 * a backslash is a separator there and not here, a trailing dot or space is
 * silently stripped by the filesystem, and half a dozen device names are
 * reserved whatever extension follows them.
 */
final class EditPaths {

    /** Longer than any real asset path and short enough to bound the check. */
    private static final int MAX_LENGTH = 200;

    private static final String[] RESERVED = {
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    };

    private EditPaths() {
    }

    /** Whether {@code path} may be resolved inside a pack folder. */
    static boolean safe(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_LENGTH) {
            return false;
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains("//")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= ' ' || c == 127) {
                return false;
            }
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == '/';
            if (!allowed) {
                return false;
            }
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
            if (part.endsWith(".") || part.startsWith(" ") || part.endsWith(" ")) {
                return false;
            }
            String stem = part.contains(".") ? part.substring(0, part.indexOf('.')) : part;
            for (String reserved : RESERVED) {
                if (stem.toLowerCase(Locale.ROOT).equals(reserved)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code path} inside {@code pack}, or null if it would land anywhere else.
     *
     * <p>Three checks, and none of them is enough alone.
     *
     * <p>{@link #safe} refuses the strings that are obviously not ours. The
     * normalised comparison after it catches a traversal that got past the
     * string rules. And then the deepest part of the path that <em>already
     * exists</em> is resolved through the filesystem and compared again, which
     * is the only one of the three that can see a link: a normalised path
     * string knows nothing about a symlink or a Windows junction sitting in
     * the middle of it, and one pointed out of the pack folder would otherwise
     * pass every check here and be written wherever it leads.
     */
    static Path resolve(Path pack, String path) {
        if (!safe(path)) {
            return null;
        }
        try {
            Path root = real(pack);
            Path target = root.resolve(path).normalize();
            if (!target.startsWith(root)) {
                return null;
            }
            // The file itself usually does not exist yet — it is about to be
            // written — so the walk is up to whatever does, which is where a
            // link would have to be.
            Path probe = target;
            while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
                probe = probe.getParent();
            }
            return probe == null || real(probe).startsWith(root) ? target : null;
        } catch (IOException e) {
            // A path the filesystem will not answer about is a path this will
            // not write to.
            return null;
        }
    }

    /** The canonical location, so two spellings of one folder compare equal. */
    private static Path real(Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
