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

    /** The pack's files: each merged group's shaders in an overlay of its own, and the metadata naming them. */
    public static Map<String, byte[]> files(Plan plan, int packFormat, String description) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        com.google.gson.JsonArray overlays = new com.google.gson.JsonArray();
        for (Outcome o : plan.merged()) {
            String directory = "rpe_merged_v" + o.minFormat();
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("min_format", o.minFormat());
            entry.addProperty("max_format", o.maxFormat());
            entry.addProperty("directory", directory);
            overlays.add(entry);
            for (Map.Entry<String, String> f : o.files().entrySet()) {
                out.put(directory + "/" + f.getKey(), f.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        out.put("pack.mcmeta", PackBuilder.packMeta(packFormat, description, overlays).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** Writes the pack to {@code file} and returns it, ready for the host. */
    public static BuiltPack write(Plan plan, String bundle, Path file, int packFormat, String description) throws IOException {
        DeterministicZip zip = new DeterministicZip();
        for (Map.Entry<String, byte[]> f : files(plan, packFormat, description).entrySet()) {
            zip.add(f.getKey(), f.getValue());
        }
        Files.createDirectories(file.getParent());
        String sha1 = zip.writeTo(file);
        return BuiltPack.of(bundle, file, sha1, Files.size(file), zip.size());
    }
}
