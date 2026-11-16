package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.core.pack.Images;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * A skin sheet the way the client draws it.
 *
 * <p>Mojang still serves a skin uploaded before 1.8 in the old 64x32 layout,
 * and the client widens it before drawing anything: the left arm and leg are
 * the right ones mirrored, the base layers are made opaque, and a hat area with
 * no clear pixel in it anywhere is taken to mean no hat at all, because it was
 * drawn before there were hats (the whole area is cleared, whatever colour it
 * is). A rig samples a 64x64 sheet, so a legacy one baked as it came drew every
 * bone from the wrong rows, and its solid hat covered the face: a player whose
 * old skin has an all-white hat area was a white box from the neck up, on the
 * rig and on every screen head, while the game showed his face.
 *
 * <p>Only a legacy sheet is touched. A 64x64 one is handed back byte for byte,
 * which keeps the pack's bytes, and so its hash, what they were for everybody
 * else.
 */
final class SkinSheets {

    private SkinSheets() {
    }

    /**
     * {@code png} widened to the 64x64 layout when it is a legacy 64x32 sheet,
     * otherwise {@code png} itself: anything else, a sheet that does not decode
     * included, is left for the caller to deal with as before.
     */
    static byte[] asDrawn(byte[] png) {
        if (!isLegacy(png)) {
            return png;
        }
        try {
            BufferedImage image = Images.read(png);
            if (image == null || image.getWidth() != 64 || image.getHeight() != 32) {
                return png;
            }
            return Images.png(widen(image));
        } catch (IOException | RuntimeException e) {
            return png;
        }
    }

    /** Whether the PNG header says 64 by 32, read without decoding the image. */
    static boolean isLegacy(byte[] png) {
        if (png == null || png.length < 24 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N'
                || png[3] != 'G' || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
            return false;
        }
        return readInt(png, 16) == 64 && readInt(png, 20) == 32;
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    /**
     * The client's own conversion, step for step (SkinTextureDownloader's
     * processLegacySkin): copy the sheet into the top half, mirror the right
     * leg and arm into the left limbs' places, then the alpha passes in the
     * client's order.
     */
    static BufferedImage widen(BufferedImage legacy) {
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                out.setRGB(x, y, legacy.getRGB(x, y));
            }
        }
        mirror(out, 4, 16, 16, 32, 4, 4);
        mirror(out, 8, 16, 16, 32, 4, 4);
        mirror(out, 0, 20, 24, 32, 4, 12);
        mirror(out, 4, 20, 16, 32, 4, 12);
        mirror(out, 8, 20, 8, 32, 4, 12);
        mirror(out, 12, 20, 16, 32, 4, 12);
        mirror(out, 44, 16, -8, 32, 4, 4);
        mirror(out, 48, 16, -8, 32, 4, 4);
        mirror(out, 40, 20, 0, 32, 4, 12);
        mirror(out, 44, 20, -8, 32, 4, 12);
        mirror(out, 48, 20, -16, 32, 4, 12);
        mirror(out, 52, 20, -8, 32, 4, 12);
        opaque(out, 0, 0, 32, 16);
        noHatIfSolid(out);
        opaque(out, 0, 16, 64, 32);
        opaque(out, 16, 48, 48, 64);
        return out;
    }

    /** A {@code w} by {@code h} rectangle copied {@code (dx, dy)} along and mirrored left to right. */
    private static void mirror(BufferedImage img, int x, int y, int dx, int dy, int w, int h) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                img.setRGB(x + dx + (w - 1 - i), y + dy + j, img.getRGB(x + i, y + j));
            }
        }
    }

    private static void opaque(BufferedImage img, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                img.setRGB(x, y, img.getRGB(x, y) | 0xFF000000);
            }
        }
    }

    /**
     * The client's "Notch transparency hack": when nothing in x 32-64, y 0-32
     * is even half clear, all of it is cleared. The body and arm rows in there
     * are made opaque again straight after, so what it leaves clear is the hat.
     */
    private static void noHatIfSolid(BufferedImage img) {
        for (int y = 0; y < 32; y++) {
            for (int x = 32; x < 64; x++) {
                if ((img.getRGB(x, y) >>> 24) < 128) {
                    return;
                }
            }
        }
        for (int y = 0; y < 32; y++) {
            for (int x = 32; x < 64; x++) {
                img.setRGB(x, y, img.getRGB(x, y) & 0x00FFFFFF);
            }
        }
    }
}
