"""A small feed server for Nightdrop (and the Couchy fork's feed line). Python 3.9+, standard library only.

    python3 feed_server.py --items items.json --lat 40.71 --lon -74.01 --wikipedia
    python3 feed_server.py --facts facts.json --port 8891 --cert cert.pem --key key.pem --token s3cret

GET /ambient    the plate: your items (a JSON file, re-read when it changes) and the weather from
                Open-Meteo (free, no key) when --lat/--lon are given
GET /popfacts   pop facts for ?title=&artist=&album=: your own from --facts (a JSON object keyed
                "artist|title", "artist|*" or "*", re-read when it changes) first, then up to three
                sentences about the artist from Wikipedia when --wikipedia is on

docs/feed.md has the shapes. This is a starting point: a real one would read your calendar, your
home automation, your listening history. Only GET requests come in; with --token, a request without
`Authorization: Bearer <token>` gets a 401.
"""
import argparse
import json
import re
import ssl
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

UA = "nightdrop-feed-example/0.1 (https://github.com/chungus-actual/nightdrop)"
# WMO weather codes (Open-Meteo `weather_code`) as one or two words
SKY = {0: "Clear", 1: "Mostly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Fog",
       51: "Drizzle", 53: "Drizzle", 55: "Drizzle", 56: "Freezing drizzle", 57: "Freezing drizzle",
       61: "Light rain", 63: "Rain", 65: "Heavy rain", 66: "Freezing rain", 67: "Freezing rain",
       71: "Light snow", 73: "Snow", 75: "Heavy snow", 77: "Snow grains", 80: "Showers", 81: "Showers",
       82: "Heavy showers", 85: "Snow showers", 86: "Snow showers", 95: "Thunderstorms",
       96: "Thunderstorms", 99: "Thunderstorms"}
MUSIC = re.compile(r"\b(band|singer|musician|rapper|group|duo|trio|dj|composer|producer|songwriter|"
                   r"guitarist|pianist|drummer|orchestra|ensemble|artist|vocalist)\b", re.I)


def get_json(url, timeout=10):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


class Feed:
    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.items, self.items_mtime = [], None
        self.weather, self.weather_at = {}, 0.0
        self.wiki = {}          # artist -> (time, facts)
        self.facts, self.facts_mtime = {}, None

    def ambient(self):
        return {"items": self._items(), "weather": self._weather()}

    def _items(self):
        p = self.args.items
        if not p or not p.is_file():
            return []
        m = p.stat().st_mtime
        with self.lock:
            if m != self.items_mtime:
                try:
                    raw = json.loads(p.read_text(encoding="utf-8"))
                    self.items = [self._item(i) for i in raw if isinstance(i, dict) and i.get("headline")]
                    self.items_mtime = m
                except (OSError, ValueError) as e:
                    print(f"items: {e}")
            return self.items

    @staticmethod
    def _item(i):
        return {"kicker": str(i.get("kicker", "")), "headline": str(i["headline"]), "sub": str(i.get("sub", "")),
                "tone": str(i.get("tone", "")), "tier": int(i.get("tier", 3))}

    def _weather(self):
        a = self.args
        if a.lat is None or a.lon is None:
            return {}
        with self.lock:
            if time.time() - self.weather_at < 600:
                return self.weather
        q = urllib.parse.urlencode({"latitude": a.lat, "longitude": a.lon, "current": "temperature_2m,weather_code",
                                    "temperature_unit": "fahrenheit" if a.units == "f" else "celsius"})
        try:
            cur = get_json("https://api.open-meteo.com/v1/forecast?" + q)["current"]
            wx = {"temp": round(cur["temperature_2m"]), "sky": SKY.get(int(cur["weather_code"]), "")}
        except Exception as e:  # keep the last reading through a blip
            print(f"weather: {e}")
            return self.weather
        with self.lock:
            self.weather, self.weather_at = wx, time.time()
        return wx

    def popfacts(self, title, artist, album):
        mine = self._own_facts(title, artist)
        if not self.args.wikipedia or not artist:
            return {"facts": mine, "pending": False}
        return {"facts": mine + self._wikipedia(artist), "pending": False}

    def _own_facts(self, title, artist):
        """--facts: {"artist|title": [...], "artist|*": [...], "*": [...]}, keys case-insensitive; each
        fact a string (kind trivia) or {"kind", "label", "text"}."""
        p = self.args.facts
        if not p or not p.is_file():
            return []
        m = p.stat().st_mtime
        with self.lock:
            if m != self.facts_mtime:
                try:
                    raw = json.loads(p.read_text(encoding="utf-8"))
                    self.facts = {str(k).lower(): v for k, v in raw.items() if isinstance(v, list)}
                    self.facts_mtime = m
                except (OSError, ValueError, AttributeError) as e:
                    print(f"facts: {e}")
            table = self.facts
        out = []
        for key in (f"{artist}|{title}", f"{artist}|*", "*"):
            for f in table.get(key.lower(), []):
                f = {"text": f} if isinstance(f, str) else f
                if isinstance(f, dict) and f.get("text"):
                    out.append({"kind": str(f.get("kind", "trivia")), "label": str(f.get("label", "")), "text": str(f["text"])})
        return out

    def _wikipedia(self, artist):
        key = artist.lower()
        with self.lock:
            hit = self.wiki.get(key)
        if hit and time.time() - hit[0] < 86400:
            return hit[1]
        facts = []
        for page in (artist, f"{artist} (band)", f"{artist} (musician)", f"{artist} (singer)"):
            try:
                s = get_json("https://en.wikipedia.org/api/rest_v1/page/summary/" + urllib.parse.quote(page.replace(" ", "_")))
            except Exception:
                continue
            if s.get("type") == "disambiguation" or not MUSIC.search(s.get("description", "") + " " + s.get("extract", "")[:200]):
                continue
            sentences = re.split(r"(?<=[.!?])\s+(?=[A-Z0-9\"'])", s.get("extract", ""))
            facts = [{"kind": "trivia", "label": "From Wikipedia", "text": t.strip()}
                     for t in sentences if 20 <= len(t.strip()) <= 160][:3]
            break
        with self.lock:
            self.wiki[key] = (time.time(), facts)
        return facts


def handler(feed, token):
    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            if token and self.headers.get("Authorization", "") != f"Bearer {token}":
                return self._send(401, {"error": "unauthorized"})
            u = urllib.parse.urlsplit(self.path)
            q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
            if u.path == "/ambient":
                return self._send(200, feed.ambient())
            if u.path == "/popfacts":
                return self._send(200, feed.popfacts(q.get("title", "")[:200], q.get("artist", "")[:200], q.get("album", "")[:200]))
            self._send(404, {"error": "not found"})

        def _send(self, code, body):
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *a):
            print(f"{self.address_string()} {fmt % a}")
    return H


def main():
    ap = argparse.ArgumentParser(description="An example feed server for Nightdrop.")
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8891)
    ap.add_argument("--items", type=Path, help="a JSON list of items for the plate (see items.json)")
    ap.add_argument("--lat", type=float)
    ap.add_argument("--lon", type=float)
    ap.add_argument("--units", choices=["f", "c"], default="f")
    ap.add_argument("--facts", type=Path, help="your own pop facts (see facts.json)")
    ap.add_argument("--wikipedia", action="store_true", help="pop facts about the artist from Wikipedia")
    ap.add_argument("--token", help="require this bearer token")
    ap.add_argument("--cert", help="serve https with this certificate (PEM)")
    ap.add_argument("--key", help="... and this key (PEM)")
    a = ap.parse_args()
    srv = ThreadingHTTPServer((a.bind, a.port), handler(Feed(a), a.token))
    if a.cert:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(a.cert, a.key)
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    print(f"feed on {'https' if a.cert else 'http'}://{a.bind}:{a.port}  (/ambient, /popfacts)")
    srv.serve_forever()


if __name__ == "__main__":
    main()
