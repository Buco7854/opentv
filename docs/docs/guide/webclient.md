# Web client

OpenTV also runs as a self-hosted web app: the same browsing, search, EPG,
catch-up, favorites and resume experience as the Android app, from any browser.
It ships as a single Docker image containing a small server that stores your
playlists in SQLite and proxies streams so the browser can play them.

Under the hood it is literally the same app: the Android client and the web
server share the same core modules (M3U parsing, content classification, the
Xtream client, EPG, catch-up, metadata enrichment and the SQLite data layer).
Only the UI differs.

The web UI is a touch-first cockpit: a persistent bottom dock (playlists
panel, Live / Movies / Series / Favorites / Search, downloads and settings),
floating panels, thin line icons, and a mini player that keeps video
playing in a docked card while you browse. It ships dark, light and
system-following themes and is designed to feel at home on large landscape
touchscreens (including in-car browsers) while scaling down to phones, where
the dock becomes a tab bar.

import ThemedImage from '@theme/ThemedImage';

<figure className="doc-screen">
  <ThemedImage
    alt="Browsing a movies category, with the bottom dock"
    sources={{ light: '/img/web/browse-light.png', dark: '/img/web/browse-dark.png' }}
  />
  <figcaption>Browsing movies: poster grid, quality badges, and the dock. Screenshots follow your theme.</figcaption>
</figure>

<div className="screens-grid">
  <figure className="doc-screen">
    <ThemedImage
      alt="The playlists panel opened from the dock"
      sources={{ light: '/img/web/playlists-panel-light.png', dark: '/img/web/playlists-panel-dark.png' }}
    />
    <figcaption>The dock's playlists panel: switch, add, refresh, edit.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="Per-channel guide sheet with replayable catch-up programmes"
      sources={{ light: '/img/web/guide-sheet-light.png', dark: '/img/web/guide-sheet-dark.png' }}
    />
    <figcaption>Per-channel guide with tap-to-replay catch-up.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="A movie detail page with play and download controls"
      sources={{ light: '/img/web/movie-detail-light.png', dark: '/img/web/movie-detail-dark.png' }}
    />
    <figcaption>Movie page: play, and the compact download control.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="Settings with the appearance section and sidebar"
      sources={{ light: '/img/web/settings-light.png', dark: '/img/web/settings-dark.png' }}
    />
    <figcaption>Settings: dark, light and system themes under Appearance.</figcaption>
  </figure>
</div>

<figure className="doc-screen doc-screen--phone">
  <ThemedImage
    alt="The web client on a phone, with the dock as a tab bar"
    sources={{ light: '/img/web/phone-light.png', dark: '/img/web/phone-dark.png' }}
  />
  <figcaption>On phones the dock becomes a tab bar.</figcaption>
</figure>

:::danger[No authentication: read this before deploying]
The web server has **no authentication and no user accounts**. Anyone who can
reach it can browse your playlists, play streams, and read your Xtream
credentials (they are embedded in stream URLs and editable in the UI).

Run it **behind an authenticating reverse proxy** (Authelia, Caddy
`basic_auth`, nginx `auth_basic`, a VPN, or a tailnet) and never expose the
port directly to the internet.
:::

## Quick start (Docker)

```bash
docker run -d \
  --name opentv-web \
  -p 127.0.0.1:8080:8080 \
  -v opentv-data:/data \
  ghcr.io/buco7854/opentv-web:latest
```

Then open `http://127.0.0.1:8080`. Playlists, guide data, favorites and resume
positions are stored in the `/data` volume (a SQLite database, the same schema
the Android app uses).

### docker-compose, behind Caddy with basic auth

```yaml
services:
  opentv-web:
    image: ghcr.io/buco7854/opentv-web:latest
    restart: unless-stopped
    volumes:
      - opentv-data:/data
    # no ports: only the proxy talks to it

  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy-data:/data

volumes:
  opentv-data:
  caddy-data:
```

```text
# Caddyfile - generate the hash with: caddy hash-password
tv.example.com {
    basic_auth {
        me $2a$14$...hashed-password...
    }
    reverse_proxy opentv-web:8080
}
```

### Configuration

| Environment variable    | Default    | Meaning                                                                                                                              |
| ----------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `PORT`                  | `8080`     | HTTP port the server listens on                                                                                                      |
| `OPENTV_DATA`           | `/data`    | Directory for the SQLite database                                                                                                    |
| `OPENTV_PAGE_SIZE`      | `50`       | Items per page in the web client's lists                                                                                             |
| `OPENTV_VIDEO_ENCODER`  | `libx264`  | Encoder for non-H.264 video (HEVC...). Set `copy` to turn transcoding off, or a hardware encoder like `h264_qsv` / `h264_nvenc` with a GPU |
| `OPENTV_X264_PRESET`    | `veryfast` | Software encode speed vs size (`ultrafast`...`slow`); only used by the default `libx264` encoder                                     |

## What it does

- **Playlists**: Xtream login, M3U link, or an uploaded `.m3u` file, with the
  same Xtream auto-detection for `get.php` links as the app.
- **Browse**: Live / Movies / Series tabs with categories, list or poster-grid
  view, quality badges, and now-playing lines with progress from your EPG.
- **Guide & catch-up**: per-channel guide sheet; past programmes replay via
  Xtream timeshift or `catchup-source` templates.
- **Search** across live, movies and series; **favorites** and **resume
  positions** stored server-side, shared by every browser you use.
- **Details**: movie/series/episode pages with panel metadata (Xtream) or
  keyless TVMaze/iTunes enrichment, cast rows and ratings, identical logic to
  the app, shared cache included.
- **Player**: HLS (hls.js), MPEG-TS (mpegts.js) and direct MP4/WebM through the
  built-in stream proxy, with track selection, speed, scaling modes,
  picture-in-picture and fullscreen.
- **Downloads**: the same offline queue as the app, run by the server. Movies
  and episodes download into the `/data` volume with pause (partial file kept),
  byte-exact resume via Range requests, retry and progress tracking; finished
  files play from any browser or can be saved to the device. Concurrency is
  limited (Settings) to respect provider connection caps.
- **Account page** with Xtream connection monitoring (active/max connections,
  expiry), throttled exactly like the app.

All provider traffic goes through the server, which keeps the app's frugal
behavior: conditional GETs for playlists and EPG, refresh throttling, and
hard caches for metadata.

## Limitations vs the Android app

- **Codecs**: browsers decode less than ExoPlayer, so for movies, series and
  catch-up the server remuxes through ffmpeg into fMP4 HLS, exposing every
  audio and subtitle track. Non-browser audio (AC3, E-AC3, DTS...) is always
  transcoded to AAC. Video is copied when the browser can play it (H.264
  everywhere, HEVC/H.265 where the browser decodes it natively, which the
  client detects per session) and transcoded to H.264 only for browsers that
  cannot, such as Firefox with HEVC. Copying is cheap; transcoding video is CPU
  heavy: on a box without a GPU, lower the cost with `OPENTV_X264_PRESET` or
  turn it off with `OPENTV_VIDEO_ENCODER=copy` (non-H.264 then plays only where
  the browser decodes it natively); with a GPU, point `OPENTV_VIDEO_ENCODER` at
  a hardware encoder (`h264_qsv`, `h264_nvenc`) for near-free transcoding. Live
  channels rely on the browser plus an on-demand audio-only transcode, so an
  unusual live video codec may still not play; those streams also play in the
  Android app.
- **Downloads** are stored on the server (the `/data` volume), not on the
  browser's device - use the save button on a finished download to copy it
  locally.
- The **User-Agent** and download settings are server-wide (they affect how
  the server talks to your provider), not per-browser.

## Running without Docker

```bash
./gradlew :server:installDist
PORT=8080 OPENTV_DATA=./server-data ./server/build/install/server/bin/server
```

Requires JDK 17+, Node.js 20+, and `ffmpeg` / `ffprobe` on `PATH` (they power
the remux that exposes tracks and transcodes non-browser audio and video; the
Docker image bundles them). The Gradle build compiles the React client in
`server/webapp` into the server's resources; the Docker build does this in its
own stage. For UI work, `cd server/webapp && npm run dev` serves the client on
:5173 with `/api` proxied to a server on :8080. See `server/webapp/README.md`
for the design system.
