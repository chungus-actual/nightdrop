package io.github.chungus_actual.nightdrop

/**
 * The presets. Each one is a FEEDBACK pass in the MilkDrop tradition: it reads the previous frame
 * (uPrev), warps it a little (zoom, rotate, drift, all driven by the audio), lets it fade, and draws
 * something new on top. The trails, tunnels and smears are that loop, not anything drawn per se.
 * Coordinates are centred and aspect-correct: p.x in about -0.9..0.9, p.y in -0.5..0.5.
 */
object Shaders {

    val NAMES = listOf("Tunnel", "Horizon", "Flow", "Mirror", "Aurora", "Rain", "Ridge", "Warp", "Petals", "Ripple", "Shards")

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

    val PRESETS = listOf(TUNNEL, HORIZON, FLOW, MIRROR, AURORA, RAIN, RIDGE, WARP, PETALS, RIPPLE, SHARDS)

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
