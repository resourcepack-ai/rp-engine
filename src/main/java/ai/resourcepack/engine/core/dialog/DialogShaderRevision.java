package ai.resourcepack.engine.core.dialog;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which copy of a Studio dialog's core shader to keep when two packs carry one.
 * Internal.
 *
 * <p>Every pack with a Studio dialog in it ships the same few core shaders in
 * its {@code rpai_dialog_v<format>} overlays: the GUI shader that hides the
 * white frame round a clicked dialog, and the sprite shader that hides the
 * footer's spare button and the game's own text box under a drawn one. Two
 * content folders, an addon's bundled pack and a server's own each carry a
 * copy, at the same path, and only one can be in the bundle. They are not
 * always the same file: a copy exported before a fix lacks it. Keeping the
 * later-sorted one, as for any other clash, let an old copy win, and the
 * player saw the frame, or the game's text box as a small square under a
 * card.
 *
 * <p>So Studio writes a revision into each of those files, as a comment
 * ({@code rpai-dialog-shaders rev N}), and the copy with the highest wins; a
 * copy with none is revision 0. Copies of one revision are the same bytes
 * (Studio derives them from the version alone), so a vertex shader and a
 * fragment shader chosen file by file still come from one pair.
 */
public final class DialogShaderRevision {

    private static final Pattern PATH = Pattern.compile("(?:^|/)rpai_dialog_v\\d+/assets/minecraft/shaders/core/[^/]+\\.(?:vsh|fsh)$");
    private static final Pattern MARK = Pattern.compile("rpai-dialog-shaders rev (\\d+)");

    private DialogShaderRevision() {}

    /** Whether a pack path is one of a Studio dialog's overlay shaders. */
    public static boolean applies(String path) {
        return PATH.matcher(path).find();
    }

    /** The revision a shader file says it is, or 0 for one that says nothing. */
    public static int of(byte[] bytes) {
        if (bytes == null) return -1;
        Matcher m = MARK.matcher(new String(bytes, StandardCharsets.UTF_8));
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Of two copies of one dialog shader, the one to keep: the higher revision,
     * or {@code mine} (the later one read) when they are the same.
     */
    public static byte[] pick(byte[] had, byte[] mine) {
        return of(had) > of(mine) ? had : mine;
    }
}
