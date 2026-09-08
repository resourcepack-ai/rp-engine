package ai.resourcepack.engine.api;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
 * <p><strong>A version stamp decides.</strong> A {@code .version} file beside
 * the content says which build of the plugin last wrote it, so restarting on
 * the same jar copies nothing and reloads nothing - a reload rebuilds every
 * pack on the server, and doing that on every boot for files that have not
 * changed is a cost with no reason. A new build overwrites its own files, and
 * ONLY its own: a file the server owner added is left alone, because the
 * folder is theirs to extend - a second skin, a different sound, a fixed
 * typo.
 *
 * <p><strong>Whole files or none.</strong> Each is written to a temporary
 * name and moved into place, so a server that dies mid-install has either the
 * old file or the new one, never half of one for the engine to choke on.
 */
public final class AddonContent {

    /** The name of the stamp file, and a name a pack may therefore not use. */
    public static final String STAMP = ".version";

    private AddonContent() {
    }

    /**
     * Installs {@code plugin}'s {@code content/<namespace>/} into the engine,
     * if this build has not already.
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
                plugin.getDescription().getVersion());
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
        Path stamp = into.resolve(STAMP);
        if (Files.isRegularFile(stamp)
                && version.equals(Files.readString(stamp, StandardCharsets.UTF_8).trim())) {
            return false;
        }
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
                if (relative.isEmpty() || relative.contains("..")) {
                    continue;
                }
                Path target = into.resolve(relative);
                Files.createDirectories(target.getParent());
                Path part = target.resolveSibling(target.getFileName() + ".part");
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                written++;
            }
        }
        Files.createDirectories(into);
        Files.writeString(stamp, version + "\n", StandardCharsets.UTF_8);
        return written > 0;
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
