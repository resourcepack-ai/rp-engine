package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * What a text effect IS to a plugin: a name and a colour.
 *
 * <p>A text effect is a function in the resource pack's text shader that draws
 * any text in one exact colour differently: waving, on fire, full of water.
 * The colour is the whole interface. Anything that can colour text can use
 * one, through RP Engine or not, which is why this hands the colour over
 * rather than hiding it. See {@link TextEffects}.
 */
public final class TextEffectInfo {

    /** Where an effect comes from. */
    public enum Source {
        /** A content folder's {@code text-effects/}, drawn by this server's own pack. */
        CONTENT,
        /** A Studio pack (a push or a distributed pack), whose shader draws it. */
        STUDIO
    }

    private final ContentId id;
    private final String name;
    private final String color;
    private final Source source;
    private final String preset;

    public TextEffectInfo(ContentId id, String name, String color, Source source, String preset) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null || name.isBlank() ? id.path() : name;
        this.color = Objects.requireNonNull(color, "color").toLowerCase(java.util.Locale.ROOT);
        this.source = Objects.requireNonNull(source, "source");
        this.preset = preset == null || preset.isBlank() ? null : preset;
    }

    public ContentId id() {
        return id;
    }

    /** What its author called it. */
    public String name() {
        return name;
    }

    /** The colour, as {@code #rrggbb}. Text in exactly this colour wears the effect. */
    public String color() {
        return color;
    }

    /** The colour as {@code 0xRRGGBB}, for an API that takes a number (Adventure's {@code TextColor.color}). */
    public int rgb() {
        return Integer.parseInt(color.substring(1), 16);
    }

    public Source source() {
        return source;
    }

    /** The ready-made effect it is, if it is one ({@code blaze}, {@code spectrum}). */
    public Optional<String> preset() {
        return Optional.ofNullable(preset);
    }

    @Override
    public String toString() {
        return id + " " + color;
    }
}
