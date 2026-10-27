package io.github.chungus_actual.nightdrop

/**
 * The presets. Each one is a FEEDBACK pass in the MilkDrop tradition: it reads the previous frame
 * (uPrev), warps it a little (zoom, rotate, drift, all driven by the audio), lets it fade, and draws
 * something new on top. The trails, tunnels and smears are that loop, not anything drawn per se.
 * Coordinates are centred and aspect-correct: p.x in about -0.9..0.9, p.y in -0.5..0.5.
 */
object Shaders {

    val NAMES = listOf("Tunnel", "Horizon", "Flow", "Mirror", "Aurora", "Rain", "Ridge", "Warp", "Petals", "Ripple", "Shards",
        "Scope", "Plasma", "Vinyl", "Fireworks", "Contour", "Tesla", "Wormhole", "Harp", "Lava", "Spiro",
        "Planet", "Flame", "Moire", "Glitch", "Helix", "Mandala", "Radar", "Silk", "Prism", "Julia")

    // A real vertex buffer (Renderer.tri), not the gl_VertexID trick: drawing with no attributes bound
    // gave the SHIELD's Tegra driver a triangle that covered only part of the screen, so everything above
    // one edge was never drawn (2026-10-25, the black half on the first run).
    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val COMMON = """#version 300 es
precision highp float;
uniform sampler2D uPrev;
uniform sampler2D uArt;
// 64x2: row 0 the waveform (0..1 = -1..1), row 1 the spectrum (0..1 = 0..2). A texture, not uniform
// arrays: the SHIELD's Tegra driver returned nothing for uWave[32..63] when indexed dynamically, which
// erased the upper half of the Tunnel's ring (2026-10-25, first run on the TV).
uniform sampler2D uAudio;
uniform vec2 uRes;
uniform float uTime, uBass, uMid, uTreb, uBeat, uHasArt, uEnergy;
uniform vec3 uC1, uC2, uC3;
in vec2 vUv;
out vec4 o;
const float TAU = 6.2831853;
float wave(float x) { return texture(uAudio, vec2(clamp(x, 0.0, 1.0) * (63.0 / 64.0) + 0.5 / 64.0, 0.25)).r * 2.0 - 1.0; }
float spec(float x) { return texture(uAudio, vec2(clamp(x, 0.0, 1.0) * (63.0 / 64.0) + 0.5 / 64.0, 0.75)).r * 2.0; }
mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}
float fbm(vec2 p) { float v = 0.0, a = 0.5; for (int k = 0; k < 5; k++) { v += a * noise(p); p = p * 2.03 + 17.1; a *= 0.5; } return v; }
vec2 aspect() { return vec2(uRes.x / uRes.y, 1.0); }
vec2 centred() { return (vUv - 0.5) * aspect(); }
vec3 prevAt(vec2 p) { return texture(uPrev, p / aspect() + 0.5).rgb; }
vec3 art(vec2 uv) { return uHasArt > 0.5 ? texture(uArt, uv).rgb : mix(uC1, uC2, uv.y); }
float seg(vec2 p, vec2 a, vec2 b) { vec2 pa = p - a, ba = b - a; return length(pa - ba * clamp(dot(pa, ba) / max(dot(ba, ba), 1e-8), 0.0, 1.0)); }
// a fade that reaches black: in an 8-bit buffer a dim pixel times 0.98 rounds back to itself, so a
// slow multiply alone leaves every old trail stuck at a grey haze
vec3 decay(vec3 c, float k) { return max(c * k - 1.0 / 255.0, 0.0); }
// a slower fade that still reaches black: half a step of the buffer per frame on average, dithered
// (subtracting half a step from every pixel rounds them all down a whole one at once)
vec3 decaySlow(vec3 c, float k) {
    vec2 fc = gl_FragCoord.xy + 5.588238 * mod(floor(uTime * 60.0), 64.0);
    return max(c * k - fract(52.9829189 * fract(dot(fc, vec2(0.06711056, 0.00583715)))) / 255.0, 0.0);
}
"""

    /** Tunnel: the frame falls inward and turns; the waveform is a ring at the centre. */
    private const val TUNNEL = COMMON + """
void main() {
    vec2 p = centred();
    float z = 0.975 - 0.035 * uBass;
    vec2 q = rot(0.006 + 0.03 * uMid * sin(uTime * 0.3)) * p * z;
    q += 0.004 * vec2(sin(uTime * 0.7 + p.y * 6.0), cos(uTime * 0.6 + p.x * 6.0)) * (0.5 + uTreb);
    vec3 prev = prevAt(q) * (0.93 - 0.03 * uTreb);
    float r = length(p);
    float a = fract(atan(p.y, p.x) / TAU + 0.5);
    // the waveform mirrored top to bottom, so the ring closes where the angle wraps (a step on the left otherwise)
    float ring = 0.16 + 0.07 * wave(1.0 - abs(2.0 * a - 1.0)) + 0.06 * uBass + 0.05 * uBeat;
    float line = 1.0 - smoothstep(0.0, 0.010, abs(r - ring));
    vec3 col = mix(uC1, uC2, 0.5 + 0.5 * sin(a * TAU * 2.0 + uTime)) * line * (0.9 + 1.2 * uBeat);
    col += uC3 * (1.0 - smoothstep(0.0, 0.035, r)) * (0.3 + uBass);
    o = vec4(min(prev + col, vec3(1.5)), 1.0);
}
"""

    /** Horizon: a synthwave floor grid racing at the tempo, the album art as the sun, the spectrum as skyline. */
    private const val HORIZON = COMMON + """
void main() {
    vec2 p = centred();
    vec3 prev = prevAt(p * 0.996 + vec2(0.0, 0.0015)) * 0.55;
    float hz = -0.08;
    vec3 col;
    if (p.y > hz) {
        col = mix(uC3 * 0.18, uC1 * 0.05, clamp((p.y - hz) * 2.2, 0.0, 1.0));
        vec2 sp = p - vec2(0.0, 0.13);
        float sr = 0.21 + 0.02 * uBass;
        float d = length(sp);
        col += uC2 * 0.45 * (1.0 - smoothstep(sr, sr + 0.18, d)) * (0.45 + uBeat);
        if (d < sr) {
            vec2 auv = sp / (2.0 * sr) + 0.5; auv.y = 1.0 - auv.y;
            float band = step(0.45, fract(sp.y * 22.0 - uTime * 0.6));
            float cut = sp.y < -0.03 ? mix(1.0, band, 1.0 - smoothstep(-0.17, -0.03, sp.y)) : 1.0;
            col = mix(col, art(auv) * 1.1, cut);
        }
        float bx = abs(floor(p.x * 40.0) / 40.0) / 0.9;
        float h = hz + 0.015 + 0.20 * spec(bx);
        if (p.y < h) col = mix(col, uC1 * 0.22 + uC3 * 0.06, 0.88);
    } else {
        float y = hz - p.y;
        float zz = 0.12 / max(y, 0.0008);
        // 12 columns and 3 rows per unit of depth: at 1x1 the near floor held one line each way
        vec2 g = vec2(p.x * zz * 12.0, zz * 3.0 + uTime * (2.0 + 3.3 * uBass));
        vec2 grid = abs(fract(g - 0.5) - 0.5) / (1.5 * fwidth(g));
        float line = 1.0 - min(min(grid.x, grid.y), 1.0);
        col = uC3 * 0.04 + uC1 * line * (0.55 + 0.9 * uBeat) * smoothstep(0.0, 0.35, y);
    }
    o = vec4(col + prev, 1.0);
}
"""

    /** Flow: a nebula that the frame is advected through, sparks on the beat. */
    private const val FLOW = COMMON + """
void main() {
    vec2 p = centred();
    float t = uTime * 0.05;
    vec2 f = vec2(fbm(p * 2.2 + t), fbm(p * 2.2 - t + 5.2)) - 0.5;
    vec3 prev = prevAt(p - f * (0.004 + 0.012 * uMid)) * (0.965 - 0.02 * uTreb);
    float n = fbm(p * 3.0 + f * 2.0 + uTime * 0.1);
    vec3 neb = mix(uC1, uC2, n) * pow(n, 3.0) * (0.05 + 0.25 * uBass);
    vec2 cell = floor(p * 40.0);
    float h = hash(cell + floor(uTime * 4.0));
    // a round glint in the middle of the cell (the whole cell lit read as confetti squares)
    float glint = 1.0 - smoothstep(0.08, 0.32, length(fract(p * 40.0) - 0.5));
    float spark = step(0.9965 - 0.012 * uBeat, h) * glint * (0.6 + 1.4 * uBeat);
    o = vec4(min(prev + neb + uC3 * spark, vec3(1.5)), 1.0);
}
"""

    /** Mirror: the album art, folded into a six-way kaleidoscope that breathes with the bass. */
    private const val MIRROR = COMMON + """
void main() {
    vec2 p = centred();
    float segs = 6.0;
    float a = atan(p.y, p.x) + uTime * 0.05;
    float r = length(p);
    a = mod(a, TAU / segs);
    a = abs(a - TAU / segs * 0.5);
    vec2 k = vec2(cos(a), sin(a)) * r;
    k = rot(uTime * 0.1) * k * (1.4 - 0.4 * uBass);
    vec3 c = art(fract(k + 0.5 + 0.02 * vec2(wave(r), wave(a / TAU))));
    vec3 prev = prevAt(p * 0.99) * 0.7;
    o = vec4(mix(prev, c * (0.65 + 0.6 * uBeat), 0.5), 1.0);
}
"""

    /** Aurora: curtains whose brightness is the spectrum, drifting upward. */
    private const val AURORA = COMMON + """
void main() {
    vec2 p = centred();
    vec3 prev = prevAt(p + vec2(0.0, -0.0025)) * 0.945;
    float x = vUv.x;
    float s = spec(abs(x - 0.5) * 2.0);
    float curtain = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float y0 = -0.12 + 0.12 * fi + 0.08 * sin(x * 6.0 + uTime * (0.3 + 0.1 * fi) + fi);
        float band = exp(-pow((p.y - y0) * (9.0 - 2.0 * fi), 2.0));
        curtain += band * (0.35 + 1.6 * s) * (0.6 + 0.5 * fbm(vec2(x * 8.0, uTime * 0.2 + fi)));
    }
    vec3 col = mix(uC1, uC3, vUv.y) * curtain * 0.075 + uC2 * 0.02 * uBeat;
    o = vec4(min(prev + col, vec3(1.5)), 1.0);
}
"""

    // ---- the second six ----

    /** Rain: neon rain down dark glass, two depths of lanes, each lane lit by its own band of the spectrum. */
    private const val RAIN = COMMON + """
void main() {
    vec2 p = centred();
    vec3 prev = prevAt(p + vec2(0.0, 0.003 + 0.004 * uBass)) * (0.86 - 0.04 * uTreb);
    vec3 col = vec3(0.0);
    for (int k = 0; k < 2; k++) {
        float fk = float(k);
        float lanes = 34.0 + 22.0 * fk;
        float x = vUv.x * lanes;
        float lane = floor(x);
        float fx = abs(fract(x) - 0.5);
        float h = hash(vec2(lane, 3.0 + fk));
        float s = spec(abs(lane / lanes - 0.5) * 2.0);
        // a constant speed per lane: the music sets how long and bright a drop is, never where it is
        float head = 1.0 - fract(uTime * (0.10 + 0.22 * h) * (1.0 - 0.35 * fk) + h);
        float d = vUv.y - head;
        float len = 0.05 + 0.24 * s;
        float tail = d > 0.0 ? pow(max(0.0, 1.0 - d / len), 2.0) : 0.0;
        float glint = exp(-d * d * 6000.0);
        float w = 1.0 - smoothstep(0.03, 0.14 + 0.06 * fk, fx);
        vec3 c = mix(uC1, uC2, h) * (1.0 - 0.45 * fk);
        col += w * (c * tail * (0.2 + 1.3 * s) + mix(uC3, vec3(1.0), 0.4) * glint * (0.35 + 0.9 * uBeat));
    }
    col += uC1 * 0.05 * (1.0 - smoothstep(0.0, 0.25, vUv.y)) * (0.4 + uBass);
    o = vec4(min(prev + col * 0.55, vec3(1.5)), 1.0);
}
"""

    /** Ridge: each frame's spectrum is a new ridge in front; the older ones recede toward the horizon. */
    private const val RIDGE = COMMON + """
void main() {
    vec2 p = centred();
    vec2 vp = vec2(0.0, 0.30);
    vec3 prev = prevAt(vp + (p - vp) * 1.013) * 0.978;
    float x = p.x / 0.9;
    float env = 1.0 - smoothstep(0.2, 1.0, abs(x));
    float y0 = -0.36 + env * (0.11 * spec(abs(x)) * (0.6 + uBass) + 0.03 * wave(x * 0.5 + 0.5));
    float d = p.y - y0;
    float line = 1.0 - smoothstep(0.0, 0.0045, abs(d));
    // dim where the envelope ends: the flat ends of every old ridge otherwise stack into bands at the sides
    vec3 c = mix(uC1, uC2, 0.5 + 0.5 * x) * (0.75 + 1.1 * uBeat) * (0.12 + 0.88 * env);
    // the newest ridge hides whatever is behind it: below the line is black ground, not older lines
    vec3 col = d < 0.0 ? vec3(0.0) : prev;
    o = vec4(min(col + c * line, vec3(1.5)), 1.0);
}
"""

    /** Warp: stars born near the middle, flung outward by the zoom; the trails are the streaks. */
    private const val WARP = COMMON + """
void main() {
    vec2 p = centred();
    // under a star's width per frame, or the trail is a row of dots instead of a streak
    float z = 0.978 - 0.014 * uBass;
    mat2 turn = rot(0.002 + 0.006 * uMid);
    // the brighter of a full step and a half step back: fills the gap a fast star leaves between frames
    vec3 prev = max(prevAt(turn * p * z), prevAt(turn * p * (0.5 + 0.5 * z))) * (0.93 + 0.03 * uBass);
    float r = length(p);
    vec2 g = p * 70.0;
    float h = hash(floor(g) + vec2(floor(mod(uTime * 30.0, 997.0)), 0.0));
    float glint = 1.0 - smoothstep(0.08, 0.38, length(fract(g) - 0.5));
    float born = step(0.997 - 0.004 * uBeat - 0.002 * uTreb, h) * glint * (1.0 - smoothstep(0.1, 0.6, r));
    vec3 c = mix(uC3, vec3(1.0), 0.55) * born * (1.2 + 2.0 * uBeat);
    c += uC1 * 0.05 * (1.0 - smoothstep(0.0, 0.10, r)) * (0.3 + uBass);
    o = vec4(min(prev + c, vec3(1.5)), 1.0);
}
"""

    /** Petals: two polar roses, five petals and three, turning against each other; the trails fold inward. */
    private const val PETALS = COMMON + """
void main() {
    vec2 p = centred();
    vec3 prev = prevAt(rot(-0.004 - 0.006 * uMid) * p * 1.006) * 0.945;
    float r = length(p), a = atan(p.y, p.x);
    float t = uTime * 0.15;
    float s = spec(1.0 - abs(2.0 * fract(a / TAU + 0.5) - 1.0));
    float r1 = (0.30 + 0.10 * uBass) * abs(cos(2.5 * a + t)) + 0.04 * s;
    float r2 = (0.19 + 0.06 * uMid) * abs(cos(1.5 * a - t * 1.3)) + 0.03 * s;
    float l1 = 1.0 - smoothstep(0.0, 0.006, abs(r - r1));
    float l2 = 1.0 - smoothstep(0.0, 0.005, abs(r - r2));
    vec3 col = (uC1 * l1 + uC2 * l2) * (0.7 + 1.2 * uBeat);
    col += uC3 * (1.0 - smoothstep(0.0, 0.02, r)) * (0.4 + uBass);
    o = vec4(min(prev + col, vec3(1.5)), 1.0);
}
"""

    /** Ripple: a still pond; every beat drops a ring at the centre and the water carries it out. */
    private const val RIPPLE = COMMON + """
void main() {
    vec2 p = centred();
    float r = length(p);
    vec2 n = vec2(noise(p * 6.0 + uTime * 0.2), noise(p * 6.0 - uTime * 0.2 + 9.0)) - 0.5;
    // a fixed step outward, not a zoom: a zoom moves a ring in proportion to its radius, so the
    // new ones near the middle barely moved before they faded
    vec3 prev = prevAt(p - p / max(r, 0.001) * 0.0042 + n * 0.0012) * 0.986;
    // the middle only ever holds the newest ring: anything drawn there continuously is carried out
    // forever and the pond fogs over (the first version's grey wash)
    if (r < 0.03) prev = vec3(0.0);
    // a ring on the beat frame, and a slow drop of its own so a quiet room still ripples
    float drop = max(step(0.85, uBeat), step(0.985, fract(uTime * 0.4)));
    float ring = (1.0 - smoothstep(0.0, 0.005, abs(r - 0.035))) * drop;
    vec3 col = mix(uC1, uC2, 0.5 + 0.5 * sin(uTime * 0.3)) * ring * 0.9;
    o = vec4(min(prev + col, vec3(1.5)), 1.0);
}
"""

    /** Shards: the album art broken into slow glass cells, each cell lit by its own band of the spectrum. */
    private const val SHARDS = COMMON + """
void main() {
    vec2 p = centred();
    vec2 g = p * 5.0;
    vec2 id0 = floor(g), f = fract(g);
    float best = 9.0, second = 9.0;
    vec2 bid = vec2(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 b = vec2(float(i), float(j));
            vec2 id = id0 + b;
            vec2 h = vec2(hash(id), hash(id + 17.3));
            vec2 pt = b + 0.5 + 0.38 * sin(uTime * 0.2 + TAU * h);
            float d = length(pt - f);
            if (d < best) { second = best; best = d; bid = id; }
            else if (d < second) { second = d; }
        }
    }
    float lit = spec(hash(bid + 3.1));
    vec2 auv = p + 0.5 + (vec2(hash(bid + 5.0), hash(bid + 8.0)) - 0.5) * 0.06 * (0.5 + uMid);
    auv = abs(mod(auv + 1.0, 2.0) - 1.0);       // mirrored repeat: the square art across a wide screen
    auv.y = 1.0 - auv.y;
    vec3 c = art(auv) * (0.3 + 0.9 * lit);
    float edge = 1.0 - smoothstep(0.0, 0.04, second - best);
    c += uC3 * edge * (0.2 + 0.8 * uBeat);
    vec3 prev = prevAt(p * 0.998) * 0.6;
    o = vec4(mix(prev, c, 0.6), 1.0);
}
"""

    // ---- twenty more (0.2.0) ----

    /** Scope: the waveform plotted against itself a few samples on, an XY trace on a phosphor screen. */
    private const val SCOPE = COMMON + """
vec2 trace(float x) {
    vec2 v = vec2(wave(x), wave(x + 0.12));
    return v / (0.35 + length(v));      // soft-limited, so a quiet track and a loud one both fill the screen
}
void main() {
    vec2 p = centred();
    vec3 prev = decay(prevAt(p * 0.994), 0.88);
    float s = 0.52;
    float d = 9.0;
    if (length(p) < s * 0.82 + 0.04) {
        mat2 m = rot(uTime * 0.06);
        vec2 a = m * trace(0.0) * s;
        for (int i = 1; i <= 40; i++) {
            vec2 b = m * trace(float(i) / 40.0 * 0.86) * s;
            d = min(d, seg(p, a, b));
            a = b;
        }
    }
    float core = 1.0 - smoothstep(0.0, 0.0032, d);
    float halo = exp(-d * 55.0) * 0.12;
    vec3 c = mix(uC1, vec3(1.0), 0.35 * core) * (core * (0.55 + 0.6 * uBeat) + halo * (0.4 + uBass));
    // the graticule, under the trace and never fed back (max, not plus)
    vec2 g = abs(fract(p * 10.0 + 0.5) - 0.5) / 10.0;
    float grid = (1.0 - smoothstep(0.0, 0.0012, min(g.x, g.y))) * step(max(abs(p.x), abs(p.y)), 0.45);
    o = vec4(max(min(prev + c, vec3(1.5)), uC1 * grid * 0.07), 1.0);
}
"""

    /** Plasma: the old demoscene plasma, sines on sines, its bands pushed about by the bass. */
    private const val PLASMA = COMMON + """
void main() {
    vec2 q = centred();
    vec2 p = q * 3.2;
    float t = uTime * 0.35;
    float v = sin(p.x * 1.4 + t) + sin(p.y * 1.9 - t * 1.3) + sin((p.x + p.y) * 1.2 + t * 0.6)
            + sin(length(p - vec2(1.4 * sin(t * 0.5), 0.8 * cos(t * 0.4))) * 2.6 - t * 1.7);
    v += 0.9 * uBass * sin(length(p) * 2.5 - uTime * 2.0);
    float k = 0.5 + 0.5 * sin(v * 1.7);
    float k2 = 0.5 + 0.5 * sin(v * 3.4 + uTime * 0.5);
    vec3 c = mix(uC1, uC2, k) * (0.10 + 0.55 * k * k * k) + uC3 * pow(k2, 8.0) * (0.3 + 0.8 * uBeat);
    o = vec4(mix(prevAt(q * 0.996), c, 0.4), 1.0);
}
"""

    /** Vinyl: the cover as the label of a record turning at 33, the spectrum a ring of bars around it. */
    private const val VINYL = COMMON + """
void main() {
    vec2 p = centred();
    float r = length(p);
    float R = 0.30, L = 0.115;
    // the bars stream outward; nothing is fed back from the record itself, or its sheen would too
    vec3 col = r * 0.988 > R + 0.012 ? decay(prevAt(p * 0.988), 0.90) : vec3(0.0);
    // angle from the top, folded left to right: the bass at the top, the treble at the bottom
    float th = abs(atan(p.x, p.y)) / 3.14159265;
    float bi = floor(th * 32.0);
    float s = spec((bi + 0.5) / 32.0);
    float gap = step(0.18, fract(th * 32.0)) * step(fract(th * 32.0), 0.82);
    float r0 = R + 0.015;
    float bar = gap * step(r0, r) * step(r, r0 + 0.015 + 0.15 * s);
    col = max(col, mix(uC1, uC2, (bi + 0.5) / 32.0) * bar * (0.55 + 0.6 * uBeat));
    if (r < R) {
        float a = atan(p.y, p.x);
        float groove = 0.5 + 0.5 * sin(r * 900.0);
        // the sheen stays put while the record turns under it, as it does under a lamp
        float sheen = pow(abs(cos(a - 0.8)), 24.0) * (0.35 + 0.65 * groove);
        col = vec3(0.025) + mix(uC3, vec3(1.0), 0.5) * sheen * 0.16 * (0.8 + 0.4 * uBass);
        col += vec3(0.25) * (1.0 - smoothstep(0.0, 0.002, abs(r - R + 0.002)));
        if (r < L) {
            vec2 q = rot(uTime * 3.49) * p / (2.0 * L) + 0.5;     // 33 and a third turns a minute
            col = art(vec2(q.x, 1.0 - q.y)) * (0.85 + 0.25 * uBeat);
            col *= 1.0 - (1.0 - smoothstep(0.0, 0.0035, abs(r - L + 0.002))) * 0.6;
            if (r < 0.007) col = vec3(0.0);
        }
    }
    o = vec4(min(col, vec3(1.5)), 1.0);
}
"""

    /** Fireworks: a shell bursts every second or so; the sparks fall and twinkle, the trails are the feedback. */
    private const val FIREWORKS = COMMON + """
void main() {
    vec2 p = centred();
    vec3 prev = decay(prevAt(p + vec2(0.0, 0.0012)), 0.93);
    vec3 col = vec3(0.0);
    const float SLOT = 0.8;
    mat2 turn = rot(TAU / 24.0);
    for (int k = 0; k < 3; k++) {
        float slot = floor(uTime / SLOT) - float(k);
        float age = uTime - slot * SLOT;          // 0 .. 2.4 seconds
        float id = mod(slot, 997.0);
        vec2 c = vec2((hash(vec2(id, 1.0)) - 0.5) * 1.1, 0.04 + 0.26 * hash(vec2(id, 7.0)));
        // nowhere near this shell: skip its sparks
        if (abs(p.x - c.x) > 0.36 || p.y > c.y + 0.34) continue;
        float spread = 1.0 - exp(-age * 3.0);
        float life = 1.0 - smoothstep(1.3, 2.4, age);
        vec3 tint = mix(uC1, uC2, hash(vec2(id, 3.0)));
        vec2 dir = rot(hash(vec2(id, 5.0)) * TAU) * vec2(1.0, 0.0);
        float b = 0.0;
        for (int j = 0; j < 24; j++) {
            float fj = float(j);
            float v = 0.20 + 0.07 * hash(vec2(fj, id));
            vec2 d = p - (c + dir * v * spread - vec2(0.0, 0.055 * age * age));
            float tw = age > 1.0 ? 0.55 + 0.45 * sin(age * 37.0 + fj * 2.3) : 1.0;
            b += exp(-dot(d, d) * 9000.0) * tw;
            dir = turn * dir;
        }
        float flash = exp(-dot(p - c, p - c) * 400.0) * max(0.0, 1.0 - age * 6.0);
        col += (tint * b * 1.4 + mix(tint, vec3(1.0), 0.6) * flash) * life;
    }
    col *= 0.85 + 0.5 * uBeat;
    o = vec4(min(prev + col, vec3(1.5)), 1.0);
}
"""

    /** Contour: a topographic map of drifting terrain; each altitude lit by its own band, the bass a hill in the middle. */
    private const val CONTOUR = COMMON + """
void main() {
    vec2 p = centred();
    float t = uTime * 0.04;
    float h = fbm(p * 1.1 + vec2(t, -t * 0.7)) + 0.5 * noise(p * 0.9 - vec2(t * 0.5, t));
    h += 0.22 * uBass * exp(-dot(p, p) * 9.0);
    float v = h * 11.0;
    float dl = abs(fract(v - 0.5) - 0.5) / max(fwidth(v), 1e-4);
    float major = 1.0 - step(0.5, mod(floor(v + 0.5), 5.0));      // every fifth line, as on a survey map
    float line = 1.0 - smoothstep(0.5 + 0.6 * major, 1.6 + 0.8 * major, dl);
    float s = spec(fract(h * 1.3));
    vec3 c = mix(uC1, uC2, smoothstep(0.3, 0.9, h)) * line * (0.25 + 0.9 * s + 0.6 * major) * (0.85 + 0.4 * uBeat);
    o = vec4(max(c + uC3 * 0.025 * h, prevAt(p) * 0.8), 1.0);
}
"""

    /** Tesla: a plasma globe; arcs crawl from the core to the glass, each lit by its own band, striking anew a few times a second. */
    private const val TESLA = COMMON + """
void main() {
    vec2 p = centred();
    vec3 prev = decay(prevAt(p * 0.996), 0.72);
    float r = length(p);
    float a = r > 0.001 ? atan(p.y, p.x) : 0.0;
    const float R = 0.40;
    vec3 col = vec3(0.0);
    float strike = mod(floor(uTime * 2.5), 997.0);
    float tip = exp(-abs(r - R) * 50.0);
    if (r < R) {
        for (int k = 0; k < 7; k++) {
            float fk = float(k);
            float base = fk / 7.0 * TAU + uTime * (0.15 + 0.1 * fk / 7.0) + hash(vec2(fk, strike)) * 0.6;
            float bend = (noise(vec2(r * 7.0 + fk * 13.0, uTime * 1.3 + fk)) - 0.5) * 1.6
                       + (noise(vec2(r * 23.0 + fk * 7.0, uTime * 4.0)) - 0.5) * 0.35;
            float ang = base + bend * smoothstep(0.03, R, r);
            float da = abs(mod(a - ang + 3.14159265, TAU) - 3.14159265) * r;
            float s = spec((fk + 0.5) / 7.0 * 0.8);
            float w = 0.0018 + 0.0025 * s;
            float glow = (1.0 - smoothstep(0.0, w, da)) * 1.1 + exp(-da * 70.0) * 0.18 + exp(-da * 40.0) * tip * 0.5;
            col += mix(uC1, uC2, fk / 6.0) * glow * (0.35 + 1.2 * s);
        }
        col += uC1 * 0.03 * (1.0 - r / R);
    }
    col += uC3 * exp(-abs(r - R) * 90.0) * 0.35;
    col += mix(uC3, vec3(1.0), 0.5) * (1.0 - smoothstep(0.0, 0.045, r)) * (0.8 + uBass);
    o = vec4(min(prev + col, vec3(1.5)), 1.0);
}
"""

    /** Wormhole: a tunnel lined with the album art, flown through at a steady clip, its seams lit on the beat. */
    private const val WORMHOLE = COMMON + """
void main() {
    vec2 p = centred();
    // the far end wanders, so the tunnel bends
    vec2 q = p - 0.07 * vec2(sin(uTime * 0.31), cos(uTime * 0.23)) * (1.0 - smoothstep(0.0, 0.5, length(p)));
    float r = max(length(q), 0.001);
    float a = atan(q.y, q.x);
    float depth = 0.35 / r + uTime * 0.9;
    // four times round (an even number: the mirrored repeat below has period 2, so the angle's wrap has no seam)
    float u = a / TAU * 4.0 + 0.08 / r * sin(uTime * 0.2);
    vec2 m = abs(mod(vec2(u, depth * 1.5), 2.0) - 1.0);
    // no cover: the demoscene checkerboard in the cover colours (the tile count round is even, so it meets)
    vec3 c = (uHasArt > 0.5 ? art(vec2(m.x, 1.0 - m.y)) : mix(uC1, uC2, mod(floor(u * 2.0) + floor(depth * 1.5), 2.0)) * 0.7)
           * (0.55 + 0.45 * uBass);
    // the tiles' seams: rings down the tunnel, and fainter lines along it
    vec2 g = 0.5 - abs(fract(vec2(u * 2.0, depth * 1.5)) - 0.5);
    float seam = max(1.0 - smoothstep(0.0, 0.035, g.y), 0.4 * (1.0 - smoothstep(0.0, 0.03, g.x)));
    c += uC3 * seam * (0.12 + 0.9 * uBeat);
    c *= smoothstep(0.0, 0.35, r);
    o = vec4(mix(prevAt(p * 1.01), c, 0.65), 1.0);
}
"""

    /** Harp: two dozen strings, each plucked by its own band of the spectrum, ringing in a standing wave. */
    private const val HARP = COMMON + """
void main() {
    vec2 p = centred();
    vec3 prev = prevAt(p) * 0.78;
    const float N = 24.0, W = 1.5, H = 0.42;
    float sp = W / N;
    float y = clamp((p.y + H) / (2.0 * H), 0.0, 1.0);
    float inY = step(abs(p.y), H);
    float k0 = floor((p.x + W * 0.5) / sp);
    vec3 col = vec3(0.0);
    for (int j = -1; j <= 1; j++) {
        float k = k0 + float(j);
        if (k < 0.0 || k > N - 1.0) continue;
        float u = (k + 0.5) / N;
        float s = spec(u);
        float mode = 1.0 + floor(hash(vec2(k, 4.0)) * 3.0);
        float x0 = -W * 0.5 + (k + 0.5) * sp;
        // far faster than the frame rate: with the feedback, what shows is the blur of a ringing string
        float disp = sp * 0.42 * min(s, 1.2) * sin(3.14159265 * y * mode) * sin(uTime * (150.0 + 60.0 * hash(vec2(k, 1.0))));
        float d = abs(p.x - x0 - disp);
        col += mix(uC1, uC2, u) * (1.0 - smoothstep(0.0, 0.0028, d)) * (0.35 + 0.9 * s) * inY;
        if (j == 0) {
            float peg = min(length(p - vec2(x0, H)), length(p - vec2(x0, -H)));
            col += uC3 * (1.0 - smoothstep(0.004, 0.007, peg));
        }
    }
    float rails = (1.0 - smoothstep(0.0, 0.002, abs(abs(p.y) - H - 0.012))) * step(abs(p.x), W * 0.5);
    o = vec4(min(prev + col * 0.45 + uC3 * rails * 0.06, vec3(1.5)), 1.0);
}
"""

    /** Lava: a lava lamp; six blobs rise and sink and run together, each swelling with its own band. */
    private const val LAVA = COMMON + """
void main() {
    vec2 p = centred();
    float f = 0.0;
    float t = uTime * 0.16;
    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        vec2 c = vec2(0.55 * sin(t * (0.7 + 0.13 * fi) + fi * 1.7), 0.32 * sin(t * (0.9 + 0.11 * fi) + fi * 2.9));
        float rad = 0.11 + 0.05 * spec(fi / 6.0 + 0.04);
        vec2 d = p - c;
        f += rad * rad / max(dot(d, d), 1e-5);
    }
    float body = smoothstep(0.95, 1.05, f);
    float glow = smoothstep(1.0, 4.0, f);                     // brightest in the thick of a blob, lit from inside
    vec3 col = mix(uC2 * 0.08, vec3(0.0), vUv.y);             // and the lamp's warmth at the bottom
    col += uC1 * 0.10 * smoothstep(0.5, 1.0, f) * (1.0 - body);
    col += mix(uC1 * 0.5, mix(uC1, vec3(1.0), 0.3), glow) * body * (0.75 + 0.35 * uBass);
    o = vec4(mix(prevAt(p + vec2(0.0, -0.001)), col, 0.5), 1.0);
}
"""

    /** Spiro: three spirograph pens drawing at once; every 16 seconds the gears change, and the slow fade lets each figure build. */
    private const val SPIRO = COMMON + """
vec2 pen(float th, float r, float d) {
    float k = (1.0 - r) / r;
    return vec2(cos(th), sin(th)) * (1.0 - r) + vec2(cos(k * th), -sin(k * th)) * d;
}
void main() {
    vec2 p = centred();
    vec3 prev = decaySlow(prevAt(p), 0.999);     // about eight seconds from full to black: a figure has time to close
    float slot = mod(floor(uTime / 16.0), 997.0);
    vec3 col = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        // a gear ratio with small numbers, so the figure closes within a few turns
        float r = (2.0 + floor(hash(vec2(slot, fi)) * 5.0)) / (7.0 + floor(hash(vec2(slot, fi + 9.0)) * 6.0));
        float d = r * (0.5 + 0.7 * hash(vec2(slot, fi + 4.0)));
        float sc = (0.44 - 0.1 * fi) / (1.0 - r + d);
        float w = 4.0 + fi;
        float th = uTime * w;
        mat2 m = rot(uTime * 0.02 * (1.0 + fi));
        // the stretch of curve the pen covered since the last frame (and a little more), in six pieces
        vec2 a = m * pen(th, r, d) * sc;
        float dist = 9.0;
        for (int j = 1; j <= 6; j++) {
            vec2 b = m * pen(th - w * 0.021 * float(j) / 6.0, r, d) * sc;
            dist = min(dist, seg(p, a, b));
            a = b;
        }
        vec3 tint = i == 0 ? uC1 : i == 1 ? uC2 : uC3;
        float lvl = i == 0 ? uBass : i == 1 ? uMid : uTreb;
        col += tint * (1.0 - smoothstep(0.0, 0.0028, dist)) * (0.55 + 0.6 * lvl + 0.4 * uBeat);
    }
    o = vec4(min(prev + col, vec3(1.5)), 1.0);
}
"""

    /** Planet: the cover wrapped round a turning globe, its rings lit band by band from the spectrum. */
    private const val PLANET = COMMON + """
void main() {
    vec2 p = centred();
    vec2 sg = floor(p * 90.0);
    float star = step(0.985, hash(sg)) * (0.3 + 0.7 * hash(sg + 3.0)) * (1.0 - smoothstep(0.1, 0.4, length(fract(p * 90.0) - 0.5)));
    vec3 col = vec3(0.45) * star;
    const float R = 0.22;
    mat2 tilt = rot(-0.32);
    vec2 rp = tilt * p;
    float rr = length(vec2(rp.x, rp.y / 0.26));
    float rt = (rr - R * 1.35) / (R * 1.05);                   // 0..1 across the rings
    vec4 ring = vec4(0.0);
    if (rt > 0.0 && rt < 1.0) {
        float band = floor(rt * 24.0);
        float s = spec(band / 24.0);
        float edge = smoothstep(0.0, 0.15, fract(rt * 24.0)) * smoothstep(0.0, 0.15, 1.0 - fract(rt * 24.0));
        float cassini = smoothstep(0.02, 0.05, abs(rt - 0.62));
        ring = vec4(mix(uC2, uC3, rt) * (0.12 + 0.95 * s) * (0.8 + 0.5 * uBeat), (0.35 + 0.5 * edge) * cassini);
    }
    if (rp.y > 0.0) col = mix(col, ring.rgb, ring.a);        // the far half, behind the planet
    float r = length(p);
    if (r < R) {
        vec3 n = vec3(p, sqrt(R * R - r * r)) / R;
        vec3 l = normalize(vec3(-0.6, 0.45, 0.65));
        float lit = max(dot(n, l), 0.0);
        n.xy = tilt * n.xy;
        float lon = atan(n.x, n.z) + uTime * 0.12;
        float lat = asin(clamp(n.y, -1.0, 1.0));
        // longitude over pi: period 2 round the globe, so the mirrored repeat meets itself at the back
        float u = abs(mod(lon / 3.14159265, 2.0) - 1.0);
        vec3 surf = art(vec2(u, 1.0 - (lat / 3.14159265 + 0.5)));
        if (uHasArt < 0.5) surf *= 0.75 + 0.25 * sin(lat * 18.0 + 2.0 * noise(vec2(lon * 2.0, lat * 6.0)));   // no cover: a gas giant's bands
        col = surf * (0.06 + 1.05 * lit) + uC1 * pow(1.0 - n.z, 3.0) * 0.5;
    }
    if (rp.y <= 0.0) col = mix(col, ring.rgb, ring.a);       // the near half, in front of it
    o = vec4(max(col, prevAt(p) * 0.82), 1.0);
}
"""

    /** Flame: the spectrum is the heat along the floor; the frame rises through turbulence and cools in the cover's colours. */
    private const val FLAME = COMMON + """
void main() {
    vec2 p = centred();
    float t = uTime;
    // the turbulence rises with the flames
    vec2 j = vec2(noise(vec2(p.x * 7.0, p.y * 4.0 - t * 2.2)), noise(vec2(p.x * 6.0 + 4.0, p.y * 5.0 - t * 1.9))) - 0.5;
    vec2 q = p - vec2(j.x * 0.008, 0.008 + 0.003 * uBass + j.y * 0.003);
    // the heat is kept in alpha, which the screen pass ignores, as 1 - heat: every other preset writes
    // alpha 1, so a switch to this one starts cold. Three taps across: the heat spreads sideways too.
    vec2 uq = q / aspect() + 0.5;
    vec2 px = vec2(3.0 / uRes.x, 0.0);
    float h = 1.0 - (2.0 * texture(uPrev, uq).a + texture(uPrev, uq + px).a + texture(uPrev, uq - px).a) * 0.25;
    // a cooling map that rises with the flames carves the sheet into tongues
    float n = noise(vec2(p.x * 14.0, p.y * 5.0 - t * 2.4));
    h = max(h * 0.992 - 0.003 - 0.032 * n * n, 0.0);
    // the floor: hot spots, new every frame, as hot as the band under them is loud
    float x = vUv.x;
    float spot = hash(vec2(floor(x * 160.0), mod(floor(t * 60.0), 997.0)));
    float fuel = (0.3 + 1.5 * spec(abs(x - 0.5) * 2.0)) * (0.3 + 0.9 * spot) * (0.95 + 0.25 * uBeat);   // embers even in a quiet room
    h = max(h, (1.0 - smoothstep(0.0, 0.015, p.y + 0.5)) * min(1.0, fuel));
    // heat to colour through the cover's colours: the first, then the second, then white at the core
    vec3 col = uC1 * smoothstep(0.02, 0.4, h) * 0.55 + uC2 * smoothstep(0.25, 0.75, h) * 0.75 + vec3(1.0) * smoothstep(0.6, 1.0, h) * 0.5;
    o = vec4(col, 1.0 - h);
}
"""

    /** Moire: two sets of fine rings from two drifting centres; where they cross, slow interference blooms. */
    private const val MOIRE = COMMON + """
void main() {
    vec2 p = centred();
    float t = uTime;
    vec2 c1 = vec2(0.32 * sin(t * 0.13), 0.18 * cos(t * 0.17));
    vec2 c2 = vec2(-0.30 * sin(t * 0.11 + 1.0), -0.16 * cos(t * 0.15 + 2.0));
    const float K = 170.0;
    float a = pow(0.5 + 0.5 * cos(length(p - c1) * K - t * 2.0), 6.0);
    float b = pow(0.5 + 0.5 * cos(length(p - c2) * K - t * 2.0), 6.0);
    // dim lines; what glows is where they cross, and that is the moire
    vec3 col = uC1 * a * (0.10 + 0.2 * uBass) + uC2 * b * (0.10 + 0.2 * uMid) + uC3 * a * b * (0.8 + 1.2 * uBeat);
    col *= 1.0 - smoothstep(0.35, 1.0, length(p));
    o = vec4(mix(prevAt(p), col, 0.6), 1.0);
}
"""

    /** Glitch: the cover cut into strips that slip on the beat, its colour channels pulled apart, the sides smeared like a broken stream. */
    private const val GLITCH = COMMON + """
vec3 card(vec2 uv) {
    if (uHasArt > 0.5) return texture(uArt, vec2(uv.x, 1.0 - uv.y)).rgb;
    // no cover: colour bars in the cover colours, a test card to break instead
    if (uv.y < 0.22) return vec3(floor(uv.x * 6.0) / 5.0) * 0.8;
    float b = floor(uv.x * 7.0);
    return b < 1.0 ? vec3(0.8) : b < 2.0 ? uC1 : b < 3.0 ? uC2 : b < 4.0 ? uC3 : b < 5.0 ? uC1 * 0.55 : b < 6.0 ? uC2 * 0.55 : uC3 * 0.55;
}
void main() {
    vec2 p = centred();
    float slot = mod(floor(uTime * 8.0), 997.0);
    float rows = 6.0 + floor(hash(vec2(slot, 2.0)) * 20.0);
    float row = floor(vUv.y * rows);
    // the beat, and now and then out of nowhere
    float amt = 0.6 * uBeat + 0.5 * step(0.93, hash(vec2(slot, 9.0)));
    float shift = (hash(vec2(row, slot + 1.0)) - 0.5) * 0.3 * amt * step(0.55, hash(vec2(row, slot)));
    vec2 uv = vec2(p.x + shift, p.y) / 0.86 + 0.5;
    float split = 0.004 + 0.02 * (uTreb + uBeat);
    vec3 c = vec3(card(uv + vec2(split, 0.0)).r, card(uv).g, card(uv - vec2(split, 0.0)).b);
    c *= (0.88 + 0.12 * sin(vUv.y * uRes.y * 3.14159265)) * (0.85 + 0.3 * uBeat);
    // stale blocks, as when a stream loses a keyframe: they keep the last frame and slide
    vec2 blk = floor(vUv * vec2(24.0, 13.5));
    float mosh = step(0.92 - 0.1 * uBeat, hash(blk + mod(floor(uTime * 3.0), 997.0)));
    vec3 col;
    if (step(abs(uv.x - 0.5), 0.5) * step(abs(uv.y - 0.5), 0.5) > 0.5) col = mosh > 0.5 ? prevAt(p + vec2(0.0, 0.003)) : c;
    else col = prevAt(vec2(p.x * 0.985, p.y)) * 0.94;         // outside the cover: slipped strips smear outward
    o = vec4(col, 1.0);
}
"""

    /** Helix: a double helix turning across the screen, its rungs lit by the spectrum. */
    private const val HELIX = COMMON + """
void main() {
    vec2 sp = centred();
    vec3 prev = decay(prevAt(sp * 0.995 + vec2(0.0018, 0.0)), 0.80);
    vec2 p = rot(-0.18) * sp;
    float axis = 0.07 * sin(p.x * 1.6 + uTime * 0.3);
    float axisSlope = 0.112 * cos(p.x * 1.6 + uTime * 0.3);
    float A = 0.15 + 0.03 * uBass;
    float ph = p.x * 6.5 - uTime * 1.1;
    float ya = axis + A * sin(ph), yb = axis - A * sin(ph);
    float za = cos(ph);
    float sa = axisSlope + A * 6.5 * cos(ph), sb = axisSlope - A * 6.5 * cos(ph);
    // thicker and brighter on the near side
    float wa = 0.004 + 0.006 * (0.5 + 0.5 * za), wb = 0.004 + 0.006 * (0.5 - 0.5 * za);
    float la = 1.0 - smoothstep(wa * 0.5, wa, abs(p.y - ya) / sqrt(1.0 + sa * sa));
    float lb = 1.0 - smoothstep(wb * 0.5, wb, abs(p.y - yb) / sqrt(1.0 + sb * sb));
    float rx = p.x * 7.0;
    float ri = floor(rx + 0.5);
    float s = spec(fract(ri / 24.0 + 0.5));
    float rung = (1.0 - smoothstep(0.0, 0.0035, abs(rx - ri) / 7.0)) * step(min(ya, yb), p.y) * step(p.y, max(ya, yb));
    vec3 col = (za > 0.0 ? uC2 : uC1) * (za > 0.0 ? lb : la) * (0.3 + 0.2 * (1.0 - abs(za)));
    col = mix(col, mix(uC3, vec3(1.0), 0.2) * (0.15 + 1.1 * s) * (0.8 + 0.5 * uBeat), rung);
    col = mix(col, (za > 0.0 ? uC1 : uC2) * 1.1, za > 0.0 ? la : lb);
    o = vec4(max(col, prev), 1.0);
}
"""

    /** Mandala: the last frame folded eight ways and turned; a spoke traced from the waveform feeds it and the symmetry grows the rest. */
    private const val MANDALA = COMMON + """
void main() {
    vec2 p = centred();
    float r = length(p);
    float a = r > 0.001 ? atan(p.y, p.x) : 0.0;
    const float SEG = TAU / 8.0;
    float fa = abs(mod(a + uTime * 0.04, SEG) - SEG * 0.5);
    vec2 k = vec2(cos(fa), sin(fa)) * r;
    vec3 prev = decay(prevAt(rot(0.012 + 0.02 * uMid) * k * (0.985 - 0.012 * uBass)), 0.955);
    // drawn in the folded frame, so it is already symmetric (the fold samples one wedge of the last frame)
    float x = clamp(r * 2.0, 0.0, 1.0);
    float d = abs(k.y - (0.03 + 0.04 * wave(x)));
    float spoke = (1.0 - smoothstep(0.0, 0.003, d)) * step(0.05, r) * step(r, 0.46);
    vec3 col = mix(uC1, uC2, x) * spoke * (0.3 + 0.8 * spec(x)) * (0.8 + 0.6 * uBeat);
    o = vec4(min(prev + col, vec3(1.5)), 1.0);
}
"""

    /** Radar: a sweep round every three seconds; the spectrum comes back as a ring of returns that fades on the phosphor. */
    private const val RADAR = COMMON + """
void main() {
    vec2 p = centred();
    float r = length(p);
    float a = r > 0.001 ? atan(p.y, p.x) : 0.0;
    const float R = 0.44;
    vec3 prev = decaySlow(prevAt(p), 0.995);     // the trace lasts about one turn of the sweep
    float sweep = mod(-uTime * TAU / 3.0, TAU) - 3.14159265;     // clockwise
    float behind = mod(a - sweep, TAU);                           // how far the sweep has gone past this angle
    float inside = step(r, R);
    // soft at both ends and wider than a frame's turn (0.035): a hard leading edge leaves a spoke on the
    // phosphor at every frame, and a narrow one leaves the returns as a row of beads
    float lead = smoothstep(0.0, 0.04, behind);
    float beam = lead * (1.0 - smoothstep(0.04, 0.25, behind)) * inside;
    float edge = lead * (1.0 - smoothstep(0.04, 0.09, behind)) * inside;
    // loud bands return further out: one sweep plots the spectrum round the scope
    float s = spec(abs(a) / 3.14159265);
    float ret = (1.0 - smoothstep(0.002, 0.01, abs(r - (0.12 + 0.3 * min(s, 1.0))))) * edge;
    vec3 col = uC1 * beam * 0.05 + mix(uC1, vec3(1.0), 0.35) * ret * (0.9 + 0.5 * uBeat);
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        vec2 c = rot(fi * 1.9 + uTime * 0.02 * (fi - 2.0)) * vec2(0.1 + 0.06 * fi, 0.0);
        col += uC2 * (1.0 - smoothstep(0.004, 0.011, length(p - c))) * edge;
    }
    float rings = 1.0 - smoothstep(0.0, 0.0015, abs(fract(r / 0.11 + 0.5) - 0.5) * 0.11);
    float cross = 1.0 - smoothstep(0.0, 0.0015, min(abs(p.x), abs(p.y)));
    float grid = max(rings, cross) * inside * 0.12 + (1.0 - smoothstep(0.0, 0.003, abs(r - R))) * 0.35;
    o = vec4(max(min(prev + col, vec3(1.5)), uC1 * grid), 1.0);
}
"""

    /** Silk: the waveform as two threads mirrored about the middle; the frame streams away from them, so every moment leaves a ribbon. */
    private const val SILK = COMMON + """
void main() {
    vec2 p = centred();
    vec2 q = vec2(p.x * 0.997 + 0.0015 * sin(uTime * 0.6 + p.y * 9.0), p.y * (0.982 - 0.008 * uBass));
    vec3 prev = decay(prevAt(q), 0.95);
    float x = vUv.x;
    float e = 1.0 / 64.0;
    float amp = 0.16 * (0.7 + 0.6 * uBass) * smoothstep(0.0, 0.12, x) * smoothstep(0.0, 0.12, 1.0 - x);
    float y0 = amp * wave(x);
    float k = amp * (wave(x + e) - wave(x - e)) / (2.0 * e) / aspect().x;
    float d = min(abs(p.y - y0), abs(p.y + y0)) / sqrt(1.0 + k * k);
    float line = 1.0 - smoothstep(0.0, 0.003, d);
    vec3 c = mix(uC1, uC2, x) * line * (0.35 + 0.9 * spec(abs(x - 0.5) * 2.0)) * (0.8 + 0.6 * uBeat);
    o = vec4(min(prev + c, vec3(1.5)), 1.0);
}
"""

    /** Prism: a triangle struck on each beat and thrown outward, turning; the colour channels travel at different speeds and fan apart. */
    private const val PRISM = COMMON + """
float triEdge(vec2 p, float r) {
    // distance to the outline of an equilateral triangle (after Inigo Quilez's 2D distance functions)
    const float k = 1.7320508;
    p.x = abs(p.x) - r;
    p.y = p.y + r / k;
    if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
    p.x -= clamp(p.x, -2.0 * r, 0.0);
    return length(p);
}
void main() {
    vec2 p = centred();
    mat2 m = rot(0.010 + 0.02 * uMid);
    vec3 prev = decay(vec3(prevAt(m * p * 0.966).r, prevAt(m * p * 0.970).g, prevAt(m * p * 0.974).b), 0.985);
    // on the beat frame, and a slow strike of its own so a quiet room still has something
    float strike = max(step(0.85, uBeat), step(0.985, fract(uTime * 0.45)));
    float line = (1.0 - smoothstep(0.002, 0.006, triEdge(rot(uTime * 0.3) * p, 0.05))) * strike;
    o = vec4(min(prev + mix(vec3(1.0), uC3, 0.35) * line * 1.4, vec3(1.5)), 1.0);
}
"""

    /** Julia: a Julia set whose constant circles the edge of the Mandelbrot set, coloured by how fast each point escapes. */
    private const val JULIA = COMMON + """
void main() {
    vec2 p = centred();
    vec2 z = rot(uTime * 0.02) * p * 2.4;
    // the constant rides the rim of the Mandelbrot set's main cardioid, where the Julia sets are richest;
    // the bass nudges it in and out across the rim
    float th = uTime * 0.05;
    vec2 c = (vec2(cos(th), sin(th)) * 0.5 - vec2(cos(2.0 * th), sin(2.0 * th)) * 0.25) * (1.0 + 0.012 * (uBass - 0.5));
    float n = 0.0, m2 = 0.0;
    for (int i = 0; i < 64; i++) {
        z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
        m2 = dot(z, z);
        if (m2 > 64.0) break;
        n += 1.0;
    }
    vec3 col = uC3 * 0.08;                                     // never escaped: inside the set
    if (m2 > 64.0) {
        float nu = n + 1.0 - log2(log2(m2) * 0.5);             // the smooth escape count
        float band = 0.5 + 0.5 * cos(nu * 0.45 + uTime * 0.4);
        col = mix(uC1, uC2, band) * (0.04 + 1.3 * pow(clamp(nu / 40.0, 0.0, 1.0), 0.9)) * (0.85 + 0.4 * uBeat);
    }
    o = vec4(mix(prevAt(p), col, 0.5), 1.0);
}
"""

    val PRESETS = listOf(TUNNEL, HORIZON, FLOW, MIRROR, AURORA, RAIN, RIDGE, WARP, PETALS, RIPPLE, SHARDS,
        SCOPE, PLASMA, VINYL, FIREWORKS, CONTOUR, TESLA, WORMHOLE, HARP, LAVA, SPIRO,
        PLANET, FLAME, MOIRE, GLITCH, HELIX, MANDALA, RADAR, SILK, PRISM, JULIA)

    /**
     * The screen pass: soft tone-map, vignette, dither (the 8-bit feedback buffer bands otherwise).
     * highp, and a dither whose numbers stay small: the first version hashed vUv * 1920x1080 at mediump,
     * which the Tegra runs as 16-bit floats (max 65504), so past a diagonal line the dot product
     * overflowed, sin(inf) gave NaN and that part of the screen was black (2026-10-25, the "half screen").
     * Interleaved gradient noise on gl_FragCoord never leaves single digits.
     */
    const val COMPOSITE = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform float uTime, uFade;
in vec2 vUv;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv).rgb;
    c = c / (1.0 + c * 0.25);
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.9;
    vec2 fc = gl_FragCoord.xy + 5.588238 * mod(floor(uTime * 60.0), 64.0);
    float n = fract(52.9829189 * fract(dot(fc, vec2(0.06711056, 0.00583715))));
    c += (n - 0.5) / 255.0;
    o = vec4(c * uFade, 1.0);
}
"""
}
