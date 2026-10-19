package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * What a VFX is to a plugin: an id, a name and how long it runs. The effect
 * itself is a timeline the engine plays; see {@link Vfx}.
 */
public final class VfxInfo {

    /** Where an effect comes from. */
    public enum Source {
        /** A content folder's {@code vfx/}, whose sprites this server's own pack carries. */
        CONTENT,
        /** A Studio pack, whose zip carries its sprites. */
        STUDIO
    }

    private final ContentId id;
    private final String name;
    private final int durationTicks;
    private final Source source;
    private final String preset;

    public VfxInfo(ContentId id, String name, int durationTicks, Source source, String preset) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null || name.isBlank() ? id.path() : name;
        this.durationTicks = durationTicks;
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

    /** How long the timeline runs, in ticks. A mote started near the end can outlive it by its own life. */
    public int durationTicks() {
        return durationTicks;
    }

    public Source source() {
        return source;
    }

    /** The ready-made effect it is, if it is one ({@code frost_nova}). */
    public Optional<String> preset() {
        return Optional.ofNullable(preset);
    }

    @Override
    public String toString() {
        return id + " (" + durationTicks + " ticks)";
    }
}
