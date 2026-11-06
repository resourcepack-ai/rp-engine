package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * What a transition is to a plugin: an id, a name and how long each
 * half runs. The transition itself is drawn by the pack's text shader; the engine
 * only times it. See {@link Transitions}.
 */
public final class TransitionInfo {

    private final ContentId id;
    private final String name;
    private final int inTicks;
    private final int holdTicks;
    private final int outTicks;
    private final String soundIn;
    private final String soundOut;

    public TransitionInfo(ContentId id, String name, int inTicks, int holdTicks, int outTicks,
                       String soundIn, String soundOut) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null || name.isBlank() ? id.path() : name;
        this.inTicks = Math.max(0, inTicks);
        this.holdTicks = Math.max(0, holdTicks);
        this.outTicks = Math.max(0, outTicks);
        this.soundIn = soundIn == null || soundIn.isBlank() ? null : soundIn;
        this.soundOut = soundOut == null || soundOut.isBlank() ? null : soundOut;
    }

    public ContentId id() {
        return id;
    }

    /** What its author called it. */
    public String name() {
        return name;
    }

    /** Ticks the cover takes to come in. */
    public int inTicks() {
        return inTicks;
    }

    /** Ticks it holds when {@link Transitions#play} is given no hold of its own. */
    public int holdTicks() {
        return holdTicks;
    }

    /** Ticks it takes to go. */
    public int outTicks() {
        return outTicks;
    }

    /** The sound event played as the cover comes in, if any. */
    public Optional<String> soundIn() {
        return Optional.ofNullable(soundIn);
    }

    /** The sound event played as the cover goes, if any. */
    public Optional<String> soundOut() {
        return Optional.ofNullable(soundOut);
    }

    @Override
    public String toString() {
        return id + " (" + inTicks + "/" + holdTicks + "/" + outTicks + " ticks)";
    }
}
