package ai.resourcepack.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said a sound is.
 *
 * <p>A custom sound is a real Minecraft sound event: the pack ships an Ogg
 * Vorbis file and a {@code sounds.json} that names it, and the server plays it
 * by id like any vanilla sound. Nothing is streamed or pushed at play time,
 * which is why a sound works for a player holding the pack and does nothing at
 * all for one who declined it.
 */
public final class SoundInfo {

    /**
     * Which volume slider a sound answers to.
     *
     * <p>Not an enum of ours: these are Minecraft's own categories and the
     * names go verbatim into {@code sounds.json}. Getting one wrong means a
     * player who turned music down still hears it, which is the sort of thing
     * that gets a server muted rather than reported.
     */
    public static final java.util.Set<String> CATEGORIES = java.util.Set.of(
            "master", "music", "record", "weather", "block",
            "hostile", "neutral", "player", "ambient", "voice");

    private final ContentId id;
    private final String file;
    private final String category;
    private final String subtitle;
    private final float volume;
    private final float pitch;
    private final boolean stream;

    private SoundInfo(ContentId id, String file, String category, String subtitle,
                      float volume, float pitch, boolean stream) {
        this.id = id;
        this.file = file;
        this.category = category;
        this.subtitle = subtitle;
        this.volume = volume;
        this.pitch = pitch;
        this.stream = stream;
    }

    /** Engine internal; built by the sound loader. */
    public static SoundInfo of(ContentId id, String file, String category, String subtitle,
                               float volume, float pitch, boolean stream) {
        return new SoundInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(file, "file"),
                category == null || category.isEmpty() ? "master" : category,
                subtitle == null ? "" : subtitle,
                volume, pitch, stream);
    }

    /** Its id, which is also the sound event name the server plays. */
    public ContentId id() {
        return id;
    }

    /**
     * The audio file within the pack's {@code assets/sounds/}, without the
     * extension.
     */
    public String file() {
        return file;
    }

    /** Which volume slider it answers to. */
    public String category() {
        return category;
    }

    /**
     * The subtitle shown to players with subtitles on, if the pack wrote one.
     *
     * <p>Worth writing. A sound with no subtitle is silent to anybody playing
     * with subtitles instead of audio, which is more people than most server
     * owners expect.
     */
    public Optional<String> subtitle() {
        return subtitle.isEmpty() ? Optional.empty() : Optional.of(subtitle);
    }

    /** Default volume when nobody says otherwise. */
    public float volume() {
        return volume;
    }

    /** Default pitch when nobody says otherwise. */
    public float pitch() {
        return pitch;
    }

    /**
     * Whether the client streams it rather than loading it whole.
     *
     * <p>For anything long. A file loaded whole holds its decompressed audio
     * in memory for the session, so a five-minute track that is not streamed
     * is a real cost paid by every player who hears it once.
     */
    public boolean stream() {
        return stream;
    }

    @Override
    public String toString() {
        return id + " (" + category + ")";
    }
}
