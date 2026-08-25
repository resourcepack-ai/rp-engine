package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.OverlayInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads screen and HUD definitions.
 *
 * <p>One class for both because they are one mechanism: a picture in the font,
 * drawn into text the game already renders. What differs is which text — a
 * container's title or the action bar — and that is a field rather than a
 * different design.
 */
public final class OverlayDefinitions {

    /**
     * Containers a screen may open as.
     *
     * <p>A custom GUI is a real container wearing a picture, so it has to be
     * one that exists. Anything else is refused at load with the list in the
     * message, because the alternative is a command that does nothing and a
     * server owner guessing at spellings.
     *
     * <p>The same set {@link GuiWindows} knows the geometry of, and that is not
     * a coincidence: a container whose window size is unknown is one whose
     * backdrop cannot be positioned, so accepting it would be accepting a
     * screen that opens in the wrong place.
     */
    public static final Set<String> CONTAINERS = GuiWindows.containers();

    private OverlayDefinitions() {
    }

    /** Everything of kind SCREEN, parsed. */
    public static Result screens(LoadReport loaded) {
        return parse(loaded, ContentKind.SCREEN);
    }

    /** Everything of kind HUD, parsed. */
    public static Result huds(LoadReport loaded) {
        return parse(loaded, ContentKind.HUD);
    }

    private static Result parse(LoadReport loaded, ContentKind kind) {
        Map<ContentId, OverlayInfo> overlays = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        Map<ContentId, Integer> codepoints = GlyphAllocator.allocate(loaded);
        for (ContentDefinition definition : loaded.definitions(kind)) {
            Integer codepoint = codepoints.get(definition.id());
            if (codepoint == null) {
                diagnostics.add(Diagnostic.error(definition.origin(), definition.id().path(),
                        "There is no room left in the Private Use Area, across icons, "
                                + "screens and HUDs together."));
                continue;
            }
            parseOne(definition, kind, codepoint, diagnostics)
                    .ifPresent(overlay -> overlays.put(overlay.id(), overlay));
        }
        return new Result(Map.copyOf(overlays), List.copyOf(diagnostics));
    }

    private static Optional<OverlayInfo> parseOne(ContentDefinition definition, ContentKind kind,
                                                  int codepoint, List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        String file = body.string("file").orElse(definition.id().path());

        String container = "";
        OverlayInfo.Slot slot = OverlayInfo.Slot.ACTION_BAR;
        if (kind == ContentKind.SCREEN) {
            container = body.string("container").orElse("chest_9x6").trim().toLowerCase(Locale.ROOT);
            if (!CONTAINERS.contains(container)) {
                diagnostics.add(Diagnostic.error(origin, where,
                        "container: " + container + " is not a container the game has. "
                                + "One of: " + sorted() + "."));
                return Optional.empty();
            }
        } else {
            Optional<String> declared = body.string("slot");
            if (declared.isPresent()) {
                try {
                    slot = OverlayInfo.Slot.valueOf(declared.get().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            "slot: " + declared.get() + " is not action_bar or boss_bar. "
                                    + "Using action_bar."));
                }
            }
        }

        // A screen's placement is arithmetic rather than taste: the sheet is
        // 256 square with the window art centred on it, so where the backdrop
        // has to go follows from the window's size. See GuiWindows, a port of
        // the only place this has ever been correct. A pack may still state its
        // own, for art laid out an unusual way.
        // Placement comes from the WINDOW REGION, never from where the ink
        // happens to be. A sheet is built by centring the vanilla window on the
        // 256 canvas (studio's centeredSheetFromVanilla), and an artist is free
        // to draw outside that region — a title plate above the window is the
        // common case. Measuring the opaque bounding box treats such a plate as
        // the top of the window and pushes the whole screen down by its height,
        // which is exactly the bug that made this look nearly right and sit
        // wrong. The region is the contract; the art is decoration around it.
        final String screenContainer = container;
        int height = body.integer("height").orElse(
                kind == ContentKind.SCREEN ? GuiWindows.SHEET_SIZE : 64);
        int ascent = body.integer("ascent").orElseGet(() -> kind == ContentKind.SCREEN
                ? GuiWindows.ascentFor(screenContainer).orElse(30)
                : 32);
        int offset = body.integer("offset").orElseGet(() -> kind == ContentKind.SCREEN
                ? GuiWindows.offsetFor(screenContainer).orElse(48)
                : 0);

        if (height < 1 || height > 256) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "height: " + height + " is outside 1 to 256, which is all a glyph can be. Using 256."));
            height = 256;
        }
        if (ascent > height) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "ascent: " + ascent + " is greater than height: " + height
                            + ", which the game refuses to draw at all. Using " + height + "."));
            ascent = height;
        }
        if (offset < 0 || offset > 512) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "offset: " + offset + " is outside 0 to 512. Using 0."));
            offset = 0;
        }

        return Optional.of(OverlayInfo.of(definition.id(), file, container, slot,
                height, ascent, offset, codepoint));
    }

    private static String sorted() {
        List<String> names = new ArrayList<>(CONTAINERS);
        java.util.Collections.sort(names);
        return String.join(", ", names);
    }

    /** The overlays, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, OverlayInfo> overlays;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, OverlayInfo> overlays, List<Diagnostic> diagnostics) {
            this.overlays = overlays;
            this.diagnostics = diagnostics;
        }

        /** Every overlay that parsed, keyed by id. */
        public Map<ContentId, OverlayInfo> overlays() {
            return overlays;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
