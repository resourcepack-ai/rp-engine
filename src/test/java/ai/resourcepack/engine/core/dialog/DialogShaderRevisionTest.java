package ai.resourcepack.engine.core.dialog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two packs carrying one of a dialog's overlay shaders keep the newest copy,
 * however they sort: an old copy without a fix used to win by being read last.
 */
class DialogShaderRevisionTest {

    private static byte[] shader(String rev) {
        return ("#version 150\n" + (rev == null ? "" : "// rpai-dialog-shaders rev " + rev + "\n") + "void main() {}\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void appliesOnlyToADialogOverlaysCoreShaders() {
        assertTrue(DialogShaderRevision.applies("rpai_dialog_v75/assets/minecraft/shaders/core/position_tex_color.fsh"));
        assertTrue(DialogShaderRevision.applies("rpai_dialog_v63/assets/minecraft/shaders/core/gui.vsh"));
        assertFalse(DialogShaderRevision.applies("assets/minecraft/shaders/core/gui.vsh"));
        assertFalse(DialogShaderRevision.applies("rpai_dialog_v75/assets/minecraft/textures/gui/sprites/widget/text_field.png"));
        assertFalse(DialogShaderRevision.applies("rpai_head_v69/assets/minecraft/shaders/core/rendertype_text.vsh"));
    }

    @Test
    void theNewestCopyWinsWhicheverIsReadLast() {
        byte[] old = shader(null);
        byte[] rev1 = shader("1");
        byte[] rev2 = shader("2");
        assertEquals(0, DialogShaderRevision.of(old));
        assertEquals(2, DialogShaderRevision.of(rev2));
        assertSame(rev1, DialogShaderRevision.pick(old, rev1));
        assertSame(rev1, DialogShaderRevision.pick(rev1, old));
        assertSame(rev2, DialogShaderRevision.pick(rev2, rev1));
        // The same revision: the later one read, as for any other file.
        byte[] again = shader("1");
        assertSame(again, DialogShaderRevision.pick(rev1, again));
    }
}
