#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

// rpai-merge:begin fragment-declarations v1
in vec4 rawColor;
in vec4 rtxA0;
in vec4 rtxA2;
in vec4 rtxW;
flat in vec3 rtxFlat;
// rpai-merge:provides GameTime
#moj_import <minecraft:globals.glsl>
// rpai-merge:end fragment-declarations
// rpai-merge:begin fragment-vanilla v1
// #moj_import <minecraft:fog.glsl>
// #moj_import <minecraft:dynamictransforms.glsl>
// uniform sampler2D Sampler0;
// in float sphericalVertexDistance;
// in float cylindricalVertexDistance;
// in vec4 vertexColor;
// in vec2 texCoord0;
// out vec4 fragColor;
// rpai-merge:end fragment-vanilla

out vec4 fragColor;

// Vanilla's own, untouched below this line. Called by our main when the
// gate says a fragment is not one of ours — which is nearly all of them.
void rpe_vanilla_main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

// rpai-merge:begin fragment-epilogue v1
// rpai-merge:renames main rpe_vanilla_main

const float SIG_R = 0.996078;
const float SIG_B = 0.003922;
const float TOL = 0.001961;

// --- primitives -------------------------------------------------------------

float sdCircle(vec2 p, float r) {
    return length(p) - r;
}

// Outer radius r, wall thickness t growing inward.
float sdRing(vec2 p, float r, float t) {
    float mid = r - t * 0.5;
    return abs(length(p) - mid) - t * 0.5;
}

float sdBox(vec2 p, vec2 b) {
    vec2 d = abs(p) - b;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

// A box inset by the corner radius, then grown back out by it — which is what
// makes the corners round without a separate case.
float sdRoundedBox(vec2 p, vec2 b, float r) {
    r = min(r, min(b.x, b.y));
    return sdBox(p, b - vec2(r)) - r;
}

float sdRegularPolygon(vec2 p, float r, float n) {
    float an = 3.141592653589793 / n;
    vec2 acs = vec2(cos(an), sin(an));
    float bn = mod(atan(p.x, p.y), 2.0 * an) - an;
    p = length(p) * vec2(cos(bn), abs(sin(bn)));
    p -= r * acs;
    p.y += clamp(-p.y, 0.0, r * acs.y);
    return length(p) * sign(p.x);
}

// --- operators --------------------------------------------------------------

float opUnion(float a, float b) { return min(a, b); }
float opSubtract(float a, float b) { return max(a, -b); }
float opIntersect(float a, float b) { return max(a, b); }

// The polynomial smooth-min. `h` is also what the caller blends colour by, so
// the seam between two shapes carries a blend of their two colours rather than
// a hard switch at the midpoint.
float opSmoothUnion(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / max(k, 0.0001), 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float smoothUnionBlend(float a, float b, float k) {
    return clamp(0.5 + 0.5 * (b - a) / max(k, 0.0001), 0.0, 1.0);
}

vec2 rotate2d(vec2 p, float degrees) {
    float a = radians(degrees);
    float s = sin(a);
    float c = cos(a);
    return vec2(p.x * c - p.y * s, p.x * s + p.y * c);
}



void dispatch(int id, vec2 cell, vec2 sub, out float d, out vec3 col, out float a) {
    d = 1e6;
    col = vec3(0.0);
    a = 0.0;
}

// ---- Text effects. See Studio's lib/shaders/text-effect.ts. ----
const float rtx_PI = 3.14159265;

struct rtx_Fx {
    vec2 p;     // where in the letter this pixel is, in the letter's own pixels:
                // 0..8 across and down (y DOWN), continuous. Outside 0..8 in
                // the room the effect asked for (pad).
    vec2 q;     // floor(p) + 0.5: the centre of the letter-pixel p is in.
                // Use it for anything that should look like pixel art.
    vec2 size;  // the letter's bitmap, in pixels: vec2(8.0) for the game's font.
    vec2 o;     // the letter's top-left on screen, in letter-pixels. ONE value
                // per letter, so it is the key for anything per-letter: a
                // phase (sin(t - o.x * 0.3)), a random choice (rtx_h2(o)).
    vec2 g;     // o + p: this pixel's place on screen, for a pattern that runs
                // across the whole word (a gradient, rain, a liquid surface).
    float t;    // seconds. Loops after 20 minutes.
    float w;    // how wide the inked part of the letter is (1..8): an "i" is 1,
                // an "m" is 5. For centring a turn, a squash or a fill.
};

vec2 rtxRA;
vec2 rtxRS;
vec2 rtxAt;

// The letter's alpha at letter-pixel p, 0 outside the letter. Bounded by the
// letter's own rectangle in the atlas, so it never reads the next glyph rtx_over.
float rtx_G(vec2 p) {
    if (p.x < 0.0 || p.y < 0.0 || p.x >= rtxRS.x || p.y >= rtxRS.y) return 0.0;
    return texture(Sampler0, (rtxRA + floor(p) + 0.5) / rtxAt).a;
}

// The most of the four neighbours, and of all eight.
float rtx_N4(vec2 p) {
    return max(max(rtx_G(p + vec2(1.0, 0.0)), rtx_G(p - vec2(1.0, 0.0))),
               max(rtx_G(p + vec2(0.0, 1.0)), rtx_G(p - vec2(0.0, 1.0))));
}

float rtx_N8(vec2 p) {
    float m = rtx_N4(p);
    m = max(m, max(rtx_G(p + vec2(1.0, 1.0)), rtx_G(p - vec2(1.0, 1.0))));
    return max(m, max(rtx_G(p + vec2(1.0, -1.0)), rtx_G(p + vec2(-1.0, 1.0))));
}

// How far p is from the nearest inked pixel, up to 3 away (9.0 past that).
float rtx_inkDist(vec2 p) {
    float d = 9.0;
    vec2 base = floor(p);
    for (int y = -3; y <= 3; y++) {
        for (int x = -3; x <= 3; x++) {
            vec2 c = base + vec2(float(x), float(y));
            if (rtx_G(c + 0.5) > 0.5) {
                vec2 e = max(abs(p - (c + 0.5)) - 0.5, 0.0);
                d = min(d, length(e));
            }
        }
    }
    return d;
}

// Hashes, 0..1.
float rtx_h1(float n) { return fract(sin(n * 12.9898) * 43758.5453); }
float rtx_h2(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float rtx_h3(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }

// Smooth value noise, and four octaves of it.
float rtx_vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 u = fract(p);
    u = u * u * (3.0 - 2.0 * u);
    return mix(mix(rtx_h2(i), rtx_h2(i + vec2(1.0, 0.0)), u.x), mix(rtx_h2(i + vec2(0.0, 1.0)), rtx_h2(i + vec2(1.0, 1.0)), u.x), u.y);
}

float rtx_fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * rtx_vnoise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

vec3 rtx_hsv(float h, float s, float v) {
    vec3 k = clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return v * mix(vec3(1.0), k, s);
}

// a drawn rtx_over b. Neither premultiplied.
vec4 rtx_over(vec4 a, vec4 b) {
    float o = a.a + b.a * (1.0 - a.a);
    if (o <= 0.0) return vec4(0.0);
    return vec4((a.rgb * a.a + b.rgb * b.a * (1.0 - a.a)) / o, o);
}

// A letter in one colour rtx_over the game's own kind of shadow: a quarter of the
// colour, one pixel down and right. sa is the letter's alpha one pixel up-left.
vec4 rtx_inkShadow(vec3 c, float a, float sa) {
    return rtx_over(vec4(c, a), vec4(c * 0.25, sa));
}

@@RPE_TEXT_EFFECT_EFFECTS@@
vec4 rtx_fragment() {
    int id = int(rtxFlat.x + 0.5);
    rtxAt = vec2(textureSize(Sampler0, 0));
    // Both corners back, by dividing out their weights. What comes back is a
    // little noisy from fragment to fragment, and everything per-letter is a
    // hash of it, so it is snapped: an atlas address is a whole texel, and a
    // glyph's position on screen a whole quarter of a GUI unit.
    vec4 a0 = rtxA0 / max(rtxW.x, 1e-6);
    vec4 a2 = rtxA2 / max(rtxW.y, 1e-6);
    a0.xy = floor(a0.xy * 4.0 + 0.5) * 0.25;
    a2.xy = floor(a2.xy * 4.0 + 0.5) * 0.25;
    rtxRA = floor(a0.zw * rtxAt + 0.5);
    rtxRS = max(floor(a2.zw * rtxAt + 0.5) - rtxRA, vec2(1.0));
    // GUI units per letter-pixel: the scale the text is drawn at.
    float scale = max((a2.x - a0.x) / rtxRS.x, 1e-3);
    rtx_Fx f;
    f.p = (rtxW.zw - a0.xy) / scale;
    f.q = floor(f.p) + 0.5;
    f.size = rtxRS;
    f.o = floor(a0.xy / scale + 0.5);
    f.g = f.o + f.p;
    f.t = GameTime * 1200.0;
    f.w = rtxFlat.y;
    vec4 room = rtx_pad(id);
    if (f.p.x < -room.x || f.p.y < -room.y || f.p.x > f.size.x + room.z || f.p.y > f.size.y + room.w) return vec4(0.0);
    vec4 c = rtx_dispatch(id, f);
    return vec4(c.rgb, clamp(c.a, 0.0, 1.0) * rtxFlat.z);
}

void main() {
    // A text effect's letter. See text-effect.ts.
    if (rtxFlat.x > 0.5) {
        vec4 rtxColor = rtx_fragment();
        if (rtxColor.a < 0.004) {
            discard;
        }
        fragColor = rtxColor;
        return;
    }

    // THE GATE. Anything that is not one of our overlays leaves here through
    // vanilla's own code, which is why that code is kept rather than rewritten.
    if (abs(rawColor.r - SIG_R) > TOL || abs(rawColor.b - SIG_B) > TOL) {
        rpe_vanilla_main();
        return;
    }

    // Ours. The green channel is which object.
    int id = int(rawColor.g * 255.0 + 0.5);

    // WHERE IN THE CANVAS THIS FRAGMENT IS, in two halves.
    //
    // texCoord0 is NOT 0..1 over the glyph. Every glyph the game draws is
    // stitched into a shared font atlas page, so texCoord0 is a small
    // sub-rectangle of that page at an address assigned at runtime — nothing
    // the pack can know. Reading it as a canvas UV puts every shape in a corner
    // a few pixels across, which renders as an overlay that draws nothing at
    // all.
    //
    // So the COARSE half is read out of the canvas glyph's own texels: the
    // texture is painted with its own texel coordinates, red across and green
    // down, and one texture read gives back the texel this fragment landed on
    // wherever the atlas put it.
    //
    // The FINE half is where inside that texel we are, which is exactly the
    // fractional part of the atlas coordinate — the atlas's own size is the
    // only thing needed to recover it, and the shader can ask for that. This is
    // what keeps the edge a true anti-aliased one at any GUI scale rather than
    // a staircase at texel resolution.
    //
    // The alternative, gl_FragCoord against ScreenSize, needs the GUI scale
    // inferred — exact only for the default "Auto" setting — and would still
    // not know where on the screen the engine chose to draw the line.
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    vec4 canvasTexel = texture(Sampler0, texCoord0);
    vec2 cell = floor(canvasTexel.rg * 255.0 + 0.5);
    vec2 sub = fract(texCoord0 * atlas);

    float d;
    vec3 shapeCol;
    float shapeAlpha;
    dispatch(id, cell, sub, d, shapeCol, shapeAlpha);

    // The canvas glyph's own COLOUR is not drawn: those texels are a coordinate
    // rather than a picture, and they are near-transparent so that the shadow
    // pass — which fails the gate above and goes through vanilla's own code —
    // discards them. The scene IS the output.
    //
    // An object's TEXT is not drawn through here — the engine sends it as
    // ordinary text in an ordinary colour, which fails the gate above.
    if (shapeAlpha < 0.01) {
        discard;
    }
    fragColor = vec4(shapeCol, shapeAlpha);
}
// rpai-merge:end fragment-epilogue
