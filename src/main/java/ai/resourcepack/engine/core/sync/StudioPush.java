package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.BuiltPack;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Fetches a pack studio pushed and turns it into something servable.
 *
 * <p>A pushed pack is <strong>already built</strong>: studio made the zip and
 * signed a URL for it. Nothing here rebuilds or inspects it — the bytes go to
 * disk, get hashed, and become a {@link BuiltPack} like any other, so the whole
 * of the serving and swapping machinery works on it unchanged.
 *
 * <p>That is the point of the bundle model earning its keep: a studio push is
 * one more bundle a player can be holding, stacked on top of whatever the
 * server's own content already gave them.
 */
public final class StudioPush {

    /** Studio packs land in one bundle, so a second push replaces the first. */
    public static final String BUNDLE = "studio";

    /** A pack larger than this is not a pack, it is a mistake or an attack. */
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private StudioPush() {
    }

    /**
     * Downloads {@code payload} into {@code outputDir}.
     *
     * @param payload the {@code APPLY} url field, which is "everything after
     *                the second space" and may be two space-joined urls. The
     *                first is the pack; the second, when present, is the
     *                animation manifest, which nothing here reads yet
     * @return the pack, or empty with the reason on the other side of the
     *         {@link #reason} field
     */
    public static Optional<BuiltPack> fetch(String payload, Path outputDir) {
        String url = packUrl(payload);
        if (url.isEmpty()) {
            reason = "bad-payload";
            return Optional.empty();
        }
        Path target = outputDir.resolve(BUNDLE + ".zip");
        Path partial = outputDir.resolve(BUNDLE + ".zip.part");
        try {
            Files.createDirectories(outputDir);
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() / 100 != 2) {
                reason = "http-" + connection.getResponseCode();
                return Optional.empty();
            }

            // Downloaded beside the real file and moved into place, so a push
            // that dies halfway cannot leave a truncated zip being served to
            // everybody who joins.
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            long size = 0;
            try (InputStream in = connection.getInputStream();
                 java.io.OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    size += read;
                    if (size > MAX_BYTES) {
                        reason = "too-large";
                        return Optional.empty();
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(BuiltPack.of(BUNDLE, target, hex(digest.digest()), size, 0));
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException e) {
            reason = "exception";
            return Optional.empty();
        } finally {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // A leftover .part is untidy and harmless; the next push
                // overwrites it.
            }
        }
    }

    /** The pack half of an {@code APPLY} payload. */
    public static String packUrl(String payload) {
        return payload == null ? "" : payload.trim().split(" ")[0];
    }

    /**
     * The manifest half, or empty when the push carries none.
     *
     * <p>Studio space-joins a second URL when the pack has animated models or
     * emotes in it. Everything the plugin needs to PLAY an emote is in there —
     * the keyframes, the bones, and which baked rig belongs to whom — because
     * a resource pack can carry the art but has nowhere to put the animation.
     */
    public static Optional<String> manifestUrl(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String[] parts = payload.trim().split(" ");
        return parts.length > 1 && !parts[1].isEmpty() ? Optional.of(parts[1]) : Optional.empty();
    }

    /** Downloads a manifest as text, or empty if it could not be had. */
    public static Optional<String> fetchText(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() / 100 != 2) {
                return Optional.empty();
            }
            try (InputStream in = connection.getInputStream()) {
                return Optional.of(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Why the last {@link #fetch} failed, in the vocabulary the protocol uses.
     *
     * <p>Static and therefore not thread-safe, which is fine because pushes
     * arrive one at a time down one socket — but it is the sort of thing that
     * stops being fine quietly, so if a second caller ever appears this becomes
     * a field on a result object.
     */
    public static volatile String reason = "exception";

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
