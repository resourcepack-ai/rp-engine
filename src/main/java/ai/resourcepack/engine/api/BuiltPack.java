package ai.resourcepack.engine.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * One built bundle, on disk, with the two things a client push needs.
 *
 * <p>A file rather than a byte array: a pack is megabytes, the server hands it
 * out over HTTP many times, and holding every bundle in the heap to serve a
 * URL would be paying for the same bytes twice.
 *
 * <p>The two values that matter to the game:
 *
 * <ul>
 *   <li>{@link #sha1()} — the client caches by it. A rebuild that changes
 *       nothing must produce the same hash, or every player redownloads on
 *       every restart and swapping bundles stops feeling instant. That is why
 *       the builder goes to such lengths over zip determinism.</li>
 *   <li>{@link #uuid()} — identifies which pack is being replaced or removed,
 *       so it is derived from the bundle NAME and not from the contents. A
 *       rebuild of {@code lobby} is the same pack with new bytes, and a UUID
 *       that moved would leave the old one applied.</li>
 * </ul>
 */
public final class BuiltPack {

    private final String bundle;
    private final Path file;
    private final String sha1;
    private final UUID uuid;
    private final long size;
    private final int entries;

    private final String url;

    private BuiltPack(String bundle, Path file, String sha1, UUID uuid, long size, int entries) {
        this(bundle, file, sha1, uuid, size, entries, "");
    }

    private BuiltPack(String bundle, Path file, String sha1, UUID uuid, long size, int entries,
                      String url) {
        this.url = url;
        this.bundle = bundle;
        this.file = file;
        this.sha1 = sha1;
        this.uuid = uuid;
        this.size = size;
        this.entries = entries;
    }

    /** Engine internal; a host reads one rather than making one. */
    public static BuiltPack of(String bundle, Path file, String sha1, long size, int entries) {
        Objects.requireNonNull(bundle, "bundle");
        return new BuiltPack(
                bundle,
                Objects.requireNonNull(file, "file"),
                Objects.requireNonNull(sha1, "sha1"),
                uuidFor(bundle),
                size,
                entries);
    }

    /**
     * The stable UUID for a bundle name.
     *
     * <p>Name-based (type 3) rather than random, so it survives a restart, a
     * rebuild and a reinstall. Two servers running the same bundle name agree
     * on it, which costs nothing and makes a support log readable.
     */
    public static UUID uuidFor(String bundle) {
        return UUID.nameUUIDFromBytes(("rpengine:bundle:" + bundle)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The bundle this was built from. */
    /**
     * A pack this engine did not build, at the address it is already served
     * from.
     *
     * <p>Empty for everything built here, which is served by the plugin's own
     * {@code PackHost}. A pack pushed from Studio arrives with a signed URL
     * that a client can already reach, and handing that straight to the player
     * is both fewer moving parts and the only thing that works on a server
     * whose {@code host.public-address} is wrong — which is every server that
     * has not been told what its own address is.
     */
    /**
     * A pack somebody else is already serving.
     *
     * <p>The file is still kept — it is what the SHA-1 was computed from, and
     * a hash is what lets a client cache a pack instead of downloading it
     * again — but nothing here will serve it.
     */
    public static BuiltPack served(String bundle, Path file, String sha1, long size, int entries,
                                   String url) {
        BuiltPack pack = of(bundle, file, sha1, size, entries);
        return new BuiltPack(pack.bundle(), file, pack.sha1(), pack.uuid(), size, entries,
                url == null ? "" : url);
    }

    public String url() {
        return url;
    }

    public String bundle() {
        return bundle;
    }

    /** Where the zip is. */
    public Path file() {
        return file;
    }

    /** Lowercase hex SHA-1 of the file, which is what the client is told. */
    public String sha1() {
        return sha1;
    }

    /** The stable per-bundle UUID. Never changes with the contents. */
    public UUID uuid() {
        return uuid;
    }

    /** Size of the zip in bytes. */
    public long size() {
        return size;
    }

    /** How many files went into it. */
    public int entries() {
        return entries;
    }

    @Override
    public String toString() {
        return bundle + " " + sha1 + " (" + entries + " files, " + size + " bytes)";
    }
}
