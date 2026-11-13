package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.core.pack.Images;
import ai.resourcepack.engine.core.pack.PackContributor.Contribution;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;

/**
 * Turns a tool's picture round when it was drawn facing the other way from
 * vanilla's.
 *
 * <p>The game's {@code item/handheld} puts the bottom-left corner of the
 * picture in the hand and points the top-right corner forward, because every
 * vanilla sword, axe and pickaxe is drawn handle bottom left, tip top right.
 * A picture drawn the mirror way (tip top left, handle bottom right) comes out
 * pointing at the ground, and the only fix used to be the server owner
 * flipping their PNG by hand.
 *
 * <p>Which diagonal a picture lies along is easy to measure: the opaque
 * pixels of a blade drawn corner to corner are strongly correlated in x and y.
 * Along vanilla's diagonal the correlation is negative (y counts down), along
 * the mirror one it is positive. Only a clear positive answer is acted on, so
 * a round shield or an upright staff is left exactly as drawn. Which END of the
 * mirror diagonal is the handle cannot be measured that cheaply; a mirrored
 * tool is almost always drawn with the handle at the bottom, so it is mirrored
 * left to right, which puts that handle in the hand.
 */
final class HeldSprites {

    /** How diagonal a picture has to be before it is turned. A thick axe head still clears it. */
    static final double THRESHOLD = 0.4;

    private HeldSprites() {
    }

    /**
     * The texture location a handheld item should draw: {@code location}
     * itself, or a mirrored copy written beside it when the picture is drawn
     * facing the other way.
     */
    static String forHand(String location, Contribution into) {
        String path = Geometry.zipPathOf(location);
        if (into.has(path + ".mcmeta")) {
            // An animated strip: its frames are stacked, so the picture as a
            // whole says nothing about which way one frame faces.
            return location;
        }
        Optional<byte[]> bytes = into.read(path);
        if (bytes.isEmpty()) {
            return location;
        }
        BufferedImage image;
        try {
            image = Images.read(bytes.get());
        } catch (IOException | RuntimeException e) {
            return location;
        }
        if (image == null || !drawnMirrored(image)) {
            return location;
        }
        String held = location + "_held";
        String heldPath = Geometry.zipPathOf(held);
        if (into.has(heldPath)) {
            return location;
        }
        try {
            into.add(heldPath, Images.png(mirror(image)));
        } catch (IOException e) {
            return location;
        }
        return held;
    }

    /** Whether the picture lies along the top-left to bottom-right diagonal. */
    static boolean drawnMirrored(BufferedImage image) {
        return diagonal(image) > THRESHOLD;
    }

    /**
     * The correlation of the opaque pixels' x and y, with y counting down:
     * near -1 for vanilla's diagonal, near 1 for the mirror one, near 0 for
     * anything round, square or upright.
     */
    static double diagonal(BufferedImage image) {
        double n = 0;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) {
                    continue;
                }
                n++;
                sx += x;
                sy += y;
                sxx += (double) x * x;
                syy += (double) y * y;
                sxy += (double) x * y;
            }
        }
        if (n < 6) {
            return 0;
        }
        double vx = sxx / n - (sx / n) * (sx / n);
        double vy = syy / n - (sy / n) * (sy / n);
        if (vx <= 0 || vy <= 0) {
            return 0;
        }
        double cov = sxy / n - (sx / n) * (sy / n);
        return cov / Math.sqrt(vx * vy);
    }

    private static BufferedImage mirror(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                out.setRGB(image.getWidth() - 1 - x, y, image.getRGB(x, y));
            }
        }
        return out;
    }
}
