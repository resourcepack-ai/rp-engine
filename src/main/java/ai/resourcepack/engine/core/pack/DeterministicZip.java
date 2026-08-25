package ai.resourcepack.engine.core.pack;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a zip that is byte-identical for identical content.
 *
 * <p>This is not tidiness. The client caches a resource pack by its SHA-1, so
 * a build that is not reproducible turns every restart into a redownload for
 * every player and makes a bundle swap feel broken instead of instant. Three
 * things are needed for it and all three are easy to lose:
 *
 * <ol>
 *   <li><strong>Sorted entries.</strong> The order a directory walk returns
 *       files in is not a promise, and differs between filesystems.</li>
 *   <li><strong>A fixed timestamp.</strong> See {@link #FIXED_TIME} — the
 *       reason it is zero rather than a nice round date is subtle and
 *       load-bearing.</li>
 *   <li><strong>A fixed compression level.</strong> Deflate output depends on
 *       it. It is pinned here rather than left to the JDK default.</li>
 * </ol>
 *
 * <p>One caveat worth knowing before someone spends a day on it: the deflate
 * implementation is the JDK's, so a major JDK upgrade could in principle
 * change the bytes. That costs one redownload after an upgrade, which is
 * acceptable; a build that is nondeterministic on one machine is not.
 */
public final class DeterministicZip {

    /**
     * Zero, meaning 1970, meaning "before 1980" in every timezone on earth.
     *
     * <p>{@link ZipEntry#setTime(long)} converts to DOS time using the default
     * timezone, so any ordinary date produces different bytes on a server in
     * Sydney and a server in Los Angeles. A pre-1980 value cannot be
     * represented in DOS time at all, so the JDK clamps it to a single
     * constant — which makes it the one input that is timezone-independent.
     *
     * <p>{@code setLastModifiedTime} is deliberately not used: it writes an
     * extended-timestamp extra field in addition, which puts real time back
     * into the file.
     */
    public static final long FIXED_TIME = 0L;

    /** Pinned so the deflate output cannot move under us. */
    public static final int LEVEL = Deflater.BEST_COMPRESSION;

    private final Map<String, byte[]> entries = new TreeMap<>();

    /**
     * Adds a file at {@code path} within the zip.
     *
     * <p>A later add of the same path replaces the earlier one, which is how
     * a bundle resolves two namespaces shipping the same vanilla override. The
     * caller decides who goes last and reports the collision; this class only
     * has to be predictable about it.
     */
    public void add(String path, byte[] content) {
        if (path == null || path.isEmpty() || content == null) {
            return;
        }
        entries.put(path, content);
    }

    /** Whether anything is already at {@code path}. */
    public boolean has(String path) {
        return path != null && entries.containsKey(path);
    }

    /** Drops whatever is at {@code path}. */
    public void remove(String path) {
        if (path != null) {
            entries.remove(path);
        }
    }

    /** How many files will be written. */
    public int size() {
        return entries.size();
    }

    /**
     * Writes the zip and returns its lowercase hex SHA-1.
     *
     * <p>The hash is taken as the bytes go out rather than by reading the file
     * back, so a pack is hashed once and never read twice.
     */
    public String writeTo(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        MessageDigest digest = sha1();
        try (OutputStream out = Files.newOutputStream(file);
             DigestOutputStream digesting = new DigestOutputStream(out, digest);
             ZipOutputStream zip = new ZipOutputStream(digesting)) {
            zip.setLevel(LEVEL);
            // TreeMap, so this is already sorted by path.
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(FIXED_TIME);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-1. If this throws, the
            // problem is not something a content pack can fix.
            throw new IllegalStateException("SHA-1 is not available", e);
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
