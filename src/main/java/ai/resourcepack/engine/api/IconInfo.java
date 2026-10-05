package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Objects;

/**
 * What a content pack said an icon is, and which character it came out as.
 *
 * <p>An icon is a picture that behaves like a letter. The pack ships a PNG, the
 * build declares it as a glyph in the default font at some codepoint, and from
 * then on putting that character in any piece of text draws the picture — a
 * chat message, an item name, a sign, a scoreboard, anywhere the game renders
 * text at all.
 *
 * <p><strong>Never store the character.</strong> Store the id and resolve it.
 * See {@link #codepoint()}.
 */
public final class IconInfo {

    private final ContentId id;
    private final String file;
    private final int height;
    private final int ascent;
    private final int codepoint;
    private final int rows;
    private final int columns;
    private final int cell;

    private IconInfo(ContentId id, String file, int height, int ascent, int codepoint,
                     int rows, int columns, int cell) {
        this.id = id;
        this.file = file;
        this.height = height;
        this.ascent = ascent;
        this.codepoint = codepoint;
        this.rows = rows;
        this.columns = columns;
        this.cell = cell;
    }

    /** Engine internal; built by the icon loader. */
    public static IconInfo of(ContentId id, String file, int height, int ascent, int codepoint) {
        return new IconInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(file, "file"),
                height, ascent, codepoint, 1, 1, 1);
    }

    /**
     * The same icon, drawn from one cell of a sheet rather than the whole PNG.
     *
     * <p>Engine internal. A sheet is split into equal cells, {@code rows} by
     * {@code columns}, numbered from 1 left to right and then top to bottom.
     * Nothing is cropped: the game's own bitmap font provider already splits
     * an image into a grid of characters, so the icon is that grid with every
     * cell but its own left empty.
     */
    public IconInfo withCell(int rows, int columns, int cell) {
        return new IconInfo(id, file, height, ascent, codepoint,
                Math.max(1, rows), Math.max(1, columns), Math.max(1, cell));
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /** The PNG within the pack's {@code assets/fonts/}, without the extension. */
    public String file() {
        return file;
    }

    /** How tall it is drawn, in the same units as a line of text (8 is a capital letter). */
    public int height() {
        return height;
    }

    /** How far above the text baseline it sits. */
    public int ascent() {
        return ascent;
    }

    /**
     * The codepoint it was assigned, in the Private Use Area.
     *
     * <p><strong>This is not stable across content changes.</strong> Codepoints
     * are handed out in id order, so adding an icon whose id sorts earlier
     * shifts every icon after it. That is fine for anything the server renders
     * — it resolves the id at the moment it writes the text — and wrong for
     * anything that stored the character itself. A glyph written into a sign,
     * a book or an item name persists as the character and will be a different
     * picture after the next reload.
     *
     * <p>The rule is the same one the whole engine runs on: <em>the id is the
     * reference</em>. Allocating stable codepoints instead would mean a file
     * mapping id to number that must never be lost or reordered, which is
     * exactly the class of problem the item scheme was designed to delete.
     */
    public int codepoint() {
        return codepoint;
    }

    /** How many rows of cells the PNG is split into. 1 for a picture that is the whole file. */
    public int rows() {
        return rows;
    }

    /** How many columns of cells the PNG is split into. 1 for a picture that is the whole file. */
    public int columns() {
        return columns;
    }

    /** Which cell is this icon, from 1, left to right and then top to bottom. */
    public int cell() {
        return cell;
    }

    /** The character itself, for putting into a piece of text right now. */
    public String character() {
        return new String(Character.toChars(codepoint));
    }

    @Override
    public String toString() {
        return id + " (U+" + Integer.toHexString(codepoint).toUpperCase(Locale.ROOT) + ")";
    }
}
