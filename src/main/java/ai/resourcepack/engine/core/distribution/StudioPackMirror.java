package ai.resourcepack.engine.core.distribution;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.pack.PlainPack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The published pack, kept as a folder in {@code content/}.
 *
 * <p>Binding a server used to point its players at the pack on the CDN and
 * write nothing here, so the owner saw no files, could not mix the pack with
 * their own content, and got two packs stacked on every client. Now the live
 * release is unpacked into {@code content/studio-<pack>/} exactly as a Studio
 * zip dropped in by hand would be, and the engine builds it into its own pack.
 * Studio stays the source: a new release replaces the folder.
 *
 * <p><b>Hand edits are kept, not lost.</b> Every file written is recorded with
 * its hash, and before a release replaces the folder anything that no longer
 * matches (changed, added) is copied to {@code studio-backups/<time>/} beside
 * {@code content/}. A folder of that name that this never wrote is moved there
 * whole.
 *
 * <p>Every method blocks on disk and is called off the main thread.
 */
public final class StudioPackMirror {

    /** Beside {@code content/}, never in it: a folder in there would be loaded. */
    public static final String BACKUPS = "studio-backups";

    static final String PREFIX = "studio-";
    static final String README = "STUDIO.txt";
    /** What a Studio export calls its content manifest; the engine reads it from there. */
    static final String CONTENT_FILE = "studio-content.json";

    private static final String STATE = "distribution-files.json";
    private static final String STAGING = "distribution-staging";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final String README_TEXT = ""
            + "This folder is the pack this server publishes from ResourcePack AI Studio.\n"
            + "\n"
            + "RP Engine writes it whenever a new version is published, and replaces\n"
            + "everything in it when it does. Change the pack in Studio and publish it\n"
            + "again; it arrives here within a minute.\n"
            + "\n"
            + "Files changed here by hand are copied to plugins/RPEngine/" + BACKUPS + "/\n"
            + "before they are replaced, so nothing is lost.\n"
            + "\n"
            + "To keep this folder as your own and stop updating it:\n"
            + "  /rp distribute off --keep\n";

    private final Path contentRoot;
    private final Path dataFolder;

    private State state;

    public StudioPackMirror(Path contentRoot, Path dataFolder) {
        this.contentRoot = contentRoot;
        this.dataFolder = dataFolder;
        this.state = readState();
    }

    /** The release the folder holds, or empty when there is none. */
    public synchronized String releaseId() {
        return state == null || !Files.isDirectory(contentRoot.resolve(state.folder)) ? "" : state.releaseId;
    }

    /** The folder's name in {@code content/}, or null when nothing is mirrored. */
    public synchronized String folder() {
        return state == null ? null : state.folder;
    }

    /** What an install did, for the console. */
    public record Installed(String folder, int keptEdits, Path backup) {
    }

    /**
     * Replaces the folder with the pack in {@code zip}, plus its content
     * manifest when there is one.
     *
     * @throws IOException when the zip is not a resource pack or cannot be
     *                     written; the folder is then as it was
     */
    public synchronized Installed install(Path zip, String packId, String releaseId, String content)
            throws IOException {
        String folder = folderFor(packId);

        // Unpacked beside content/ first, so a bad download never takes the
        // live folder away.
        Path staging = dataFolder.resolve(STAGING);
        deleteTree(staging);
        if (!PlainPack.extract(zip, staging)) {
            deleteTree(staging);
            throw new IOException("the published pack is not a resource pack");
        }

        Path backup = null;
        int kept = 0;
        // A different pack bound since last time: the old folder goes.
        if (state != null && !state.folder.equals(folder)) {
            Saved saved = putAway(contentRoot.resolve(state.folder), state);
            backup = saved.where;
            kept += saved.count;
        }
        Path target = contentRoot.resolve(folder);
        Saved saved = putAway(target, state != null && state.folder.equals(folder) ? state : null);
        backup = backup != null ? backup : saved.where;
        kept += saved.count;

        // Copied rather than renamed: see PlainPack#extract on Windows.
        copyTree(staging, target);
        deleteTree(staging);
        if (content != null && !content.isBlank()) {
            Files.writeString(target.resolve(CONTENT_FILE), content, StandardCharsets.UTF_8);
        }
        Files.writeString(target.resolve(README), README_TEXT, StandardCharsets.UTF_8);

        state = new State(folder, releaseId, hashes(target));
        writeState();
        return new Installed(folder, kept, backup);
    }

    /**
     * Stops mirroring. With {@code keep} the folder stays as ordinary
     * content; without it the folder goes, hand edits backed up first.
     *
     * @return whether there was anything to stop
     */
    public synchronized boolean remove(boolean keep) throws IOException {
        if (state == null) {
            return false;
        }
        Path target = contentRoot.resolve(state.folder);
        if (keep) {
            Files.deleteIfExists(target.resolve(README));
        } else {
            putAway(target, state);
        }
        state = null;
        Files.deleteIfExists(dataFolder.resolve(STATE));
        return true;
    }

    /** {@code studio-<pack slug>}, made into a name the content loader accepts. */
    static String folderFor(String packId) {
        String name = PlainPack.namespaceFor(PREFIX + (packId == null ? "" : packId) + ".zip");
        return ContentId.isValidNamespace(name) && name.length() > PREFIX.length() ? name : PREFIX + "pack";
    }

    // -----------------------------------------------------------------------

    private record Saved(Path where, int count) {
    }

    /**
     * Empties {@code folder} out of content/, first copying what somebody
     * changed by hand to a backup. Without {@code known} (a folder this never
     * wrote) the whole of it counts as theirs.
     */
    private Saved putAway(Path folder, State known) throws IOException {
        if (!Files.isDirectory(folder)) {
            return new Saved(null, 0);
        }
        Map<String, String> now = hashes(folder);
        List<String> edited = new ArrayList<>();
        for (Map.Entry<String, String> file : now.entrySet()) {
            String recorded = known == null ? null : known.files.get(file.getKey());
            if (!file.getValue().equals(recorded)) {
                edited.add(file.getKey());
            }
        }
        Path where = null;
        if (!edited.isEmpty()) {
            where = dataFolder.resolve(BACKUPS).resolve(LocalDateTime.now().format(STAMP))
                    .resolve(folder.getFileName().toString());
            for (String relative : edited) {
                Path out = where.resolve(relative);
                Files.createDirectories(out.getParent());
                Files.copy(folder.resolve(relative), out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        deleteTree(folder);
        return new Saved(where, edited.size());
    }

    /** Every file under {@code folder}, by its path relative to it with / separators. */
    private static Map<String, String> hashes(Path folder) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                out.put(folder.relativize(path).toString().replace('\\', '/'), sha1(path));
            }
        }
        return out;
    }

    private static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path out = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(out);
                } else {
                    Files.copy(path, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Unlike PlainPack's, a file that will not go is an error: half a pack must not be left loaded. */
    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    // -----------------------------------------------------------------------

    /** What was last written: which folder, which release, and every file's hash. */
    private static final class State {
        String folder;
        String releaseId;
        Map<String, String> files;

        State(String folder, String releaseId, Map<String, String> files) {
            this.folder = folder;
            this.releaseId = releaseId;
            this.files = files;
        }
    }

    private State readState() {
        Path file = dataFolder.resolve(STATE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            State read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
            if (read == null || read.folder == null || !ContentId.isValidNamespace(read.folder)) {
                return null;
            }
            if (read.releaseId == null) {
                read.releaseId = "";
            }
            if (read.files == null) {
                read.files = Map.of();
            }
            return read;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeState() throws IOException {
        Files.createDirectories(dataFolder);
        Files.writeString(dataFolder.resolve(STATE), GSON.toJson(state), StandardCharsets.UTF_8);
    }
}
