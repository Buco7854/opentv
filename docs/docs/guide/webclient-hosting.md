---
title: Self-hosting
---

# Self-hosting

The web client is one Docker image. Run it, put it behind an authenticating
reverse proxy, and point it at the `/data` volume for its SQLite database.

:::danger[No authentication]
The server has no authentication of its own. Anyone who can reach it can use
your playlists and read your provider credentials. Always keep it behind a
reverse proxy with auth, a VPN, or a tailnet, and never expose the port to the
internet.
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
positions live in the `/data` volume (a SQLite database, the same schema the
Android app uses).

## docker-compose, behind Caddy with basic auth

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
# Caddyfile. Generate the password hash with: caddy hash-password
tv.example.com {
    basic_auth {
        me $2a$14$...hashed-password...
    }
    reverse_proxy opentv-web:8080
}
```

## Configuration

| Environment variable       | Default    | Meaning                                                                                                                              |
| -------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `PORT`                     | `8080`     | HTTP port the server listens on                                                                                                      |
| `OPENTV_DATA`              | `/data`    | Directory for the SQLite database                                                                                                    |
| `OPENTV_PAGE_SIZE`         | `50`       | Items per page in the web client's lists                                                                                             |
| `OPENTV_VIDEO_ENCODER`     | `libx264`  | Encoder for non-H.264 video (HEVC and friends). Set `copy` to turn transcoding off, or a hardware encoder like `h264_qsv` / `h264_nvenc` with a GPU |
| `OPENTV_X264_PRESET`       | `veryfast` | Software encode speed vs size (`ultrafast` to `slow`); only used by the default `libx264` encoder                                    |
| `OPENTV_PROVIDER_CONNECTIONS` | `1`     | How many concurrent provider reads to allow when a panel does not report its own `max_connections`. Playback and downloads share this budget |
| `OPENTV_TRUSTED_PROXIES`   | (unset)    | Comma-separated proxy IPs and CIDRs (e.g. `127.0.0.1,10.0.0.0/8`). When a request comes from one of these, the real viewer IP is read from `X-Forwarded-For` for the [Now watching](/guide/webclient-now-watching) page |

Mutable settings are stored atomically in `/data/server-settings.json`. On the
first start after upgrading, the previous `settings.properties` is imported and
kept as `settings.properties.bak`; the stream-token key is preserved.

## What it does

- **Playlists**: Xtream login, M3U link, or an uploaded `.m3u` file, with the
  same Xtream auto-detection for `get.php` links as the app.
- **Browse**: Live / Movies / Series tabs with categories, list or poster-grid
  view, quality badges, and now-playing lines with progress from your EPG.
- **Guide and catch-up**: per-channel guide sheet; past programmes replay via
  Xtream timeshift or `catchup-source` templates.
- **Search** across live, movies and series; **favorites** and **resume
  positions** stored server-side, shared by every browser you use.
- **Details**: movie/series/episode pages with panel metadata (Xtream) or
  keyless TVMaze/iTunes enrichment, cast rows and ratings, identical logic to
  the app.
- **Player**: HLS (hls.js), MPEG-TS (mpegts.js) and direct MP4/WebM through the
  built-in stream proxy, with track selection, speed, scaling, picture-in-picture
  and fullscreen.
- **Downloads**: the same offline queue as the app, run by the server, into the
  `/data` volume, with pause, byte-exact resume, retry and progress. Finished
  files play from any browser or save to the device.
- **Now watching**: see and control who is watching, with live stream
  diagnostics. See [Now watching](/guide/webclient-now-watching).

All provider traffic goes through the server, which keeps the app's frugal
behavior: conditional GETs for playlists and EPG, refresh throttling, and hard
caches for metadata.

## Limitations vs the Android app

- **Codecs**: browsers decode less than ExoPlayer, so for movies, series and
  catch-up the server remuxes through ffmpeg into fMP4 HLS, exposing every audio
  and subtitle track. Non-browser audio (AC3, E-AC3, DTS) is always transcoded to
  AAC. Video is copied when the browser can play it (H.264 everywhere, HEVC where
  the browser decodes it natively, which the client detects per session) and
  transcoded to H.264 only for browsers that cannot, such as Firefox with HEVC.
  Copying is cheap; transcoding video is CPU heavy. On a box without a GPU, lower
  the cost with `OPENTV_X264_PRESET` or turn it off with
  `OPENTV_VIDEO_ENCODER=copy` (non-H.264 then plays only where the browser
  decodes it natively). With a GPU, point `OPENTV_VIDEO_ENCODER` at a hardware
  encoder (`h264_qsv`, `h264_nvenc`) for near-free transcoding. Live channels
  rely on the browser plus an on-demand audio-only transcode, so an unusual live
  video codec may still not play. Those streams still play in the Android app.
- **Downloads** are stored on the server (the `/data` volume), not on the
  browser's device. Use the save button on a finished download to copy it to the
  device you are browsing from.
- The **User-Agent** and download settings are server-wide (they affect how the
  server talks to your provider), not per-browser.

## Running without Docker

```bash
./gradlew :server:installDist
PORT=8080 OPENTV_DATA=./server-data ./server/build/install/server/bin/server
```

Requires JDK 25+, Node.js 20+, and `ffmpeg` / `ffprobe` on `PATH` (they power
the remux that exposes tracks and transcodes non-browser audio and video; the
Docker image bundles them). The Gradle build compiles the React client in
`server/webapp` into the server's resources; the Docker build does this in its
own stage. For UI work, `cd server/webapp && npm run dev` serves the client on
:5173 with `/api/v1` proxied to a server on :8080. See `server/webapp/README.md`
for the design system.
