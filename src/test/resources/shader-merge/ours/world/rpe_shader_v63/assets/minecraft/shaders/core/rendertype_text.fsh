#version 150

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

// rpai-merge:begin fragment-declarations v1
in vec4 rawColor;
in vec3 rpwPos;
flat in mat4 rpwToClip;
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


// ---- Hud (signature 0) ----
// Design pixels per canvas texel. The fragment's texel index and its position
// within that texel are both read off the glyph; this is what turns the pair
// into a point on the author's canvas. See ourMain().
const vec2 TEXEL_0 = vec2(2.000000, 2.000000);
// The author's canvas, which is the TOP of the emitted glyph. Everything below
// it is the lift — empty space that exists only to let the glyph hang clear of
// the text baseline — and the dispatcher clips to this so a shape drawn past
// the bottom edge cannot spill into it.
const vec2 CANVAS_0 = vec2(256.0, 128.0);
const float FEATHER_0 = 1.000;

// d: distance to the composed surface. col: its colour at this fragment.
void scene_0(vec2 p, out float d, out vec3 col, out float shapeAlpha) {
    d = 1e6;
    col = vec3(0.0);
    shapeAlpha = 0.0;

    // circle #1 (union)
    float d0 = sdCircle((p - vec2(64.0, 32.0)), 16.0);
    col = mix(col, vec3(0.302, 0.5529, 1.0), step(d0, d));
    shapeAlpha = mix(shapeAlpha, 1.0, step(d0, d));
    d = opUnion(d, d0);

    // ring #2 (union)
    float d1 = sdRing(rotate2d(p - vec2(64.0, 32.0), -15.0), 16.0, 4.0);
    col = mix(col, vec3(0.302, 0.5529, 1.0), step(d1, d));
    shapeAlpha = mix(shapeAlpha, 1.0, step(d1, d));
    d = opUnion(d, d1);

    // box #3 (union)
    float d2 = sdBox(rotate2d(p - vec2(64.0, 32.0), -30.0), vec2(24.0, 12.0));
    col = mix(col, vec3(0.302, 0.5529, 1.0), step(d2, d));
    shapeAlpha = mix(shapeAlpha, 1.0, step(d2, d));
    d = opUnion(d, d2);

    // roundedBox #4 (union)
    float d3 = sdRoundedBox(rotate2d(p - vec2(64.0, 32.0), -45.0), vec2(24.0, 12.0), 16.0);
    col = mix(col, vec3(0.302, 0.5529, 1.0), step(d3, d));
    shapeAlpha = mix(shapeAlpha, 1.0, step(d3, d));
    d = opUnion(d, d3);

    // polygon #5 (union)
    float d4 = sdRegularPolygon(rotate2d(p - vec2(64.0, 32.0), -60.0), 16.0, 6.0);
    col = mix(col, vec3(0.302, 0.5529, 1.0), step(d4, d));
    shapeAlpha = mix(shapeAlpha, 1.0, step(d4, d));
    d = opUnion(d, d4);
}

void dispatch(int id, vec2 cell, vec2 sub, out float d, out vec3 col, out float a) {
    d = 1e6;
    col = vec3(0.0);
    a = 0.0;
    if (id == 0) {
        vec2 p = (cell + sub) * TEXEL_0;
        scene_0(p, d, col, a);
        a *= 1.0 - smoothstep(-FEATHER_0 * 0.5, FEATHER_0 * 0.5, d);
        // The canvas is a frame, not a suggestion: a shape drawn past its
        // edge stops there, and the empty strip below it that the lift adds
        // never draws anything at all.
        if (any(lessThan(p, vec2(0.0))) || any(greaterThan(p, CANVAS_0))) {
            a = 0.0;
        }
        return;
    }
}

// ======== 3D shader objects (world.ts) ========
const float RPW_GLYPH = 16.0;
const float RPW_BOUND = 0.2500;
// How far OUTSIDE the box (in blocks) its inward faces are still drawn. A
// camera a hair outside a wall has that wall's outward face nearer than the
// near plane (0.05), so the game clips it away; if the inward faces behind it
// were thrown away too, the object vanished in a slice cut along the wall's
// line for the frames it took to walk through. Drawing both for a moment
// costs a translucent edge blended twice, for a few centimetres of walking.
const float RPW_INSIDE_MARGIN = 0.15;

// ---- shared by every world object ----
float rpw_hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float rpw_noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(rpw_hash(i + vec3(0, 0, 0)), rpw_hash(i + vec3(1, 0, 0)), f.x),
                   mix(rpw_hash(i + vec3(0, 1, 0)), rpw_hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(rpw_hash(i + vec3(0, 0, 1)), rpw_hash(i + vec3(1, 0, 1)), f.x),
                   mix(rpw_hash(i + vec3(0, 1, 1)), rpw_hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}
float rpw_fbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * rpw_noise(p);
        p = p * 2.02 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return v;
}
mat2 rpw_rot(float a) {
    float c = cos(a);
    float s = sin(a);
    return mat2(c, -s, s, c);
}
// A cheap sky for reflections: the colour a ray going this way would see.
vec3 rpw_sky(vec3 d) {
    float h = clamp(d.y, -1.0, 1.0);
    vec3 top = vec3(0.35, 0.55, 0.95);
    vec3 horizon = vec3(0.78, 0.86, 0.98);
    vec3 ground = vec3(0.32, 0.30, 0.27);
    vec3 c = h > 0.0 ? mix(horizon, top, pow(h, 0.6)) : mix(horizon, ground, pow(-h, 0.4));
    return c;
}
// ---- what the server says about this one placed object (see WORLD_OBJECT_CONTRACT) ----
int rpw_state = 0;
int rpw_prev_state = 0;
float rpw_state_time = 1000.0;
float rpw_seed = 0.0;


// ---- Sphere (world object 1) ----

vec4 rpw1_object(vec3 ro, vec3 rd, float t0, float t1, float time, vec3 light, inout float depth) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - 0.81;
    float h = b * b - c;
    if (h < 0.0) return vec4(0.0);
    float t = -b - sqrt(h);
    if (t < 0.0) return vec4(0.0);
    vec3 n = normalize(ro + rd * t);
    depth = t;
    vec3 base = 0.5 + 0.5 * n;
    float diffuse = 0.35 + 0.65 * max(dot(n, normalize(vec3(0.4, 0.8, 0.3))), 0.0);
    return vec4(base * diffuse * light, 1.0);
}


vec4 rpw_dispatch(int id, vec3 ro, vec3 rd, float t0, float t1, float time, vec3 light, inout float depth) {
    if (id == 1) return rpw1_object(ro, rd, t0, t1, time, light, depth);
    return vec4(0.0);
}
bool rpw_refracts(int id) {
    return false;
}
bool rpw_gravity(int id) {
    return false;
}

// Set by the item marker, whose layer the transparency pass reads: there a
// gravity lens covers its whole sphere (see rpw_trace).
bool rpw_lens_pass = false;

// Where the eye really is in camera-relative space. The origin, except while
// walking: view bobbing is a translation in the projection matrix, which puts
// the eye up to a tenth of a block off it. Within that of a box wall the
// origin and the eye are on opposite sides of it, so the wall was solved
// facing the wrong way and drew a mirrored copy of the object a box away,
// for the frames each step took. Set by the item marker (rpw_eye_of).
vec3 rpw_eye = vec3(0.0);

// The one point the projection sends to nowhere: clip x, y and w all zero.
vec3 rpw_eye_of(mat4 m) {
    mat3 a = mat3(m[0].xyw, m[1].xyw, m[2].xyw);
    if (abs(determinant(a)) < 1e-12) return vec3(0.0);
    return -(inverse(a) * m[3].xyw);
}

// The object in its sphere of radius R at C, turned by rot (the object's own
// axes in camera-relative world space; the identity when it is not turned).
// Returns the object's colour (alpha 0 where there is nothing, -1 off its
// sphere) and writes gl_FragDepth.
vec4 rpw_trace(int id, vec3 P, vec3 C, float R, vec3 light, mat3 rot);

// bound is the object's radius over the face's width; box is 1 when the face
// is the outside of one side of the item marker's box (the object sits half a
// face behind it), -1 when it is the inside (seen from within the box, the
// object is half a face in front of it), and 0 for the text marker's flat
// quad (the object is centred on it).
vec4 rpw_shade(int id, vec3 P, vec2 uv, vec2 atlas, vec2 q, vec3 light, float bound, float box) {
    // The quad's edge vectors, from how position and quad coordinate change
    // across the screen. The atlas coordinate is used for the derivative
    // rather than q: it is linear everywhere, whereas q is only right where
    // the texel read landed on our marker, which a helper lane at the edge may
    // not have.
    vec2 qx = dFdx(uv) * atlas / RPW_GLYPH;
    vec2 qy = dFdy(uv) * atlas / RPW_GLYPH;
    vec3 px = dFdx(P);
    vec3 py = dFdy(P);
    float det = qx.x * qy.y - qy.x * qx.y;
    if (abs(det) < 1e-12) return vec4(0.0, 0.0, 0.0, -1.0);
    // [px py] = [U V] * [qx qy]  =>  [U V] = [px py] * inverse([qx qy]).
    vec3 U = (px * qy.y - py * qx.y) / det;
    vec3 V = (py * qx.x - px * qy.x) / det;
    vec3 C = P - U * (q.x - 0.5) - V * (q.y - 0.5);
    vec3 nrm = cross(U, V);
    if (box != 0.0 && dot(nrm, nrm) > 0.0) {
        nrm = normalize(nrm);
        float h = length(U) * 0.5;
        C += nrm * sign(dot(C, nrm)) * h * box;
        // The inside of the box is drawn only from within it (see rpw_item_main).
        float hm = h + RPW_INSIDE_MARGIN;
        if (box < 0.0 && (abs(dot(C, normalize(U))) >= hm || abs(dot(C, normalize(V))) >= hm || abs(dot(C, nrm)) >= hm))
            return vec4(0.0, 0.0, 0.0, -1.0);
    }
    float R = length(U) * bound;
    return rpw_trace(id, P, C, R, light, mat3(1.0));
}

vec4 rpw_trace(int id, vec3 P, vec3 C, float R, vec3 light, mat3 rot) {
    // The ray leaves the eye (rpw_eye) through P. The object is asked in its
    // own frame: rot's transpose undoes its turn, which leaves lengths alone,
    // so a distance along the ray is the same number in both.
    vec3 wd = normalize(P - rpw_eye);
    vec3 rd = transpose(rot) * wd;
    vec3 ro = transpose(rot) * ((rpw_eye - C) / R);
    float b = dot(ro, rd);
    float c = dot(ro, ro) - 1.0;
    float h = b * b - c;
    if (h < 0.0) return vec4(0.0, 0.0, 0.0, -1.0);
    h = sqrt(h);
    float t0 = max(-b - h, 0.0);
    float t1 = -b + h;
    if (t1 <= 0.0) return vec4(0.0, 0.0, 0.0, -1.0);

    float depth = t0;
    vec4 color = rpw_dispatch(id, ro, rd, t0, t1, GameTime * 1200.0, light, depth);
    // A gravity lens in the item layer is the whole sphere, at the sphere's
    // own depth: the transparency pass solves the sphere from that depth and
    // bends the world behind every pixel of it, not only where the object drew.
    bool sphere = rpw_lens_pass && rpw_gravity(id);
    if (sphere) depth = t0;
    else if (color.a < 0.004) return vec4(0.0);

    // Depth of the surface the object reported, written so that blocks in
    // front hide it and it hides what is behind. Window depth is an affine
    // function of NDC depth whose two constants depend on the backend's
    // convention (OpenGL's -1..1 or a zero-to-one range); the quad's own
    // fragment says which, by comparing its known NDC depth with gl_FragCoord.z.
    vec3 hit = rpw_eye + wd * (clamp(depth, t0, t1) * R);
    vec4 clip = rpwToClip * vec4(hit, 1.0);
    vec4 quadClip = rpwToClip * vec4(P, 1.0);
    float ndcQuad = quadClip.z / quadClip.w;
    bool halfRange = abs(gl_FragCoord.z - (ndcQuad * 0.5 + 0.5)) <= abs(gl_FragCoord.z - ndcQuad);
    float ndc = clip.z / clip.w;
    gl_FragDepth = clamp(halfRange ? ndc * 0.5 + 0.5 : ndc, 0.0, 1.0);
    return vec4(color.rgb, clamp(color.a, 0.0, 1.0));
}

const float RPW_SIG_R = 0.992157;
const float RPW_SIG_B = 0.980392;

bool rpw_is_ours() {
    return abs(rawColor.r - RPW_SIG_R) < 0.5 / 255.0 && abs(rawColor.b - RPW_SIG_B) < 0.5 / 255.0;
}

void rpw_main() {
    int id = int(rawColor.g * 255.0 + 0.5);
    // Where on the quad this fragment is, 0..1 (y down). Coarse from the
    // texel's stored coordinate, fine from the atlas coordinate's fraction —
    // the same reading the HUD canvas does, see patch.ts.
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    vec4 texel = texture(Sampler0, texCoord0);
    vec2 q = (floor(texel.rg * 255.0 + 0.5) + fract(texCoord0 * atlas)) / RPW_GLYPH;
    // Light: vanilla multiplied the vertex colour by the lightmap; dividing the
    // mark back out leaves the light. Red is used for all three because the
    // mark's red is large enough to divide by accurately.
    vec3 light = clamp(vertexColor.rgb / max(rawColor.rgb, vec3(1.0 / 255.0)), 0.0, 1.0);
    vec4 color = rpw_shade(id, rpwPos, texCoord0, atlas, q, vec3(light.r), RPW_BOUND, 0.0);
    if (color.a < 0.004) discard;
    fragColor = color;
}

void main() {
    gl_FragDepth = gl_FragCoord.z;
    // A 3D object in the world. See world.ts.
    if (rpw_is_ours()) {
        rpw_main();
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
