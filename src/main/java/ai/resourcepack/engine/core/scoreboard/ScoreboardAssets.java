package ai.resourcepack.engine.core.scoreboard;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.Images;
import ai.resourcepack.engine.core.pack.PackContributor.Contribution;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Draws each scoreboard's panel into a bundle, and says which font providers
 * its pieces need. Called by the font builder, which owns the one font file
 * they go in.
 *
 * <p>The pieces are written under {@code textures/font/}, which is where the
 * pack optimiser is told to leave a picture's faint and clear pixels alone;
 * both matter here (see {@link ScoreboardPanel}).
 */
public final class ScoreboardAssets {

    private ScoreboardAssets() {
    }

    /** Whether {@code bundle} holds any scoreboard, which is enough to need the font's spaces. */
    public static boolean any(Bundle bundle, LoadReport loaded) {
        for (ScoreboardDefinitions.Board board : ScoreboardDefinitions.parse(loaded).boards().values()) {
            if (bundle.namespaces().contains(board.info().id().namespace())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the pieces of every panel in {@code bundle} and returns their
     * providers, in codepoint order so the font file is the same bytes for the
     * same content.
     */
    public static List<String> contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        List<ScoreboardDefinitions.Board> boards = new ArrayList<>(ScoreboardDefinitions.parse(loaded).boards().values());
        boards.sort(Comparator.comparingInt(ScoreboardDefinitions.Board::codepoint));
        List<String> providers = new ArrayList<>();
        for (ScoreboardDefinitions.Board board : boards) {
            ContentId id = board.info().id();
            ScoreboardPanel panel = board.panel();
            if (panel == null || !bundle.namespaces().contains(id.namespace())) {
                continue;
            }
            BufferedImage source = source(panel, id, into);
            String folder = "font/scoreboards/" + id.path() + "/";
            try {
                piece(id, folder + "left", panel.left(source), panel, board.codepoint() + ScoreboardPanel.LEFT,
                        into, providers);
                piece(id, folder + "right", panel.right(source), panel, board.codepoint() + ScoreboardPanel.RIGHT,
                        into, providers);
                for (int index = 0; index < ScoreboardPanel.MIDDLES; index++) {
                    piece(id, folder + "middle_" + ScoreboardPanel.middleWidth(index), panel.middle(index, source),
                            panel, board.codepoint() + ScoreboardPanel.FIRST_MIDDLE + index, into, providers);
                }
            } catch (IOException e) {
                into.error(id.namespace() + "/scoreboards", id.path(),
                        "Could not write its background. " + e.getMessage());
            }
        }
        return providers;
    }

    /** The pack's own picture for a panel cut from one, or null for a panel drawn from colours. */
    private static BufferedImage source(ScoreboardPanel panel, ContentId id, Contribution into) {
        if (panel.texture() == null) {
            return null;
        }
        String path = "assets/" + id.namespace() + "/textures/gui/" + panel.texture() + ".png";
        Optional<byte[]> png = into.read(path);
        BufferedImage image = null;
        if (png.isPresent()) {
            try {
                image = Images.read(png.get());
            } catch (IOException | RuntimeException e) {
                image = null;
            }
        }
        if (image == null) {
            into.error(id.namespace() + "/scoreboards", id.path(), "No image at " + path
                    + ", so its background is drawn from its colours instead.");
        }
        return image;
    }

    private static void piece(ContentId id, String path, BufferedImage image, ScoreboardPanel panel, int codepoint,
                              Contribution into, List<String> providers) throws IOException {
        into.add("assets/" + id.namespace() + "/textures/" + path + ".png", Images.png(image));
        providers.add("    {\n"
                + "      \"type\": \"bitmap\",\n"
                + "      \"file\": \"" + id.namespace() + ':' + path + ".png\",\n"
                + "      \"height\": " + panel.height() + ",\n"
                + "      \"ascent\": " + panel.ascent() + ",\n"
                + "      \"chars\": [\"\\" + "u" + String.format("%04X", codepoint) + "\"]\n"
                + "    }");
    }
}
