package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Declares every icon as a glyph in the game's default font.
 *
 * <p>The default font on purpose, rather than a font of our own. A font of our
 * own would only render where the server can set the font of a text component,
 * which rules out an item name typed into an anvil, a sign, a scoreboard, and
 * every plugin that writes a plain string. Putting the glyphs in
 * {@code minecraft:default} makes them work everywhere text does.
 *
 * <p>The cost is that {@code assets/minecraft/font/default.json} is one vanilla
 * file that every pack in a bundle would want to write. Nothing here lets them:
 * the file is generated once per bundle from every namespace's icons at once,
 * so there is nothing to merge and nothing to collide. A pack that ships its
 * own copy through {@code overrides/} would replace ours and delete every
 * icon in the bundle, so that is refused loudly rather than silently obeyed.
 */
public final class IconAssets implements PackContributor {

    static final String DEFAULT_FONT = "assets/minecraft/font/default.json";

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        IconDefinitions.Result parsed = IconDefinitions.parse(loaded);

        List<IconInfo> shipped = new ArrayList<>();
        for (IconInfo icon : parsed.icons().values()) {
            if (!bundle.namespaces().contains(icon.id().namespace())) {
                continue;
            }
            String texture = "assets/" + icon.id().namespace() + "/textures/font/" + icon.file() + ".png";
            if (!into.has(texture)) {
                into.error(icon.id().namespace() + "/fonts", icon.id().path(),
                        "No image at " + texture + ".");
                continue;
            }
            shipped.add(icon);
        }

        if (shipped.isEmpty()) {
            return;
        }
        if (into.has(DEFAULT_FONT)) {
            // Loud, because the failure is total and silent: every icon in the
            // bundle would stop existing, and the pack that caused it would
            // look fine.
            into.error("overrides/font/default.json", "",
                    "A pack in the bundle " + bundle.name() + " overrides the default font, which would "
                            + "replace the icons of every pack beside it. Remove it; icons are declared "
                            + "in fonts/ and merged automatically.");
            return;
        }

        StringBuilder json = new StringBuilder("{\n  \"providers\": [\n");
        for (int i = 0; i < shipped.size(); i++) {
            IconInfo icon = shipped.get(i);
            json.append("    {\n")
                    .append("      \"type\": \"bitmap\",\n")
                    .append("      \"file\": \"").append(icon.id().namespace())
                    .append(":font/").append(icon.file()).append(".png\",\n")
                    .append("      \"height\": ").append(icon.height()).append(",\n")
                    .append("      \"ascent\": ").append(icon.ascent()).append(",\n")
                    .append("      \"chars\": [\"\\u").append(hex(icon.codepoint())).append("\"]\n")
                    .append("    }").append(i < shipped.size() - 1 ? "," : "").append('\n');
        }
        json.append("  ]\n}\n");
        into.add(DEFAULT_FONT, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The codepoint as a four-digit escape.
     *
     * <p>Written as an escape rather than as the character itself so the file
     * is plain ASCII. A Private Use Area character in a JSON file survives most
     * editors and is mangled by the rest, and the failure looks like the icon
     * simply not existing.
     */
    private static String hex(int codepoint) {
        return String.format("%04X", codepoint);
    }
}
