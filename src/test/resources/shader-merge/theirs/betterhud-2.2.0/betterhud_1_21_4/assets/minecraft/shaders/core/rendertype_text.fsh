#version 150
#define HEIGHT_BIT 13
#define MAX_BIT 10
#define ADD_OFFSET 4095
#define DEFAULT_OFFSET 10
#moj_import <fog.glsl>
uniform vec4 ColorModulator;uniform float FogStart;uniform float FogEnd;uniform vec4 FogColor;in float vertexDistance;uniform sampler2D Sampler0;in vec4 vertexColor;in vec2 texCoord0;out vec4 fragColor;void main() {
#ifdef IS_GRAYSCALE
vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
vec4 texColor = texture(Sampler0, texCoord0);
#endif
#ifdef IS_SEE_THROUGH
vec4 color = texColor * vertexColor;
#else
vec4 color = texColor * vertexColor * ColorModulator;
#endif
if (color.a < 0.1) {discard;}
#ifdef IS_SEE_THROUGH
fragColor = color * ColorModulator;
#elif defined(IS_GUI)
fragColor = color;
#else
fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
#endif
}