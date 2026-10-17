package ai.resourcepack.engine.core.pack;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the core shaders that our packs and somebody else's both replace.
 *
 * <p>A core shader is one file per name, and when two packs on a client ship
 * the same one the game keeps the higher pack's copy and nothing else. No
 * merge, no error: whatever the lower pack's shader did simply stops. HUD and
 * text-effect plugins all draw through the text shader, so this is the usual
 * way two of them break each other, and the broken one looks like a bug in a
 * feature nobody touched. This class only says that it is happening; it
 * changes nothing in any pack.
 *
 * <p>"Somebody else's" is whatever pack another plugin left on disk: a built
 * folder or a zip somewhere under its data folder. That is how every plugin
 * we know of hands its pack to a host, and reading it needs no knowledge of
 * the plugin, so one scan covers the ones we have heard of and the ones we
 * have not. A pack that only ever exists in memory, or on a web server, is
 * not seen.
 */
public final class ShaderClashes {

    /** A core shader file, in the base of a pack or in one of its overlays. */
    private static final Pattern CORE = Pattern.compile(
            "(?:.*/)?assets/minecraft/shaders/core/([a-z0-9_./-]+)\\.(?:vsh|fsh|json)");

    /** How deep under a plugin's folder a pack is looked for. */
    static final int MAX_DEPTH = 7;

    /** How many entries one plugin's folder may cost before the scan gives up on it. */
    static final int ENTRY_BUDGET = 20_000;

    /** A zip bigger than this is a backup or a world, not a pack. */
    static final long MAX_ZIP_BYTES = 512L * 1024 * 1024;

    private ShaderClashes() {
    }

    /**
     * The shader's name with the file type taken off, and 26.x's {@code text}
     * read as the {@code rendertype_text} it was renamed from: a pack built
     * for either version is replacing the same thing.
     */
    static String shaderName(String path) {
        Matcher m = CORE.matcher(path.replace('\\', '/'));
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1);
        return name.equals("text") ? "rendertype_text" : name;
    }

    /** The core shaders a zip replaces, by name. Empty for anything that is not a readable zip. */
    public static Set<String> inZip(Path zip) {
        Set<String> out = new TreeSet<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.isDirectory() ? null : shaderName(entry.getName());
                if (name != null) {
                    out.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
        return out;
    }

    /**
     * What one plugin's folder was found to replace, where the first pack
     * doing so is (for a person), and every such pack (for a merge): a zip,
     * or the folder a loose pack's {@code pack.mcmeta} is in.
     */
    public record Found(Set<String> shaders, String where, List<Path> packs) {
    }

    /**
     * Every core shader a pack under {@code folder} replaces: unpacked
     * folders and zips alike, as far as {@link #MAX_DEPTH} and
     * {@link #ENTRY_BUDGET} reach. Never throws; a folder it cannot read is a
     * folder with nothing in it.
     *
     * @param relativeTo what {@code where} is written relative to, normally the plugins folder
     */
    public static Found scan(Path folder, Path relativeTo) {
        Set<String> shaders = new TreeSet<>();
        String[] where = {null};
        int[] budget = {ENTRY_BUDGET};
        Set<Path> packs = new java.util.LinkedHashSet<>();
        if (folder == null || !Files.isDirectory(folder)) {
            return new Found(Set.of(), null, List.of());
        }
        try {
            Files.walkFileTree(folder, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            return --budget[0] < 0 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (--budget[0] < 0) {
                                return FileVisitResult.TERMINATE;
                            }
                            String path = folder.relativize(file).toString().replace('\\', '/');
                            String name = shaderName(path);
                            if (name != null) {
                                shaders.add(name);
                                if (where[0] == null) {
                                    where[0] = label(relativeTo, file, path);
                                }
                                Path root = packRoot(file);
                                if (root != null) {
                                    packs.add(root);
                                }
                            } else if (path.endsWith(".zip") && attrs.size() <= MAX_ZIP_BYTES) {
                                Set<String> inside = inZip(file);
                                if (!inside.isEmpty()) {
                                    shaders.addAll(inside);
                                    packs.add(file);
                                    if (where[0] == null) {
                                        where[0] = relative(relativeTo, file);
                                    }
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException ignored) {
            // What was found before the failure is still true.
        }
        return new Found(Collections.unmodifiableSet(shaders), where[0], List.copyOf(packs));
    }

    /**
     * The pack a loose core shader belongs to: the folder holding
     * {@code pack.mcmeta} above its {@code assets/}, which is one up from it in
     * the base and two up in an overlay. Null when there is none, because a
     * folder that is not a pack is not one any client is sent.
     */
    static Path packRoot(Path shader) {
        Path assets = shader;
        for (int i = 0; i < 4 && assets != null; i++) {
            assets = assets.getParent();
        }
        // shader -> core -> shaders -> minecraft -> assets
        if (assets == null || !assets.getFileName().toString().equals("assets")) {
            return null;
        }
        Path base = assets.getParent();
        if (base != null && Files.isRegularFile(base.resolve("pack.mcmeta"))) {
            return base;
        }
        Path overlayOf = base == null ? null : base.getParent();
        if (overlayOf != null && Files.isRegularFile(overlayOf.resolve("pack.mcmeta"))) {
            return overlayOf;
        }
        return null;
    }

    /** The pack folder a loose shader file sits in: the path up to its {@code assets/}. */
    private static String label(Path relativeTo, Path file, String path) {
        int at = path.lastIndexOf("assets/minecraft/shaders/core/");
        String full = relative(relativeTo, file);
        int cut = full.length() - (path.length() - at);
        return cut > 0 ? full.substring(0, cut - 1) : full;
    }

    static String relative(Path relativeTo, Path file) {
        try {
            return relativeTo == null ? file.toString() : relativeTo.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /** One shader both sides replace, and who on each side. Sorted, so it compares and prints stably. */
    public record Clash(String shader, List<String> ours, List<String> theirs) {

        /** One line, for the console and for chat. */
        public String describe() {
            return "Shader clash: " + shader + " is replaced by " + String.join(" and ", ours)
                    + " and also by " + String.join(" and ", theirs)
                    + ". A player's game keeps only one copy, from whichever pack is higher in their list,"
                    + " so what the other copy draws stops working.";
        }
    }

    /**
     * The shaders replaced on both sides.
     *
     * @param ours   what each of our packs replaces, by a name a person can read
     * @param theirs what each other plugin's packs replace, by that plugin's name
     */
    public static List<Clash> find(Map<String, Set<String>> ours, Map<String, Set<String>> theirs) {
        Map<String, Set<String>> ourSide = byShader(ours);
        Map<String, Set<String>> theirSide = byShader(theirs);
        List<Clash> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : ourSide.entrySet()) {
            Set<String> others = theirSide.get(e.getKey());
            if (others != null && !others.isEmpty()) {
                out.add(new Clash(e.getKey(), List.copyOf(e.getValue()), List.copyOf(others)));
            }
        }
        return out;
    }

    private static Map<String, Set<String>> byShader(Map<String, Set<String>> bySource) {
        Map<String, Set<String>> out = new TreeMap<>();
        if (bySource == null) {
            return out;
        }
        for (Map.Entry<String, Set<String>> e : new TreeMap<>(bySource).entrySet()) {
            for (String shader : e.getValue()) {
                out.computeIfAbsent(shader, k -> new TreeSet<>()).add(e.getKey());
            }
        }
        return out;
    }
}
