package ai.resourcepack.engine.core.pack;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Reading and writing pictures without the temp folder.
 *
 * <p>Given a plain stream, ImageIO buffers it through a file in
 * {@code java.io.tmpdir}. Many hosts give a server a small {@code /tmp} of its
 * own, and once that is full every picture fails with "No space left on
 * device" while the server's own disk has room, so the pack never builds.
 * These buffer in memory instead, which for pictures the size of a pack's is
 * the cheaper choice anyway. Local rather than {@code ImageIO.setUseCache},
 * which would change it for every plugin on the server.
 */
public final class Images {
    private Images() {}

    /** [data] for an ImageIO reader, buffered in memory. */
    public static ImageInputStream stream(byte[] data) {
        return new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
    }

    /** The picture in [data], or null if no reader knows its format. */
    public static BufferedImage read(byte[] data) throws IOException {
        return ImageIO.read(stream(data));
    }

    /** [image] as [format] onto [out]. */
    public static void write(RenderedImage image, String format, OutputStream out) throws IOException {
        try (ImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, format, ios)) throw new IOException("This Java has no " + format + " writer");
        }
    }

    /** [image] as PNG bytes. */
    public static byte[] png(RenderedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(image, "png", out);
        return out.toByteArray();
    }
}
