# The feed

Nightdrop reads two GET endpoints under the `feed` base URL in `nightdrop.json`. Both are optional:
a server that answers only `/ambient` gets the plate and no pop facts. `examples/feed_server.py` is a
working one to start from.

| Request | Used by | Polled |
|---|---|---|
| `GET <feed>/ambient` | the screensaver's plate; the Couchy fork's feed line | every 60 s while showing |
| `GET <feed>/popfacts?title=&artist=&album=` | pop facts over the visualizer | once per song, again after 20 s, then every 20 s while `pending` (at most 5 asks) |

Both send `Authorization: Bearer <token>` when the config has a `token`. Anything but a 200 with a JSON
body counts as no answer. A failed `/ambient` keeps the last good answer for 30 minutes, then the plate
drops the feed. The read timeout is 30 s for `/ambient` and 40 s for `/popfacts`, so a server may
gather its answer on the request.

## /ambient

```json
{
  "items": [
    {"kicker": "Calendar", "headline": "Dinner with the neighbours at 7", "sub": "their place", "tone": "live", "tier": 1},
    {"kicker": "Bins", "headline": "Recycling goes out tonight", "tone": "needs", "tier": 2}
  ],
  "weather": {"temp": 72, "sky": "Sunny"}
}
```

| Field | Type | Meaning |
|---|---|---|
| `items` | list | in order of importance; the plate shows the first `plateItems` (default 3), the Couchy line the first |
| `items[].kicker` | string | small label above the headline |
| `items[].headline` | string | required; an item without one is skipped |
| `items[].sub` | string | optional smaller line under the headline |
| `items[].tone` | string | kicker colour: `needs` or `fault` warm orange, `live` teal, anything else amber |
| `items[].tier` | int | `1` on the first item makes it the larger hero line (default 3) |
| `weather.temp` | int | shown as `72°` after the date; leave the key out when unknown |
| `weather.sky` | string | shown after the temperature, lowercased |

Unknown fields are ignored, so a server can send more than this.

## /popfacts

```json
{
  "facts": [
    {"kind": "house", "text": "First heard in this house on March 3"},
    {"kind": "track", "label": "On Spotify", "text": "Filed under jazz funk and acid jazz"},
    {"kind": "trivia", "text": "The band formed in San Diego in the early 90s."}
  ],
  "pending": true
}
```

| Field | Type | Meaning |
|---|---|---|
| `facts` | list | shown in this order, one card every 20 s from 12 s into the song, at most 8 a song |
| `facts[].text` | string | required; keep it to a sentence |
| `facts[].kind` | string | picks the card's colour from the album art: `house` first colour, `track` second, anything else third |
| `facts[].label` | string | the card's small heading; default by kind: `house` "In this house", `track` "About the track", others "Trivia" |
| `pending` | bool | more facts are coming (a slow lookup in the background): ask again in 20 s |

A re-ask only adds facts whose text is new, so a server can return the whole list every time.

## TLS

| Setup | Config |
|---|---|
| plain http on your LAN | `"feed": "http://192.0.2.20:8891"` |
| https, certificate from a public CA | `"feed": "https://feed.example.com"` |
| https, your own CA | `"feed": "https://192.0.2.20:8891", "ca": "ca.pem"`, with `ca.pem` beside `nightdrop.json` (or the PEM inline). Only that CA is trusted for the feed, and if the file is missing the feed is off. |
