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
     * @return the pack, or why there is not one, in the vocabulary the
     *         protocol uses
     */
    public static Fetch fetch(String payload, Path outputDir) {
        String url = packUrl(payload);
        if (url.isEmpty()) {
            return Fetch.failed("bad-payload");
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
                return Fetch.failed("http-" + connection.getResponseCode());
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
                        return Fetch.failed("too-large");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return Fetch.of(BuiltPack.of(BUNDLE, target, hex(digest.digest()), size, 0));
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException e) {
            return Fetch.failed("exception");
        } finally {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // A leftover .part is untidy and harmless; the next push
                // overwrites it.
            }
        }
    }

    /**
     * Which slot of an {@code APPLY} payload holds what.
     *
     * <p><strong>The payload is positional, not "a pack and a manifest".</strong>
     * Studio space-joins a fixed sequence of URLs and writes {@code -} for a
     * slot it has nothing for, popping only the trailing empties. Its own
     * comment says why the order can be relied on: a plugin older than a slot
     * simply never reads that index, so appending is safe and inserting is not.
     *
     * <p>Reading the wrong index does not fail loudly. The rigs manifest is
     * also JSON with a {@code packId} in it, so merging it as an emote manifest
     * parses cleanly and yields nothing — which reads as "this pack has no
     * emotes" for a pack full of them.
     */
    private static final int SLOT_PACK = 0;
    private static final int SLOT_RIGS = 1;
    private static final int SLOT_BEDROCK = 2;
    private static final int SLOT_EMOTES = 3;

    /** The pack itself. */
    public static String packUrl(String payload) {
        return slot(payload, SLOT_PACK).orElse("");
    }

    /**
     * The emote manifest, or empty when the push carries none.
     *
     * <p>Everything needed to PLAY an emote is in there — the keyframes, the
     * bones, and which baked rig belongs to whom — because a resource pack can
     * carry the art and has nowhere to put the animation.
     */
    public static Optional<String> emotesUrl(String payload) {
        return slot(payload, SLOT_EMOTES);
    }

    /** The animated-model rigs, on the same terms. Nothing reads these yet. */
    public static Optional<String> rigsUrl(String payload) {
        return slot(payload, SLOT_RIGS);
    }

    /** One slot, with {@code -} and an absent trailing slot both meaning empty. */
    static Optional<String> slot(String payload, int index) {
        if (payload == null) {
            return Optional.empty();
        }
        String[] parts = payload.trim().split(" ");
        if (index >= parts.length) {
            return Optional.empty();
        }
        String value = parts[index];
        return value.isEmpty() || value.equals("-") ? Optional.empty() : Optional.of(value);
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
     * A downloaded pack, or why there is not one.
     *
     * <p>A returned value rather than a field somebody reads afterwards. The
     * reason used to live in a mutable static, which was correct only while
     * pushes arrived one at a time down one socket — the kind of thing that
     * stops being true without anything failing loudly enough to notice.
     */
    public static final class Fetch {

        private final BuiltPack pack;
        private final String reason;

        private Fetch(BuiltPack pack, String reason) {
            this.pack = pack;
            this.reason = reason;
        }

        static Fetch of(BuiltPack pack) {
            return new Fetch(pack, "");
        }

        static Fetch failed(String reason) {
            return new Fetch(null, reason);
        }

        /** The pack, or empty. */
        public Optional<BuiltPack> pack() {
            return Optional.ofNullable(pack);
        }

        /** Why there is not one. Empty on success. */
        public String reason() {
            return reason;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
