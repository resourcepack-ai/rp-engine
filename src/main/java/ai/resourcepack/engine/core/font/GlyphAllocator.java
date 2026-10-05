package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.LoadReport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * Hands out codepoints to everything that becomes a glyph.
 *
 * <p>Icons, GUI backgrounds and HUD overlays are the same mechanism wearing
 * three names: a picture declared in the font and drawn by putting its
 * character into a piece of text. They therefore share <strong>one</strong>
 * number line. Allocating per kind would hand the same codepoint to an icon and
 * a screen, and the failure is a chat message that draws a full-screen GUI
 * across somebody's view.
 *
 * <p>Order is by kind then id, both fixed, so the same content allocates
 * identically on every machine and every restart. See
 * {@link ai.resourcepack.engine.api.IconInfo#codepoint()} for why they are not
 * stable across content <em>changes</em>, and why that is the right trade.
 *
 * <p><strong>An animated icon takes a run of codepoints, one per
 * frame</strong>, and that is why icons come LAST. Screens and HUDs are always
 * one glyph each, so putting them first means their numbers never depend on
 * how many frames an icon has — and how many frames a GIF has is only known by
 * reading the GIF, which the overlay loader has no reason to do. Icons were
 * first until 2026-10-05; a codepoint was never promised to survive a content
 * change, so moving them broke nothing that followed the rules.
 */
public final class GlyphAllocator {

    /**
     * The Unicode Private Use Area: {@code U+E000} to {@code U+F8FF}.
     *
     * <p>6,400 glyphs, and the only range where one cannot collide with a real
     * character somebody might legitimately type.
     */
    public static final int FIRST_CODEPOINT = 0xE000;
    public static final int LAST_CODEPOINT = 0xF8FF;

    /** Every kind that becomes a picture in the font, in allocation order. */
    static final ContentKind[] GLYPH_KINDS = {ContentKind.SCREEN, ContentKind.HUD, ContentKind.FONT};

    private GlyphAllocator() {
    }

    /**
     * Every glyph-bearing id in {@code loaded}, mapped to its codepoint, with
     * every definition taking one glyph.
     *
     * <p>Right for screens and HUDs whatever the icons are, because they are
     * allocated first. Wrong for an animated icon, whose loader asks
     * {@link #allocate(LoadReport, ToIntFunction)} instead.
     */
    public static Map<ContentId, Integer> allocate(LoadReport loaded) {
        return allocate(loaded, definition -> 1);
    }

    /**
     * Every glyph-bearing id in {@code loaded}, mapped to its FIRST codepoint;
     * a definition that takes {@code n} glyphs owns that one and the
     * {@code n - 1} after it.
     *
     * <p>Includes ids whose definitions later turn out to be unusable. That
     * costs a codepoint out of six thousand and buys something worth more: a
     * broken definition does not shift every glyph after it, so fixing one
     * typo does not change what every other glyph resolves to.
     *
     * <p>A definition whose whole run does not fit is left out, rather than
     * given the codepoints that are left: half an animation would draw the
     * next glyph's picture as its later frames.
     */
    public static Map<ContentId, Integer> allocate(LoadReport loaded, ToIntFunction<ContentDefinition> glyphs) {
        if (loaded == null) {
            return Map.of();
        }
        Map<ContentId, Integer> allocated = new LinkedHashMap<>();
        int next = FIRST_CODEPOINT;
        for (ContentKind kind : GLYPH_KINDS) {
            Map<ContentId, ContentDefinition> sorted = new TreeMap<>();
            for (ContentDefinition definition : loaded.definitions(kind)) {
                sorted.put(definition.id(), definition);
            }
            for (ContentDefinition definition : sorted.values()) {
                int wanted = kind == ContentKind.FONT ? Math.max(1, glyphs.applyAsInt(definition)) : 1;
                if (next + wanted - 1 > LAST_CODEPOINT) {
                    return Map.copyOf(allocated);
                }
                allocated.put(definition.id(), next);
                next += wanted;
            }
        }
        return Map.copyOf(allocated);
    }

    /** Whether {@code loaded} asks for more glyphs than there is room for, one each. */
    public static boolean overflows(LoadReport loaded) {
        if (loaded == null) {
            return false;
        }
        int wanted = 0;
        for (ContentKind kind : GLYPH_KINDS) {
            wanted += loaded.definitions(kind).size();
        }
        return wanted > LAST_CODEPOINT - FIRST_CODEPOINT + 1;
    }
}
