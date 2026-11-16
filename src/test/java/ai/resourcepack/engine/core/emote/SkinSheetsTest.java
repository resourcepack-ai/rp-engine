package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.core.pack.Images;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A legacy 64x32 sheet becomes what the client draws; anything else is left alone. */
class SkinSheetsTest {

    private static final int FACE = 0xFF7A4A30;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int ARM = 0xFF2040C0;

    /** A pre-1.8 sheet with no alpha anywhere: a face, and a hat area painted solid white. */
    private static BufferedImage oldSkin() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, x >= 32 && y < 16 ? WHITE : 0xFF101010);
            }
        }
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                img.setRGB(x, y, FACE);
            }
        }
        // The right arm's front, with one marked column to see the mirror.
        for (int y = 20; y < 32; y++) {
            for (int x = 44; x < 48; x++) {
                img.setRGB(x, y, x == 44 ? ARM : 0xFF303030);
            }
        }
        return img;
    }

    @Test
    void aSolidHatOnAnOldSkinIsNoHat() throws IOException {
        BufferedImage out = Images.read(SkinSheets.asDrawn(Images.png(oldSkin())));
        assertEquals(64, out.getWidth());
        assertEquals(64, out.getHeight());
        // The hat over the face is clear, so the face shows: it was a white box.
        assertEquals(0, out.getRGB(40, 8) >>> 24);
        assertEquals(0, out.getRGB(47, 15) >>> 24);
        assertEquals(FACE, out.getRGB(8, 8));
        // The arm rows the clearing passed over are opaque again.
        assertEquals(0xFF, out.getRGB(44, 20) >>> 24);
    }

    @Test
    void theLeftArmIsTheRightOneMirrored() throws IOException {
        BufferedImage out = Images.read(SkinSheets.asDrawn(Images.png(oldSkin())));
        // Right arm front x 44-48 lands at 36-40 on row 52 (20 + 32), mirrored: 44 becomes 39.
        assertEquals(ARM, out.getRGB(39, 52));
        assertEquals(0xFF303030, out.getRGB(36, 52));
    }

    @Test
    void aHatWithAnyClearPixelIsKept() throws IOException {
        BufferedImage img = oldSkin();
        img.setRGB(33, 1, 0x00000000);
        BufferedImage out = Images.read(SkinSheets.asDrawn(Images.png(img)));
        assertEquals(WHITE, out.getRGB(40, 8));
    }

    @Test
    void aModernSheetComesBackByteForByte() throws IOException {
        byte[] modern = Images.png(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));
        assertSame(modern, SkinSheets.asDrawn(modern));
        byte[] notPng = {1, 2, 3};
        assertArrayEquals(notPng, SkinSheets.asDrawn(notPng));
    }
}
