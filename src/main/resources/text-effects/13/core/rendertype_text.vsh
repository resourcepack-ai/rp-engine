#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

// rpai-merge:begin vertex-declarations v1
out vec4 rawColor;
out vec4 rtxA0;
out vec4 rtxA2;
out vec4 rtxW;
flat out vec3 rtxFlat;
uniform sampler2D Sampler0;
// rpai-merge:end vertex-declarations
// rpai-merge:begin vertex-vanilla v1
// #moj_import <fog.glsl>
// in vec3 Position;
// in vec4 Color;
// in vec2 UV0;
// in ivec2 UV2;
// uniform sampler2D Sampler2;
// uniform mat4 ModelViewMat;
// uniform mat4 ProjMat;
// uniform mat3 IViewRotMat;
// uniform int FogShape;
// out float vertexDistance;
// out vec4 vertexColor;
// out vec2 texCoord0;
// rpai-merge:end vertex-vanilla

void rpe_vertex_main() {
// rpai-merge:begin vertex-prologue v1
    // Un-multiplied, for the gate. See patch.ts.
    rawColor = Color;
// rpai-merge:end vertex-prologue
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
}

// rpai-merge:begin vertex-epilogue v1
// rpai-merge:renames main rpe_vertex_main
@@RPE_TEXT_EFFECT_PADS@@
// Grows a glyph's quad by RTX_PAD_SCALE GUI units per letter-pixel of room: a
// glyph is drawn at whatever scale its text is (four for a title), and a
// vertex cannot see the other corners to measure it. The fragment stage
// throws away what the effect did not ask for.
const float RTX_PAD_SCALE = 4.0;

// How wide the inked part of this corner's letter is, read off the atlas.
// Assumes an 8x8 cell, which the game's default font has.
float rtx_ink_width(int corner) {
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    vec2 cell = vec2(8.0) / atlas;
    vec2 topLeft = UV0 - vec2(corner >= 2 ? cell.x : 0.0, (corner == 1 || corner == 2) ? cell.y : 0.0);
    for (int column = 7; column > 0; column--) {
        for (int row = 0; row < 8; row++) {
            vec2 at = topLeft + (vec2(float(column), float(row)) + 0.5) / atlas;
            if (textureLod(Sampler0, at, 0.0).a > 0.0) return float(column + 1);
        }
    }
    return 1.0;
}

// Runs after vanilla's main, so everything vanilla set is already set; only a
// letter in a text effect's colour is changed.
void rtx_vertex() {
    rtxA0 = vec4(0.0);
    rtxA2 = vec4(0.0);
    rtxW = vec4(0.0, 0.0, Position.xy);
    rtxFlat = vec3(0.0, 8.0, Color.a);
    // GUI text only, which is drawn with an orthographic projection.
    if (ProjMat[3][3] != 1.0) return;
    ivec3 rgb = ivec3(round(Color.rgb * 255.0));
    // A quarter of an effect's colour is its shadow, which the effect redraws
    // itself: this copy is moved off the screen.
    if (rgb.r == 63 && rgb.b == 62 && rtx_known(rgb.g)) {
        gl_Position = vec4(-4.0, -4.0, 0.0, 1.0);
        return;
    }
    if (rgb.r != 252 || rgb.b != 248 || (rgb.g & 3) != 0 || !rtx_known(rgb.g >> 2)) return;
    int id = rgb.g >> 2;
    // The game's corner order for a glyph: top-left, bottom-left,
    // bottom-right, top-right.
    int corner = gl_VertexID % 4;
    vec4 room = rtx_pad(id) * RTX_PAD_SCALE;
    vec3 grown = Position;
    grown.x += corner < 2 ? -room.x : room.z;
    grown.y += (corner == 0 || corner == 3) ? -room.y : room.w;
    gl_Position = ProjMat * ModelViewMat * vec4(grown, 1.0);
    rtxW = vec4(corner == 0 ? 1.0 : 0.0, corner == 2 ? 1.0 : 0.0, grown.xy);
    if (corner == 0) rtxA0 = vec4(Position.xy, UV0);
    if (corner == 2) rtxA2 = vec4(Position.xy, UV0);
    rtxFlat = vec3(float(id), rtx_ink_width(corner), Color.a);
}
void main() {
    rpe_vertex_main();

    // Perspective text (signs/nameplates) must never become a HUD.
    if (abs(ProjMat[3][3] - 1.0) < 0.001) {
      ivec3 mark = ivec3(floor(Color.rgb * 255.0 + 0.5));
      vec2 gui = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);
      
    }
    rtx_vertex();
}
// rpai-merge:end vertex-epilogue
