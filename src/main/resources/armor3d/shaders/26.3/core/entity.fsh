#version 450
#extension GL_ARB_separate_shader_objects : require

// 26.3's entity fragment shader with obj³'s lighting in it. The varyings are
// entity.vsh's, location for location. Vanilla's order is kept: the face
// colour first (objmc_light.glsl), then overlay, lightmap, glint, the OIT
// accumulation and fog (calculateFinalColor); the alpha-only OIT variant
// writes no colour at all.
#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
// Guarded like entity.vsh: NO_CARDINAL_LIGHTING pipelines have no Lighting UBO.
#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#include <minecraft:light.glsl>
#endif
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif

layout(location = 4) in vec4 lightColor;
layout(location = 5) in vec4 overlayColor;
layout(location = 6) in vec2 texCoord;
#ifdef GLINT
layout(location = 7) in vec2 texCoordGlint;
#endif

layout(location = 8) in vec2 texCoord2;   // next animated-texture frame (armor/entity cross-fade)
layout(location = 9) in vec3 Pos;
layout(location = 10) in float transition;
layout(location = 11) flat in int isCustom;
layout(location = 12) flat in int isGUI;
layout(location = 13) flat in int isHand;
layout(location = 14) flat in int noshadow;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color, bool lit) {
    #ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #endif

    // White on an EMISSIVE variant; a custom face with noshadow is unlit.
    if (lit) color *= lightColor;

    #ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    // Matches BlendFuntion.GLINT
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    // objmc debug bypass (isCustom == 2 flag set in vertex shader)
    if (isCustom == 2) {
        #ifndef OIT_ALPHA_ONLY
        fragColor = vec4(overlayColor.rgb, 1.0);
        #endif
        return;
    }

    // Animated-texture cross-fade: transition > 0 only when the armour path set
    // texCoord2 and a fade; otherwise a plain sample.
    vec4 color = transition > 0.0
        ? mix(texture(Sampler0, texCoord), texture(Sampler0, texCoord2), transition)
        : texture(Sampler0, texCoord);

    #ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
    #endif

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef PER_FACE_LIGHTING
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
    #else
    vec4 faceVertexColor = vertexColor;
    #endif

    #ifdef DISSOLVE
    if (faceVertexColor.a < texture(DissolveMaskSampler, texCoord).a) {
        discard;
    }
    // The dissolve effect entirely replaces translucency
    faceVertexColor.a = 1.0;
    #endif

    //custom lighting
    #define ENTITY
    #include <minecraft:objmc_light.glsl>

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color, isCustom == 0 || noshadow == 0);
    #endif
}
