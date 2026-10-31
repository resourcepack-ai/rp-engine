package ai.resourcepack.engine.core.cape;

import ai.resourcepack.engine.core.emote.CapeSway;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeModelTest {

    /**
     * Studio's {@code capeModel("ns:capes/x")}, verbatim. A pushed cape is
     * drawn by Studio's model and a content folder's by this one, so the two
     * have to be the same cape. If Studio's changes, paste its new output here.
     */
    private static final String STUDIO =
            "{\"textures\":{\"cape\":\"ns:capes/x\",\"particle\":\"ns:capes/x\"},\"elements\":[{\"from\":[2.85,-8.15,6.85],"
                    + "\"to\":[13.15,8.15,8.15],\"faces\":{\"north\":{\"uv\":[0.25,0.5,2.75,8.5],\"texture\":\"#cape\"},"
                    + "\"south\":{\"uv\":[3,0.5,5.5,8.5],\"texture\":\"#cape\"},\"east\":{\"uv\":[0,0.5,0.25,8.5],\"texture\":\"#cape\"},"
                    + "\"west\":{\"uv\":[2.75,0.5,3,8.5],\"texture\":\"#cape\"},\"up\":{\"uv\":[2.75,0,0.25,0.5],\"texture\":\"#cape\"},"
                    + "\"down\":{\"uv\":[5.25,0,2.75,0.5],\"texture\":\"#cape\"}}}]}";

    @Test
    void theModelIsStudios() {
        assertEquals(STUDIO, CapeModel.json("ns:capes/x"));
    }

    @Test
    void aStillCapeHangsBehindTheBack() {
        // Vanilla's rest lean is six degrees; the hem of a cape at rest must
        // be BEHIND the hinge (-Z in the wearer's frame) and below it.
        Vector3f hem = new CapeSway().wearerTurn().transform(new Vector3f(0, -16, 0));
        assertTrue(hem.z < 0, "hem at z " + hem.z);
        assertTrue(hem.y < -15, "hem at y " + hem.y);
    }

    @Test
    void anHdTextureIsACapeAndAnOddOneIsNot() {
        assertArrayEquals(new int[] {128, 64}, CapeDefinitions.pngSize(png(128, 64)));
        assertNull(CapeDefinitions.pngSize(new byte[] {1, 2, 3}));
    }

    @Test
    void aPushedCapeIsLabelledStudio() {
        assertEquals("studio:abc", new Cape("abc", null, "cape:abc", null, null).label());
        assertEquals("myserver:founder", new Cape("myserver:founder", "Founder", "cape:myserver/founder",
                "myserver:cape/", null).label());
        assertEquals("myserver:cape/founder",
                new Cape("myserver:founder", "Founder", "cape:myserver/founder", "myserver:cape/", null)
                        .art().itemModel("cape:myserver/founder"));
    }

    /** A PNG signature and IHDR with this size; nothing after it is read. */
    private static byte[] png(int w, int h) {
        byte[] b = new byte[24];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        System.arraycopy(sig, 0, b, 0, sig.length);
        b[16] = (byte) (w >>> 24);
        b[17] = (byte) (w >>> 16);
        b[18] = (byte) (w >>> 8);
        b[19] = (byte) w;
        b[20] = (byte) (h >>> 24);
        b[21] = (byte) (h >>> 16);
        b[22] = (byte) (h >>> 8);
        b[23] = (byte) h;
        return b;
    }
}
