package ai.resourcepack.engine.core.font;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes small GIFs for tests, with the JDK's own encoder.
 *
 * <p>Written here rather than checked in as binary files so that what each
 * test's GIF contains — which patch, at which offset, disposed how — is
 * readable next to the assertion about it.
 */
public final class Gifs {

    private Gifs() {
    }

    /** One frame: a patch, where it is drawn, and what happens to it afterwards. */
    public record Frame(BufferedImage patch, int left, int top, String disposal, int delayHundredths) {

        public static Frame of(BufferedImage patch) {
            return new Frame(patch, 0, 0, "none", 10);
        }
    }

    /** A solid patch of one colour. */
    public static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    /** {@code count} full frames of {@code width} by {@code height}, each a different grey, at one delay. */
    public static byte[] frames(int count, int width, int height, int delayHundredths) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int grey = (i * 37) % 256;
            frames.add(new Frame(solid(width, height, (grey << 16) | (grey << 8) | grey), 0, 0, "none",
                    delayHundredths));
        }
        return write(frames);
    }

    /** The frames as one animated GIF; the logical screen is the first frame's size. */
    public static byte[] write(List<Frame> frames) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (Frame frame : frames) {
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(frame.patch()), param);
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                IIOMetadataNode control = child(root, "GraphicControlExtension");
                control.setAttribute("disposalMethod", frame.disposal());
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", String.valueOf(frame.delayHundredths()));
                control.setAttribute("transparentColorIndex", "0");
                IIOMetadataNode descriptor = child(root, "ImageDescriptor");
                descriptor.setAttribute("imageLeftPosition", String.valueOf(frame.left()));
                descriptor.setAttribute("imageTopPosition", String.valueOf(frame.top()));
                metadata.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(frame.patch(), null, metadata), param);
            }
            writer.endWriteSequence();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equals(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode made = new IIOMetadataNode(name);
        root.appendChild(made);
        return made;
    }
}
