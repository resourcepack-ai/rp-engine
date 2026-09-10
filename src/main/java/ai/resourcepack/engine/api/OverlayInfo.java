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
 * <p>This is the thing the previous engine recorded as a known gap: a pack
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
    private final String title;
    private final String container;
    private final Slot slot;
    private final int height;
    private final int ascent;
    private final int offset;
    private final int codepoint;
    private final String color;
    private final String font;
    private final String text;
    private final java.util.List<OverlayTrigger> triggers;
    private final java.util.List<OverlayRun> runs;
    private final int advance;
    private final String shiftPlus;
    private final String shiftMinus;
    private final boolean pushed;

    /**
     * One positioned run of text within an overlay.
     *
     * <p>Everything here was worked out by the pack build, because all of it
     * depends on codepoints and advances only the pack declares: {@code shift}
     * is space characters that move the cursor to this run's x, and
     * {@code font} is a font whose baseline sits at its y. The engine writes
     * what it is given and computes none of it.
     */
    public static final class OverlayRun {

        private final String shift;
        private final int x;
        private final String text;
        private final String font;
        private final String color;
        private final int advance;
        private final java.util.Map<String, String> players;
        private final Bar bar;

        /**
         * How to build a run whose LENGTH depends on a number.
         *
         * <p>A progress bar, and the reason it is a shape the engine assembles
         * rather than a string the pack ships: the value is not known until a
         * tick before the line is sent, so the pack can only hand over the
         * rectangles and say how long full is.
         *
         * <p>The rectangles are plain white glyphs in powers-of-two widths,
         * tinted by the run's own colour. A bitmap glyph advances by its width
         * plus the pixel the font renderer puts between glyphs, so each one is
         * followed by a single left shift from the overlay's own alphabet —
         * without that a bar is a dotted line.
         */
        public static final class Bar {
            private final java.util.List<Glyph> glyphs;
            private final int total;
            private final String value;
            private final String max;
            private final boolean fill;
            private final int segments;
            private final int gap;
            private final java.util.List<Shade> colors;

            /** One rectangle: the character, and how many pixels it draws. */
            public record Glyph(String character, int px) {
            }

            /**
             * One colour the fill takes at or below a fraction of full.
             *
             * <p><b>{@code color} is a MARK rather than a colour</b>, and that is
             * what makes this possible at all: a run's RGB is the address the
             * pack's vertex shader routes on, and the shader restores an authored
             * colour per address. So a bar that changes colour is a pack that has
             * declared one address per threshold, and this is the engine picking
             * between addresses that already exist. It cannot invent one — a
             * colour with no branch behind it is drawn as the address itself.
             */
            public record Shade(double at, String color) {
            }

            /** A bar of one colour, in one continuous strip. */
            public Bar(java.util.List<Glyph> glyphs, int total, String value, String max, boolean fill) {
                this(glyphs, total, value, max, fill, 0, 0, java.util.List.of());
            }

            /**
             * @param segments how many chunks to break the bar into, or 0 for one
             *                 continuous strip. The fill rounds to whole chunks.
             * @param gap      pixels of nothing after each chunk. Read only when
             *                 {@code segments} is non-zero.
             * @param colors   what colour the fill takes by how full it is, lowest
             *                 threshold first. Empty keeps the run's own colour.
             *                 See {@link Shade}.
             */
            public Bar(java.util.List<Glyph> glyphs, int total, String value, String max, boolean fill,
                       int segments, int gap, java.util.List<Shade> colors) {
                this.glyphs = glyphs == null ? java.util.List.of() : java.util.List.copyOf(glyphs);
                this.total = Math.max(0, total);
                this.value = value == null ? "" : value;
                this.max = max == null ? "" : max;
                this.fill = fill;
                this.segments = Math.max(0, segments);
                this.gap = Math.max(0, gap);
                this.colors = colors == null ? java.util.List.of() : java.util.List.copyOf(colors);
            }

            public java.util.List<Glyph> glyphs() {
                return glyphs;
            }

            /** How many pixels long the bar is when full. */
            public int total() {
                return total;
            }

            /** The placeholder that fills it. A bare name, no braces. */
            public String value() {
                return value;
            }

            /** What full means: a placeholder name, or a plain number. */
            public String max() {
                return max;
            }

            /** Whether this run is the fill rather than the background. */
            public boolean fill() {
                return fill;
            }

            /** How many chunks the bar is broken into, or 0 for one strip. */
            public int segments() {
                return segments;
            }

            /** Pixels after each chunk. Only meaningful when {@link #segments} is set. */
            public int gap() {
                return gap;
            }

            /** What colour the fill takes by how full it is. See {@link Shade}. */
            public java.util.List<Shade> colors() {
                return colors;
            }
        }

        public OverlayRun(String shift, int x, String text, String font, String color) {
            this(shift, x, text, font, color, 0, java.util.Map.of());
        }

        /**
         * A run whose width is stated rather than measured, and which may draw a
         * different character for each player.
         *
         * @param advance how far drawing this run moves the cursor, or 0 to
         *                measure the drawn string. Stated for a glyph the pack
         *                invented, whose width the engine's width table cannot know.
         * @param players the character to draw instead of {@code text}, by
         *                lowercase undashed UUID. Empty for ordinary text.
         */
        public OverlayRun(String shift, int x, String text, String font, String color,
                          int advance, java.util.Map<String, String> players) {
            this(shift, x, text, font, color, advance, players, null);
        }

        public OverlayRun(String shift, int x, String text, String font, String color,
                          int advance, java.util.Map<String, String> players, Bar bar) {
            this.bar = bar;
            this.shift = shift == null ? "" : shift;
            this.x = x;
            this.text = text == null ? "" : text;
            this.font = font == null ? "" : font;
            this.color = color == null ? "" : color;
            this.advance = Math.max(0, advance);
            this.players = players == null ? java.util.Map.of() : java.util.Map.copyOf(players);
        }

        /**
         * How far drawing this run moves the cursor, or 0 to measure it.
         *
         * <p>Zero for words, which the engine measures against vanilla's
         * own glyph widths. Non-zero for a picture drawn as a glyph — a player's
         * head — where there is no table to consult and the pack states the
         * exact figure it declared in the font.
         */
        public int advance() {
            return advance;
        }

        /**
         * What this run draws for one player, or {@link #text()} for anybody
         * not named.
         *
         * <p><b>This is how a head is the viewer's own face.</b> A resource pack
         * cannot read a skin, so a push bakes one glyph per recipient and this
         * map says which is whose. Everyone else — a player who joined after the
         * push, anybody on a pack from an export — draws the default, which is
         * Steve rather than nothing.
         */
        public String textFor(java.util.UUID viewer) {
            if (viewer == null || players.isEmpty()) {
                return text;
            }
            return players.getOrDefault(viewer.toString().toLowerCase(java.util.Locale.ROOT).replace("-", ""), text);
        }

        /** The per-player characters, by lowercase undashed UUID. See {@link #textFor}. */
        public java.util.Map<String, String> players() {
            return players;
        }

        /** How to build this run as a progress bar, or null for ordinary text. */
        public Bar bar() {
            return bar;
        }

        /**
         * Space characters moving the cursor to this run's x.
         *
         * <p><b>Right for the FIRST run only.</b> Every run drawn moves the
         * cursor by however wide the drawn string turned out to be, and this
         * was worked out as if the cursor were still where the picture left it
         * — so a second run positioned this way lands one string-width too far
         * right. Use it when {@link OverlayInfo#positionsRuns()} is false and
         * there is nothing better; otherwise place the run from {@link #x()}.
         */
        public String shift() {
            return shift;
        }

        /** Where this run starts, in pixels from the picture's left edge. */
        public int x() {
            return x;
        }

        /** The text, with {@code {name}} placeholders still in it. */
        public String text() {
            return text;
        }

        /** A font whose baseline sits at this run's y. */
        public String font() {
            return font;
        }

        /** {@code #rrggbb}, or empty for white. */
        public String color() {
            return color;
        }
    }

    private OverlayInfo(ContentId id, String file, String title, String container, Slot slot,
                        int height, int ascent, int offset, int codepoint,
                        String color, String font, String text,
                        java.util.List<OverlayTrigger> triggers,
                        java.util.List<OverlayRun> runs,
                        boolean pushed, int advance, String shiftPlus, String shiftMinus) {
        this.pushed = pushed;
        this.advance = advance;
        this.shiftPlus = shiftPlus == null ? "" : shiftPlus;
        this.shiftMinus = shiftMinus == null ? "" : shiftMinus;
        this.id = id;
        this.file = file;
        this.title = title;
        this.container = container;
        this.slot = slot;
        this.height = height;
        this.ascent = ascent;
        this.offset = offset;
        this.codepoint = codepoint;
        this.color = color == null ? "" : color;
        this.font = font == null ? "" : font;
        this.text = text == null ? "" : text;
        this.triggers = triggers == null ? java.util.List.of() : java.util.List.copyOf(triggers);
        this.runs = runs == null ? java.util.List.of() : java.util.List.copyOf(runs);
    }

    /**
     * The positioned runs of text this overlay draws. Empty if it has none.
     *
     * <p>Preferred over {@link #text()}, which is the older unpositioned form
     * kept so an overlay pushed before positioning existed still draws.
     */
    public java.util.List<OverlayRun> runs() {
        return runs;
    }

    /**
     * What shows this overlay with no plugin involved. Empty if nothing does.
     *
     * <p>See {@link OverlayTrigger}. An overlay with no triggers waits for
     * {@link Overlays#show} or for {@code /rp hud}, which is the ordinary case
     * for anything a plugin drives.
     */
    public java.util.List<OverlayTrigger> triggers() {
        return triggers;
    }

    /**
     * A line drawn after the picture, in the default font. Empty if there is none.
     *
     * <p>May contain {@code {name}} placeholders, filled per player from
     * whatever {@link Overlays#set} last put there. <b>This is the only part of
     * an overlay a live server value can drive.</b> A shader object's shapes are
     * compiled into the pack and a core shader has no channel a server can push
     * a number through — text is the exception only because it never enters the
     * shader at all.
     */
    public String text() {
        return text;
    }

    /**
     * The colour this must be drawn in, {@code #rrggbb}, or empty for white.
     *
     * <p><b>An address rather than a look, when it is set.</b> A shader overlay
     * is recognised by the pack's own core shader from the exact colour its
     * text arrived with — so this run drawn in any other colour draws nothing,
     * and ordinary text drawn in THIS colour would run that object's shapes
     * inside every letter of it.
     *
     * <p>Empty for everything the engine builds itself and for every pack
     * pushed before shaders existed, which is what keeps those drawing white
     * exactly as they did.
     */
    public String color() {
        return color;
    }

    /**
     * The font this must be drawn in, or empty for the default.
     *
     * <p>A shader object's canvas glyph lives in a font of its own, so it stays
     * out of the icon list and cannot collide with an icon's codepoint.
     */
    public String font() {
        return font;
    }

    /** Engine internal; built by the screen or HUD loader. */
    public static OverlayInfo of(ContentId id, String file, String container, Slot slot,
                                 int height, int ascent, int offset, int codepoint) {
        return new OverlayInfo(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(file, "file"),
                "",
                container == null ? "" : container,
                slot == null ? Slot.ACTION_BAR : slot,
                height, ascent, offset, codepoint, "", "", "", java.util.List.of(), java.util.List.of(),
                false, 0, "", "");
    }

    /**
     * A screen or overlay in a pack this engine did NOT build.
     *
     * <p>The picture is positioned by a run of characters — some negative
     * space, then the glyph — and for our own content that run is worked out
     * from the window geometry and the codepoint we allocated. Neither is ours
     * for a pushed Studio pack: it allocated the codepoint and it did the
     * arithmetic. So the whole run arrives as one already-correct string, and
     * this is the constructor that takes it.
     */
    public static OverlayInfo pushed(ContentId id, String title, String container, Slot slot) {
        return pushed(id, title, container, slot, "", "", "", java.util.List.of(), java.util.List.of());
    }

    /**
     * As {@link #pushed(ContentId, String, String, Slot)}, with the colour and
     * font the run has to be drawn in.
     *
     * <p>Both empty for an ordinary pushed overlay. A SHADER overlay sets them,
     * and for it they are not styling: see {@link #color()}.
     */
    public static OverlayInfo pushed(ContentId id, String title, String container, Slot slot,
                                     String color, String font, String text,
                        java.util.List<OverlayTrigger> triggers,
                        java.util.List<OverlayRun> runs) {
        return new OverlayInfo(
                Objects.requireNonNull(id, "id"),
                "",
                Objects.requireNonNull(title, "title"),
                container == null ? "" : container,
                slot == null ? Slot.ACTION_BAR : slot,
                0, 0, 0, 0, color, font, text, triggers, runs,
                true, 0, "", "");
    }

    /**
     * The same overlay, told where the cursor is and how to move it.
     *
     * <p>Separate from the constructor because it arrived after the wire did: a
     * manifest written by an older Studio carries none of it, and everything
     * here degrades to {@link OverlayRun#shift()} rather than to nothing.
     *
     * @param advance    how far the picture moves the cursor, in pixels
     * @param shiftPlus  the characters that move it right by 1, 2, 4 … 512
     * @param shiftMinus their negative twins
     */
    public OverlayInfo withCursor(int advance, String shiftPlus, String shiftMinus) {
        return new OverlayInfo(id, file, title, container, slot, height, ascent, offset, codepoint,
                color, font, text, triggers, runs, pushed, advance, shiftPlus, shiftMinus);
    }

    /**
     * Whether this engine can place the runs itself.
     *
     * <p>It can only when the pack told it where the picture leaves the cursor
     * and gave it the characters to move it with — both of which are codepoints
     * the pack allocated, so there is nothing to fall back on but each run's
     * own pre-built shift.
     */
    public boolean positionsRuns() {
        return advance > 0 && !shiftPlus.isEmpty() && !shiftMinus.isEmpty();
    }

    /** How far the picture moves the cursor, in pixels. See {@link #positionsRuns()}. */
    public int advance() {
        return advance;
    }

    /** The characters that move the cursor right by 1, 2, 4 … 512 pixels, in that order. */
    public String shiftPlus() {
        return shiftPlus;
    }

    /** Their negative twins. */
    public String shiftMinus() {
        return shiftMinus;
    }

    /**
     * Whether this overlay's art came from a pushed pack rather than from this
     * server's own content.
     *
     * <p><b>What it is for is deciding who may be shown it.</b> A pushed pack
     * goes to the player who asked for it, and everybody else on the server is
     * holding whatever the server itself serves — so drawing a pushed overlay
     * for them sends a character their client has no glyph for, and they get a
     * row of missing-glyph boxes above their hotbar for something they never
     * asked to see. The engine's own content is not gated this way, because a
     * server's own bundle is what its players are already wearing.
     */
    public boolean fromPushedPack() {
        return pushed;
    }

    /**
     * The characters that draw it, when they did not come from this engine.
     *
     * <p>Empty for our own content, where {@link #offset()} and
     * {@link #codepoint()} say the same thing in pieces.
     */
    public String title() {
        return title;
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
