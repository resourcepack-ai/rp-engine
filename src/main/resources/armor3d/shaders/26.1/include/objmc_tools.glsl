//objmc
//https://github.com/Godlander/objmc

#define PI 3.1415926535897932

ivec4 getmeta(ivec2 topleft, int offset) {
    return ivec4(texelFetch(Sampler0, topleft + ivec2(offset,0), 0) * 255.0 + 0.5);
}
vec3 getpos(ivec2 topleft, int w, int h, int index) {
    int i = index*3;
    vec4 x = texelFetch(Sampler0, topleft + ivec2((i  )%w,h+((i  )/w)), 0);
    vec4 y = texelFetch(Sampler0, topleft + ivec2((i+1)%w,h+((i+1)/w)), 0);
    vec4 z = texelFetch(Sampler0, topleft + ivec2((i+2)%w,h+((i+2)/w)), 0);
    return vec3(
        (x.r*256)+(x.g)+(x.b/256),
        (y.r*256)+(y.g)+(y.b/256),
        (z.r*256)+(z.g)+(z.b/256)
    )*(255./256.) - vec3(128);
}
vec2 getuv(ivec2 topleft, int w, int h, int index) {
    int i = index*2;
    vec4 x = texelFetch(Sampler0, topleft + ivec2((i  )%w,h+((i  )/w)), 0);
    vec4 y = texelFetch(Sampler0, topleft + ivec2((i+1)%w,h+((i+1)/w)), 0);
    return vec2(
        ((x.g*65280)+(x.b*255))/65535,
        ((y.g*65280)+(y.b*255))/65535
    );
}
ivec2 getvert(ivec2 topleft, int w, int h, int index) {
    int i = index*2;
    ivec4 a = ivec4(texelFetch(Sampler0, topleft + ivec2((i  )%w,h+((i  )/w)), 0)*255.0+0.5);
    ivec4 b = ivec4(texelFetch(Sampler0, topleft + ivec2((i+1)%w,h+((i+1)/w)), 0)*255.0+0.5);
    return ivec2(
        ((a.r*65536)+(a.g*256)+a.b),
        ((b.r*65536)+(b.g*256)+b.b)
    );
}

bool getb(int i, int b) {
    return bool((i>>b)&1);
}
int getb(int i, int b, int s) {
    return (i>>b)&((1<<s)-1);
}

//gui item model detection from Onnowhere
bool isgui(mat4 ProjMat) {
    return ProjMat[2][3] == 0.0;
}
//first person hand item model detection (Bálint nonsense)
bool ishand(mat4 ProjMat) {
    return abs(ProjMat[3][2] + 0.10005) < 0.00001;
}

//hue to rgb
vec3 hrgb(float h) {
    vec3 K = vec3(1.0, 2.0 / 3.0, 1.0 / 3.0);
    vec3 p = abs(fract(K.xyz + h) * 6.0 - 3.0);
    return clamp(p - K.xxx, 0.0, 1.0);
}

//4 point bezier formula from Dominexis
vec3 bezb(vec3 a, vec3 b, vec3 c, vec3 d, float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return (d-3*c+3*b-a)*t3 + (3*c-6*b+3*a)*t2 + (3*b-3*a)*t + a;
}
vec3 bezier(vec3 a, vec3 b, vec3 c, vec3 d, float t) {
    return bezb(b,b+(c-a)/6,c-(d-b)/6,c,t);
}

// ── Armor UV decode (MC 26.2+) ──────────────────────────────────────────────
// On 26.2 entity geometry lives in shared vertex arenas with per-frame base
// offsets, so gl_VertexID-derived indices are garbage. The humanoid armor
// layer UV LAYOUT (64x32 grid, fixed since alpha) identifies everything
// instead: which box a face belongs to, and which face of the box it is.
// Returns ivec2(partKind, f6):
//   partKind: 0 head, 1 body, 2 arm, 3 leg, -1 not recognized
//   f6 (matches the vertexID-order decode): 0 down, 1 up, 2 west, 3 north,
//   4 east, 5 south. Left limbs share the right limb's rects (legacy layout);
//   the SIDE is recovered separately from the quad winding.
// m = face UV midpoint in layout units (UV0 * vec2(64, 32)).
ivec2 oc_armor_uvface(vec2 m) {
    // box uv origins + dims (w, h, d): head(0,0,8,8,8) body(16,16,8,12,4)
    // arm(40,16,4,12,4) leg(0,16,4,12,4)
    for (int i = 0; i < 4; i++) {
        vec2 o  = (i == 0) ? vec2( 0.0,  0.0) : (i == 1) ? vec2(16.0, 16.0)
                : (i == 2) ? vec2(40.0, 16.0) : vec2( 0.0, 16.0);
        vec3 wh = (i == 0) ? vec3(8.0, 8.0, 8.0) : (i == 1) ? vec3(8.0, 12.0, 4.0)
                : vec3(4.0, 12.0, 4.0);
        float w = wh.x, h = wh.y, d = wh.z;
        vec2 r = m - o;
        if (r.x < 0.0 || r.y < 0.0 || r.x >= 2.0*(w + d) || r.y >= d + h) continue;
        int f6;
        if (r.y < d) {
            // top strip: up/down caps (culled by the armor path anyway)
            if (r.x < d || r.x >= d + 2.0*w) continue;
            f6 = (r.x < d + w) ? 1 : 0;
        } else {
            // side strip: west | north | east | south (calibration order)
            f6 = (r.x < d) ? 2 : (r.x < d + w) ? 3 : (r.x < d + w + d) ? 4 : 5;
        }
        return ivec2(i, f6);
    }
    return ivec2(-1, -1);
}

// ── A carrier quad's four corners (RPAI) ────────────────────────────────────
// obj³ reads the other three corners of this vertex's quad off the other three
// lanes of its subgroup quad. That holds only where the GPU shades a quad's
// four vertices in four adjacent lanes, and AMD's does not always: on an
// RX 7600 (RDNA3, measured with a standalone GL test drawing quads the way
// the game does) one quad in twenty-four straddles two lane groups, and once
// triangles in a draw are culled - as the armour path culls every up/down and
// empty face - the survivors are compacted and FOUR IN TEN were wrong. Each
// such vertex went wherever its wrong neighbours put it, so a face became a
// spike to the far cull point. Where the technique was proven, 26.2 on
// somebody else's machine, the lane quad was the quad.
//
// So the lane quad is used only when it IS a quad: one corner in each uv
// quadrant and a parallelogram in space (a box face is one under any pose).
// Otherwise the quad's own vertices are looked for by gl_VertexID (a quad is
// four consecutive vertices) in the lanes round this one, which survives the
// compaction, and a missing fourth corner is the parallelogram's. False means
// this wave cannot see the quad at all - its vertices were shaded in another
// one - and the caller drops the vertex rather than drawing it somewhere.
//
// Corners come back ordered by uv quadrant: top-right, top-left, bottom-left,
// bottom-right, which is the emission order of an unmirrored face.
bool oc_quad_order(vec3 p[4], vec2 t[4], out vec3 c[4], out vec2 u[4]) {
    vec2 lo = min(min(t[0], t[1]), min(t[2], t[3]));
    vec2 hi = max(max(t[0], t[1]), max(t[2], t[3]));
    vec2 mid = (lo + hi) * 0.5;
    int seen = 0;
    for (int i = 0; i < 4; i++) { c[i] = vec3(0.0); u[i] = vec2(0.0); }
    for (int i = 0; i < 4; i++) {
        int k = (t[i].x > mid.x) ? ((t[i].y < mid.y) ? 0 : 3) : ((t[i].y < mid.y) ? 1 : 2);
        if ((seen & (1 << k)) != 0) return false;
        seen |= 1 << k;
        c[k] = p[i]; u[k] = t[i];
    }
    if (seen != 15 || hi.x <= lo.x || hi.y <= lo.y) return false;
    float size = length(c[0] - c[1]) + length(c[0] - c[3]);
    return length(c[0] + c[2] - c[1] - c[3]) <= size * 2e-3 + 1e-5;
}
bool oc_gather_quad(vec3 pos, vec2 uv, out vec3 c[4], out vec2 u[4]) {
    vec3 p[4]; vec2 t[4];
    p[0] = subgroupQuadBroadcast(pos, 0); p[1] = subgroupQuadBroadcast(pos, 1);
    p[2] = subgroupQuadBroadcast(pos, 2); p[3] = subgroupQuadBroadcast(pos, 3);
    t[0] = subgroupQuadBroadcast(uv, 0);  t[1] = subgroupQuadBroadcast(uv, 1);
    t[2] = subgroupQuadBroadcast(uv, 2);  t[3] = subgroupQuadBroadcast(uv, 3);
    bool ok = oc_quad_order(p, t, c, u);
#ifdef GL_KHR_shader_subgroup_shuffle
    // Every lane shuffles, whether or not it needs to: a lane that skipped
    // the shuffle would be one its neighbours read garbage from.
    int me = gl_VertexID;
    vec3 sp[4]; vec2 st[4]; bool sh[4];
    for (int k = 0; k < 4; k++) { sp[k] = vec3(0.0); st[k] = vec2(0.0); sh[k] = false; }
    int lane = int(gl_SubgroupInvocationID), last = int(gl_SubgroupSize) - 1;
    for (int d = -3; d <= 3; d++) {
        uint l = uint(clamp(lane + d, 0, last));
        int v = subgroupShuffle(me, l);
        vec3 q = subgroupShuffle(pos, l);
        vec2 w = subgroupShuffle(uv, l);
        if ((v >> 2) == (me >> 2)) { sh[v & 3] = true; sp[v & 3] = q; st[v & 3] = w; }
    }
    if (!ok) {
        // Emission order runs round the face, so a missing corner is its two
        // neighbours less the one opposite it.
        for (int k = 0; k < 4; k++) {
            if (!sh[k] && sh[(k + 1) & 3] && sh[(k + 2) & 3] && sh[(k + 3) & 3]) {
                sp[k] = sp[(k + 1) & 3] + sp[(k + 3) & 3] - sp[(k + 2) & 3];
                st[k] = st[(k + 1) & 3] + st[(k + 3) & 3] - st[(k + 2) & 3];
                sh[k] = true;
            }
        }
        if (sh[0] && sh[1] && sh[2] && sh[3]) ok = oc_quad_order(sp, st, c, u);
    }
#endif
    return ok;
}
