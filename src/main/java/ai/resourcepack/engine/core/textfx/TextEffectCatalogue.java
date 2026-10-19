package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.TextEffectInfo;
import ai.resourcepack.engine.api.TextEffects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Every text effect this server can name, from both sources: a content
 * folder's (replaced on each reload) and every live Studio pack's (asked for
 * each time, since a push lands between reloads). The API's
 * {@link TextEffects}.
 *
 * <p>Also the one place the rest of the engine formats text from a content
 * file through ({@link #formatInstalled}): every name, lore line, message and
 * title goes through it, so {@code <fx:fire>} works wherever {@code &6} does.
 */
public final class TextEffectCatalogue implements TextEffects {

    private static volatile TextEffectCatalogue installed;

    private final Supplier<Map<ContentId, TextEffectInfo>> studio;
    private volatile Map<ContentId, TextEffectInfo> authored = Map.of();

    public TextEffectCatalogue(Supplier<Map<ContentId, TextEffectInfo>> studio) {
        this.studio = studio;
    }

    /** Makes this the catalogue {@link #formatInstalled} reads. */
    public void install() {
        installed = this;
    }

    /** Forgets it again, on disable. */
    public static void uninstall() {
        installed = null;
    }

    /**
     * {@code <fx:...>} tags resolved by the running engine's catalogue, or the
     * text unchanged when there is none (a test, or before enable). Ampersand
     * codes are the caller's.
     */
    public static String formatInstalled(String text) {
        TextEffectCatalogue here = installed;
        return here == null ? (text == null ? "" : text) : here.format(text);
    }

    /** The content folder's effects, after a reload. */
    public void replaceAuthored(Map<ContentId, TextEffectInfo> effects) {
        authored = Map.copyOf(effects);
    }

    /** The content folder's alone. */
    public Map<ContentId, TextEffectInfo> authored() {
        return authored;
    }

    private Map<ContentId, TextEffectInfo> studio() {
        Map<ContentId, TextEffectInfo> got = studio == null ? null : studio.get();
        return got == null ? Map.of() : got;
    }

    @Override
    public List<ContentId> ids() {
        List<ContentId> out = new ArrayList<>(authored.keySet());
        for (ContentId id : studio().keySet()) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        out.sort(Comparator.comparing(ContentId::toString));
        return out;
    }

    @Override
    public Optional<TextEffectInfo> info(ContentId id) {
        if (id == null) {
            return Optional.empty();
        }
        TextEffectInfo own = authored.get(id);
        return own != null ? Optional.of(own) : Optional.ofNullable(studio().get(id));
    }

    @Override
    public Optional<TextEffectInfo> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        if (wanted.contains(":")) {
            return ContentId.parse(wanted).flatMap(this::info);
        }
        Optional<TextEffectInfo> own = byPath(authored, wanted);
        return own.isPresent() ? own : byPath(studio(), wanted);
    }

    private static Optional<TextEffectInfo> byPath(Map<ContentId, TextEffectInfo> effects, String path) {
        return effects.entrySet().stream()
                .filter(e -> e.getKey().path().equals(path))
                .min(Comparator.comparing(e -> e.getKey().toString()))
                .map(Map.Entry::getValue);
    }

    @Override
    public String apply(ContentId id, String text) {
        return info(id).map(effect -> TextEffectTags.apply(effect, text)).orElse(text == null ? "" : text);
    }

    @Override
    public String format(String text) {
        return format(text, effect -> true);
    }

    /** As {@link #format(String)}, with {@code allowed} deciding effect by effect, as chat's permissions do. */
    public String format(String text, Predicate<TextEffectInfo> allowed) {
        return TextEffectTags.format(text, this::find, allowed);
    }
}
