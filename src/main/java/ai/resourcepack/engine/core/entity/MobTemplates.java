package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mob templates: {@code extends: other_mob} (or MythicMobs' {@code Template:})
 * makes a mob everything the other one is, plus what it says itself.
 *
 * <p>Resolved once, before anything reads a mob, by rewriting the ENTITY
 * definitions of a {@link LoadReport}: the entity reader and the skill reader
 * then see one merged body each and know nothing of templates. The merge:
 *
 * <ul>
 *   <li>a key the mob writes wins over its template's;</li>
 *   <li>a map in both is merged the same way, key by key ({@code sounds:},
 *   {@code animations:}, {@code boss-bar:}...);</li>
 *   <li>a list is replaced, except {@code skills:}, which is the template's
 *   lines followed by the mob's (MythicMobs adds a template's skills to the
 *   mob's own);</li>
 *   <li>{@code abstract: true} marks a mob that exists only to be extended:
 *   it needs no {@code type:} and cannot be spawned.</li>
 * </ul>
 *
 * <p>A template is named like a metaskill: bare, it is the mob's own pack's
 * first, then any pack's; {@code otherpack:brute} is another pack's. Chains
 * work (a template may extend another); a loop is reported and the mobs in it
 * load as written.
 *
 * <p>Free of Bukkit, and tested as such ({@code MobTemplatesTest}).
 */
public final class MobTemplates {

    /** How long a chain of templates may be before it is called a mistake. */
    static final int MAX_CHAIN = 16;

    private MobTemplates() {
    }

    /** The report with every ENTITY's template merged in and every abstract one left out. */
    public static Result resolve(LoadReport loaded) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(null, diagnostics);
        }
        List<ContentDefinition> entities = loaded.definitions(ContentKind.ENTITY);
        boolean any = false;
        for (ContentDefinition definition : entities) {
            if (parentName(definition.body()) != null || abstractOne(definition.body())) {
                any = true;
                break;
            }
        }
        if (!any) {
            return new Result(loaded, diagnostics);
        }
        Map<ContentId, ContentDefinition> byId = new LinkedHashMap<>();
        for (ContentDefinition definition : entities) {
            byId.put(definition.id(), definition);
        }
        Map<ContentId, Map<String, Object>> merged = new HashMap<>();
        List<ContentDefinition> out = new ArrayList<>();
        for (ContentDefinition definition : loaded.definitions()) {
            if (definition.kind() != ContentKind.ENTITY) {
                out.add(definition);
                continue;
            }
            if (abstractOne(definition.body())) {
                continue;
            }
            Map<String, Object> body = mergedBody(definition, byId, merged, new LinkedHashSet<>(), diagnostics);
            out.add(ContentDefinition.of(definition.entry(), DefinitionNode.of(body), definition.origin()));
        }
        return new Result(LoadReport.of(loaded.packs(), out, loaded.diagnostics()), diagnostics);
    }

    private static boolean abstractOne(DefinitionNode body) {
        return body.bool("abstract").orElse(Boolean.FALSE);
    }

    /** {@code extends:}, {@code template:} or {@code Template:}, when it is a name. */
    static String parentName(DefinitionNode body) {
        for (String key : List.of("extends", "template", "Template", "Extends")) {
            Object raw = body.raw(key);
            if (raw instanceof String && !((String) raw).isBlank()) {
                return ((String) raw).trim();
            }
        }
        return null;
    }

    private static Map<String, Object> mergedBody(ContentDefinition definition, Map<ContentId, ContentDefinition> byId,
                                                  Map<ContentId, Map<String, Object>> done, Set<ContentId> chain,
                                                  List<Diagnostic> diagnostics) {
        Map<String, Object> known = done.get(definition.id());
        if (known != null) {
            return known;
        }
        Map<String, Object> own = new LinkedHashMap<>(definition.body().values());
        String parent = parentName(definition.body());
        if (parent == null) {
            done.put(definition.id(), own);
            return own;
        }
        Optional<ContentDefinition> found = find(parent, definition.id(), byId);
        if (found.isEmpty()) {
            diagnostics.add(Diagnostic.warning(definition.origin(), definition.id().path(),
                    "extends: " + parent + " is no mob, so this one is only what it says itself."));
            done.put(definition.id(), own);
            return own;
        }
        chain.add(definition.id());
        if (chain.contains(found.get().id()) || chain.size() > MAX_CHAIN) {
            diagnostics.add(Diagnostic.warning(definition.origin(), definition.id().path(),
                    "extends: " + parent + " goes round in a loop (" + chain + "), so this mob is only what "
                            + "it says itself."));
            done.put(definition.id(), own);
            return own;
        }
        Map<String, Object> base = mergedBody(found.get(), byId, done, chain, diagnostics);
        Map<String, Object> result = merge(base, own);
        // The child is not abstract because its parent was, and does not
        // carry the parent's pointer either: that is resolved now.
        result.remove("abstract");
        for (String key : List.of("extends", "template", "Template", "Extends")) {
            result.remove(key);
        }
        done.put(definition.id(), result);
        return result;
    }

    private static Optional<ContentDefinition> find(String name, ContentId from, Map<ContentId, ContentDefinition> byId) {
        String written = name.trim();
        if (written.indexOf(':') > 0) {
            return ContentId.parse(written).map(byId::get);
        }
        Optional<ContentId> own = ContentId.of(from.namespace(), written);
        if (own.isPresent() && byId.containsKey(own.get())) {
            return Optional.of(byId.get(own.get()));
        }
        return byId.values().stream().filter(d -> d.id().path().equals(written))
                .min((a, b) -> a.id().compareTo(b.id()));
    }

    /** {@code child} over {@code parent}: maps merge, {@code skills} lists join, everything else is the child's. */
    static Map<String, Object> merge(Map<String, Object> parent, Map<String, Object> child) {
        Map<String, Object> out = new LinkedHashMap<>(parent);
        for (Map.Entry<String, Object> entry : child.entrySet()) {
            String key = entry.getKey();
            Object mine = entry.getValue();
            Object theirs = out.get(key);
            if (isSkills(key)) {
                Object other = theirs != null ? theirs : skillsOf(parent, key);
                if (other instanceof List && mine instanceof List) {
                    List<Object> joined = new ArrayList<>((List<?>) other);
                    joined.addAll((List<?>) mine);
                    removeSkills(out);
                    out.put(key, joined);
                    continue;
                }
            }
            if (theirs instanceof Map && mine instanceof Map) {
                out.put(key, merge(stringKeys((Map<?, ?>) theirs), stringKeys((Map<?, ?>) mine)));
            } else {
                out.put(key, mine);
            }
        }
        return out;
    }

    private static boolean isSkills(String key) {
        return key.equals("skills") || key.equals("Skills");
    }

    /** The parent's skills under either spelling, so {@code Skills:} and {@code skills:} still join. */
    private static Object skillsOf(Map<String, Object> parent, String key) {
        Object lower = parent.get("skills");
        return lower != null ? lower : parent.get("Skills");
    }

    private static void removeSkills(Map<String, Object> map) {
        map.remove("skills");
        map.remove("Skills");
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null) {
                out.put(k.toString(), v);
            }
        });
        return out;
    }

    /** The rewritten report, and what was wrong with the templates. */
    public static final class Result {
        private final LoadReport report;
        private final List<Diagnostic> diagnostics;

        Result(LoadReport report, List<Diagnostic> diagnostics) {
            this.report = report;
            this.diagnostics = List.copyOf(diagnostics);
        }

        public LoadReport report() {
            return report;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
