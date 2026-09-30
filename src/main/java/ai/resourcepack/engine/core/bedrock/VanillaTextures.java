package ai.resourcepack.engine.core.bedrock;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Vanilla textures, for the Bedrock copy of a model that paints with them.
 *
 * <p>A Java model can name {@code minecraft:block/oak_planks} and let every
 * client supply the pixels. A Bedrock entity is drawn from ONE texture the pack
 * ships, so those pixels have to be baked in — and a server has no copy of the
 * game's art. They are fetched once from the public mirror of the game's
 * assets Studio also reads, and kept on disk.
 *
 * <p><strong>Never on the thread that asked.</strong> {@link #cached} answers
 * only from disk and remembers what it did not have; {@link #fetchMissing}
 * downloads those off the main thread and says whether anything new arrived,
 * so the caller can build the pack again. A first build therefore has the
 * missing-texture checkerboard where a vanilla texture goes, for the few
 * seconds until the second one.
 */
public final class VanillaTextures {

    /** The assets release the mirror is read at. Studio reads the same one. */
    static final String VERSION = "1.21.11";
    private static final String MIRROR = "https://raw.githubusercontent.com/misode/mcmeta/" + VERSION
            + "-assets/assets/minecraft/textures/";

    private final Path cache;
    private final Logger log;
    private final Set<String> missed = ConcurrentHashMap.newKeySet();
    /** Asked once per run; a texture the mirror does not have is not asked for again. */
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();

    public VanillaTextures(Path cache, Logger log) {
        this.cache = cache;
        this.log = log;
    }

    /** {@code block/oak_planks}, from disk, or null (and remembered as wanted). */
    public BufferedImage cached(String path) {
        if (!safe(path)) return null;
        Path file = cache.resolve(path + ".png");
        if (Files.isRegularFile(file)) {
            try {
                return ImageIO.read(file.toFile());
            } catch (IOException e) {
                return null;
            }
        }
        if (!attempted.contains(path)) missed.add(path);
        return null;
    }

    /**
     * Downloads everything {@link #cached} was asked for and did not have.
     * Blocking; call it off the main thread.
     *
     * @return whether at least one texture arrived
     */
    public boolean fetchMissing() {
        Collection<String> wanted = new LinkedHashSet<>(missed);
        missed.clear();
        boolean any = false;
        for (String path : wanted) {
            if (!attempted.add(path)) continue;
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(MIRROR + path + ".png").toURL().openConnection();
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(10_000);
                if (connection.getResponseCode() != 200) continue;
                byte[] bytes;
                try (InputStream in = connection.getInputStream()) {
                    bytes = in.readAllBytes();
                }
                Path file = cache.resolve(path + ".png");
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
                any = true;
            } catch (IOException | IllegalArgumentException e) {
                log.fine("Could not fetch vanilla texture " + path + ": " + e.getMessage());
            }
        }
        return any;
    }

    public boolean hasMissing() {
        return !missed.isEmpty();
    }

    /** A texture path with nothing in it that could climb out of the cache. */
    private static boolean safe(String path) {
        return path.matches("[a-z0-9_./-]+") && !path.contains("..");
    }
}
