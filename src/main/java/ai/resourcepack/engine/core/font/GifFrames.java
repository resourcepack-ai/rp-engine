package ai.resourcepack.engine.core.font;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * A GIF, read into whole frames. Internal.
 *
 * <p>The JDK's own reader, because a GIF decoder is not a thing to ship a
 * second copy of — and because the JDK one is what every server already has.
 * What it does NOT do is composite: {@link ImageReader#read(int)} hands back
 * each frame exactly as stored, which in most GIFs is a small patch that
 * changed since the last one, drawn at an offset onto whatever the previous
 * frame left behind. Taken on its own that patch is a picture of a mouth with
 * no face around it. So this replays the file the way a browser does: one
 * canvas the size of the logical screen, each patch drawn at its
 * {@code imageLeftPosition}/{@code imageTopPosition}, and after each frame the
 * canvas put back as its disposal method says — left alone ({@code none},
 * {@code doNotDispose}), the patch cleared to transparent
 * ({@code restoreToBackgroundColor}, which every browser draws as transparent
 * rather than as the background colour the spec names), or the whole canvas
 * restored to what it was before the patch ({@code restoreToPrevious}).
 *
 * <p>Free of Bukkit and tested with GIFs the tests write themselves.
 */
final class GifFrames {

    /**
     * What a browser does with a frame delay of 0 or 1 hundredths, which is
     * 100 milliseconds. Those values are in the wild because old encoders
     * wrote them meaning "as fast as you can", and played literally they would
     * be a blur — every browser decided the same thing, and a GIF somebody
     * made looks right in a browser, so that is the reference.
     */
    static final int SLOW_DELAY_MS = 100;

    private final List<BufferedImage> frames;
    private final List<Integer> delays;
    private final int width;
    private final int height;
    private final int total;

    private GifFrames(List<BufferedImage> frames, List<Integer> delays, int width, int height, int total) {
        this.frames = frames;
        this.delays = delays;
        this.width = width;
        this.height = height;
        this.total = total;
    }

    /**
     * How many frames the file has and how long each lasts, without drawing
     * any of them. What the server needs at startup, where the picture is
     * nobody's business but the client's.
     */
    static Optional<GifFrames> measure(byte[] gif, int maxFrames) {
        return read(gif, maxFrames, false);
    }

    /** Every frame, composited, up to {@code maxFrames}. */
    static Optional<GifFrames> decode(byte[] gif, int maxFrames) {
        return read(gif, maxFrames, true);
    }

    private static Optional<GifFrames> read(byte[] gif, int maxFrames, boolean draw) {
        if (gif == null || gif.length == 0) {
            return Optional.empty();
        }
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            return Optional.empty();
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
            if (in == null) {
                return Optional.empty();
            }
            reader.setInput(in, false);
            int total = reader.getNumImages(true);
            if (total < 1) {
                return Optional.empty();
            }
            int[] screen = logicalScreen(reader.getStreamMetadata());
            int kept = Math.min(total, Math.max(1, maxFrames));

            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            BufferedImage canvas = null;
            int width = screen[0];
            int height = screen[1];
            for (int index = 0; index < kept; index++) {
                Frame frame = frame(reader.getImageMetadata(index));
                delays.add(frame.delayMs);
                if (!draw) {
                    if (width <= 0 || height <= 0) {
                        // No logical screen: the first frame's own size is the
                        // picture's, which is what every encoder that omits it means.
                        width = Math.max(width, frame.left + frame.width);
                        height = Math.max(height, frame.top + frame.height);
                    }
                    continue;
                }
                BufferedImage patch = reader.read(index);
                if (canvas == null) {
                    width = width > 0 ? width : frame.left + patch.getWidth();
                    height = height > 0 ? height : frame.top + patch.getHeight();
                    canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                }
                BufferedImage before = frame.disposal.equals("restoreToPrevious") ? copy(canvas) : null;
                Graphics2D g = canvas.createGraphics();
                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(patch, frame.left, frame.top, null);
                g.dispose();
                frames.add(copy(canvas));
                if (frame.disposal.equals("restoreToBackgroundColor")) {
                    Graphics2D clear = canvas.createGraphics();
                    clear.setComposite(AlphaComposite.Clear);
                    clear.fillRect(frame.left, frame.top, patch.getWidth(), patch.getHeight());
                    clear.dispose();
                } else if (before != null) {
                    canvas = before;
                }
            }
            if (width <= 0 || height <= 0) {
                return Optional.empty();
            }
            return Optional.of(new GifFrames(List.copyOf(frames), List.copyOf(delays), width, height, total));
        } catch (IOException | RuntimeException e) {
            // A broken GIF is the pack's problem and the caller says so; the
            // JDK reader is known to throw unchecked on some malformed LZW.
            return Optional.empty();
        } finally {
            reader.dispose();
        }
    }

    /** The composited frames, empty when only measured. */
    List<BufferedImage> frames() {
        return frames;
    }

    /** Each kept frame's delay, in milliseconds. */
    List<Integer> delays() {
        return delays;
    }

    /** How many frames were kept. */
    int count() {
        return delays.size();
    }

    /** How many frames the file has, which can be more than were kept. */
    int total() {
        return total;
    }

    /** The logical screen: every frame's size. */
    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /**
     * Frames a second from the average delay, clamped to 1 to {@code max}.
     *
     * <p>An average, because an icon has one rate. A GIF that holds its last
     * frame for a second and flicks through the others plays evenly here,
     * which is the honest cost of a frame being a glyph rather than a clip.
     */
    int fps(int max) {
        if (delays.isEmpty()) {
            return 1;
        }
        long sum = 0;
        for (int delay : delays) {
            sum += delay;
        }
        double average = (double) sum / delays.size();
        return (int) Math.max(1, Math.min(max, Math.round(1000.0 / average)));
    }

    /**
     * The frames stacked top to bottom into one PNG: a sheet of
     * {@code count} rows and one column, which the font cuts into a glyph per
     * frame exactly as it cuts any other sheet.
     *
     * <p>Scaled down, nearest neighbour, when a frame is larger than
     * {@code maxSide} on either side — a font glyph that big does not fit the
     * page the game packs glyphs onto, and the game's answer is to draw
     * nothing. Nearest neighbour because these are nearly always pixel art.
     */
    byte[] strip(int maxSide) throws IOException {
        int w = width;
        int h = height;
        if (w > maxSide || h > maxSide) {
            double scale = Math.min((double) maxSide / w, (double) maxSide / h);
            w = Math.max(1, Math.min(maxSide, (int) Math.round(width * scale)));
            h = Math.max(1, Math.min(maxSide, (int) Math.round(height * scale)));
        }
        BufferedImage sheet = new BufferedImage(w, h * frames.size(), BufferedImage.TYPE_INT_ARGB);
        for (int k = 0; k < frames.size(); k++) {
            BufferedImage frame = frames.get(k);
            for (int y = 0; y < h; y++) {
                int sy = Math.min(height - 1, (int) ((long) y * height / h));
                for (int x = 0; x < w; x++) {
                    int sx = Math.min(width - 1, (int) ((long) x * width / w));
                    sheet.setRGB(x, k * h + y, frame.getRGB(sx, sy));
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(sheet, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    /** {@code LogicalScreenDescriptor}'s width and height, or zeros when it has none. */
    private static int[] logicalScreen(IIOMetadata stream) {
        if (stream == null) {
            return new int[] {0, 0};
        }
        Node root = stream.getAsTree("javax_imageio_gif_stream_1.0");
        Node screen = child(root, "LogicalScreenDescriptor");
        return new int[] {attribute(screen, "logicalScreenWidth", 0), attribute(screen, "logicalScreenHeight", 0)};
    }

    private static Frame frame(IIOMetadata image) {
        Node root = image.getAsTree("javax_imageio_gif_image_1.0");
        Node descriptor = child(root, "ImageDescriptor");
        Node control = child(root, "GraphicControlExtension");
        int hundredths = attribute(control, "delayTime", 0);
        String disposal = control == null ? "none" : text(control, "disposalMethod", "none");
        return new Frame(
                attribute(descriptor, "imageLeftPosition", 0),
                attribute(descriptor, "imageTopPosition", 0),
                attribute(descriptor, "imageWidth", 0),
                attribute(descriptor, "imageHeight", 0),
                hundredths <= 1 ? SLOW_DELAY_MS : hundredths * 10,
                disposal);
    }

    private static Node child(Node parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (name.equals(node.getNodeName())) {
                return node;
            }
        }
        return null;
    }

    private static String text(Node node, String name, String fallback) {
        NamedNodeMap attributes = node == null ? null : node.getAttributes();
        Node value = attributes == null ? null : attributes.getNamedItem(name);
        return value == null ? fallback : value.getNodeValue();
    }

    private static int attribute(Node node, String name, int fallback) {
        try {
            return Integer.parseInt(text(node, name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record Frame(int left, int top, int width, int height, int delayMs, String disposal) {
    }
}
