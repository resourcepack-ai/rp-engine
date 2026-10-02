package ai.resourcepack.engine.core.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A part's rest pose: a cube the pack drew re-centred and untilted, put back
 * where the model says it is.
 *
 * <p>The cases are real cubes from a customer's models, which sat a few
 * pixels to a block away from where Studio drew them.
 */
class RigRestPoseTest {

    private static RigStore.Part part(float[] anchor, float[] pivot, float[] degrees) {
        RigStore.Step step = new RigStore.Step();
        step.pivot = pivot;
        step.rotate = degrees;
        RigStore.Part part = new RigStore.Part();
        part.program = List.of(step);
        part.anchor = anchor;
        return part;
    }

    /** Model px to the engine's model space, block-centred. */
    private static Vector3f space(float x, float y, float z) {
        return new Vector3f((x - 8f) / 16f, (y - 8f) / 16f, (z - 8f) / 16f);
    }

    /**
     * Every corner of the shifted cube, through the rest pose, lands where the
     * same corner of the source cube lands turned about the source pivot.
     */
    private static void assertRestoresSource(float[] from, float[] to, float[] anchor, float[] pivot, float[] degrees) {
        Matrix4f rest = new Matrix4f();
        part(anchor, pivot, degrees).applyRest(rest);

        Vector3f p = space(pivot[0], pivot[1], pivot[2]);
        Matrix4f expected = new Matrix4f()
                .translate(p)
                .rotateXYZ((float) Math.toRadians(degrees[0]),
                        (float) Math.toRadians(degrees[1]),
                        (float) Math.toRadians(degrees[2]))
                .translate(-p.x, -p.y, -p.z);

        for (int corner = 0; corner < 8; corner++) {
            float x = (corner & 1) == 0 ? from[0] : to[0];
            float y = (corner & 2) == 0 ? from[1] : to[1];
            float z = (corner & 4) == 0 ? from[2] : to[2];
            Vector3f drawn = rest.transformPosition(space(x - anchor[0], y - anchor[1], z - anchor[2]));
            Vector3f source = expected.transformPosition(space(x, y, z));
            assertEquals(source.x, drawn.x, 1e-5f, "x of corner " + corner);
            assertEquals(source.y, drawn.y, 1e-5f, "y of corner " + corner);
            assertEquals(source.z, drawn.z, 1e-5f, "z of corner " + corner);
        }
    }

    @Test
    void aTiltedCubeOffCentreStaysWhereItWasDrawn() {
        // upper_left_conduit: came out 5px right and 5px up.
        assertRestoresSource(
                new float[] {-2, 19, 6}, new float[] {0, 23, 7.94f},
                new float[] {-9, 13, -1},
                new float[] {-1, 21, 7}, new float[] {0, 0, 26.57f});
    }

    @Test
    void aCubeTurnedOnTwoAxesAboutAPivotOutsideItStaysWhereItWasDrawn() {
        // crest_housing_c: pivot above the cube, a block out of place.
        assertRestoresSource(
                new float[] {4.5f, 25.5f, 5.5f}, new float[] {11.5f, 28, 7.5f},
                new float[] {0, 23, -1.5f},
                new float[] {8, 31, 6.5f}, new float[] {180, 0, -135});
    }

    @Test
    void withoutAnAnchorTheTurnIsAboutThePivotAsWritten() {
        assertRestoresSource(
                new float[] {-2, 19, 6}, new float[] {0, 23, 7.94f},
                new float[] {0, 0, 0},
                new float[] {-1, 21, 7}, new float[] {0, 0, 26.57f});
    }
}
