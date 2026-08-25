package ai.resourcepack.engine.api;

import java.util.Objects;

/**
 * A full-size picture drawn over the game: a GUI background or a HUD overlay.
 *
 * <p>Both are the same trick. The picture is declared as one enormous glyph in
 * the font, and drawing it means putting that character into a piece of text
 * the game already renders — a container's title for a GUI, the action bar or a
 * boss bar for a HUD. Some negative space in front of it slides it into
 * position, because text starts where text starts and art needs to be
 * somewhere else.
 *
 * <p>This is the thing `the previous engine` records as a known gap: a pack
 * ships no manifest of its screens, so that engine only ever learns escape
 * sequences at the moment somebody pastes one into a command. Here the build
 * makes the pack, so the ids are known and
 * {@code open(player, "mypack:shop")} is possible at all.
 */
public final class OverlayInfo {

    /** Where a HUD overlay is drawn. Ignored for a screen. */
    public enum Slot {

        /** Above the hotbar. Fades on its own, so it is redrawn while shown. */
        ACTION_BAR,

        /** The boss bar at the top of the screen. Stays until cleared. */
        BOSS_BAR
    }

    private final ContentId id;
    private final String file;
    private final String container;
    private final Slot slot;
    private final int height;
    private final int ascent;
    private final int offset;
    private final int codepoint;

    private OverlayInfo(ContentId id, String file, String container, Slot slot,
                        int height, int ascent, int offset, int codepoint) {
        this.id = id;
        this.file = file;
        this.container = container;
        this.slot = slot;
        this.height = height;
        this.ascent = ascent;
        this.offset = offset;
        this.codepoint = codepoint;
    }

    /** Engine internal; built by the screen or HUD loader. */
    public static OverlayInfo of(ContentId id, String file, String container, Slot slot,
                                 int height, int ascent, int offset, int codepoint) {
        return new OverlayInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(file, "file"),
                container == null ? "" : container,
                slot == null ? Slot.ACTION_BAR : slot,
                height, ascent, offset, codepoint);
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /** The PNG within the pack's {@code assets/textures/gui/}, without the extension. */
    public String file() {
        return file;
    }

    /**
     * Which vanilla container a screen opens as, empty for a HUD.
     *
     * <p>A custom GUI is a real container wearing a picture, so it has to be a
     * container that exists: the row count and the slots are vanilla's, and
     * only the backdrop is ours.
     */
    public String container() {
        return container;
    }

    /** Where a HUD overlay is drawn. */
    public Slot slot() {
        return slot;
    }

    /** How tall the picture is drawn, in pixels. */
    public int height() {
        return height;
    }

    /** How far above the text baseline it sits. */
    public int ascent() {
        return ascent;
    }

    /**
     * How far left the picture is nudged, in pixels.
     *
     * <p>Negative space in front of the glyph. Without it a GUI background
     * starts where the title text starts, which is not where the window is.
     */
    public int offset() {
        return offset;
    }

    /** The codepoint it was assigned. See {@link IconInfo#codepoint()}. */
    public int codepoint() {
        return codepoint;
    }

    /** The character itself. */
    public String character() {
        return new String(Character.toChars(codepoint));
    }

    @Override
    public String toString() {
        return id + (container.isEmpty() ? " (" + slot + ")" : " (" + container + ")");
    }
}
