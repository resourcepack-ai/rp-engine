package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.McVersion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads CraftEngine's content as RP Engine content.
 *
 * <p>A CraftEngine pack is {@code resources/<pack>/} in its plugin folder:
 * a {@code pack.yml}, a {@code configuration/} tree of YAML whose top-level
 * keys are SECTIONS ({@code items:}, {@code blocks:}, {@code furniture:},
 * {@code images:}, {@code emoji:}, {@code recipes:}, {@code templates:},
 * {@code sounds:}, {@code equipments:}, {@code lang:}, ...), each holding
 * namespaced ids, and a {@code resourcepack/} folder that is an ordinary
 * resource pack. Dropped into RP Engine's content folder as it is, it loads:
 * the configuration is read here, and the pack builder copies the resource
 * pack folder into the built pack as written. One of their YAML files inside
 * a pack of ours works too, recognised by its shape (a section name holding a
 * map of ids), so there is nothing to turn on.
 *
 * <p>Everything is read in two passes over every pack at once, because
 * CraftEngine's own content leans on that: templates are defined in one pack
 * and used in another, an item's {@code <lang:...>} name is written in a third
 * file, and an item can place a block defined somewhere else. So the
 * {@link Library} gathers every pack first, then each pack is translated.
 *
 * <p>What comes across is mapped onto RP Engine's own things rather than
 * copied key for key - an item, a placed model ({@code place:}), a custom
 * block, an icon, a recipe, a sound - and everything that cannot is a
 * warning naming the id. See the per-kind classes for the detail.
 */
final class CraftEngine {

    /** Section names, with their singular, plural and kebab spellings, to the canonical one. */
    static final Map<String, String> SECTIONS = sections();

    /** Sections that are a map of locales rather than a map of ids. */
    private static final Set<String> FREE_FORM = Set.of("lang", "translations", "block_state_mappings",
            "damage_rules", "skip_optimization", "config_factory");

    private CraftEngine() {
    }

    private static Map<String, String> sections() {
        Map<String, String> map = new LinkedHashMap<>();
        alias(map, "items", "item", "items");
        alias(map, "blocks", "block", "blocks");
        alias(map, "furniture", "furniture");
        alias(map, "images", "image", "images");
        alias(map, "emoji", "emoji", "emojis");
        alias(map, "recipes", "recipe", "recipes");
        alias(map, "templates", "template", "templates");
        alias(map, "categories", "category", "categories");
        alias(map, "sounds", "sound", "sounds");
        alias(map, "jukebox_songs", "jukebox_song", "jukebox_songs");
        alias(map, "equipments", "equipment", "equipments");
        alias(map, "lang", "lang", "language", "languages");
        alias(map, "translations", "translation", "translations", "l10n", "localization", "i18n",
                "internationalization");
        alias(map, "loot", "loot", "loots");
        alias(map, "loot_sources", "loot_source", "loot_sources", "vanilla_loot", "vanilla_loots");
        alias(map, "atlases", "atlas", "atlases");
        alias(map, "paintings", "painting", "paintings");
        alias(map, "entities", "entity", "entities");
        alias(map, "attributes", "attribute", "attributes");
        alias(map, "attribute_operations", "attribute_operation", "attribute_operations");
        alias(map, "equipment_sets", "equipment_set", "equipment_sets");
        alias(map, "advancements", "advancement", "advancements");
        alias(map, "configured_features", "configured_feature", "configured_features");
        alias(map, "placed_features", "placed_feature", "placed_features");
        alias(map, "global_variables", "global_variable", "global_variables");
        alias(map, "block_state_mappings", "block_state_mapping", "block_state_mappings");
        alias(map, "damage_rules", "damage_rule", "damage_rules");
        alias(map, "skip_optimization", "skip_optimization");
        alias(map, "config_factory", "config_factory", "config_factories");
        return Map.copyOf(map);
    }

    private static void alias(Map<String, String> map, String canonical, String... names) {
        for (String name : names) {
            map.put(name, canonical);
            map.put(name.replace('_', '-'), canonical);
        }
    }

    /** The canonical section a top-level key names ({@code lang#items} is {@code lang}), or null. */
    static String section(String key) {
        int hash = key.indexOf('#');
        return SECTIONS.get(hash < 0 ? key : key.substring(0, hash));
    }

    /**
     * Whether a parsed file is CraftEngine's: a top-level section holding a
     * map of ids (or of locales, for the language sections), or a version
     * key at the top. None of ours has either: our top-level keys are ids,
     * and an id's body is a map of properties, not of more maps.
     */
    static boolean looksLikeOne(DefinitionNode document) {
        // ItemsAdder's files also have an items: section of bare ids, beside
        // an info: block CraftEngine never writes.
        if (document.raw("info") instanceof Map || ItemsAdder.looksLikeOne(document)) {
            return false;
        }
        for (String key : document.keys()) {
            if (key.startsWith("$$")) {
                return true;
            }
            String section = section(key);
            if (section == null || !(document.raw(key) instanceof Map<?, ?> body) || body.isEmpty()) {
                continue;
            }
            boolean allMaps = true;
            for (Map.Entry<?, ?> entry : body.entrySet()) {
                String id = String.valueOf(entry.getKey());
                if (id.indexOf(':') > 0 || id.startsWith("$$")) {
                    return true;
                }
                if (!(entry.getValue() instanceof Map) && !(section.equals("templates")
                        || section.equals("global_variables"))) {
                    allMaps = false;
                }
            }
            if (allMaps) {
                return true;
            }
        }
        return false;
    }

    // ---- shared helpers -------------------------------------------------------

    /** The first of {@code keys} present, in snake or kebab case, as CraftEngine's ConfigKeys read them. */
    static Object get(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) value = map.get(key.replace('_', '-'));
            if (value != null) return value;
        }
        return null;
    }

    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? CraftEngineYaml.cast(map) : null;
    }

    static List<Object> list(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return new ArrayList<>(list);
        return List.of(value);
    }

    static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object element : list(value)) {
            if (element != null && !(element instanceof Map) && !(element instanceof List)) {
                out.add(element.toString());
            }
        }
        return out;
    }

    static String string(Object value) {
        return value == null || value instanceof Map || value instanceof List ? null : value.toString();
    }

    static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim().replace("_", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static boolean truthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return false;
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        return text.equals("true") || text.equals("yes") || text.equals("on") || text.equals("1");
    }

    /**
     * A settings or data section with its keys read the way CraftEngine reads
     * those: {@code -} as {@code _}, and anything from a {@code #} on cut off.
     */
    static Map<String, Object> normalised(Object section) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> raw = map(section);
        if (raw == null) return out;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            int hash = key.indexOf('#');
            if (hash >= 0) key = key.substring(0, hash);
            out.put(key.replace('-', '_').toLowerCase(Locale.ROOT), entry.getValue());
        }
        return out;
    }

    /** A resource location with CraftEngine's default namespace for one, {@code minecraft}. */
    static String location(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.endsWith(".png")) trimmed = trimmed.substring(0, trimmed.length() - 4);
        if (trimmed.endsWith(".json")) trimmed = trimmed.substring(0, trimmed.length() - 5);
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }

    /** {@code x,y,z}, a number, or a list of one or three, as three numbers; null when it is none of those. */
    static double[] vector(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return new double[] {n.doubleValue(), n.doubleValue(), n.doubleValue()};
        List<Object> parts = new ArrayList<>();
        if (value instanceof List<?> list) {
            parts.addAll(list);
        } else {
            for (String part : value.toString().replace("_", "").split(",")) parts.add(part.trim());
        }
        double[] out = new double[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            Double parsed = number(parts.get(i));
            if (parsed == null) return null;
            out[i] = parsed;
        }
        if (out.length == 1) return new double[] {out[0], out[0], out[0]};
        return out.length == 3 ? out : null;
    }

    static boolean isZero(double[] vector) {
        return vector == null || (vector[0] == 0 && vector[1] == 0 && vector[2] == 0);
    }

    static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    // ---- the library ------------------------------------------------------------

    /** One definition under an id section, before and after its templates are applied. */
    static final class Entry {
        final String section;
        final String id;
        final Object raw;
        final String origin;
        final String folder;
        Map<String, Object> body;

        Entry(String section, String id, Object raw, String origin, String folder) {
            this.section = section;
            this.id = id;
            this.raw = raw;
            this.origin = origin;
            this.folder = folder;
        }

        String path() {
            return id.substring(id.indexOf(':') + 1);
        }

        String namespace() {
            return id.substring(0, id.indexOf(':'));
        }
    }

    /** What a translated pack hands back to the loader. */
    record Output(ContentKind kind, String path, Map<String, Object> body, String origin) {
    }

    /** One pack's CraftEngine content, gathered before anything is translated. */
    static final class Pack {
        final String folder;
        final String namespace;
        final Path directory;
        final List<Entry> entries = new ArrayList<>();
        final Map<String, Set<String>> otherSections = new LinkedHashMap<>();
        final Set<String> locales = new TreeSet<>();

        Pack(String folder, String namespace, Path directory) {
            this.folder = folder;
            this.namespace = namespace;
            this.directory = directory;
        }
    }

    /** Every CraftEngine pack in the content folder, and what they share. */
    static final class Library {
        final int version;
        final CraftEngineTemplates templates = new CraftEngineTemplates();
        final Map<String, String> lang = new LinkedHashMap<>();
        final Map<String, String> translations = new LinkedHashMap<>();
        final Map<String, Pack> packs = new LinkedHashMap<>();
        /** A CraftEngine id (items, blocks, furniture, images, sounds) to the RP Engine id it became. */
        final Map<String, String> index = new LinkedHashMap<>();
        final Map<String, Entry> images = new LinkedHashMap<>();
        final Map<String, Entry> equipments = new LinkedHashMap<>();
        final Map<String, Entry> blocks = new LinkedHashMap<>();
        final Map<String, Entry> furniture = new LinkedHashMap<>();
        /** Models CraftEngine would generate, by the path it writes them to; see CraftEngineItems. */
        final Map<String, Map<String, Object>> generated = new LinkedHashMap<>();
        private final Map<String, Integer> langRank = new LinkedHashMap<>();
        private final Map<String, Integer> translationRank = new LinkedHashMap<>();

        Library(McVersion version) {
            this.version = CraftEngineYaml.number(version);
        }

        boolean isEmpty() {
            return packs.isEmpty();
        }

        Pack pack(String folder, String namespace, Path directory) {
            return packs.computeIfAbsent(folder, key -> new Pack(folder, namespace, directory));
        }

        /** Resolves version keys in a document, as CraftEngine's reader does while parsing. */
        Map<String, Object> resolve(Map<?, ?> document) {
            Object resolved = CraftEngineYaml.resolve(document, version);
            return resolved instanceof Map<?, ?> map ? CraftEngineYaml.cast(map) : new LinkedHashMap<>();
        }

        /** Adds one file's sections to {@code pack}. */
        void add(Pack pack, String origin, Map<String, Object> document) {
            for (Map.Entry<String, Object> top : document.entrySet()) {
                String section = section(top.getKey());
                Map<String, Object> body = map(top.getValue());
                if (section == null) {
                    if (!top.getKey().startsWith("$$")) {
                        pack.otherSections.computeIfAbsent("unknown section " + top.getKey(), k -> new TreeSet<>())
                                .add(origin);
                    }
                    continue;
                }
                if (body == null) continue;
                switch (section) {
                    case "lang":
                        for (Map.Entry<String, Object> locale : body.entrySet()) {
                            pack.locales.add(locale.getKey());
                            int rank = rank(locale.getKey());
                            if (rank > 0) flatten("", map(locale.getValue()), lang, langRank, rank);
                        }
                        break;
                    case "translations":
                        for (Map.Entry<String, Object> locale : body.entrySet()) {
                            pack.locales.add(locale.getKey());
                            int rank = rank(locale.getKey());
                            if (rank > 0) flatten("", map(locale.getValue()), translations, translationRank, rank);
                        }
                        break;
                    default:
                        if (FREE_FORM.contains(section)) {
                            pack.otherSections.computeIfAbsent(section, k -> new TreeSet<>()).add(origin);
                            break;
                        }
                        for (Map.Entry<String, Object> entry : body.entrySet()) {
                            String key = entry.getKey();
                            if (key.startsWith("$$")) continue;
                            String id = key.indexOf(':') >= 0 ? key : pack.namespace + ":" + key;
                            if (section.equals("templates")) {
                                templates.define(id, entry.getValue());
                            } else {
                                pack.entries.add(new Entry(section, id, entry.getValue(), origin, pack.folder));
                            }
                        }
                }
            }
        }

        /** How good an English source a locale is: en_us beats en beats all; 0 is not English. */
        private static int rank(String locale) {
            String name = locale.toLowerCase(Locale.ROOT).replace('-', '_');
            if (name.equals("en_us")) return 3;
            if (name.equals("en")) return 2;
            if (name.equals("all")) return 1;
            return name.startsWith("en_") ? 1 : 0;
        }

        private static void flatten(String prefix, Map<String, Object> values, Map<String, String> into,
                                    Map<String, Integer> ranks, int rank) {
            if (values == null) return;
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> nested) {
                    flatten(key, CraftEngineYaml.cast(nested), into, ranks, rank);
                } else if (entry.getValue() != null && ranks.getOrDefault(key, 0) <= rank) {
                    into.put(key, entry.getValue().toString());
                    ranks.put(key, rank);
                }
            }
        }

        /** The English text for a language key, from {@code lang:} first and {@code translations:} after. */
        String english(String key) {
            String value = lang.get(key);
            return value != null ? value : translations.get(key);
        }

        /**
         * Applies templates to every definition, then works out which RP
         * Engine id each one becomes. A definition whose template cannot be
         * found is skipped and named.
         */
        void prepare(List<Diagnostic> diagnostics) {
            for (Pack pack : packs.values()) {
                List<Entry> kept = new ArrayList<>();
                for (Entry entry : pack.entries) {
                    try {
                        Object expanded = templates.expand(entry.raw, entry.namespace(), entry.path());
                        Map<String, Object> body = map(expanded);
                        if (body == null) {
                            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                                    "is not a map once its templates are applied, so it was skipped."));
                            continue;
                        }
                        if (body.containsKey("enable") && !truthy(body.get("enable"))) {
                            continue;
                        }
                        entry.body = body;
                        kept.add(entry);
                    } catch (CraftEngineTemplates.Failure failure) {
                        diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                                failure.getMessage() + ", so it was skipped."
                                        + (failure.getMessage().contains("template default:")
                                        ? " CraftEngine's default: templates ship in its default_templates pack;"
                                        + " copy that folder into the content folder beside this one."
                                        : "")));
                    }
                }
                pack.entries.clear();
                pack.entries.addAll(kept);
            }
            for (Pack pack : packs.values()) {
                for (Entry entry : pack.entries) {
                    String ours = pack.folder + ":" + entry.path();
                    switch (entry.section) {
                        case "items":
                        case "blocks":
                        case "furniture":
                        case "images":
                        case "sounds":
                            index.putIfAbsent(entry.id, ours);
                            break;
                        default:
                    }
                    if (entry.section.equals("items") || entry.section.equals("blocks")) {
                        CraftEngineItems.collectGenerated(entry, generated);
                    }
                    switch (entry.section) {
                        case "images":
                            images.putIfAbsent(entry.id, entry);
                            break;
                        case "equipments":
                            equipments.putIfAbsent(entry.id, entry);
                            break;
                        case "blocks":
                            blocks.putIfAbsent(entry.id, entry);
                            break;
                        case "furniture":
                            furniture.putIfAbsent(entry.id, entry);
                            break;
                        default:
                    }
                }
            }
        }

        /**
         * A reference to a CraftEngine id, as RP Engine writes it: a pack's
         * own item as its RP Engine id, a vanilla one as its material.
         *
         * @param defaultNamespace what a bare id means here; CraftEngine reads
         *                         most references as {@code minecraft:}
         * @return the reference, or null for an id no loaded pack defines and
         *         the game does not have
         */
        String reference(String raw, String defaultNamespace) {
            if (raw == null) return null;
            String id = raw.trim();
            if (id.indexOf(':') < 0) id = defaultNamespace + ":" + id;
            String ours = index.get(id);
            if (ours != null) return ours;
            if (id.startsWith("minecraft:")) return id.substring("minecraft:".length()).toUpperCase(Locale.ROOT);
            return null;
        }

        /** The RP Engine id of a pack's own definition, which is the folder and the CraftEngine path. */
        String ours(Entry entry) {
            return entry.folder + ":" + entry.path();
        }

        /** Translates one pack; see the per-kind classes. */
        List<Output> translate(String folder, List<Diagnostic> diagnostics) {
            Pack pack = packs.get(folder);
            if (pack == null) return List.of();
            List<Output> out = new ArrayList<>();
            namespaces(pack, diagnostics);
            CraftEngineItems.translate(this, pack, out, diagnostics);
            CraftEngineAssets.translate(this, pack, out, diagnostics);
            others(pack, diagnostics);
            return out;
        }

        /**
         * Says once per namespace when ids are moving: RP Engine's namespace
         * is the folder, and CraftEngine's is whatever the id says.
         */
        private void namespaces(Pack pack, List<Diagnostic> diagnostics) {
            Map<String, String> firstSeen = new LinkedHashMap<>();
            for (Entry entry : pack.entries) {
                if (Set.of("items", "blocks", "furniture", "images", "sounds", "emoji", "recipes")
                        .contains(entry.section) && !entry.namespace().equals(pack.folder)) {
                    firstSeen.putIfAbsent(entry.namespace(), entry.origin);
                }
            }
            for (Map.Entry<String, String> moved : firstSeen.entrySet()) {
                String namespace = moved.getKey();
                diagnostics.add(Diagnostic.warning(moved.getValue(),
                        "Ids in " + namespace + " are loaded as " + pack.folder + ":..., because the folder is "
                                + "the namespace in RP Engine. " + (ContentId.isValidNamespace(namespace)
                                && !namespace.equals("minecraft")
                                ? "Rename the folder to " + namespace + " to keep ids like " + namespace
                                + ":something working in commands and other plugins."
                                : "Give ids in this pack the folder's namespace.")));
            }
        }

        /** The sections nothing here reads, one warning each. */
        private void others(Pack pack, List<Diagnostic> diagnostics) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, String> origins = new LinkedHashMap<>();
            for (Entry entry : pack.entries) {
                switch (entry.section) {
                    case "items":
                    case "blocks":
                    case "furniture":
                    case "images":
                    case "emoji":
                    case "recipes":
                    case "sounds":
                    case "equipments":
                        break;
                    default:
                        counts.merge(entry.section, 1, Integer::sum);
                        origins.putIfAbsent(entry.section, entry.origin);
                }
            }
            for (Map.Entry<String, Integer> section : counts.entrySet()) {
                diagnostics.add(Diagnostic.warning(origins.get(section.getKey()),
                        section.getValue() + " " + section.getKey() + " entr" + (section.getValue() == 1 ? "y" : "ies")
                                + " skipped: " + why(section.getKey())));
            }
            for (Map.Entry<String, Set<String>> section : pack.otherSections.entrySet()) {
                diagnostics.add(Diagnostic.warning(section.getValue().iterator().next(),
                        section.getKey() + " skipped: " + why(section.getKey())));
            }
            Set<String> foreign = new LinkedHashSet<>();
            for (String locale : pack.locales) {
                if (rank(locale) == 0) foreign.add(locale);
            }
            if (!foreign.isEmpty()) {
                diagnostics.add(Diagnostic.warning(pack.folder,
                        "Language entries for " + String.join(", ", foreign) + " were not kept: names and lore "
                                + "come across in English, from the en_us entries, written into the item itself."));
            }
        }

        private static String why(String section) {
            switch (section) {
                case "categories":
                    return "categories are CraftEngine's item browser, which RP Engine does not have; "
                            + "/rp give and tab completion list every item.";
                case "jukebox_songs":
                    return "a music disc is a whole game rather than an item property here; ItemUseEvent "
                            + "is what to build one on.";
                case "loot":
                case "loot_sources":
                    return "standalone loot tables and changes to vanilla drops have no RP Engine equivalent.";
                case "entities":
                    // Read from its EntityParser: an entry is keyed by an
                    // existing entity type and holds attributes and tags.
                    return "a CraftEngine entity entry gives an existing mob type (vanilla, or another plugin's) "
                            + "attribute values and tags for CraftEngine's damage rules. It has no model, name or "
                            + "spawn, so there is no RP Engine entity in it; to put a model on mobs that exist, "
                            + "bind one with /rp bind or Models.bind.";
                case "paintings":
                case "advancements":
                case "configured_features":
                case "placed_features":
                case "atlases":
                case "attributes":
                case "attribute_operations":
                case "equipment_sets":
                case "damage_rules":
                case "global_variables":
                case "config_factory":
                case "block_state_mappings":
                case "skip_optimization":
                    return "it has no RP Engine equivalent.";
                default:
                    return "not a CraftEngine section RP Engine reads.";
            }
        }
    }
}
