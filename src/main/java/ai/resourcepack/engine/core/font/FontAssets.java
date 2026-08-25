package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the one font file that every glyph in a bundle lives in.
 *
 * <p>The game's default font, not one of ours. A font of our own would only
 * render where the server can set the font of a text component, which rules out
 * an item name typed into an anvil, a sign, a scoreboard, and every plugin that
 * writes a plain string. In the default font a glyph works anywhere text does.
 *
 * <p>The cost is that {@code assets/minecraft/font/default.json} is one vanilla
 * file that every pack in a bundle would want to write. Nothing here lets them:
 * it is generated once per bundle from every namespace's icons, screens and HUD
 * overlays at once, so there is nothing to merge and nothing to collide.
 *
 * <p>It also carries the <strong>space provider</strong>, which is what makes a
 * GUI backdrop land over the window instead of after the title text.
 */
public final class FontAssets implements PackContributor {

    static final String DEFAULT_FONT = "assets/minecraft/font/default.json";
    static final String VANILLA_LANG = "assets/minecraft/lang/en_us.json";

    /**
     * Codepoints for negative space, one per power of two.
     *
     * <p>Sits above the glyph range so it can never collide with content, and
     * is powers of two so any shift up to 511 pixels is at most nine
     * characters. A provider per pixel would be 512 entries in a file the
     * client parses on every pack load.
     */
    static final int FIRST_SPACE_CODEPOINT = 0xF900;
    static final int SPACE_STEPS = 10;

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        List<String> providers = new ArrayList<>();

        IconDefinitions.Result icons = IconDefinitions.parse(loaded);
        for (IconInfo icon : icons.icons().values()) {
            if (!bundle.namespaces().contains(icon.id().namespace())) {
                continue;
            }
            String texture = texturePath(icon.id().namespace(), "font/" + icon.file());
            if (missing(texture, icon.id(), "fonts", into)) {
                continue;
            }
            providers.add(bitmap(icon.id().namespace(), "font/" + icon.file(),
                    icon.height(), icon.ascent(), icon.codepoint()));
        }

        boolean anyScreen = false;
        for (OverlayInfo overlay : OverlayDefinitions.screens(loaded, measure(loaded, into))
                .overlays().values()) {
            anyScreen |= addOverlay(overlay, "screens", bundle, into, providers);
        }
        for (OverlayInfo overlay : OverlayDefinitions.huds(loaded).overlays().values()) {
            addOverlay(overlay, "huds", bundle, into, providers);
        }

        if (providers.isEmpty()) {
            return;
        }
        if (into.has(DEFAULT_FONT)) {
            // Loud, because the failure is total and silent: every glyph in
            // the bundle would stop existing, and the pack that caused it
            // would look fine.
            into.error("overrides/font/default.json", "",
                    "A pack in the bundle " + bundle.name() + " overrides the default font, which would "
                            + "replace the glyphs of every pack beside it. Remove it; icons, screens and "
                            + "HUDs are declared in their own folders and merged automatically.");
            return;
        }

        providers.add(space());
        into.add(DEFAULT_FONT, ("{\n  \"providers\": [\n" + String.join(",\n", providers)
                + "\n  ]\n}\n").getBytes(StandardCharsets.UTF_8));

        if (anyScreen) {
            hideInventoryLabel(bundle, into);
        }
    }

    /**
     * Blanks the player-inventory label while the bundle holds any screen.
     *
     * <p>The client draws that word itself from {@code container.inventory},
     * and the server cannot touch it: an open-screen packet carries a window
     * id, a menu type and the container title, and the title is the one thing
     * already spent getting the backdrop on screen. Nor can the backdrop cover
     * it — vanilla draws the title first and the inventory label after, so the
     * word lands on top of whatever the sheet painted there.
     *
     * <p>A language file is the only lever there is. Two things about what it
     * does, both properties of the key rather than choices made here:
     *
     * <ul>
     *   <li><strong>It is global.</strong> Every container screen loses the
     *       word, including the player's own inventory, not only the ones a
     *       plugin opens with our title.</li>
     *   <li><strong>It is English.</strong> Blanking it in a dozen locales
     *       nobody asked about is a dozen files to keep correct.</li>
     * </ul>
     */
    private void hideInventoryLabel(Bundle bundle, Contribution into) {
        if (into.has(VANILLA_LANG)) {
            into.error("overrides/lang/en_us.json", "",
                    "A pack in the bundle " + bundle.name() + " ships its own vanilla language file, so "
                            + "the word Inventory cannot be blanked and will be drawn across every "
                            + "custom screen. Add an empty container.inventory to it yourself.");
            return;
        }
        into.add(VANILLA_LANG,
                "{\n  \"container.inventory\": \"\"\n}\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Measures every screen's sheet, so the placement follows the art.
     *
     * <p>Read through {@code source} rather than out of the zip because the
     * plugin does the same measurement for its own copy of the metrics, and
     * both have to agree. Deriving the same number two different ways is how
     * two halves of one feature drift apart.
     */
    private static java.util.Map<ai.resourcepack.engine.api.ContentId, int[]> measure(
            LoadReport loaded, Contribution into) {
        java.util.Map<ai.resourcepack.engine.api.ContentId, int[]> insets = new java.util.HashMap<>();
        for (OverlayInfo overlay : OverlayDefinitions.screens(loaded).overlays().values()) {
            into.source(overlay.id().namespace(), "assets/textures/gui/" + overlay.file() + ".png")
                    .flatMap(GuiSheet::inset)
                    .ifPresent(inset -> insets.put(overlay.id(), inset));
        }
        return insets;
    }

    /** @return whether the overlay actually made it into the bundle */
    private boolean addOverlay(OverlayInfo overlay, String folder, Bundle bundle,
                               Contribution into, List<String> providers) {
        if (!bundle.namespaces().contains(overlay.id().namespace())) {
            return false;
        }
        String texture = texturePath(overlay.id().namespace(), "gui/" + overlay.file());
        if (missing(texture, overlay.id(), folder, into)) {
            return false;
        }
        providers.add(bitmap(overlay.id().namespace(), "gui/" + overlay.file(),
                overlay.height(), overlay.ascent(), overlay.codepoint()));
        return true;
    }

    private static String texturePath(String namespace, String path) {
        return "assets/" + namespace + "/textures/" + path + ".png";
    }

    private boolean missing(String texture, ContentId id, String folder, Contribution into) {
        if (into.has(texture)) {
            return false;
        }
        into.error(id.namespace() + "/" + folder, id.path(), "No image at " + texture + ".");
        return true;
    }

    private static String bitmap(String namespace, String path, int height, int ascent, int codepoint) {
        return "    {\n"
                + "      \"type\": \"bitmap\",\n"
                + "      \"file\": \"" + namespace + ':' + path + ".png\",\n"
                + "      \"height\": " + height + ",\n"
                + "      \"ascent\": " + ascent + ",\n"
                + "      \"chars\": [\"" + escape(codepoint) + "\"]\n"
                + "    }";
    }

    /**
     * One provider holding every negative-space step.
     *
     * <p>Vanilla's own {@code space} provider type, which moves the cursor
     * without drawing anything. The alternative everybody used before it
     * existed was a transparent bitmap per width, which is a pile of PNGs that
     * exist to be invisible.
     */
    private static String space() {
        StringBuilder advances = new StringBuilder();
        for (int step = 0; step < SPACE_STEPS; step++) {
            if (step > 0) {
                advances.append(",\n");
            }
            advances.append("        \"").append(escape(FIRST_SPACE_CODEPOINT + step))
                    .append("\": ").append(-(1 << step));
        }
        return "    {\n"
                + "      \"type\": \"space\",\n"
                + "      \"advances\": {\n" + advances + "\n      }\n"
                + "    }";
    }

    /**
     * A codepoint as an escape.
     *
     * <p>So the file is plain ASCII. A Private Use Area character in a JSON
     * file survives some editors and is mangled by the rest, and the failure
     * looks like the glyph simply not existing.
     */
    private static String escape(int codepoint) {
        // The backslash and the u are concatenated rather than written
        // together, because javac translates unicode escapes BEFORE it parses
        // and counts the backslashes in front of the u to decide. Written the
        // obvious way this line is a compile error in a file that never meant
        // to contain an escape. The same trap is documented in
        // the previous engine's the design notes.
        return "\\" + "u" + String.format("%04X", codepoint);
    }
}
