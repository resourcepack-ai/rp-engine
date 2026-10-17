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
out vec4 rpeScreen;
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
    // Zero unless a full-screen glyph's own branch sets it.
    rpeScreen = vec4(0.0);
// rpai-merge:end vertex-prologue
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
}

// rpai-merge:begin vertex-epilogue v1
// rpai-merge:renames main rpe_vertex_main
void main() {
    rpe_vertex_main();

    // Perspective text (signs/nameplates) must never become a HUD.
    if (abs(ProjMat[3][3] - 1.0) < 0.001) {
      ivec3 mark = ivec3(floor(Color.rgb * 255.0 + 0.5));
      vec2 gui = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);
      {
      vec2 delta = vec2(0.0, 0.0);
      bool matched = mark.r == 254 && mark.g == 0 && mark.b == 1;
      if (mark.r == 240 && mark.g == 0 && mark.b == 8) {
        delta.y += 0.000;
        vertexColor = vec4(0.533, 0.800, 1.000, Color.a);
        rawColor = vec4(0.0); // Text always takes the vanilla fragment path.
        matched = true;
      } else if ((mark.r == 60 || mark.r == 59) && (mark.g == 0 || mark.g == -1) && (mark.b == 2 || mark.b == 1)) {
        delta.y += 0.000;
        // A quarter of the authored colour, which is what a shadow is. Restored
        // rather than left as the dimmed MARK, or the ghost would be a shadow of
        // the address instead of a shadow of the label.
        vertexColor = vec4(0.133, 0.200, 0.250, Color.a);
        rawColor = vec4(0.0);
        matched = true;
      }
if (mark.r == 240 && mark.g == 0 && mark.b == 16) {
        delta.y += -12.000;
        vertexColor = vec4(1.000, 0.400, 0.400, Color.a);
        rawColor = vec4(0.0); // Text always takes the vanilla fragment path.
        matched = true;
      } else if ((mark.r == 60 || mark.r == 59) && (mark.g == 0 || mark.g == -1) && (mark.b == 4 || mark.b == 3)) {
        delta.y += -12.000;
        // A quarter of the authored colour, which is what a shadow is. Restored
        // rather than left as the dimmed MARK, or the ghost would be a shadow of
        // the address instead of a shadow of the label.
        vertexColor = vec4(0.250, 0.100, 0.100, Color.a);
        rawColor = vec4(0.0);
        matched = true;
      }
if (mark.r == 240 && mark.g == 0 && mark.b == 24) {
        delta.y += 0.000;
        vertexColor = vec4(1.000, 1.000, 1.000, Color.a);
        rawColor = vec4(0.0); // Text always takes the vanilla fragment path.
        matched = true;
      } else if ((mark.r == 60 || mark.r == 59) && (mark.g == 0 || mark.g == -1) && (mark.b == 6 || mark.b == 5)) {
        delta.y += 0.000;
        // A quarter of the authored colour, which is what a shadow is. Restored
        // rather than left as the dimmed MARK, or the ghost would be a shadow of
        // the address instead of a shadow of the label.
        vertexColor = vec4(0.250, 0.250, 0.250, Color.a);
        rawColor = vec4(0.0);
        matched = true;
      }
if (mark.r == 240 && mark.g == 0 && mark.b == 32) {
        delta.y += 0.000;
        vertexColor = vec4(0.106, 0.106, 0.094, Color.a);
        rawColor = vec4(0.0); // Text always takes the vanilla fragment path.
        matched = true;
      } else if ((mark.r == 60 || mark.r == 59) && (mark.g == 0 || mark.g == -1) && (mark.b == 8 || mark.b == 7)) {
        delta.y += 0.000;
        // A quarter of the authored colour, which is what a shadow is. Restored
        // rather than left as the dimmed MARK, or the ghost would be a shadow of
        // the address instead of a shadow of the label.
        vertexColor = vec4(0.026, 0.026, 0.024, Color.a);
        rawColor = vec4(0.0);
        matched = true;
      }
if (mark.r == 240 && mark.g == 0 && mark.b == 40) {
        delta.y += 0.000;
        vertexColor = vec4(0.302, 1.000, 0.533, Color.a);
        rawColor = vec4(0.0); // Text always takes the vanilla fragment path.
        matched = true;
      } else if ((mark.r == 60 || mark.r == 59) && (mark.g == 0 || mark.g == -1) && (mark.b == 10 || mark.b == 9)) {
        delta.y += 0.000;
        // A quarter of the authored colour, which is what a shadow is. Restored
        // rather than left as the dimmed MARK, or the ghost would be a shadow of
        // the address instead of a shadow of the label.
        vertexColor = vec4(0.075, 0.250, 0.133, Color.a);
        rawColor = vec4(0.0);
        matched = true;
      }
      if (matched) gl_Position.xy += (ProjMat * vec4(delta, 0.0, 0.0)).xy;
    }
{
      vec2 delta = vec2(0.0);
      bool matched = false;
      if (mark.r == 254 && mark.g == 1 && mark.b == 1) {
        vec2 corner = step(3.5, floor(textureLod(Sampler0, UV0, 0.0).rg * 255.0 + 0.5));
        gl_Position = vec4((corner.x * 2.0 - 1.0) * gl_Position.w, (1.0 - corner.y * 2.0) * gl_Position.w, gl_Position.z, gl_Position.w);
        rpeScreen = vec4(corner * gui, gui);
      }
      
      if (matched) gl_Position.xy += (ProjMat * vec4(delta, 0.0, 0.0)).xy;
    }
    }
}
// rpai-merge:end vertex-epilogue
