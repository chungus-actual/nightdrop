"""Render Nightdrop's presets on a desktop GPU, straight from Shaders.kt.

    pip install -r tools/requirements.txt
    python tools/preview.py                       # every preset, no album art, into preview/
    python tools/preview.py --art cover.png       # with a cover (the Mirror, Shards and Horizon sun use it)
    python tools/preview.py --demo-art Tunnel Warp
    python tools/preview.py --demo-art --anim tour.webp Tunnel Horizon Petals   # an animated tour

Each preset runs its feedback loop for --seconds of synthetic 124 BPM music (a kick on the beat, a
moving spectrum, a waveform), then the composite pass: one PNG per preset plus sheet.png. GLSL ES 3.00
becomes 3.30 core with only the version line changed. Faithful to the device except for its precision
quirks (a mediump float is 16-bit on some GPUs): check new shaders on the device too.
"""
import argparse
import colorsys
import math
import re
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SHADERS = ROOT / "app/src/main/java/io/github/chungus_actual/nightdrop/Shaders.kt"
BANDS = 64
DEFAULT = [(0.10, 0.62, 0.62), (0.96, 0.62, 0.26), (0.62, 0.34, 0.96)]   # Palette.DEFAULT

ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
ap.add_argument("names", nargs="*", help="presets to render (default: all)")
ap.add_argument("--shaders", type=Path, default=SHADERS)
ap.add_argument("--art", type=Path, help="an album cover to feed the shaders")
ap.add_argument("--demo-art", action="store_true", help="a generated cover instead of a real one")
ap.add_argument("--out", type=Path, default=Path("preview"))
ap.add_argument("--size", default="1280x720")
ap.add_argument("--seconds", type=float, default=6.0)
ap.add_argument("--fps", type=int, default=60)
ap.add_argument("--anim", type=Path, help="also write an animated WebP touring the presets")
ap.add_argument("--anim-size", default="800x450")
ap.add_argument("--anim-seconds", type=float, default=3.0, help="seconds of each preset in the tour")
ap.add_argument("--anim-fps", type=int, default=15)
args = ap.parse_args()
W, H = (int(v) for v in args.size.split("x"))
args.out.mkdir(parents=True, exist_ok=True)

kt = args.shaders.read_text(encoding="utf-8")
raw = lambda name: re.search(rf'val {name} = """(.*?)"""', kt, re.S).group(1)
gl3 = lambda s: s.replace("#version 300 es", "#version 330 core")
VERTEX, COMMON, COMPOSITE = gl3(raw("VERTEX")), raw("COMMON"), gl3(raw("COMPOSITE"))
bodies = dict(re.findall(r'private const val (\w+) = COMMON \+ """(.*?)"""', kt, re.S))
names = re.findall(r'"(\w+)"', re.search(r"val NAMES = listOf\((.*?)\)", kt, re.S).group(1))
order = re.search(r"val PRESETS = listOf\((.*?)\)", kt, re.S).group(1).replace(" ", "").replace("\n", "").split(",")
presets = [(n, gl3(COMMON + bodies[k])) for n, k in zip(names, order)]


def palette(img):
    """Palette.from(): hue bins weighted by saturation x value, the top three, lifted to glow."""
    s = np.asarray(img.convert("RGB").resize((24, 24), Image.BILINEAR), dtype=np.float32) / 255
    bins = np.zeros((12, 4))
    for r, g, b in s.reshape(-1, 3):
        h, sat, v = colorsys.rgb_to_hsv(r, g, b)
        w = sat * v
        if w < 0.08:
            continue
        bins[min(11, int(h * 360 / 30))] += (r * w, g * w, b * w, w)
    top = sorted([b for b in bins if b[3] > 0.5], key=lambda b: -b[3])[:3]
    out = []
    for b in top:
        h, sat, v = colorsys.rgb_to_hsv(*(b[:3] / b[3]))
        out.append(colorsys.hsv_to_rgb(h, max(sat, 0.45), max(v, 0.85)))
    return [out[i] if i < len(out) else DEFAULT[i] for i in range(3)]


def demo_cover(n=512):
    """A made-up cover: a dusk gradient, a sun, two ridges. Nobody's artwork."""
    y = np.linspace(0, 1, n)[:, None, None]
    sky = (1 - y) * np.array([0.98, 0.42, 0.36]) + y * np.array([0.18, 0.08, 0.36])
    img = np.repeat(sky, n, axis=1)
    yy, xx = np.mgrid[0:n, 0:n] / n
    sun = (xx - 0.5) ** 2 + (yy - 0.42) ** 2 < 0.16 ** 2
    img[sun] = (1.0, 0.82, 0.38)
    for k, (amp, base, col) in enumerate(((0.05, 0.70, (0.10, 0.36, 0.42)), (0.07, 0.82, (0.04, 0.16, 0.22)))):
        ridge = base + amp * np.sin(xx * (7 + 5 * k) + k) - 0.03 * np.cos(xx * 23)
        img[yy > ridge] = col
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).convert("RGBA")


ctx = moderngl.create_standalone_context(require=330)
vbo = ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], dtype="f4").tobytes())
cover = Image.open(args.art).convert("RGBA").resize((512, 512)) if args.art else (demo_cover() if args.demo_art else None)
has_art = cover is not None
# row 0 of the image is texture row 0, as GLUtils.texImage2D uploads a Bitmap (the shaders flip y themselves)
art = ctx.texture((512, 512), 4, (cover or Image.new("RGBA", (512, 512))).tobytes())
pal = palette(cover) if has_art else DEFAULT
audio = ctx.texture((BANDS, 2), 1)
audio.filter = (moderngl.LINEAR, moderngl.LINEAR)
comp = ctx.program(vertex_shader=VERTEX, fragment_shader=COMPOSITE)
comp_vao = ctx.vertex_array(comp, [(vbo, "2f", "aPos")])
screen = ctx.simple_framebuffer((W, H))
rng = np.random.default_rng(7)


def setu(prog, name, v):
    if name in prog:
        prog[name].value = v


def composite(tex, t):
    screen.use()
    tex.use(0)
    for k, v in (("uTex", 0), ("uTime", t), ("uFade", 1.0)):
        setu(comp, k, v)
    comp_vao.render(moderngl.TRIANGLES, vertices=3)
    return Image.frombytes("RGB", (W, H), screen.read(components=3)).transpose(Image.FLIP_TOP_BOTTOM)


def run(src, frames=None):
    """The preset's still after --seconds; with [frames], also its last --anim-seconds as tour frames."""
    prog = ctx.program(vertex_shader=VERTEX, fragment_shader=src)
    vao = ctx.vertex_array(prog, [(vbo, "2f", "aPos")])
    tex = [ctx.texture((W, H), 4) for _ in range(2)]
    for t in tex:
        t.filter = (moderngl.LINEAR, moderngl.LINEAR)
        t.repeat_x = t.repeat_y = False
    fbo = [ctx.framebuffer(color_attachments=[t]) for t in tex]
    for f in fbo:
        f.clear(0, 0, 0, 1)
    cur, beat, last_phase = 0, 0.0, 0.0
    i = np.arange(BANDS)
    total = int(args.fps * args.seconds)
    every = max(1, round(args.fps / args.anim_fps))
    first = total - int(args.fps * args.anim_seconds)
    aw, ah = (int(v) for v in args.anim_size.split("x"))
    for frame in range(total):
        t = frame / args.fps
        phase = (t * 124 / 60) % 1
        bass = 0.15 + 0.85 * math.exp(-phase * 6)
        beat = 1.0 if phase < last_phase else beat * 0.88
        last_phase = phase
        mid = 0.35 + 0.15 * math.sin(t * 1.7)
        treb = 0.25 + 0.15 * math.sin(t * 5.3) + 0.05 * rng.random()
        spec = (1 - i / BANDS) ** 1.5 * (0.5 + 0.7 * bass * (i < 12)) + 0.25 * np.abs(np.sin(i * 0.4 + t * 3)) + 0.08 * rng.random(BANDS)
        wave = 0.6 * bass * np.sin(i * 0.5 + t * 20) + 0.2 * np.sin(i * 1.3 - t * 7)
        data = np.concatenate([np.clip(wave * 0.5 + 0.5, 0, 1), np.clip(spec * 0.5, 0, 1)]) * 255
        audio.write(data.astype(np.uint8).tobytes())
        fbo[1 - cur].use()
        tex[cur].use(0)
        art.use(1)
        audio.use(2)
        for k, v in (("uPrev", 0), ("uArt", 1), ("uAudio", 2), ("uRes", (W, H)), ("uTime", t), ("uBass", bass),
                     ("uMid", mid), ("uTreb", treb), ("uBeat", beat), ("uHasArt", 1.0 if has_art else 0.0),
                     ("uC1", pal[0]), ("uC2", pal[1]), ("uC3", pal[2])):
            setu(prog, k, v)
        vao.render(moderngl.TRIANGLES, vertices=3)
        cur = 1 - cur
        if frames is not None and frame >= first and (frame - first) % every == 0:
            frames.append(composite(tex[cur], t).resize((aw, ah), Image.LANCZOS))
    img = composite(tex[cur], args.seconds)
    for o in tex + fbo + [vao, prog]:
        o.release()
    return img


def font(px):
    for name in ("DejaVuSans.ttf", "arial.ttf", "Helvetica.ttc"):
        try:
            return ImageFont.truetype(name, px)
        except OSError:
            pass
    return ImageFont.load_default()


shots = []
tour = [] if args.anim else None
for n, s in presets:
    if args.names and n not in args.names:
        continue
    try:
        im = run(s, tour)
        im.save(args.out / f"{n.lower()}.png")
        shots.append((n, im))
        print("ok  ", n)
    except moderngl.Error as e:
        print("FAIL", n, str(e)[:600])
if shots:
    cols = 3 if len(shots) > 4 else len(shots)
    rows = (len(shots) + cols - 1) // cols
    tw, th = 640, 360
    sheet = Image.new("RGB", (cols * tw, rows * th))
    label = font(22)
    for k, (n, im) in enumerate(shots):
        tile = im.resize((tw, th), Image.LANCZOS)
        d = ImageDraw.Draw(tile)
        d.text((18, th - 38), n.upper(), font=label, fill=(236, 239, 234))
        sheet.paste(tile, ((k % cols) * tw, (k // cols) * th))
    if len(shots) % cols:
        # the empty last tile carries the name
        d = ImageDraw.Draw(sheet)
        x0, y0 = (len(shots) % cols) * tw, (rows - 1) * th
        big = font(44)
        w = d.textlength("NIGHTDROP", font=big)
        d.text((x0 + (tw * (cols - len(shots) % cols) - w) / 2, y0 + th / 2 - 30), "NIGHTDROP", font=big, fill=(232, 242, 240))
        d.rectangle((x0 + tw / 2 - 120, y0 + th / 2 + 30, x0 + tw / 2 + 120, y0 + th / 2 + 31), fill=(47, 208, 200))
    sheet.save(args.out / "sheet.png")
    print("sheet", args.out / "sheet.png")
if tour:
    tour[0].save(args.anim, save_all=True, append_images=tour[1:], duration=round(1000 / args.anim_fps),
                 loop=0, quality=72, method=4)
    print("tour ", args.anim, f"{len(tour)} frames, {args.anim.stat().st_size / 1e6:.1f} MB")
