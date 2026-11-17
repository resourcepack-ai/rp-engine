package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.OverlayInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link OverlayBoxes}: a container laid out across from what its runs came to.
 */
class OverlayBoxesTest {

    private static OverlayInfo.Box.Item run(int... runs) {
        return new OverlayInfo.Box.Item(java.util.Arrays.stream(runs).boxed().toList(), -1);
    }

    private static OverlayInfo.Box.Item box(int index) {
        return new OverlayInfo.Box.Item(List.of(), index);
    }

    private static boolean[] all(int n) {
        boolean[] drawn = new boolean[n];
        java.util.Arrays.fill(drawn, true);
        return drawn;
    }

    @Test
    void aRowPutsEachChildAfterTheLastWithTheGap() {
        // x 10, pivot left, gap 4, pad 3: 10+3, then +20+4, then +5+4.
        OverlayInfo.Box row = new OverlayInfo.Box(10, 0, true, 4, 3, 0, 0, -1, List.of(run(0), run(1), run(2)));
        OverlayBoxes.Layout layout = OverlayBoxes.layout(List.of(row), new int[]{20, 5, 7}, all(3));
        assertArrayEquals(new Integer[]{13, 37, 46}, layout.starts());
        assertEquals(3 + 20 + 4 + 5 + 4 + 7 + 3, layout.width()[0]);
    }

    @Test
    void aCentredPivotKeepsTheRowCentredAsItGrows() {
        OverlayInfo.Box row = new OverlayInfo.Box(100, 0.5, true, 2, 0, 0, 0, -1, List.of(run(0), run(1)));
        OverlayBoxes.Layout narrow = OverlayBoxes.layout(List.of(row), new int[]{10, 10}, all(2));
        OverlayBoxes.Layout wide = OverlayBoxes.layout(List.of(row), new int[]{30, 10}, all(2));
        assertEquals(100 - 11, narrow.left()[0]);
        assertEquals(100 - 21, wide.left()[0]);
        assertEquals(narrow.left()[0] + narrow.width()[0], 200 - narrow.left()[0]);
    }

    @Test
    void aChildThatDrawsNothingTakesNoRoom() {
        OverlayInfo.Box row = new OverlayInfo.Box(0, 0, true, 4, 0, 0, 0, -1, List.of(run(0), run(1), run(2)));
        OverlayBoxes.Layout layout = OverlayBoxes.layout(List.of(row), new int[]{8, 50, 8},
                new boolean[]{true, false, true});
        assertEquals(0, layout.starts()[0]);
        assertEquals(12, layout.starts()[2]);
        assertEquals(20, layout.width()[0]);
    }

    @Test
    void aContainerWithNothingDrawnIsHidden() {
        OverlayInfo.Box row = new OverlayInfo.Box(0, 0, true, 4, 4, 0, 0, -1, List.of(run(0)));
        OverlayBoxes.Layout layout = OverlayBoxes.layout(List.of(row), new int[]{8}, new boolean[]{false});
        assertFalse(layout.shown()[0]);
        assertNull(layout.starts()[0]);
    }

    @Test
    void aColumnAlignsEachChildInItsWidth() {
        // Column centred: widest 20, so 10 sits 5 in.
        OverlayInfo.Box column = new OverlayInfo.Box(0, 0, false, 2, 1, 0, 0.5, -1, List.of(run(0), run(1)));
        OverlayBoxes.Layout layout = OverlayBoxes.layout(List.of(column), new int[]{20, 10}, all(2));
        assertArrayEquals(new Integer[]{1, 6}, layout.starts());
        assertEquals(22, layout.width()[0]);
    }

    @Test
    void aMinimumWidthLeavesRoomTheRowIsJustifiedIn() {
        OverlayInfo.Box row = new OverlayInfo.Box(0, 0, true, 0, 0, 100, 1, -1, List.of(run(0)));
        OverlayBoxes.Layout layout = OverlayBoxes.layout(List.of(row), new int[]{10}, all(1));
        assertEquals(90, layout.starts()[0]);
    }

    @Test
    void nestedContainersSizeFromTheInsideOut() {
        // Three panels in a row, each a column of two runs.
        OverlayInfo.Box outer = new OverlayInfo.Box(0, 0, true, 6, 2, 0, 0, -1, List.of(box(1), box(2), box(3)));
        OverlayInfo.Box a = new OverlayInfo.Box(0, 0, false, 1, 3, 0, 0, -1, List.of(run(0), run(1)));
        OverlayInfo.Box b = new OverlayInfo.Box(0, 0, false, 1, 3, 0, 0, -1, List.of(run(2), run(3)));
        OverlayInfo.Box c = new OverlayInfo.Box(0, 0, false, 1, 3, 0, 0, -1, List.of(run(4), run(5)));
        OverlayBoxes.Layout layout = OverlayBoxes.layout(List.of(outer, a, b, c),
                new int[]{10, 30, 12, 4, 8, 8}, all(6));
        // a is 30+6 = 36 wide from 2; b from 2+36+6 = 44; c from 44+18+6 = 68.
        assertEquals(36, layout.width()[1]);
        assertEquals(18, layout.width()[2]);
        assertEquals(14, layout.width()[3]);
        assertArrayEquals(new Integer[]{5, 5, 47, 47, 71, 71}, layout.starts());
        assertEquals(2 + 36 + 6 + 18 + 6 + 14 + 2, layout.width()[0]);
    }

    @Test
    void aContainerHoldingItselfIsReadAsEmptyRatherThanForever() {
        OverlayInfo.Box loop = new OverlayInfo.Box(0, 0, true, 0, 0, 0, 0, -1, List.of(box(0), run(0)));
        OverlayBoxes.Layout layout = OverlayBoxes.layout(List.of(loop), new int[]{5}, all(1));
        // It names itself, so it is nobody's root and nothing is placed.
        assertNull(layout.starts()[0]);
    }
}
