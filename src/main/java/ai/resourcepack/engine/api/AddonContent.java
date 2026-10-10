package ai.resourcepack.engine.api;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Installs the content folder a plugin carries in its own jar into the
 * engine's {@code content/} directory.
 *
 * <p><strong>Every addon needs this and none of it is about what the addon
 * IS.</strong> A plugin built on the engine ships its models, items, vehicles
 * and emotes as an ordinary content folder inside its jar
 * ({@code resources/content/<namespace>/}), and on enable it has to end up in
 * {@code plugins/RPEngine/content/<namespace>/} where the engine reads it.
 * The first addon wrote seventy lines to do that; the second would have
 * copied them, and the third would have copied the copy, bugs included.
 *
 * <pre>
 * if (AddonContent.install(this, engine, "skateboards")) {
 *     engine.reload();
 * }
 * </pre>
 *
 * <p><strong>A version stamp decides whether to look.</strong> A
 * {@code .version} file beside the content says which build of the plugin last
 * wrote it, so restarting on the same jar copies nothing and reloads nothing -
 * a reload rebuilds every pack on the server, and doing that on every boot for
 * files that have not changed is a cost with no reason.
 *
 * <p><strong>The folder is the server owner's to change, and an update keeps
 * their changes.</strong> A new build writes only the files it ships, so a file
 * the owner added (a second skin, a different sound) is never touched. And a
 * file the addon shipped that the owner has since EDITED is kept too: an
 * {@code .installed} file beside the content records a hash of every file as it
 * was installed, and a file whose contents no longer match its hash has been
 * changed by somebody, so the new version is not written over it. The console
 * names every file kept that way. Files the owner has not touched are updated,
 * and files new in this build are added.
 *
 * <p>To take the new version of a file that was kept, delete it and restart
 * (or reload the addon): a missing file is always installed. A folder written
 * before {@code .installed} existed has no hashes, so its first update installs
 * every shipped file over what is there, exactly as installs always did, and
 * then writes the record: the protection starts from the update after. An edit
 * made before then is overwritten once, as it always was, because an edit and
 * an old version look alike and freezing every old file would stop the addon
 * updating at all.
 *
 * <p><strong>Whole files or none.</strong> Each is written to a temporary
 * name and moved into place, so a server that dies mid-install has either the
 * old file or the new one, never half of one for the engine to choke on.
 */
public final class AddonContent {

    /** The name of the stamp file, and a name a pack may therefore not use. */
    public static final String STAMP = ".version";

    /**
     * The name of the file recording each installed file's hash, and a name a
     * pack may therefore not use either. One line per file: the SHA-256 of its
     * contents as installed, a tab, and its path inside the folder.
     */
    public static final String MANIFEST = ".installed";

    private AddonContent() {
    }

    /**
     * Installs {@code plugin}'s {@code content/<namespace>/} into the engine,
     * if this build has not already. Files the server owner has changed since
     * they were installed are kept as the owner left them; see the class note.
     *
     * @return whether anything was written - which is exactly when the caller
     *         owes the engine a {@code reload()}. Reloading when
     *         nothing changed is a wasted pack build; not reloading when
     *         something did is content the server cannot see until it
     *         restarts.
     * @throws IOException if the jar cannot be read or the folder cannot be
     *         written. Worth catching and disabling the plugin over: an addon
     *         whose content did not install is an addon whose items do not
     *         exist, and failing loudly at boot beats a command that says
     *         nothing later.
     */
    public static boolean install(Plugin plugin, Plugin engine, String namespace)
            throws IOException {
        return install(pluginJar(plugin), "content/" + namespace + "/",
                engine.getDataFolder().toPath().resolve("content").resolve(namespace),
                plugin.getDescription().getVersion(), plugin.getLogger());
    }

    /**
     * The same, spelled out, for a plugin whose layout is not the ordinary
     * one - content under a different prefix, a namespace that is not the
     * folder name, a version stamp of its own choosing.
     *
     * @param jar       the plugin's jar
     * @param prefix    the entry prefix inside it, ending in a slash
     * @param into      where the files go
     * @param version   what to stamp, and what to compare against
     */
    public static boolean install(File jar, String prefix, Path into, String version)
            throws IOException {
        return install(jar, prefix, into, version, Logger.getLogger(AddonContent.class.getName()));
    }

    /**
     * The same, saying what it kept on {@code log}.
     *
     * @param log where a file kept because the owner changed it is reported
     */
    public static boolean install(File jar, String prefix, Path into, String version, Logger log)
            throws IOException {
        Path stamp = into.resolve(STAMP);
        if (Files.isRegularFile(stamp)
                && version.equals(Files.readString(stamp, StandardCharsets.UTF_8).trim())) {
            return false;
        }
        Path root = into.toAbsolutePath().normalize();
        Path manifest = into.resolve(MANIFEST);
        boolean recorded = Files.isRegularFile(manifest);
        Map<String, String> before = recorded ? readManifest(manifest) : Map.of();
        Map<String, String> after = new TreeMap<>();
        List<String> kept = new ArrayList<>();
        int written = 0;
        try (JarFile file = new JarFile(jar)) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                String relative = entry.getName().substring(prefix.length());
                // `..` is refused rather than resolved: a jar is a file
                // somebody else built, and an entry named its way out of the
                // content folder would be writing anywhere on the server.
                if (relative.isEmpty() || relative.contains("..")
                        || relative.equals(STAMP) || relative.equals(MANIFEST)) {
                    continue;
                }
                // And the result is held to the folder as well, since `..`
                // is not the only way out: `ns//etc/x` or `C:/x` resolves to
                // an absolute path, which replaces `into` entirely.
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root) || target.equals(root)) {
                    log.warning("Skipped " + entry.getName() + " in " + jar.getName()
                            + ": it names a file outside " + into + ".");
                    continue;
                }
                byte[] shipped;
                try (InputStream in = file.getInputStream(entry)) {
                    shipped = in.readAllBytes();
                }
                String hash = hash(shipped);
                if (Files.isRegularFile(target)) {
                    String now = hash(Files.readAllBytes(target));
                    if (now.equals(hash)) {
                        // Already this build's: nothing to write.
                        after.put(relative, hash);
                        continue;
                    }
                    String installed = before.get(relative);
                    // With no record at all (a folder from before there was
                    // one), every shipped file is written, as it always was.
                    if (recorded && (installed == null || !installed.equals(now))) {
                        // Changed since it was installed, or the owner's own
                        // file at a path this build now ships: it stays. What
                        // was recorded stays recorded, so it is still the
                        // owner's next time.
                        if (installed != null) {
                            after.put(relative, installed);
                        }
                        kept.add(relative);
                        continue;
                    }
                }
                Files.createDirectories(target.getParent());
                Path part = target.resolveSibling(target.getFileName() + ".part");
                Files.write(part, shipped);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                after.put(relative, hash);
                written++;
            }
        }
        Files.createDirectories(into);
        writeManifest(manifest, after);
        Files.writeString(stamp, version + "\n", StandardCharsets.UTF_8);
        if (!kept.isEmpty()) {
            log.warning("Kept " + kept.size() + (kept.size() == 1 ? " file" : " files") + " in " + into
                    + " as " + (kept.size() == 1 ? "it is" : "they are") + " rather than updating to " + version
                    + ", because " + (kept.size() == 1 ? "it has" : "they have")
                    + " been changed since installed: " + String.join(", ", kept)
                    + ". To take this version of one, delete it and restart the server.");
        }
        return written > 0;
    }

    /** {@code path -> hash} from a manifest, anything malformed skipped. */
    static Map<String, String> readManifest(Path manifest) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab == 64 && line.length() > tab + 1) {
                out.put(line.substring(tab + 1), line.substring(0, tab));
            }
        }
        return out;
    }

    private static void writeManifest(Path manifest, Map<String, String> hashes) throws IOException {
        StringBuilder text = new StringBuilder();
        hashes.forEach((path, hash) -> text.append(hash).append('\t').append(path).append('\n'));
        Path part = manifest.resolveSibling(manifest.getFileName() + ".part");
        Files.writeString(part, text, StandardCharsets.UTF_8);
        Files.move(part, manifest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every Java has it: the platform promises SHA-256.
            throw new IllegalStateException(e);
        }
    }

    /**
     * A plugin's own jar.
     *
     * <p>{@code JavaPlugin.getFile()} is protected, so a plugin can hand its
     * own jar over but nothing can ask for somebody else's. Reflection here
     * rather than asking every caller to pass it: the ordinary call should be
     * three arguments, and the alternative is every addon writing
     * {@code getFile()} into a public method of its own to get at it.
     */
    private static File pluginJar(Plugin plugin) throws IOException {
        try {
            java.lang.reflect.Method file =
                    org.bukkit.plugin.java.JavaPlugin.class.getDeclaredMethod("getFile");
            file.setAccessible(true);
            return (File) file.invoke(plugin);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Cannot find " + plugin.getName() + "'s own jar", e);
        }
    }
}
