package ai.resourcepack.engine.core.font;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Finds where the art actually sits on a GUI sheet.
 *
 * <p>{@link GuiWindows} works the placement out from which container a screen
 * opens as, which is right when the art is a vanilla window drawn to vanilla
 * proportions. Real art often is not: a sheet may hold a 96-pixel panel with no
 * player-inventory section at all, and no row count places that correctly
 * because it is not a chest of any size.
 *
 * <p>So measure it. The opaque bounding box of the sheet <em>is</em> the
 * window, and its top-left corner is the inset the placement arithmetic wants.
 * Nothing has to be declared and nothing has to be guessed.
 *
 * <p>Costs a PNG decode per screen per build, which is a handful of images once
 * per reload.
 */
public final class GuiSheet {

    /** Below this an alpha value is a stray artefact rather than art. */
    private static final int OPAQUE_ENOUGH = 8;

    private GuiSheet() {
    }

    /**
     * The top-left corner of everything drawn on {@code png}.
     *
     * @return {@code {left, top}}, or empty if the image cannot be read or is
     *         entirely transparent
     */
    public static Optional<int[]> inset(byte[] png) {
        if (png == null || png.length == 0) {
            return Optional.empty();
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException e) {
            // A file that is not an image, or an image type this JVM has no
            // reader for. Either way the fallback is the container geometry,
            // which is what happened before this class existed.
            return Optional.empty();
        }
        if (image == null) {
            return Optional.empty();
        }

        int left = image.getWidth();
        int top = image.getHeight();
        boolean any = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) < OPAQUE_ENOUGH) {
                    continue;
                }
                any = true;
                if (x < left) {
                    left = x;
                }
                if (y < top) {
                    top = y;
                }
            }
        }
        return any ? Optional.of(new int[]{left, top}) : Optional.empty();
    }
}
