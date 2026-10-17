#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
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
// in vec4 lightMapColor;
// in vec4 overlayColor;
// in vec2 texCoord0;
// out vec4 fragColor;
// rpai-merge:end fragment-vanilla

out vec4 fragColor;

// Vanilla's own, untouched below this line. Called by our main when the
// gate says a fragment is not one of ours — which is nearly all of them.
void rpe_vanilla_main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

// rpai-merge:begin fragment-epilogue v1
// rpai-merge:renames main rpe_vanilla_main
flat in vec3 rpwProvPos;
flat in vec2 rpwProvUV;
flat in vec4 rpwTint;

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

// The item marker is a box WORLD_ITEM_BOX radii from the middle to each face.
const float RPW_ITEM_BOUND = 0.454545;

bool rpw_item_is_ours(vec4 texel) {
    float g = texel.g * 255.0;
    float b = texel.b * 255.0;
    return b > 249.5 && b < 251.5 && g > 159.5 && g < 191.5;
}

// The flag in the alpha's low bits: 5 is a lens, 7 a gravity lens, 3 is an
// object that is neither.
float rpw_encode_alpha(float a, float flag) {
    return (floor(clamp(a, 0.0, 1.0) * 31.0 + 0.5) * 8.0 + flag) / 255.0;
}

// What a server says about this placed copy, read off the item's tint
// (custom_model_data colour 0; world.ts's WORLD_CONTROL_PERIOD has the
// layout). Sets the rpw_state globals and answers whether anybody is driving
// it: the default tint (white) means nobody, and the object draws as it
// always did.
bool rpw_controls(vec4 tint) {
    ivec3 c = ivec3(floor(tint.rgb * 255.0 + 0.5));
    int v = (c.r << 16) | (c.g << 8) | c.b;
    int start = v & 4095;
    if (start > 4000) return false;
    rpw_state = (v >> 12) & 15;
    rpw_prev_state = (v >> 16) & 15;
    rpw_seed = float((v >> 20) & 15);
    if (start == 4000) {
        rpw_state_time = 1000.0;
    } else {
        // The client's clock is the game tick modulo 24000, which the period
        // divides, so the tick modulo the period is all the server had to send.
        float age = mod(GameTime * 24000.0 - float(start), 4000.0);
        // A state the server stamped a moment ahead of this client's clock
        // reads as nearly a whole period old: it has only just begun.
        rpw_state_time = age > 3900.0 ? 0.0 : age / 20.0;
    }
    return true;
}

// The model axes along which each face direction's texture u and v run, and
// its normal, in the order the texture's alpha names them (down, up, north,
// south, west, east): Minecraft's own face layout for a [0,0,16,16] uv
// (FaceInfo, and BlockFaceUV's corner order).
//
// Stated with X and Z negated, because an item display turns its item half a
// turn about Y before anything else (ItemDisplayRenderer): the model's +X is
// the world's -X on a display nobody has turned. Negating here makes the
// object's frame the world's on such a display, as it is undriven.
void rpw_face_axes(int dir, out vec3 u, out vec3 v, out vec3 n) {
    if (dir == 0)      { u = vec3(-1.0, 0.0, 0.0); v = vec3(0.0, 0.0, 1.0);  n = vec3(0.0, -1.0, 0.0); }
    else if (dir == 1) { u = vec3(-1.0, 0.0, 0.0); v = vec3(0.0, 0.0, -1.0); n = vec3(0.0, 1.0, 0.0); }
    else if (dir == 2) { u = vec3(1.0, 0.0, 0.0);  v = vec3(0.0, -1.0, 0.0); n = vec3(0.0, 0.0, 1.0); }
    else if (dir == 3) { u = vec3(-1.0, 0.0, 0.0); v = vec3(0.0, -1.0, 0.0); n = vec3(0.0, 0.0, -1.0); }
    else if (dir == 4) { u = vec3(0.0, 0.0, -1.0); v = vec3(0.0, -1.0, 0.0); n = vec3(1.0, 0.0, 0.0); }
    else               { u = vec3(0.0, 0.0, 1.0);  v = vec3(0.0, -1.0, 0.0); n = vec3(-1.0, 0.0, 0.0); }
}

// A driven object's box, from the face this fragment is on, at any turn. The
// face's plane comes from the screen derivatives of the position (exact: the
// position is linear across a face). Its texture u edge U then follows from
// two points on it with known texture coordinates, this fragment and the
// triangle's provoking vertex (or, right beside that vertex, the neighbouring
// pixel): V is U turned a quarter about the normal, which way round fixed by
// the face's direction, so d = U*e.x + V*e.y is two equations in the two
// unknowns of U within the plane. With U, V and the normal in the world, and
// the same three in the model from the face table, the turn is their product.
vec4 rpw_item_oriented(int id, int dir, float facing, vec2 q, vec2 dq, vec2 atlas, vec3 light) {
    vec3 P = rpwPos;
    vec3 Px = dFdx(P);
    vec3 Py = dFdy(P);
    vec2 qx = dFdx(texCoord0) * atlas / RPW_GLYPH;
    vec2 qy = dFdy(texCoord0) * atlas / RPW_GLYPH;
    vec3 nw = cross(Px, Py);
    if (dot(nw, nw) < 1e-30) return vec4(0.0, 0.0, 0.0, -1.0);
    nw = normalize(nw);
    // A face that is drawn is turned toward the eye.
    if (dot(nw, P - rpw_eye) > 0.0) nw = -nw;
    vec3 um;
    vec3 vm;
    vec3 nm;
    rpw_face_axes(dir, um, vm, nm);
    float s = dot(cross(nm, um), vm);
    vec3 d;
    vec2 e;
    // Any distance from the provoking vertex is enough: both points are on
    // the face exactly, so the solve only loses precision within a hair of it.
    if (dot(dq, dq) > 1e-6) {
        d = P - rpwProvPos;
        e = dq;
    } else if (dot(qx, qx) >= dot(qy, qy)) {
        d = Px;
        e = qx;
    } else {
        d = Py;
        e = qy;
    }
    float ee = dot(e, e);
    if (ee < 1e-12) return vec4(0.0, 0.0, 0.0, -1.0);
    vec3 U = (e.x * d - s * e.y * cross(nw, d)) / ee;
    vec3 V = s * cross(nw, U);
    float side = length(U);
    if (side < 1e-6) return vec4(0.0, 0.0, 0.0, -1.0);
    // The middle is half a side behind an outward face and half a side in
    // front of an inward one.
    vec3 C = P - U * (q.x - 0.5) - V * (q.y - 0.5) - nw * (side * 0.5 * facing);
    mat3 rot = mat3(U / side, V / side, nw) * transpose(mat3(um, vm, nm));
    // The inside only from within: the eye is in the box.
    if (facing < 0.0 && any(greaterThanEqual(abs(transpose(rot) * (rpw_eye - C)), vec3(side * 0.5 + RPW_INSIDE_MARGIN))))
        return vec4(0.0, 0.0, 0.0, -1.0);
    return rpw_trace(id, P, C, side * RPW_ITEM_BOUND, light, rot);
}

void rpw_item_main(vec4 texel, vec2 atlas) {
    // Only the side of a face turned toward the camera is drawn (and should a
    // pipeline not cull, the back is thrown away here).
    if (!gl_FrontFacing) discard;
    // The box's walls are two-sided (see worldItemFiles), and the texture says
    // which side this is. From outside the box only outward faces are drawn,
    // and the middle is half a side behind each. From WITHIN the box, which is
    // any camera less than a half-side from the middle along every axis, so a
    // little way off the object on a diagonal and not only inside it, the
    // inward faces are drawn, with the middle half a side in FRONT of each;
    // from outside, the far walls' inward faces also face the camera, and the
    // test below throws them away. An object used to vanish whenever a camera
    // came that close, most of all off to a corner of the screen.
    float facing = texel.b * 255.0 > 250.5 ? -1.0 : 1.0;
    int id = int(texel.g * 255.0 + 0.5) - 160;
    float cell = floor(texel.r * 255.0 + 0.5);
    vec2 inSprite = vec2(floor(cell / 16.0), mod(cell, 16.0));
    // Where on the quad this fragment is, and where the triangle's provoking
    // vertex is: both exact, from the sprite's own origin in the atlas.
    vec2 origin = floor(texCoord0 * atlas) - inSprite;
    vec2 q = (texCoord0 * atlas - origin) / RPW_GLYPH;
    vec2 qp = (rpwProvUV * atlas - origin) / RPW_GLYPH;
    vec2 dq = q - qp;
    vec3 light = clamp(lightMapColor.rgb, 0.0, 1.0);
    rpw_lens_pass = true;
    rpw_eye = rpw_eye_of(rpwToClip);
    vec4 color;
    if (rpw_controls(rpwTint)) {
        // Driven by a server: drawn in the box's own frame, so turning the
        // display turns the object (see rpw_item_oriented).
        int dir = int(texel.a * 255.0 + 0.5) - 128;
        color = rpw_item_oriented(id, dir, facing, q, dq, atlas, light);
    } else if (abs(dq.x) > 0.05 && abs(dq.y) > 0.05) {
        // The marker is a box billboarded with the camera, so every face lies
        // across two of the camera's own axes. This fragment and the
        // triangle's provoking vertex (a corner of the same face, a whole
        // face apart rather than one pixel) give the box exactly, without
        // knowing which face this is: the face's normal is the axis the two
        // do not differ along; the box's side is how far apart they are over
        // how far apart their texture coordinates are (summed over both
        // axes, so it does not matter which way round the face's texture
        // lies); the middle is half a side from the corner toward this
        // fragment across the face, and half a side further from the camera
        // along the normal. Screen-space derivatives would give the same
        // answer with a little jitter per pixel, which is invisible in the
        // object and turns into streaks wherever its depth is read back.
        vec3 ax0 = vec3(ModelViewMat[0][0], ModelViewMat[1][0], ModelViewMat[2][0]);
        vec3 ax1 = vec3(ModelViewMat[0][1], ModelViewMat[1][1], ModelViewMat[2][1]);
        vec3 ax2 = vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]);
        mat3 toCam = transpose(mat3(ax0, ax1, ax2));
        vec3 d = toCam * (rpwPos - rpwProvPos);
        vec3 ad = abs(d);
        vec3 n = ad.x < ad.y ? (ad.x < ad.z ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0))
                             : (ad.y < ad.z ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
        float side = (ad.x + ad.y + ad.z - dot(ad, n)) / (abs(dq.x) + abs(dq.y));
        vec3 p = toCam * rpwPos;
        vec3 ec = toCam * rpw_eye;
        vec3 c = mix(toCam * rpwProvPos + sign(d) * side * 0.5, p + sign(p - ec) * side * 0.5 * facing, n);
        // The inside only from within: the eye is in the box.
        if (facing < 0.0 && any(greaterThanEqual(abs(c - ec), vec3(side * 0.5 + RPW_INSIDE_MARGIN)))) discard;
        vec3 C = ax0 * c.x + ax1 * c.y + ax2 * c.z;
        color = rpw_trace(id, rpwPos, C, side * RPW_ITEM_BOUND, light, mat3(1.0));
    } else {
        color = rpw_shade(id, rpwPos, texCoord0, atlas, q, light, RPW_ITEM_BOUND, facing);
    }
    bool gravity = rpw_gravity(id);
    if (color.a < 0.0 || (color.a < 0.004 && !gravity)) discard;
    fragColor = vec4(color.rgb, rpw_encode_alpha(color.a, rpw_refracts(id) ? 5.0 : (gravity ? 7.0 : 3.0)));
}

void main() {
    gl_FragDepth = gl_FragCoord.z;
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    vec4 texel = texelFetch(Sampler0, ivec2(texCoord0 * atlas), 0);
    if (rpw_item_is_ours(texel)) {
        rpw_item_main(texel, atlas);
        return;
    }
    rpe_vanilla_main();
}
// rpai-merge:end fragment-epilogue
