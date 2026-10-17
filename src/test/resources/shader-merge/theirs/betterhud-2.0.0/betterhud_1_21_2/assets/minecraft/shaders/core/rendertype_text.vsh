#version 150
#define HEIGHT_BIT 13
#define MAX_BIT 10
#define ADD_OFFSET 4095
#define DEFAULT_OFFSET 10
#define SHADER_VERSION 0
#moj_import <fog.glsl>
#moj_import <sample_lightmap.glsl>
#if SHADER_VERSION >= 3
#moj_import <dynamictransforms.glsl>
#moj_import <projection.glsl>
#moj_import <globals.glsl>
out float sphericalVertexDistance;out float cylindricalVertexDistance;
#else
uniform mat4 ProjMat;uniform mat4 ModelViewMat;uniform int FogShape;out float vertexDistance;uniform vec2 ScreenSize;uniform float GameTime;
#endif
in vec3 Position;in vec4 Color;in vec2 UV0;in ivec2 UV2;uniform sampler2D Sampler0;uniform sampler2D Sampler2;uniform vec3 ChunkOffset;out vec4 vertexColor;out vec2 texCoord0;out float applyColor;bool range(float t, float m1, float m2) {return t >= m1 && t <= m2;}bool range(vec2 t, vec2 m1, vec2 m2) {return range(t.x, m1.x, m2.x) && range(t.y, m1.y, m2.y);}bool range(vec3 t, vec3 m1, vec3 m2) {return range(t.x, m1.x, m2.x) && range(t.y, m1.y, m2.y) && range(t.z, m1.z, m2.z);}bool checkElement(float z) {if (z == 0) return true; else if (z == 1000) return true; else if (z == -90) return true; else if (z == 2800) return true; return false;}float fogDistance(vec3 pos, int shape) {if (shape == 0) {return length(pos);} else {float distXZ = length(pos.xz);float distY = abs(pos.y);return max(distXZ, distY);}}void main() {vec3 pos = Position;vec2 ui = ceil(2 / vec2(ProjMat[0][0], -ProjMat[1][1]));vec2 uiScreen = ui / ScreenSize;vec3 color = Color.xyz;applyColor = 0;vertexColor = Color * sample_lightmap(Sampler2, UV2);if (pos.y >= ui.y && ProjMat[3].x == -1) {int bit = int(pos.y) >> HEIGHT_BIT;if (((bit >> MAX_BIT) & 1) == 1) {int id = bit - (1 << MAX_BIT);pos.x -= 0.5 * ui.x;pos.y -= (bit << HEIGHT_BIT) + ADD_OFFSET + DEFAULT_OFFSET;float xGui = 0;float yGui = 0;float layer = 0;float opacity = 1;bool outline = false;int property = 0;switch (id) {case 1:break;case 2:xGui = ui.x * 35.0 / 100.0;yGui = ui.y * 100.0 / 100.0;break;case 3:xGui = ui.x * 50.0 / 100.0;break;case 4:xGui = ui.x * 65.0 / 100.0;yGui = ui.y * 100.0 / 100.0;break;}
#if SHADER_VERSION < 2
vertexColor = (checkElement(pos.z) && !outline) ? vec4(0) : Color * sample_lightmap(Sampler2, UV2) * vec4(1, 1, 1, opacity);
#else
vertexColor = Color * sample_lightmap(Sampler2, UV2) * vec4(1, 1, 1, opacity);
#endif
if ((property & 1) > 0) {pos.y += 4 * sin((GameTime * 1200 + pos.x / ui.x) * 3.1415 * 2);}if ((property & 2) > 0) {int hash = int(pos.x) * int(pos.y);float time = GameTime * 1200;hash = 31 * (hash + int(vertexColor.x + time));float r = float(hash % 224 + 32) / 255;hash = 31 * (hash + int(vertexColor.y + time));float g = float(hash % 224 + 32) / 255;hash = 31 * (hash + int(vertexColor.z + time));float b = float(hash % 224 + 32) / 255;float maxValue = max(max(r, g), b);vertexColor = vec4(pow(r / maxValue, 3), pow(g / maxValue, 3), pow(b / maxValue, 3), vertexColor.w);}if ((property & 4) > 0) {int hash = int(pos.x) * int(pos.y);float time = GameTime * 1200;hash = 31 * (hash + int(vertexColor.x + time));float r = vertexColor.x + float(hash % 64) / 255;hash = 31 * (hash + int(vertexColor.y + time));float g = vertexColor.y + float(hash % 64) / 255;hash = 31 * (hash + int(vertexColor.z + time));float b = vertexColor.z + float(hash % 64) / 255;vertexColor = vec4(r, g, b, vertexColor.w);}pos.x += xGui;pos.y += yGui;pos.z += layer;}} else {}
#if SHADER_VERSION >= 3
sphericalVertexDistance = fog_spherical_distance(pos);cylindricalVertexDistance = fog_cylindrical_distance(pos);
#else
vertexDistance = fogDistance(pos, FogShape);
#endif
texCoord0 = UV0;gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);}