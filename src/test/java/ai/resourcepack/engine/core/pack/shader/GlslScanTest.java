package ai.resourcepack.engine.core.pack.shader;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlslScanTest {

    /** BetterHud's shape: every statement that is not a directive joined onto one line. */
    private static final String MINIFIED = "#version 330\n"
            + "#define HEIGHT_BIT 13\n"
            + "#if !defined(IS_GUI)\n"
            + "#moj_import <minecraft:fog.glsl>\n"
            + "#endif\n"
            + "#moj_import <projection.glsl>\n"
            + "in vec3 Position;in vec4 Color;in vec2 UV0;\n"
            + "#if !defined(IS_GUI)\n"
            + "uniform sampler2D Sampler0;out float sphericalVertexDistance;\n"
            + "#endif\n"
            + "out vec4 vertexColor;out vec2 texCoord0;out float applyColor;"
            + "bool range(float t, float m1, float m2) {return t >= m1 && t <= m2;}"
            + "void main() {vec3 pos = Position; // a comment main() {\n"
            + "if (pos.y > 0) {pos.y = 0;}gl_Position = ProjMat * vec4(pos, 1.0);}\n";

    /** What two includes declare, as the engine's table or a pack would say. */
    private static final GlslScan.Includes INCLUDES = file -> switch (file) {
        case "projection.glsl" -> Set.of("ProjMat", "Projection");
        case "fog.glsl" -> Set.of("FogColor", "apply_fog");
        default -> null;
    };

    @Test
    void readsAMinifiedFile() {
        GlslScan scan = GlslScan.of(MINIFIED, INCLUDES);
        assertEquals(1, scan.mainCount());
        assertEquals(List.of("vec4"), scan.declarationsOf("Color").stream().map(d -> d.signature().replace("in ", "")).toList());
        assertTrue(scan.declarationsOf("Sampler0").get(0).conditional());
        assertFalse(scan.declarationsOf("vertexColor").get(0).conditional());
        assertEquals(GlslScan.Kind.FUNCTION, scan.declarationsOf("range").get(0).kind());
        assertFalse(scan.provided().contains("pos"), "a local is not a top-level name");
        assertTrue(scan.provided().contains("ProjMat"), "projection.glsl's names count");
    }

    @Test
    void knowsWhatIsImportedAndWhetherOnlyUnderACondition() {
        GlslScan scan = GlslScan.of(MINIFIED, INCLUDES);
        assertTrue(scan.importOf("fog.glsl").conditional());
        assertFalse(scan.importOf("projection.glsl").conditional());
        assertNull(scan.importOf("globals.glsl"));
        assertEquals(Boolean.FALSE, scan.importedConditionally("ProjMat"));
        assertEquals(Boolean.TRUE, scan.importedConditionally("apply_fog"));
        assertNull(scan.importedConditionally("GameTime"));
    }

    @Test
    void settlesWhatTheFileItselfDecides() {
        // BetterHud's branch per version: a constant it defines, tested by #if.
        String source = "#version 150\n#define SHADER_VERSION 2\n#moj_import <fog.glsl>\n"
                + "#if SHADER_VERSION >= 3\nin float sphericalVertexDistance;\n#else\nuniform vec4 FogColor;\n#endif\n"
                + "#ifdef IS_GUI\nuniform float Gui;\n#else\nuniform float World;\n#endif\n"
                + "#if 0\nuniform float Never;\n#endif\nvoid main() {}\n";
        GlslScan scan = GlslScan.of(source, INCLUDES);
        assertTrue(scan.declarationsOf("sphericalVertexDistance").isEmpty(), "a branch the file rules out is not read");
        assertFalse(scan.declarationsOf("FogColor").get(0).conditional(), "the branch it rules in is unconditional");
        assertTrue(scan.declarationsOf("Never").isEmpty());
        assertEquals("(defined(IS_GUI))", scan.declarationsOf("Gui").get(0).condition());
        assertEquals("(!(defined(IS_GUI)))", scan.declarationsOf("World").get(0).condition(), "an #else is the negation");
        assertEquals(List.of("FogColor (also in fog.glsl)"), scan.declaredTwice(), "and so the file cannot compile");
    }

    @Test
    void findsMainOutsideComments() {
        String source = "// void main() {\nvoid main(){x();}\n/* void main() { */";
        GlslScan scan = GlslScan.of(source);
        assertEquals(1, scan.mainCount());
        int[] at = scan.main();
        assertNotNull(at);
        assertEquals("void main(){", source.substring(at[0], at[1]));
    }

    @Test
    void readsUniformBlocksAndTheirMembers() {
        GlslScan scan = GlslScan.of("layout(std140) uniform Fog {\n vec4 FogColor;\n float FogStart;\n};\n"
                + "struct Light { vec3 dir; };\nconst float A = 1.0, B = 2.0;\nuniform float GameTime;\n"
                + "layout(location = 0) in vec3 Position;\n");
        Set<String> names = scan.provided();
        assertTrue(names.containsAll(Set.of("Fog", "FogColor", "FogStart", "Light", "A", "B", "GameTime", "Position")), names.toString());
        assertFalse(names.contains("dir"), "a struct's members are not names of the file");
        assertEquals("in vec3", scan.declarationsOf("Position").get(0).signature());
    }
}
