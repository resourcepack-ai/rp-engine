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

        // The same files the server counted frames from, so the build and the
        // server allocate the same codepoints. See IconDefinitions.
        IconDefinitions.Result icons = IconDefinitions.parse(loaded, into::source);
        // In codepoint order: the result is a map whose iteration order is
        // nobody's promise, and this file is hashed into the pack's SHA-1.
        List<IconInfo> ordered = new ArrayList<>(icons.icons().values());
        ordered.sort(java.util.Comparator.comparingInt(IconInfo::codepoint));
        java.util.Set<String> gifs = new java.util.TreeSet<>();
        for (IconInfo icon : ordered) {
            if (!bundle.namespaces().contains(icon.id().namespace())) {
                continue;
            }
            String location = textureOf(icon);
            String textureNamespace = location.substring(0, location.indexOf(':'));
            String texturePathPart = location.substring(location.indexOf(':') + 1);
            String texture = texturePath(textureNamespace, texturePathPart);
            if (isGif(icon)) {
                String source = gifSource(location);
                if (!into.has(texture) && !writeStrip(icon, source, texture, into)) {
                    continue;
                }
                gifs.add(source);
            } else if (missing(texture, icon.id(), "fonts", into)) {
                continue;
            }
            providers.add(bitmap(textureNamespace, texturePathPart,
                    icon.height(), icon.ascent(), chars(icon)));
            measure(icon, texture, into);
        }
        // The GIFs themselves are source, not something the client reads: the
        // strip beside each is what the font draws. Shipping both would make
        // every player download the animation twice.
        for (String gif : gifs) {
            into.drop(gif);
        }

        boolean anyScreen = false;
        for (OverlayInfo overlay : OverlayDefinitions.screens(loaded).overlays().values()) {
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
     * Tells {@link TextWidth} how wide an icon is drawn, so a value carrying it
     * in an overlay measures as itself. The game scales a bitmap glyph's cell to
     * the declared height and rounds its width; that rounded width is what is
     * recorded. Nothing is recorded when the PNG cannot be read.
     */
    private static void measure(IconInfo icon, String texture, Contribution into) {
        java.util.Optional<byte[]> png = into.read(texture);
        if (png.isEmpty() || png.get().length < 24) {
            return;
        }
        byte[] b = png.get();
        int width = ((b[16] & 0xff) << 24) | ((b[17] & 0xff) << 16) | ((b[18] & 0xff) << 8) | (b[19] & 0xff);
        int height = ((b[20] & 0xff) << 24) | ((b[21] & 0xff) << 16) | ((b[22] & 0xff) << 8) | (b[23] & 0xff);
        int columns = Math.max(1, icon.columns());
        int rows = Math.max(1, icon.rows()) * Math.max(1, icon.frames());
        int cellWidth = width / columns;
        int cellHeight = height / rows;
        if (cellWidth <= 0 || cellHeight <= 0) {
            return;
        }
        int drawn = (int) Math.round(cellWidth * (double) icon.height() / cellHeight);
        for (int frame = 0; frame < Math.max(1, icon.frames()); frame++) {
            TextWidth.glyph(icon.codepoint(frame), drawn);
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
                overlay.height(), overlay.ascent(), "\"" + escape(overlay.codepoint()) + "\""));
        return true;
    }

    /**
     * The texture an icon draws, as {@code namespace:path} under
     * {@code textures/}.
     *
     * <p>A plain {@code file} is under the pack's own {@code textures/font/};
     * a namespaced one is a resource location, which is how CraftEngine names
     * its images ({@code minecraft:font/image/emojis}) out of a resource pack
     * folder it ships beside its configuration.
     */
    public static String textureOf(IconInfo icon) {
        return textureOf(icon.id().namespace(), icon.file());
    }

    /**
     * As {@link #textureOf(IconInfo)}, for an icon not yet built. A GIF keeps
     * its {@code .gif} here, so the PNG the font is pointed at is the strip
     * written beside it, {@code <name>.gif.png}.
     */
    static String textureOf(String namespace, String file) {
        if (file.indexOf(':') > 0) {
            return file.endsWith(".png") ? file.substring(0, file.length() - 4) : file;
        }
        return namespace + ":font/" + file;
    }

    /** Whether an icon is drawn from a GIF, and so from a strip the build writes. */
    static boolean isGif(IconInfo icon) {
        return icon.file().endsWith(".gif");
    }

    /** Where a GIF icon's GIF is in the bundle, from its texture location. */
    static String gifSource(String location) {
        int colon = location.indexOf(':');
        return "assets/" + location.substring(0, colon) + "/textures/" + location.substring(colon + 1);
    }

    /**
     * Draws a GIF icon's frames into the strip its glyphs are cut from.
     *
     * <p>Exactly as many frames as the icon was allocated codepoints for, which
     * the server worked out from the same file; a strip with a different number
     * of rows would cut every frame at the wrong height.
     *
     * @return whether the strip was written
     */
    private static boolean writeStrip(IconInfo icon, String source, String strip, Contribution into) {
        java.util.Optional<GifFrames> frames = into.read(source)
                .flatMap(bytes -> GifFrames.decode(bytes, icon.frames()));
        String origin = icon.id().namespace() + "/fonts";
        if (frames.isEmpty()) {
            into.error(origin, icon.id().path(), "No readable GIF at " + source + ".");
            return false;
        }
        GifFrames gif = frames.get();
        if (gif.count() != icon.rows()) {
            into.error(origin, icon.id().path(), source + " has " + gif.count() + " frames where "
                    + icon.rows() + " were counted. Reload, and if it persists the file is changing under the build.");
            return false;
        }
        if (gif.width() > MAX_GLYPH_SIDE || gif.height() > MAX_GLYPH_SIDE) {
            // Scaled rather than refused: a GIF found online is very often a
            // few hundred pixels, and an icon is drawn at `height` whatever its
            // pixels are, so the only thing lost is detail nobody would see.
            into.warn(origin, icon.id().path(), source + " is " + gif.width() + " by " + gif.height()
                    + ". A font glyph can be at most " + MAX_GLYPH_SIDE + " on a side, so each frame was "
                    + "scaled down to fit. Scale it yourself for control over how.");
        }
        try {
            into.add(strip, gif.strip(MAX_GLYPH_SIDE));
            return true;
        } catch (java.io.IOException e) {
            into.error(origin, icon.id().path(), "Could not write the frames of " + source + ". " + e.getMessage());
            return false;
        }
    }

    /**
     * The largest a glyph may be on either side: the page the game packs font
     * glyphs onto is 256 square, and a glyph that does not fit is not drawn.
     */
    static final int MAX_GLYPH_SIDE = 256;

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

    private static String bitmap(String namespace, String path, int height, int ascent, String chars) {
        return "    {\n"
                + "      \"type\": \"bitmap\",\n"
                + "      \"file\": \"" + namespace + ':' + path + ".png\",\n"
                + "      \"height\": " + height + ",\n"
                + "      \"ascent\": " + ascent + ",\n"
                + "      \"chars\": [" + chars + "]\n"
                + "    }";
    }

    /**
     * The {@code chars} rows of an icon's provider, already quoted.
     *
     * <p>One string for an ordinary icon. For one cell of a sheet, the game's
     * own grid: a bitmap provider with several rows of several characters
     * splits its PNG into that many equal cells, and a NUL (U+0000) in a cell
     * means nothing is drawn from it. So each icon on a sheet is the same PNG
     * with every cell but its own empty, and nothing is ever cropped - the
     * sheet ships once, and the picture is exactly what was drawn.
     *
     * <p>An animated icon is the same rule with more than one cell filled:
     * frame {@code k} is the cell {@code k} after the icon's own, at the
     * codepoint {@code k} after its own. So one provider declares every frame
     * of a strip, and the strip is cut once.
     */
    static String chars(IconInfo icon) {
        StringBuilder rows = new StringBuilder();
        int first = icon.cell();
        int last = icon.cell() + icon.frames() - 1;
        int cell = 1;
        for (int row = 0; row < icon.rows(); row++) {
            if (row > 0) {
                rows.append(", ");
            }
            rows.append('"');
            for (int column = 0; column < icon.columns(); column++, cell++) {
                rows.append(escape(cell >= first && cell <= last ? icon.codepoint() + (cell - first) : 0));
            }
            rows.append('"');
        }
        return rows.toString();
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
        // to contain an escape.
        return "\\" + "u" + String.format("%04X", codepoint);
    }
}
