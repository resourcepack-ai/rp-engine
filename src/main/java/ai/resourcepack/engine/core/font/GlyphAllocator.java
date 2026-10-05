package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.LoadReport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

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
    static final ContentKind[] GLYPH_KINDS = {ContentKind.FONT, ContentKind.SCREEN, ContentKind.HUD};

    private GlyphAllocator() {
    }

    /**
     * Every glyph-bearing id in {@code loaded}, mapped to its codepoint.
     *
     * <p>Includes ids whose definitions later turn out to be unusable. That
     * costs a codepoint out of six thousand and buys something worth more: a
     * broken definition does not shift every glyph after it, so fixing one
     * typo does not change what every other glyph resolves to.
     */
    public static Map<ContentId, Integer> allocate(LoadReport loaded) {
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
            for (ContentId id : sorted.keySet()) {
                if (next > LAST_CODEPOINT) {
                    return Map.copyOf(allocated);
                }
                allocated.put(id, next++);
            }
        }
        return Map.copyOf(allocated);
    }

    /** Whether {@code loaded} asks for more glyphs than there is room for. */
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
