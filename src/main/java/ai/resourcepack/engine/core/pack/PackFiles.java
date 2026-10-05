package ai.resourcepack.engine.core.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a file out of a pack's folder, by the pack's namespace and a path
 * relative to it.
 *
 * <p>For the rare definition that cannot be understood without its art. An
 * icon drawn from a GIF is the case that made this: how many frames it has is
 * how many codepoints it takes, the server has to know that before any pack is
 * built, and only the GIF can say. Both the build and the server read it the
 * same way, through this, so the two cannot come to different numbers.
 *
 * <p>The same shape as {@link PackContributor.Contribution#source}, so a
 * contributor hands its own over as {@code into::source}.
 */
@FunctionalInterface
public interface PackFiles {

    /** Reads nothing: for a caller that has no folder, such as a test of the YAML alone. */
    PackFiles NONE = (namespace, relativePath) -> Optional.empty();

    /** A file in the pack folder for {@code namespace}, or empty. */
    Optional<byte[]> read(String namespace, String relativePath);

    /**
     * The file that the build would put at {@code zipPath}, read from wherever
     * in the content folder it came from.
     *
     * <p>The build's routing run backwards — see
     * {@link PackBuilder#sourcesOf} — so a GIF is found whether the pack keeps
     * it under {@code assets/}, in ItemsAdder's {@code textures/}, or in a
     * CraftEngine resource pack folder.
     */
    default Optional<byte[]> find(String zipPath, Collection<String> namespaces) {
        for (Map.Entry<String, String> source : PackBuilder.sourcesOf(zipPath, namespaces)) {
            Optional<byte[]> found = read(source.getKey(), source.getValue());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** The pack folders under {@code contentRoot}, the same root the loader and the builder read. */
    static PackFiles folder(Path contentRoot) {
        return (namespace, relativePath) -> {
            if (contentRoot == null || namespace == null || relativePath == null) {
                return Optional.empty();
            }
            Path pack = contentRoot.resolve(namespace).normalize();
            Path file = pack.resolve(relativePath).normalize();
            // Never outside the pack's own folder, whatever the definition
            // asked for. A content pack is somebody else's file on your disk.
            if (!file.startsWith(pack) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (IOException e) {
                return Optional.empty();
            }
        };
    }
}
