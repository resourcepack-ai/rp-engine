package ai.resourcepack.engine.core.armor3d;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shader encoding and the separation pass, held to Studio's.
 *
 * <p>Every hash below was computed by running Studio's own separation pass and
 * shader encoder over exactly this fixture (the same parts, in the same order,
 * with the same faces and the same 32x32 atlas) and hashing what came out: the
 * decoded RGBA of each layer texture and the bytes of each equipment asset. A
 * shader reads those textures by byte position, so "the same picture" is not
 * close enough; the same bytes is the test. Change either side and re-derive
 * them from Studio's code, never from this one.
 */
class Armor3dShaderTest {

    private static final int S = 32;

    /** One fixture cube: name, bone, from, to, rotation, origin, faces. */
    private record Cube(String name, String bone, double[] from, double[] to, double[] rotation, double[] origin,
                        Map<String, double[]> faces) {
    }

    private static Map<String, double[]> all() {
        Map<String, double[]> faces = new LinkedHashMap<>();
        faces.put("north", new double[]{0, 0, 8, 12});
        faces.put("south", new double[]{8, 0, 16, 12});
        faces.put("east", new double[]{16, 0, 20, 12});
        faces.put("west", new double[]{20, 0, 24, 12});
        faces.put("up", new double[]{0, 12, 8, 16});
        faces.put("down", new double[]{8, 12, 16, 16});
        return faces;
    }

    private static Map<String, double[]> faces(Object... pairs) {
        Map<String, double[]> faces = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            faces.put((String) pairs[i], (double[]) pairs[i + 1]);
        }
        return faces;
    }

    private static double[] v(double... values) {
        return values;
    }

    private static List<Cube> fixture() {
        return List.of(
                new Cube("helm", "head", v(-4.5, 24, -4.5), v(4.5, 32.5, 4.5), null, null, all()),
                new Cube("plate", "body", v(-4.5, 12, -2.5), v(4.5, 24, 2.5), null, null, all()),
                new Cube("trim", "body", v(-2, 14, -2.5), v(2, 16, -2.5), null, null,
                        faces("north", v(24, 0, 28, 2), "south", v(28, 0, 32, 2))),
                new Cube("badge", "body", v(-1, 18, -3), v(1, 20, -2.5), null, null,
                        faces("north", v(24, 4, 26, 6), "up", v(26, 4, 28, 5))),
                new Cube("badge2", "body", v(-0.5, 18.5, -3), v(0.5, 19.5, -2.8), null, null,
                        faces("north", v(28, 4, 29, 5))),
                new Cube("pad", "right_arm", v(-9, 21, -3), v(-4, 24, 3), v(0, 0, 30), v(-6.5, 22.5, 0), all()),
                new Cube("tilt", "left_arm", v(4, 14, -2), v(8, 17, 2), v(10, -20, 35), v(6, 15.5, 0),
                        faces("west", v(0, 16, 4, 19), "east", v(4, 16, 8, 19), "up", v(8, 16, 12, 20))),
                new Cube("belt", "waist", v(-4.5, 11, -2.6), v(4.5, 13, 2.6), null, null, all()),
                new Cube("thigh", "left_leg", v(0, 5, -2.2), v(4.2, 11, 2.2), null, null, all()),
                new Cube("shin", "right_leg", v(-4.2, 5, -2.2), v(0, 11, 2.2), null, null, all()),
                new Cube("boot", "right_foot", v(-4.1, 0, -2.5), v(0.1, 3, 2.5), null, null, all()),
                new Cube("toe", "left_foot", v(0, 0, -3.5), v(4, 1.5, -2.5), null, null,
                        faces("north", v(16, 20, 20, 22), "up", v(20, 20, 24, 21))));
    }

    /** The atlas: pixel (x, y) is (8x, 8y, 4(x + y)), transparent wherever x + y is a multiple of 7. */
    private static byte[] atlas() {
        byte[] data = new byte[S * S * 4];
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int i = (y * S + x) * 4;
                data[i] = (byte) (x * 8);
                data[i + 1] = (byte) (y * 8);
                data[i + 2] = (byte) ((x + y) * 4);
                data[i + 3] = (byte) ((x + y) % 7 == 0 ? 0 : 255);
            }
        }
        return data;
    }

    private static List<Armor3dSeparation.Part> separated(List<Cube> cubes) {
        List<Armor3dSeparation.Part> parts = new ArrayList<>();
        for (Cube cube : cubes) {
            parts.add(new Armor3dSeparation.Part(cube.from().clone(), cube.to().clone(), cube.rotation(),
                    cube.origin(), cube.faces().keySet()));
        }
        Armor3dSeparation.separate(parts);
        return parts;
    }

    private static Armor3dShaderEncoder.Encoded encode(List<Cube> cubes, List<Armor3dSeparation.Part> boxes) {
        List<Armor3dShaderEncoder.Part> parts = new ArrayList<>();
        for (int i = 0; i < cubes.size(); i++) {
            Cube cube = cubes.get(i);
            Map<String, Armor3dShaderEncoder.FaceArt> faces = new LinkedHashMap<>();
            cube.faces().forEach((name, rect) -> faces.put(name, new Armor3dShaderEncoder.FaceArt(rect, 0)));
            parts.add(new Armor3dShaderEncoder.Part(cube.bone(), boxes.get(i).from(), boxes.get(i).to(),
                    cube.rotation(), cube.origin(), faces));
        }
        return Armor3dShaderEncoder.encode("minecraft", "rpai_fixture", parts, S, S, atlas());
    }

    @Test
    void separationMovesWhatStudiosMoves() {
        List<Armor3dSeparation.Part> parts = separated(fixture());

        // Studio's output for the same set: the flat trim given thickness, the
        // smaller badge stepped out in front of the bigger one, the belt's
        // sides stepped out past the plate's.
        assertArrayEquals(v(-2, 14, -2.56), parts.get(2).from(), 1e-12);
        assertArrayEquals(v(2, 16, -2.44), parts.get(2).to(), 1e-12);
        assertArrayEquals(v(-1, 18, -3), parts.get(3).from(), 1e-12);
        assertArrayEquals(v(-0.5, 18.5, -3.06), parts.get(4).from(), 1e-12);
        assertArrayEquals(v(-4.56, 11, -2.6), parts.get(7).from(), 1e-12);
        assertArrayEquals(v(4.56, 13, 2.6), parts.get(7).to(), 1e-12);
        // Everything else exactly as drawn.
        for (int i : new int[]{0, 1, 5, 6, 8, 9, 10, 11}) {
            assertArrayEquals(fixture().get(i).from(), parts.get(i).from(), 0, fixture().get(i).name());
            assertArrayEquals(fixture().get(i).to(), parts.get(i).to(), 0, fixture().get(i).name());
        }
    }

    @Test
    void theEncodingIsStudiosByteForByte() throws IOException {
        List<Cube> cubes = fixture();
        Armor3dShaderEncoder.Encoded encoded = encode(cubes, separated(cubes));

        assertEquals(52, encoded.nfaces());
        assertEquals("32x128 95842b0e1f2c31a55566289cb52947205d578d2d95bd5fa50a1d4cc0e8dd6919",
                pixels(encoded.baseFiles().get("assets/minecraft/textures/entity/equipment/humanoid/rpai_fixture.png")));
        assertEquals("32x64 666578854864f3134fa59c633952f8ea1c89091f8d0bc71dfa7d1fc7acc34cff",
                pixels(encoded.baseFiles().get(
                        "assets/minecraft/textures/entity/equipment/humanoid_leggings/rpai_fixture.png")));
        assertEquals(2, encoded.baseFiles().size());

        Map<String, String> assets = new HashMap<>();
        encoded.overlayFiles().forEach((path, bytes) -> assets.put(path, sha256(bytes)));
        assertEquals(Map.of(
                "assets/minecraft/equipment/rpai_fixture_helmet.json",
                "10ad59ea22d3c8917e684cc4023eafaeb7e2a0127e63817eba290c202656a4fa",
                "assets/minecraft/equipment/rpai_fixture_chestplate.json",
                "72543fecde619f57f9d4acf76b75359de90494db6f02dff8bb4f885fb22e3fc8",
                "assets/minecraft/equipment/rpai_fixture_leggings.json",
                "9d6f1a0c4686eba5fddc345cb80dbb6ea5281b73dec0b850423970c29cea4c65",
                "assets/minecraft/equipment/rpai_fixture_boots.json",
                "17dfc3a5b80bca19801fbc860a6dcaed014f61f1fd59feb601cb6c4b18bf93d5"), assets);
        assertEquals("minecraft:rpai_fixture_leggings", encoded.equipment().get(Armor3dSet.Piece.LEGGINGS));
    }

    @Test
    void theEncoderAloneIsStudiosToo() throws IOException {
        // The same set unseparated, so a fault in the encoder cannot hide
        // behind one in the separation pass or the other way round.
        List<Cube> cubes = fixture();
        List<Armor3dSeparation.Part> asDrawn = new ArrayList<>();
        for (Cube cube : cubes) {
            asDrawn.add(new Armor3dSeparation.Part(cube.from(), cube.to(), cube.rotation(), cube.origin(),
                    cube.faces().keySet()));
        }
        Armor3dShaderEncoder.Encoded encoded = encode(cubes, asDrawn);

        assertEquals("32x128 e8a0cedbcf65e58a2a19a264f7381e7ada7c7a1b0358400bb75b50dc781398b9",
                pixels(encoded.baseFiles().get("assets/minecraft/textures/entity/equipment/humanoid/rpai_fixture.png")));
        assertEquals("32x64 42d8e6e1e9b937ec44ef2aa9ae68cde8a301fab11a8f167e93fc2c444efb8d0b",
                pixels(encoded.baseFiles().get(
                        "assets/minecraft/textures/entity/equipment/humanoid_leggings/rpai_fixture.png")));
    }

    @Test
    void toFixedRoundsAsJavaScriptDoes() {
        assertEquals("0.000", Armor3dShaderEncoder.toFixed(-0.0, 3));
        assertEquals("-0.000", Armor3dShaderEncoder.toFixed(-1e-17, 3));
        assertEquals("1.000", Armor3dShaderEncoder.toFixed(0.9999995, 3));
        assertEquals("-0.281250", Armor3dShaderEncoder.toFixed(-0.28125, 6));
    }

    /** "WxH sha256" of a PNG's decoded RGBA, rows top to bottom. */
    private static String pixels(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int i = (y * image.getWidth() + x) * 4;
                rgba[i] = (byte) (argb >> 16);
                rgba[i + 1] = (byte) (argb >> 8);
                rgba[i + 2] = (byte) argb;
                rgba[i + 3] = (byte) (argb >>> 24);
            }
        }
        return image.getWidth() + "x" + image.getHeight() + " " + sha256(rgba);
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
