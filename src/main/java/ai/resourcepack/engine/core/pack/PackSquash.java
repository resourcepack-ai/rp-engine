package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.BuiltPack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@code pack.packsquash}: each bundle run through
 * <a href="https://packsquash.aylas.org/">PackSquash</a> before it is served.
 *
 * <p>The server owner downloads PackSquash and points the config at it; the
 * plugin ships none. The bundle is unzipped into a work folder, PackSquash is
 * run on it with the owner's options file plus the two paths it needs, and the
 * zip it writes is what players are sent. The result is kept, keyed by the
 * bundle's SHA-1, the options and the PackSquash file, so a restart or a reload
 * that changed nothing is instant and only a real change runs it again.
 *
 * <p>Three things it does on our behalf, because without them a squashed RP
 * Engine pack is a broken one:
 * <ul>
 *   <li><b>Overlay folders are copied as they are.</b> PackSquash 0.4 does not
 *       know a pack.mcmeta overlay and skips every file in one, which is every
 *       shader RP Engine ships per game version (HUDs, dialogs, 3D armour, text
 *       effects). Each top-level folder other than {@code assets} and
 *       {@code data} gets a {@code force_include} table.</li>
 *   <li><b>The default options keep the shader textures exact.</b> Those
 *       shaders read exact pixel colours out of font glyphs and armour sheets,
 *       and PackSquash's default palette reduction moves them. See
 *       {@link #DEFAULT_OPTIONS}.</li>
 *   <li><b>A pack PackSquash would have lost files from is not served.</b>
 *       Anything it says it skipped, other than source files the game never
 *       reads, means the unsquashed pack is served instead, with the names.</li>
 * </ul>
 * A squashed pack that comes out no smaller is not served either.
 */
public final class PackSquash {

    /** Written to the options file the first time, and never again: after that it is the owner's. */
    public static final String DEFAULT_OPTIONS = String.join("\n",
            "# PackSquash options for RP Engine's packs.",
            "# Every option: https://packsquash.aylas.org/ (the options files page).",
            "#",
            "# RP Engine sets pack_directory and output_file_path itself, and keeps every",
            "# overlay folder (where its per-version shaders live) as it is. Anything",
            "# else here is yours. Top-level options go above the first [table].",
            "#",
            "# Tables apply to the files their path pattern matches. Give each file one",
            "# table only: a file two tables match does not reliably get either.",
            "",
            "# Smaller still, and harder for somebody to unzip and copy your pack. The",
            "# game reads it fine; most zip tools will not open it.",
            "# size_increasing_zip_obfuscation = true",
            "# zip_spec_conformance_level = 'disregard'",
            "",
            "# Font glyphs and 3D armour textures carry exact colours that RP Engine's",
            "# shaders read (HUDs, dialogs, text effects, rank tags, 3D armour). Keep",
            "# them lossless, or those stop drawing right.",
            "['**/textures/font/**/*.png']",
            "color_quantization_target = 'none'",
            "skip_alpha_optimizations = true",
            "",
            "['**/armor3d/**/*.png']",
            "color_quantization_target = 'none'",
            "skip_alpha_optimizations = true",
            "",
            "['**/textures/entity/equipment/**/*.png']",
            "color_quantization_target = 'none'",
            "skip_alpha_optimizations = true",
            "",
            "# Sounds are re-encoded at PackSquash's own quality, which is where most of",
            "# the saving is. To keep them exactly as they are, uncomment this:",
            "# ['**/*.ogg']",
            "# transcode_ogg = false",
            "");

    /** File types that are never part of a pack the game reads: their being left out is no loss. */
    private static final Set<String> SOURCE_ONLY = Set.of(
            "bbmodel", "yml", "yaml", "md", "psd", "xcf", "kra", "ase", "aseprite", "blend", "bak", "ini");

    /** One PackSquash run's outcome: the squashed zip, or why there is none. */
    record Outcome(Path zip, String problem) {
        static Outcome ok(Path zip) {
            return new Outcome(zip, null);
        }

        static Outcome failed(String problem) {
            return new Outcome(null, problem);
        }
    }

    /** What the config says. */
    public record Settings(boolean enabled, Path executable, Path options) {
    }

    private final Logger logger;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RPEngine PackSquash");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Future<Outcome>> running = new ConcurrentHashMap<>();
    /** A squashed file's SHA-1 -> the SHA-1 of the pack it was made from. */
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    /** Each problem is said once a run, not on every reload. */
    private final Set<String> said = ConcurrentHashMap.newKeySet();

    public PackSquash(Logger logger) {
        this.logger = logger;
    }

    /** Stops a run in progress, for when the plugin is going away. */
    public void shutdown() {
        worker.shutdownNow();
    }

    /**
     * Each pack squashed, or itself where it cannot be. Main thread.
     *
     * @param waitMillis how long to wait for a run that is not done yet. Past
     *                   it the pack is served unsquashed this time, the run
     *                   finishes in the background, and the next reload or
     *                   restart serves its result
     */
    public List<BuiltPack> squash(List<BuiltPack> packs, Settings settings, long waitMillis) {
        if (!settings.enabled() || packs.isEmpty()) {
            return packs;
        }
        Path executable = executable(settings.executable());
        if (executable == null) {
            sayOnce("PackSquash is turned on (pack.packsquash) but there is no PackSquash at "
                    + settings.executable() + ". Download it from https://packsquash.aylas.org/ and put it "
                    + "there, then /rp reload. Packs are served unsquashed until then.");
            return packs;
        }
        byte[] options;
        try {
            if (!Files.isRegularFile(settings.options())) {
                Files.createDirectories(settings.options().getParent());
                Files.writeString(settings.options(), DEFAULT_OPTIONS, StandardCharsets.UTF_8);
                logger.info("Wrote PackSquash's default options to " + settings.options() + ".");
            }
            options = Files.readAllBytes(settings.options());
        } catch (IOException e) {
            sayOnce("Could not read PackSquash's options at " + settings.options() + ": " + e.getMessage()
                    + ". Packs are served unsquashed.");
            return packs;
        }
        long deadline = System.currentTimeMillis() + Math.max(0, waitMillis);
        List<BuiltPack> out = new ArrayList<>(packs.size());
        for (BuiltPack pack : packs) {
            out.add(squashOne(pack, executable, options, deadline));
        }
        return out;
    }

    private BuiltPack squashOne(BuiltPack pack, Path executable, byte[] options, long deadline) {
        String key;
        try {
            key = key(pack, executable, options);
        } catch (IOException e) {
            return pack;
        }
        Path folder = pack.file().getParent().resolve("squashed");
        Path target = folder.resolve(pack.bundle() + "-" + key + ".zip");
        if (Files.isRegularFile(target)) {
            return squashed(pack, target);
        }
        Future<Outcome> run = running.computeIfAbsent(key,
                k -> worker.submit(() -> run(pack, executable, new String(options, StandardCharsets.UTF_8), target)));
        Outcome outcome;
        try {
            outcome = run.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.info("PackSquash is still working on '" + pack.bundle() + "', so it is served unsquashed for "
                    + "now. The squashed pack is used from the next /rp reload or restart.");
            return pack;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return pack;
        } catch (ExecutionException e) {
            running.remove(key);
            logger.warning("PackSquash failed on '" + pack.bundle() + "': " + e.getCause()
                    + ". It is served unsquashed.");
            return pack;
        }
        running.remove(key);
        if (outcome.zip() == null) {
            sayOnce("PackSquash: '" + pack.bundle() + "' " + outcome.problem() + " It is served unsquashed.");
            return pack;
        }
        return squashed(pack, outcome.zip());
    }

    /**
     * The SHA-1 of the pack a squashed file was made from, or {@code sha1}
     * itself for one that was not squashed. PackSquash's output is not the
     * same twice (the order of its files and its sound encoding vary), so this
     * is what says two servers are serving the same pack.
     */
    public String sourceOf(String sha1) {
        return sources.getOrDefault(sha1, sha1);
    }

    private BuiltPack squashed(BuiltPack pack, Path zip) {
        try {
            String sha1 = sha1(zip);
            sources.put(sha1, pack.sha1());
            return BuiltPack.of(pack.bundle(), zip, sha1, Files.size(zip), pack.entries());
        } catch (IOException e) {
            return pack;
        }
    }

    /** Runs PackSquash once. On the worker thread. */
    private Outcome run(BuiltPack pack, Path executable, String userOptions, Path target) throws IOException {
        // Absolute throughout: PackSquash runs in the work folder, and a path
        // relative to the server's would point nowhere from there.
        target = target.toAbsolutePath();
        executable = executable.toAbsolutePath();
        Path folder = target.getParent();
        Files.createDirectories(folder);
        Path work = folder.resolve("work-" + pack.bundle());
        deleteTree(work);
        Path input = work.resolve("pack");
        Path output = work.resolve("out.zip");
        Path options = work.resolve("options.toml");
        try {
            Files.createDirectories(input);
            Set<String> topFolders = unzip(pack.file(), input);
            Files.writeString(options, options(input, output, topFolders, userOptions), StandardCharsets.UTF_8);

            Process process = new ProcessBuilder(executable.toString(), options.toString())
                    .directory(work.toFile())
                    .redirectErrorStream(true)
                    .start();
            List<String> skipped = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String path = skippedPath(line);
                    if (path != null) {
                        skipped.add(path);
                    }
                    if (lines.size() < 400) {
                        lines.add(line);
                    }
                }
            }
            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                return Outcome.failed("was not squashed: the server is stopping.");
            }
            if (exit != 0 || !Files.isRegularFile(output)) {
                return Outcome.failed("could not be squashed (PackSquash exited with " + exit + "): "
                        + lastProblem(lines));
            }
            List<String> lost = lost(skipped);
            if (!lost.isEmpty()) {
                return Outcome.failed("would have lost " + lost.size() + " file(s) PackSquash does not know, "
                        + "starting with " + String.join(", ", lost.subList(0, Math.min(3, lost.size())))
                        + ". Add a table for them with force_include = true to its options file.");
            }
            long before = Files.size(pack.file());
            long after = Files.size(output);
            if (after >= before) {
                return Outcome.failed("came out no smaller (" + before + " bytes before, " + after
                        + " after), so the original is kept. Its options are probably lossless; see the "
                        + "options file for what saves space.");
            }
            Files.move(output, target, StandardCopyOption.REPLACE_EXISTING);
            removeOlder(folder, pack.bundle(), target);
            logger.info("PackSquash: '" + pack.bundle() + "' " + kib(before) + " -> " + kib(after) + ".");
            return Outcome.ok(target);
        } finally {
            deleteTree(work);
        }
    }

    /**
     * The options file PackSquash is run with: the two paths, the owner's
     * top-level options, a {@code force_include} table for each overlay folder,
     * then the owner's tables. TOML wants every top-level key above the first
     * table, and PackSquash does not reliably give a file to the later of two
     * tables that match it, so the overlays' come before the owner's.
     */
    static String options(Path input, Path output, Set<String> topFolders, String userOptions) {
        StringBuilder top = new StringBuilder();
        StringBuilder tables = new StringBuilder();
        boolean inTables = false;
        for (String line : userOptions.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.trim();
            if (!inTables && trimmed.startsWith("[")) {
                inTables = true;
            }
            if (!inTables && (trimmed.startsWith("pack_directory") || trimmed.startsWith("output_file_path"))) {
                continue;
            }
            (inTables ? tables : top).append(line).append('\n');
        }
        StringBuilder out = new StringBuilder();
        out.append("pack_directory = ").append(literal(input.toAbsolutePath().toString())).append('\n');
        out.append("output_file_path = ").append(literal(output.toAbsolutePath().toString())).append('\n');
        out.append(top);
        for (String folder : topFolders) {
            if (folder.equals("assets") || folder.equals("data")) {
                continue;
            }
            out.append("\n[").append(literal(folder + "/**")).append("]\nforce_include = true\n");
        }
        out.append('\n').append(tables);
        return out.toString();
    }

    /** A TOML literal string; a path with a quote in it falls back to a basic string. */
    static String literal(String value) {
        if (value.indexOf('\'') < 0 && value.indexOf('\n') < 0) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** The path in a PackSquash "* path: Skipped" line, or null. */
    static String skippedPath(String line) {
        if (line.startsWith("* ") && line.endsWith(": Skipped")) {
            return line.substring(2, line.length() - ": Skipped".length());
        }
        return null;
    }

    /** The skipped files the game might need: everything but source files. */
    static List<String> lost(List<String> skipped) {
        List<String> lost = new ArrayList<>();
        for (String path : skipped) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!SOURCE_ONLY.contains(extension)) {
                lost.add(path);
            }
        }
        return lost;
    }

    private static String lastProblem(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "no output";
    }

    /** Unzips {@code zip} into {@code into}; returns the top-level folder names. */
    private static Set<String> unzip(Path zip, Path into) throws IOException {
        Set<String> top = new TreeSet<>();
        Path root = into.toAbsolutePath().normalize();
        try (InputStream raw = Files.newInputStream(zip); ZipInputStream in = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path to = root.resolve(entry.getName()).normalize();
                if (!to.startsWith(root)) {
                    continue;
                }
                int slash = entry.getName().indexOf('/');
                if (slash > 0) {
                    top.add(entry.getName().substring(0, slash));
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return top;
    }

    /** The executable, trying {@code .exe} beside a bare name on Windows; null when there is none. */
    private static Path executable(Path configured) {
        if (Files.isRegularFile(configured)) {
            return configured;
        }
        Path exe = configured.resolveSibling(configured.getFileName() + ".exe");
        return Files.isRegularFile(exe) ? exe : null;
    }

    /** What a squashed copy depends on: the pack, the options and the PackSquash it was made with. */
    private static String key(BuiltPack pack, Path executable, byte[] options) throws IOException {
        MessageDigest digest = sha1Digest();
        digest.update(pack.sha1().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(options);
        digest.update((byte) 0);
        digest.update((Files.size(executable) + ":" + Files.getLastModifiedTime(executable).toMillis())
                .getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    private static String sha1(Path file) throws IOException {
        MessageDigest digest = sha1Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Drops this bundle's older squashed copies, so the folder holds one per bundle. */
    private static void removeOlder(Path folder, String bundle, Path keep) {
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(bundle + "-") && name.endsWith(".zip")
                        && name.length() == bundle.length() + 1 + 16 + 4 && !p.equals(keep);
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // In use by a download; the next build takes it.
                }
            });
        } catch (IOException ignored) {
            // Nothing to tidy.
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Left for the next run, which clears it first.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }

    private static String kib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
    }

    private void sayOnce(String message) {
        if (said.add(message)) {
            logger.warning(message);
        }
    }
}
