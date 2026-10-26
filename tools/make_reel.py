"""Make a reel for Nightdrop's screensaver (or the Couchy fork's wallpaper) from any video.

    python3 tools/make_reel.py in.mp4 out.mp4
    python3 tools/make_reel.py in.mkv out.mp4 --start 30 --length 90
    python3 tools/make_reel.py loop.mp4 out.mp4 --seamless            # the source already loops
    python3 tools/make_reel.py pixelart.mp4 out.mp4 --seamless --pixel
    python3 tools/make_reel.py in.mp4 out.mp4 --encoder libx265 --size 1920x1080

A reel plays on a loop for minutes at a time, so it has to loop without a visible seam. This takes the
segment [start + F, start + L + F) and crossfades its last F seconds into the segment's first F seconds
[start, start + F): the last frame then flows into the first. On a source that already loops the blend
is invisible; on a cut from a longer video it hides the jump.

Output: cropped to fill 16:9 at --size, the source frame rate (at most 60), HEVC Main 4:2:0, no audio,
faststart. 4:2:0 matters: the Tegra X1 (NVIDIA SHIELD) cannot decode 4:4:4 HEVC at 4K, and an encoder
fed RGB frames picks 4:4:4 unless told otherwise. Needs ffmpeg and ffprobe on the PATH; hevc_nvenc
needs an NVIDIA GPU, libx265 runs anywhere (slowly at 4K).
"""
import argparse
import subprocess
import sys


def probe(path, entries):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", entries,
                          "-of", "csv=p=0", path], capture_output=True, text=True, check=True).stdout
    return out.strip().split("\n")[0]


def main():
    ap = argparse.ArgumentParser(description="Make a seamless looping reel from a video.")
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--start", type=float, default=0.0, help="seconds into the source (default 0)")
    ap.add_argument("--length", type=float, default=120.0, help="longest reel in seconds (default 120)")
    ap.add_argument("--fade", type=float, default=2.0, help="seam crossfade in seconds (default 2)")
    ap.add_argument("--seamless", action="store_true", help="the source already loops: keep it whole, no crossfade")
    ap.add_argument("--pixel", action="store_true", help="pixel art: nearest-neighbour scaling")
    ap.add_argument("--size", default="3840x2160", help="output size (default 3840x2160)")
    ap.add_argument("--encoder", choices=["hevc_nvenc", "libx265"], default="hevc_nvenc")
    ap.add_argument("--quality", type=int, default=17, help="NVENC cq / x265 crf (lower is better, default 17)")
    a = ap.parse_args()

    w, h = (int(v) for v in a.size.lower().split("x"))
    dur = float(probe(a.src, "format=duration"))
    num, den = (probe(a.src, "stream=r_frame_rate").split("/") + ["1"])[:2]
    fps = min(60.0, float(num) / float(den or 1))
    f = a.fade
    length = min(a.length, dur - a.start) if a.seamless else min(a.length, dur - a.start - f)
    if length < max(6.0, 3 * f):
        sys.exit(f"only {dur - a.start:.1f} s from {a.start:.1f} s: too short for a reel with a {f:g} s seam")

    flags = ":flags=neighbor" if a.pixel else ""
    v = (f"fps={fps:g},scale={w}:{h}:force_original_aspect_ratio=increase{flags},crop={w}:{h},setsar=1,"
         f"format=yuv420p")
    if a.seamless:
        chain = f"[0:v]{v}[out]"
        take = length
    else:
        chain = (f"[0:v]{v},split[a][b];"
                 f"[a]trim=start={f}:end={length + f},setpts=PTS-STARTPTS[body];"
                 f"[b]trim=start=0:end={f},setpts=PTS-STARTPTS[head];"
                 f"[body][head]xfade=transition=fade:duration={f}:offset={length - f:.3f},format=yuv420p[out]")
        take = length + f
    if a.encoder == "hevc_nvenc":
        enc = ["-c:v", "hevc_nvenc", "-profile:v", "main", "-preset", "p7", "-tune", "hq", "-rc", "vbr",
               "-cq", str(a.quality), "-b:v", "30M", "-maxrate", "50M", "-bufsize", "60M"]
    else:
        enc = ["-c:v", "libx265", "-profile:v", "main", "-preset", "slow", "-crf", str(a.quality),
               "-x265-params", "log-level=error"]
    cmd = ["ffmpeg", "-nostdin", "-y", "-v", "error", "-ss", f"{a.start:.3f}", "-t", f"{take:.3f}", "-i", a.src,
           "-filter_complex", chain, "-map", "[out]", "-an", *enc, "-g", str(int(round(fps * 2))),
           "-tag:v", "hvc1", "-movflags", "+faststart", a.out]
    subprocess.run(cmd, check=True)
    got = probe(a.out, "stream=width,height,pix_fmt,profile,r_frame_rate")
    print(f"{a.out}: {length:.0f} s at {fps:g} fps from {a.start:.0f} s of {dur:.0f} s -> {got}")


if __name__ == "__main__":
    main()
