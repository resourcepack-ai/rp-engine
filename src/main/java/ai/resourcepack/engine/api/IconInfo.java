package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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
 *
 * <p><strong>An icon can be animated, and the server is what animates
 * it.</strong> Every frame is a glyph of its own, at consecutive codepoints from
 * {@link #codepoint()}, and anything that redraws text over time asks which
 * frame is showing now ({@link #frameAt(long)}, or
 * {@link Icons#characterNow}). There is no shader in it. The other plugins
 * animate a glyph by replacing the game's text shader, a pack can carry only
 * one of those, and Studio's packs already carry theirs for their own
 * overlays — so whichever pack was on top would silently break the other. The
 * price is that text sent once (a chat line, an item name, a sign) shows the
 * first frame and stays there.
 */
public final class IconInfo {

    /** Frames a second when a pack does not say. */
    public static final int DEFAULT_FPS = 10;

    /**
     * The fastest an icon can change, which is once a tick.
     *
     * <p>Nothing the server sends is redrawn faster than that, so a higher rate
     * would only be frames that are never shown — and the ones that are shown
     * would come at uneven intervals, which reads as a stutter.
     */
    public static final int MAX_FPS = 20;

    /**
     * The most frames one icon may have.
     *
     * <p>Every frame is a codepoint out of the 6,400 the Private Use Area holds
     * for icons, screens and HUDs together, so one long GIF could otherwise eat
     * a real slice of the whole number line.
     */
    public static final int MAX_FRAMES = 64;

    private final ContentId id;
    private final String file;
    private final int height;
    private final int ascent;
    private final int codepoint;
    private final int rows;
    private final int columns;
    private final int cell;
    private final int frames;
    private final int fps;
    private final boolean loops;
    private final List<String> aliases;
    private final String permission;

    private IconInfo(ContentId id, String file, int height, int ascent, int codepoint,
                     int rows, int columns, int cell, int frames, int fps, boolean loops,
                     List<String> aliases, String permission) {
        this.id = id;
        this.file = file;
        this.height = height;
        this.ascent = ascent;
        this.codepoint = codepoint;
        this.rows = rows;
        this.columns = columns;
        this.cell = cell;
        this.frames = frames;
        this.fps = fps;
        this.loops = loops;
        this.aliases = aliases;
        this.permission = permission;
    }

    /** Engine internal; built by the icon loader. */
    public static IconInfo of(ContentId id, String file, int height, int ascent, int codepoint) {
        return new IconInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(file, "file"),
                height, ascent, codepoint, 1, 1, 1, 1, DEFAULT_FPS, true, List.of(), null);
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
                Math.max(1, rows), Math.max(1, columns), Math.max(1, cell),
                frames, fps, loops, aliases, permission);
    }

    /**
     * The same icon, played as {@code frames} pictures one after another.
     *
     * <p>Engine internal. Frame {@code k} (from 0) is cell {@link #cell()}
     * {@code + k} of the sheet and is drawn by codepoint {@link #codepoint()}
     * {@code + k}. A strip of frames stacked top to bottom is therefore a sheet
     * of {@code frames} rows and one column, and the game's own grid cuts it
     * exactly as it cuts any other sheet, with nothing cropped. Clamped to 1 to
     * {@link #MAX_FRAMES} and 1 to {@link #MAX_FPS}; the loader has already
     * warned about anything outside them.
     */
    public IconInfo withAnimation(int frames, int fps, boolean loops) {
        return new IconInfo(id, file, height, ascent, codepoint, rows, columns, cell,
                Math.max(1, Math.min(MAX_FRAMES, frames)),
                Math.max(1, Math.min(MAX_FPS, fps)), loops, aliases, permission);
    }

    /**
     * The same icon, with the words that type it in chat and who may type it.
     *
     * <p>Engine internal. Neither changes what the icon looks like or where it
     * sits in the font; chat reads them and nothing else does.
     */
    public IconInfo withChat(List<String> aliases, String permission) {
        return new IconInfo(id, file, height, ascent, codepoint, rows, columns, cell,
                frames, fps, loops,
                aliases == null ? List.of() : List.copyOf(aliases),
                permission == null || permission.isBlank() ? null : permission);
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /**
     * The picture, under the pack's {@code assets/textures/font/}, or a
     * resource location under {@code textures/} when it has a namespace.
     * Without the extension for a PNG. A GIF keeps its {@code .gif}, and what
     * the font actually draws is the strip of frames the build writes beside
     * it, at {@code <file>.png}.
     */
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
     * The codepoint it was assigned, in the Private Use Area. For an animated
     * icon, the codepoint of its first frame.
     *
     * <p><strong>This is not stable across content changes.</strong> Codepoints
     * are handed out in id order, so adding an icon whose id sorts earlier
     * shifts every icon after it — and so does giving an earlier icon more
     * frames. That is fine for anything the server renders — it resolves the
     * id at the moment it writes the text — and wrong for anything that stored
     * the character itself. A glyph written into a sign, a book or an item name
     * persists as the character and will be a different picture after the next
     * reload.
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

    /** Which cell is this icon (its first frame), from 1, left to right and then top to bottom. */
    public int cell() {
        return cell;
    }

    /** How many frames it has. 1 for a still picture, which is most of them. */
    public int frames() {
        return frames;
    }

    /** Whether it has more than one frame. */
    public boolean animated() {
        return frames > 1;
    }

    /** Frames a second, 1 to {@link #MAX_FPS}. Meaningless for a still picture. */
    public int fps() {
        return fps;
    }

    /**
     * Whether it starts again after its last frame. One that does not stops on
     * the last frame, timed from when the server started — so it plays once,
     * shortly after a restart, and is its last frame from then on.
     */
    public boolean loops() {
        return loops;
    }

    /**
     * The words that type it in chat besides {@code :id:}, such as {@code <3}.
     * Each is matched only as a whole word, never inside one.
     */
    public List<String> aliases() {
        return aliases;
    }

    /**
     * The permission needed to type it in chat, by an alias or by its id, or
     * empty when anybody who may use chat icons at all may use this one.
     */
    public Optional<String> permission() {
        return Optional.ofNullable(permission);
    }

    /**
     * Which frame, from 0, is showing {@code elapsedMillis} after the animation
     * began.
     *
     * <p>Pure arithmetic, so it is the same answer on every machine and in every
     * test: the frame moves on every {@code 1000 / fps} milliseconds, wraps when
     * the icon loops and holds on the last frame when it does not. Always 0 for
     * a still picture, and for any time before the start.
     */
    public int frameAt(long elapsedMillis) {
        if (frames <= 1 || elapsedMillis <= 0) {
            return 0;
        }
        long shown = elapsedMillis * fps / 1000L;
        return loops ? (int) (shown % frames) : (int) Math.min(shown, frames - 1L);
    }

    /** The codepoint of one frame, from 0. Out of range is clamped to the first or the last. */
    public int codepoint(int frame) {
        return codepoint + Math.max(0, Math.min(frames - 1, frame));
    }

    /**
     * The character itself, for putting into a piece of text right now.
     *
     * <p>Always the FIRST frame of an animated icon, which is right for anything
     * drawn once. Something that redraws over time wants
     * {@link Icons#characterNow}, or {@link #character(int)} with
     * {@link #frameAt(long)}.
     */
    public String character() {
        return new String(Character.toChars(codepoint));
    }

    /** The character of one frame, from 0. Out of range is clamped. */
    public String character(int frame) {
        return new String(Character.toChars(codepoint(frame)));
    }

    @Override
    public String toString() {
        return id + " (U+" + Integer.toHexString(codepoint).toUpperCase(Locale.ROOT)
                + (frames > 1 ? ", " + frames + " frames at " + fps + " fps" : "") + ")";
    }
}
