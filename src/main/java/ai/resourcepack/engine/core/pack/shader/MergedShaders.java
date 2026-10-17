package ai.resourcepack.engine.core.pack.shader;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.core.pack.DeterministicZip;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.pack.PackFormats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Plans, for every version of the game, the text shader a player should end
 * up with when our packs and another plugin's both replace it, and writes the
 * pack that gives it to them.
 *
 * <p>That pack goes on top of everything: above ours, which Studio's code is
 * in, and above theirs, which is why it can hold the one file both need. It
 * carries nothing else and has no base files, only version overlays, so a
 * version where nothing could be merged is left exactly as it was.
 *
 * <p>Per version because the files are: a pack ships one text shader per
 * group of versions in overlays (Studio's do, and BetterHud's do), and the two
 * packs' groups do not line up. So every format a real release uses is worked
 * out on its own, from the files a client on it would read in each pack, and
 * formats that come out the same are put back together into one overlay.
 */
public final class MergedShaders {

    private static final String[] EXTENSIONS = {"vsh", "fsh", "json"};

    /** The last pack format whose client reads a core shader's program JSON: 1.21.4's. */
    static final int LAST_PROGRAM_JSON_FORMAT = 46;

    /** What happened for one group of versions. */
    public record Outcome(int minFormat, int maxFormat, String versions, Map<String, String> files, String refused) {

        public boolean merged() {
            return refused == null;
        }
    }

    /** The plan: what is merged and what is not, version by version. */
    public record Plan(List<Outcome> outcomes) {

        public List<Outcome> merged() {
            return outcomes.stream().filter(Outcome::merged).toList();
        }

        public List<Outcome> refused() {
            return outcomes.stream().filter(o -> !o.merged()).toList();
        }

        public boolean isEmpty() {
            return merged().isEmpty();
        }
    }

    private MergedShaders() {
    }

    /**
     * @param ours   our packs a player holds, bottom first, as the client stacks them
     * @param theirs the other plugin's pack
     */
    public static Plan plan(List<PackSource> ours, PackSource theirs) {
        List<Outcome> raw = new ArrayList<>();
        for (PackFormats.Range release : PackFormats.ranges()) {
            Outcome one = forFormat(release.format(), release.label(), ours, theirs);
            if (one != null) {
                raw.add(one);
            }
        }
        return new Plan(join(raw));
    }

    /** One format, or null when the two packs do not both replace the text shader on it. */
    static Outcome forFormat(int format, String label, List<PackSource> ours, PackSource theirs) {
        String name = ShaderMerge.textShaderName(format);
        Map<String, String> mine = new LinkedHashMap<>();
        Map<String, String> other = new LinkedHashMap<>();
        for (String ext : EXTENSIONS) {
            if (ext.equals("json") && format > LAST_PROGRAM_JSON_FORMAT) {
                // Read by nobody past 1.21.4. A base pack's JSON would otherwise
                // be "found" on every newer version and merged for no client.
                continue;
            }
            String path = ShaderMerge.corePath(name, ext);
            topOf(ours, path, format).ifPresent(text -> mine.put(ext, text));
            theirs.resolve(path, format).ifPresent(text -> other.put(ext, text));
        }
        if (mine.isEmpty() || other.isEmpty()) {
            return null;
        }
        GlslScan.Includes includes = includesFor(format, ours, theirs);
        try {
            // Theirs has to compile on its own first. If it cannot, a merge
            // would only put a broken shader where a working one of ours is.
            for (String ext : new String[] {"vsh", "fsh"}) {
                if (other.containsKey(ext)) {
                    List<String> twice = GlslScan.of(other.get(ext), includes).declaredTwice();
                    if (!twice.isEmpty()) {
                        throw new ShaderMerge.Refused("the other pack's own " + (ext.equals("vsh") ? "vertex" : "fragment")
                                + " shader cannot compile on this version: it declares " + String.join(", ", twice)
                                + ". That is a bug in the plugin that made it; an update may fix it.");
                    }
                }
            }
            Map<String, String> files = new LinkedHashMap<>();
            String vsh = stage(other.get("vsh"), mine.get("vsh"), ShaderMerge.Stage.VERTEX, includes);
            String fsh = stage(other.get("fsh"), mine.get("fsh"), ShaderMerge.Stage.FRAGMENT, includes);
            if (vsh != null && fsh != null) {
                List<String> missing = ShaderMerge.unlinked(vsh, fsh);
                if (!missing.isEmpty()) {
                    throw new ShaderMerge.Refused("the fragment stage reads " + String.join(", ", missing)
                            + ", which the vertex stage it would be paired with does not write.");
                }
            }
            String json = mine.containsKey("json") && other.containsKey("json")
                    ? ShaderMerge.mergeProgram(other.get("json"), mine.get("json"))
                    : mine.getOrDefault("json", other.get("json"));
            if (vsh != null) {
                files.put(ShaderMerge.corePath(name, "vsh"), vsh);
            }
            if (fsh != null) {
                files.put(ShaderMerge.corePath(name, "fsh"), fsh);
            }
            if (json != null) {
                files.put(ShaderMerge.corePath(name, "json"), json);
            }
            return new Outcome(format, format, label, files, null);
        } catch (ShaderMerge.Refused refused) {
            return new Outcome(format, format, label, Map.of(), refused.getMessage());
        }
    }

    /**
     * One stage: both sides have it, so it is merged; or only one does, so that
     * one is what the client would read anyway and is written as it is.
     */
    private static String stage(String theirs, String ours, ShaderMerge.Stage stage, GlslScan.Includes includes)
            throws ShaderMerge.Refused {
        if (theirs != null && ours != null) {
            return ShaderMerge.mergeStage(theirs, ours, stage, includes);
        }
        if (ours != null && ShaderMerge.marked(ours)) {
            // Ours alone in this stage: still Studio's file, made of vanilla's.
            return ours;
        }
        if (ours != null) {
            throw new ShaderMerge.Refused("Studio's shader has no merge markers: it was built before shaders could be merged. Push or export the pack again.");
        }
        return theirs;
    }

    /**
     * What each include file a client on {@code format} reads declares: the
     * other pack's own copy if it ships one (BetterHud ships shims of
     * {@code globals.glsl} for old versions), then ours, then the game's, and
     * whatever those import in turn.
     */
    static GlslScan.Includes includesFor(int format, List<PackSource> ours, PackSource theirs) {
        Map<String, java.util.Set<String>> memo = new java.util.HashMap<>();
        return new GlslScan.Includes() {
            @Override
            public java.util.Set<String> namesOf(String file) {
                return namesOf(file, new java.util.HashSet<>());
            }

            private java.util.Set<String> namesOf(String file, java.util.Set<String> visiting) {
                if (memo.containsKey(file)) {
                    return memo.get(file);
                }
                if (!visiting.add(file)) {
                    return java.util.Set.of();
                }
                String path = "assets/minecraft/shaders/include/" + file;
                Optional<String> text = theirs.resolve(path, format);
                if (text.isEmpty()) {
                    text = topOf(ours, path, format);
                }
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                if (text.isPresent()) {
                    GlslScan scan = GlslScan.of(text.get(), f -> namesOf(f, visiting));
                    for (GlslScan.Declaration d : scan.declarations()) {
                        names.add(d.name());
                    }
                    for (GlslScan.Import i : scan.imports()) {
                        java.util.Set<String> more = namesOf(i.file(), visiting);
                        if (more != null) {
                            names.addAll(more);
                        }
                    }
                } else {
                    Map<String, VanillaIncludes.Include> vanilla = VanillaIncludes.BY_FORMAT.get(format);
                    VanillaIncludes.Include include = vanilla == null ? null : vanilla.get(file);
                    if (include == null) {
                        memo.put(file, null);
                        return null;
                    }
                    names.addAll(include.names());
                    for (String more : include.imports()) {
                        java.util.Set<String> m = namesOf(more, visiting);
                        if (m != null) {
                            names.addAll(m);
                        }
                    }
                }
                memo.put(file, names);
                return names;
            }
        };
    }

    /** The topmost of our packs holding {@code path} for {@code format}. */
    private static Optional<String> topOf(List<PackSource> ours, String path, int format) {
        for (int i = ours.size() - 1; i >= 0; i--) {
            Optional<String> found = ours.get(i).resolve(path, format);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Neighbouring formats with the same answer, as one group. */
    private static List<Outcome> join(List<Outcome> raw) {
        List<Outcome> out = new ArrayList<>();
        for (Outcome o : raw) {
            Outcome last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.files().equals(o.files()) && Objects.equals(last.refused(), o.refused())) {
                out.set(out.size() - 1, new Outcome(last.minFormat(), o.maxFormat(),
                        last.versions() + ", " + o.versions(), last.files(), last.refused()));
            } else {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * {@code ours} again, with the merged shaders added to it: every file it
     * had, each merged group's shaders in an overlay of its own listed LAST
     * (an overlay later in the list wins, so they win over the pack's own
     * text shader), and its {@code pack.mcmeta} saying so. Everything else in
     * the metadata is kept as it was.
     *
     * <p>Into the pack rather than beside it, so a player is sent what they
     * were always sent, once, and not a third pack after it.
     *
     * @return the new pack, or null when nothing merged and {@code ours} stands
     */
    public static BuiltPack into(BuiltPack ours, Plan plan, String bundle, Path out) throws IOException {
        if (plan.isEmpty()) {
            return null;
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(ours.file().toFile())) {
            var all = zip.entries();
            while (all.hasMoreElements()) {
                java.util.zip.ZipEntry entry = all.nextElement();
                if (!entry.isDirectory()) {
                    try (var in = zip.getInputStream(entry)) {
                        entries.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        com.google.gson.JsonObject meta;
        try {
            byte[] raw = entries.get("pack.mcmeta");
            meta = raw == null ? new com.google.gson.JsonObject()
                    : com.google.gson.JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            meta = new com.google.gson.JsonObject();
        }
        com.google.gson.JsonObject pack = meta.has("pack") && meta.get("pack").isJsonObject()
                ? meta.getAsJsonObject("pack") : new com.google.gson.JsonObject();
        int packFormat = pack.has("pack_format") ? pack.get("pack_format").getAsInt() : 46;
        String description = pack.has("description") && pack.get("description").isJsonPrimitive()
                ? pack.get("description").getAsString() : "";
        com.google.gson.JsonArray overlays = new com.google.gson.JsonArray();
        if (meta.has("overlays") && meta.get("overlays").isJsonObject()
                && meta.getAsJsonObject("overlays").get("entries") instanceof com.google.gson.JsonArray existing) {
            overlays.addAll(existing);
        }
        for (Outcome o : plan.merged()) {
            String directory = DIRECTORY + o.minFormat();
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("min_format", o.minFormat());
            entry.addProperty("max_format", o.maxFormat());
            entry.addProperty("directory", directory);
            overlays.add(entry);
            for (Map.Entry<String, String> f : o.files().entrySet()) {
                entries.put(directory + "/" + f.getKey(), f.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        // The pack and overlays sections as the builder writes them, so the
        // format rules every version accepts hold; every other section of the
        // pack's own metadata (a language, a filter, Studio's own) is kept.
        com.google.gson.JsonObject rebuilt = com.google.gson.JsonParser.parseString(
                PackBuilder.packMeta(packFormat, description, overlays)).getAsJsonObject();
        com.google.gson.JsonObject newPack = rebuilt.getAsJsonObject("pack");
        if (pack.has("min_format") || pack.has("supported_formats")) {
            // The pack already says which formats it supports (a Studio pack
            // spanning many versions does). That range is the pack's to set;
            // it is only widened, never narrowed, to cover the new overlays.
            int[] own = declaredRange(pack, packFormat);
            int min = Math.min(own[0], newPack.get("min_format").getAsInt());
            int max = Math.max(own[1], newPack.get("max_format").getAsInt());
            newPack = pack.deepCopy();
            if (min < own[0] || max > own[1]) {
                newPack.remove("supported_formats");
                newPack.remove("min_format");
                newPack.remove("max_format");
                newPack.remove("description");
                if (min <= PackBuilder.LAST_OLD_PACK_FORMAT) {
                    com.google.gson.JsonObject range = new com.google.gson.JsonObject();
                    range.addProperty("min_inclusive", min);
                    range.addProperty("max_inclusive", max);
                    newPack.add("supported_formats", range);
                }
                newPack.addProperty("min_format", min);
                newPack.addProperty("max_format", max);
                newPack.add("description", pack.has("description") ? pack.get("description")
                        : new com.google.gson.JsonPrimitive(""));
            }
        } else if (pack.has("description") && !pack.get("description").isJsonPrimitive()) {
            newPack.add("description", pack.get("description"));
        }
        meta.add("pack", newPack);
        meta.add("overlays", rebuilt.get("overlays"));
        entries.put("pack.mcmeta", new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(meta).getBytes(StandardCharsets.UTF_8));

        DeterministicZip zip = new DeterministicZip();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            zip.add(e.getKey(), e.getValue());
        }
        Files.createDirectories(out.getParent());
        String sha1 = zip.writeTo(out);
        return BuiltPack.of(bundle, out, sha1, Files.size(out), zip.size());
    }

    /**
     * The formats a pack section says it supports: {@code min_format} and
     * {@code max_format} (a number, or [major, minor] from 1.21.9), else
     * {@code supported_formats} (a number, [min, max], or an object), else its
     * {@code pack_format} alone.
     */
    static int[] declaredRange(com.google.gson.JsonObject pack, int packFormat) {
        int min = packFormat;
        int max = packFormat;
        try {
            if (pack.has("min_format") && pack.has("max_format")) {
                return new int[] {major(pack.get("min_format")), major(pack.get("max_format"))};
            }
            com.google.gson.JsonElement s = pack.get("supported_formats");
            if (s == null) {
                return new int[] {min, max};
            }
            if (s.isJsonPrimitive()) {
                min = max = s.getAsInt();
            } else if (s.isJsonArray() && s.getAsJsonArray().size() == 2) {
                min = s.getAsJsonArray().get(0).getAsInt();
                max = s.getAsJsonArray().get(1).getAsInt();
            } else if (s.isJsonObject()) {
                min = s.getAsJsonObject().get("min_inclusive").getAsInt();
                max = s.getAsJsonObject().get("max_inclusive").getAsInt();
            }
        } catch (RuntimeException e) {
            // Malformed: the pack's own format is the one thing it surely meant.
        }
        return new int[] {Math.min(min, packFormat), Math.max(max, packFormat)};
    }

    private static int major(com.google.gson.JsonElement format) {
        return format.isJsonArray() ? format.getAsJsonArray().get(0).getAsInt() : format.getAsInt();
    }

    /** Where a merged group's shaders go in a pack: an overlay directory per group. */
    static final String DIRECTORY = "rpe_merged_v";

}
