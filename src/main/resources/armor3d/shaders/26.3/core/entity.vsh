#version 450
#extension GL_KHR_shader_subgroup_basic: enable
#extension GL_KHR_shader_subgroup_ballot: enable

// 26.3's entity shader with obj³'s decode in it: the 26.1 port's main, on the
// 26.3 file's interface. 26.3 compiles every shader for Vulkan, so every
// varying sits at an explicit location (the fragment shader reads the same
// numbers), files come in by #include, a vertex is counted with
// gl_VertexIndex (objmc_main.glsl), and the other corners of a quad are read
// with subgroupBroadcast, the only lane read the OpenGL backend can translate
// (objmc_tools.glsl). Vanilla's own varyings keep their numbers
// (0-7); the decode's start at 8.
//
// The same guards as vanilla: NO_CARDINAL_LIGHTING pipelines have no Lighting
// UBO, and the OIT alpha-only variant is given no overlay or lightmap sampler.
#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#include <minecraft:light.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:globals.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

uniform sampler2D Sampler0;
#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler1;
#endif
#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
#else
layout(location = 2) out vec4 vertexColor;
#endif

// Written on every variant (white where the variant has no sampler for it):
// the decode's own lighting reads both whatever the pipeline is.
layout(location = 4) out vec4 lightColor;
layout(location = 5) out vec4 overlayColor;
layout(location = 6) out vec2 texCoord;
#ifdef GLINT
layout(location = 7) out vec2 texCoordGlint;
#endif

// texCoord2 is written by objmc_main.glsl's animated-texture path (issue #9).
layout(location = 8) out vec2 texCoord2;
layout(location = 9) out vec3 Pos;
layout(location = 10) out float transition;
layout(location = 11) flat out int isCustom;
layout(location = 12) flat out int isGUI;
layout(location = 13) flat out int isHand;
layout(location = 14) flat out int noshadow;

#include <minecraft:objmc_tools.glsl>

void main() {
    Pos = Position;
    texCoord = UV0;
    lightColor = vec4(1);
    overlayColor = vec4(1);
#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif
#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif
#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    lightColor = sample_lightmap(Sampler2, UV2);
#endif
#ifdef APPLY_TEXTURE_MATRIX
    texCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
#ifdef GLINT
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

    //objmc
    #define ENTITY
    #include <minecraft:objmc_main.glsl>

    gl_Position = ProjMat * ModelViewMat * vec4(Pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Pos);
    cylindricalVertexDistance = fog_cylindrical_distance(Pos);
}
