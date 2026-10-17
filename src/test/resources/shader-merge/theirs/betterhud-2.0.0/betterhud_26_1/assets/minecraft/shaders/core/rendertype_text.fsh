#version 150
#define HEIGHT_BIT 13
#define MAX_BIT 10
#define ADD_OFFSET 4095
#define DEFAULT_OFFSET 10
#define SHADER_VERSION 3
#moj_import <fog.glsl>
#if SHADER_VERSION >= 3
#moj_import <dynamictransforms.glsl>
in float sphericalVertexDistance;in float cylindricalVertexDistance;
#else
uniform vec4 ColorModulator;uniform float FogStart;uniform float FogEnd;uniform vec4 FogColor;in float vertexDistance;
#endif
uniform sampler2D Sampler0;in vec4 vertexColor;in vec2 texCoord0;out vec4 fragColor;void main() {vec4 texColor = texture(Sampler0, texCoord0);vec4 color = texColor * vertexColor * ColorModulator;if (color.a < 0.1) {discard;}
#if SHADER_VERSION >= 3
fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#else
fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
#endif
}