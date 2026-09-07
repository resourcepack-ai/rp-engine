package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emotes written by hand into a content folder's {@code emotes/}.
 *
 * <p><strong>The same JSON a Studio push carries, one emote per key.</strong>
 * A file in {@code emotes/} is a map of emote name to emote — {@code name},
 * {@code length}, {@code loop}, {@code animators} keyed by bone then channel,
 * optionally {@code root}, {@code rootMotion}, {@code triggers} and
 * {@code props} — exactly what {@link EmoteStore} already reads out of
 * {@code emotes.json}. There is no second format to learn and no translation
 * that can drift: Studio's emote editor exports the entry, a server owner
 * pastes it, and the director plays it as it plays a pushed one. YAML works
 * too, being a superset of JSON, for anybody who prefers to write it.
 *
 * <p><strong>Emote names are server-wide, like a pushed pack's.</strong> The
 * director, {@code /emote} and a vehicle seat all name an emote by its bare
 * name, so two packs defining {@code wave} is a collision rather than two
 * emotes, and is reported as one. The registry still holds
 * {@code <namespace>:<name>} so {@code /rp info} can say where an emote came
 * from.
 *
 * <p>Pure over the {@link LoadReport}: the store is told the result by whoever
 * ran the load, under a pack id of {@code content:<namespace>} so a reload
 * replaces a folder's emotes whole and a folder that went away takes its
 * emotes with it.
 */
public final class AuthoredEmotes {

    /** The pack id prefix every folder's emotes are held under in the store. */
    public static final String PACK_PREFIX = "content:";

    private static final Gson GSON = new Gson();

    private AuthoredEmotes() {
    }

    public static Result parse(LoadReport loaded) {
        Map<String, Map<String, EmoteStore.Emote>> byNamespace = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (ContentDefinition definition : loaded.definitions(ContentKind.EMOTE)) {
            String namespace = definition.id().namespace();
            String name = definition.id().path();
            String origin = definition.origin();

            String previous = owner.get(name);
            if (previous != null && !previous.equals(namespace)) {
                diagnostics.add(Diagnostic.error(origin, name,
                        "An emote called \"" + name + "\" is already defined by " + previous
                                + ". Emote names are shared across every pack on the server, because /emote "
                                + "and a vehicle seat name one without a namespace - rename one of them."));
                continue;
            }

            EmoteStore.Emote emote;
            try {
                emote = GSON.fromJson(GSON.toJson(rawMap(definition.body())), EmoteStore.Emote.class);
            } catch (JsonSyntaxException | IllegalStateException e) {
                diagnostics.add(Diagnostic.error(origin, name,
                        "Not an emote. " + firstLine(e.getMessage())));
                continue;
            }
            if (emote == null) {
                diagnostics.add(Diagnostic.error(origin, name, "Not an emote: empty."));
                continue;
            }
            if (emote.name == null || emote.name.isEmpty()) {
                emote.name = name;
            }
            if (!(emote.length > 0)) {
                diagnostics.add(Diagnostic.error(origin, name,
                        "length must be above zero: how many seconds the emote runs."));
                continue;
            }
            boolean moves = (emote.animators != null && !emote.animators.isEmpty())
                    || (emote.root != null && !emote.root.isEmpty())
                    || (emote.props != null && !emote.props.isEmpty())
                    || (emote.performers != null && !emote.performers.isEmpty());
            if (!moves) {
                diagnostics.add(Diagnostic.error(origin, name,
                        "Moves nothing: it needs animators (bone -> channel -> keyframes), a root, props or performers."));
                continue;
            }
            for (String bone : emote.animators == null ? Collections.<String>emptySet() : emote.animators.keySet()) {
                if (!RigGeometry.ALL_BONES.contains(bone)) {
                    diagnostics.add(Diagnostic.warning(origin, name,
                            "Names a bone \"" + bone + "\" the rig does not have, so that animator plays nothing. Bones: "
                                    + RigGeometry.ALL_BONES));
                }
            }

            owner.put(name, namespace);
            byNamespace.computeIfAbsent(namespace, k -> new LinkedHashMap<>()).put(name, emote);
        }
        return new Result(byNamespace, diagnostics);
    }

    /** A definition body back into the plain map Gson can read an emote out of. */
    private static Map<String, Object> rawMap(DefinitionNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : node.keys()) {
            map.put(key, node.raw(key));
        }
        return map;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /** What the folders held, per namespace, and what was wrong with it. */
    public static final class Result {
        private final Map<String, Map<String, EmoteStore.Emote>> byNamespace;
        private final List<Diagnostic> diagnostics;

        Result(Map<String, Map<String, EmoteStore.Emote>> byNamespace, List<Diagnostic> diagnostics) {
            this.byNamespace = Collections.unmodifiableMap(byNamespace);
            this.diagnostics = Collections.unmodifiableList(diagnostics);
        }

        public Map<String, Map<String, EmoteStore.Emote>> byNamespace() {
            return byNamespace;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        public int count() {
            int total = 0;
            for (Map<String, EmoteStore.Emote> emotes : byNamespace.values()) {
                total += emotes.size();
            }
            return total;
        }
    }
}
