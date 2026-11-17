package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.OverlayInfo;

import java.util.List;

/**
 * Lays an overlay's containers out across, from what each run came to.
 *
 * <p>A container is runs side by side or one under another (see
 * {@link OverlayInfo.Box}). Their heights were known when the pack was built,
 * so the pack already lifts each run to its row; their widths depend on what
 * the placeholders filled in with, a tick before the line is sent, so this is
 * where each run's start is decided. Studio does the same arithmetic with
 * every label as wide as it is written, which is what each run's own {@code x}
 * holds, and the two have to agree to the pixel when nothing is filled in:
 * Studio's container layout is the other end, and {@code OverlayBoxesStudioTest}
 * replays what it worked out.
 *
 * <p>A run that draws nothing (its condition failed) takes no room, and a
 * container with nothing drawn in it takes none either and draws no
 * background.
 */
final class OverlayBoxes {

    private OverlayBoxes() {
    }

    /**
     * Where each run starts and how wide each container came out.
     *
     * @param starts each run's start in pixels from the picture's left edge,
     *               or null for a run in no container
     * @param left   each container's left edge
     * @param width  each container's width
     * @param shown  whether each container holds anything drawn
     */
    record Layout(Integer[] starts, int[] left, int[] width, boolean[] shown) {
    }

    /**
     * @param boxes the overlay's containers
     * @param ink   each run's drawn width in pixels: what it moved the cursor
     *              by, less the pixel the font leaves after the last glyph (a
     *              bar's assembled width has no such pixel)
     * @param drawn whether each run draws anything
     */
    static Layout layout(List<OverlayInfo.Box> boxes, int[] ink, boolean[] drawn) {
        int n = boxes.size();
        Layout out = new Layout(new Integer[ink.length], new int[n], new int[n], new boolean[n]);
        int[] content = new int[n];
        // 0 unvisited, 1 measuring, 2 measured: a container naming one of its
        // own ancestors is read as empty rather than recursing for ever.
        int[] state = new int[n];
        boolean[] child = new boolean[n];
        for (OverlayInfo.Box box : boxes) {
            for (OverlayInfo.Box.Item item : box.items()) {
                if (item.box() >= 0 && item.box() < n) {
                    child[item.box()] = true;
                }
            }
        }
        for (int b = 0; b < n; b++) {
            measure(b, boxes, ink, drawn, out, content, state);
        }
        for (int b = 0; b < n; b++) {
            if (child[b] || !out.shown()[b]) {
                continue;
            }
            OverlayInfo.Box box = boxes.get(b);
            place(b, box.x() - (int) Math.round(box.pivot() * out.width()[b]), boxes, ink, drawn, out, content,
                    new boolean[n]);
        }
        return out;
    }

    private static void measure(int b, List<OverlayInfo.Box> boxes, int[] ink, boolean[] drawn, Layout out,
                                int[] content, int[] state) {
        if (state[b] != 0) {
            return;
        }
        state[b] = 1;
        OverlayInfo.Box box = boxes.get(b);
        int count = 0;
        int along = 0;
        int across = 0;
        for (OverlayInfo.Box.Item item : box.items()) {
            int w = itemWidth(item, boxes, ink, drawn, out, content, state);
            if (w < 0) {
                continue;
            }
            count++;
            along += w;
            across = Math.max(across, w);
        }
        content[b] = box.row() ? along + box.gap() * Math.max(0, count - 1) : across;
        out.width()[b] = Math.max(box.min(), content[b] + 2 * box.pad());
        out.shown()[b] = count > 0;
        state[b] = 2;
    }

    /** How wide one child is, or -1 when it draws nothing and takes no room. */
    private static int itemWidth(OverlayInfo.Box.Item item, List<OverlayInfo.Box> boxes, int[] ink,
                                 boolean[] drawn, Layout out, int[] content, int[] state) {
        if (item.box() >= 0) {
            if (item.box() >= boxes.size()) {
                return -1;
            }
            measure(item.box(), boxes, ink, drawn, out, content, state);
            return state[item.box()] == 2 && out.shown()[item.box()] ? out.width()[item.box()] : -1;
        }
        int widest = -1;
        for (int run : item.runs()) {
            if (run >= 0 && run < ink.length && drawn[run]) {
                widest = Math.max(widest, Math.max(0, ink[run]));
            }
        }
        return widest;
    }

    /** {@link #itemWidth} once everything is measured. */
    private static int measuredWidth(OverlayInfo.Box.Item item, int boxes, int[] ink, boolean[] drawn, Layout out) {
        if (item.box() >= 0) {
            return item.box() < boxes && out.shown()[item.box()] ? out.width()[item.box()] : -1;
        }
        int widest = -1;
        for (int run : item.runs()) {
            if (run >= 0 && run < ink.length && drawn[run]) {
                widest = Math.max(widest, Math.max(0, ink[run]));
            }
        }
        return widest;
    }

    private static void place(int b, int left, List<OverlayInfo.Box> boxes, int[] ink, boolean[] drawn,
                              Layout out, int[] content, boolean[] placed) {
        if (placed[b]) {
            return;
        }
        placed[b] = true;
        out.left()[b] = left;
        OverlayInfo.Box box = boxes.get(b);
        int inner = out.width()[b] - 2 * box.pad();
        int cursor = left + box.pad() + (box.row() ? (int) Math.round(box.align() * (inner - content[b])) : 0);
        for (OverlayInfo.Box.Item item : box.items()) {
            int w = measuredWidth(item, boxes.size(), ink, drawn, out);
            if (w < 0) {
                continue;
            }
            int at = box.row() ? cursor : left + box.pad() + (int) Math.round(box.align() * (inner - w));
            if (item.box() >= 0) {
                place(item.box(), at, boxes, ink, drawn, out, content, placed);
            } else {
                for (int run : item.runs()) {
                    if (run >= 0 && run < ink.length) {
                        out.starts()[run] = at;
                    }
                }
            }
            cursor += w + box.gap();
        }
    }
}
