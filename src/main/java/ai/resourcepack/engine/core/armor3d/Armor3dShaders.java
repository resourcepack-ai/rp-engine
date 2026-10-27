package ai.resourcepack.engine.core.armor3d;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which clients draw an authored 3D armour set THEMSELVES, and the core
 * shaders that let them.
 *
 * <p>A core shader is written against one version's renderer, so each range is
 * a PORT, shipped in a pack overlay that only clients of those resource pack
 * formats load. <strong>A range ships only once it has been seen working in a
 * real client</strong>, because a core shader that fails to compile does not
 * degrade: the client refuses the WHOLE pack. Studio keeps the same table and
 * the same rule; only its verified range is here.
 *
 * <p>The shader files under {@code armor3d/shaders/} in this jar are Studio's
 * port of obj³'s armour shaders (github.com/JagerMeistars/obj-cubed, MIT,
 * (c) 2022 Godlander and contributors) for that range, copied byte for byte.
 * They are the decode half of {@link Armor3dShaderEncoder} and must change with
 * it, and with Studio's copy: a pushed pack and this engine's pack may both be
 * on one client, overriding the same entity shader.
 */
final class Armor3dShaders {

    /**
     * One range.
     *
     * @param directory the overlay directory, Studio's name for the same range
     * @param minFormat   the oldest resource pack format it is for
     * @param maxFormat   the newest, inclusive
     * @param minProtocol the oldest client protocol it is for
     * @param maxProtocol the newest, inclusive
     * @param port      the shader set's folder under {@code armor3d/shaders/}
     */
    record Range(String directory, int minFormat, int maxFormat, int minProtocol, int maxProtocol, String port) {
    }

    /** 26.1 - 26.2: obj³'s own target, where the technique was proven in game. */
    static final List<Range> RANGES = List.of(
            new Range("rpai_armor3d_26_1", 84, 88, 775, 776, "26.1"));

    /** The files of every port, relative to {@code assets/minecraft/shaders/}. */
    private static final List<String> PORT_FILES = List.of(
            "core/entity.fsh", "core/entity.vsh",
            "include/objmc_light.glsl", "include/objmc_main.glsl", "include/objmc_tools.glsl");

    /** The licence that travels with every copy of the shaders. */
    static final String LICENCE_FILE = "OBJCUBED-LICENSE.txt";

    private Armor3dShaders() {
    }

    /** The protocol ranges a set drawn by these shaders is drawn by, for {@link Armor3dSet#drawnBy}. */
    static List<int[]> protocols() {
        return RANGES.stream().map(r -> new int[]{r.minProtocol(), r.maxProtocol()}).toList();
    }

    /** What one range's overlay holds besides the equipment assets: the shaders and the licence. */
    static Map<String, byte[]> files(Range range) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String file : PORT_FILES) {
            out.put("assets/minecraft/shaders/" + file, resource(range.port() + "/" + file));
        }
        out.put(LICENCE_FILE, resource(LICENCE_FILE));
        return out;
    }

    private static byte[] resource(String path) {
        try (InputStream in = Armor3dShaders.class.getResourceAsStream("/armor3d/shaders/" + path)) {
            if (in == null) {
                throw new IllegalStateException("The jar is missing armor3d/shaders/" + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
