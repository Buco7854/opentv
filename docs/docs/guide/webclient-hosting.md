---
title: Self-hosting
---

# Self-hosting

The web client is one Docker image. It includes local password/MFA and optional
OIDC authentication. Put it behind a TLS reverse proxy and point it at the
`/data` volume for its catalog and user databases.

## Quick start (Docker)

```bash
docker run -d \
  --name opentv-web \
  -p 127.0.0.1:8080:8080 \
  -v opentv-data:/data \
  -e OPENTV_AUTH_ENCRYPTION_KEY='PASTE_32_BYTE_KEY' \
  ghcr.io/buco7854/opentv-web:latest
```

Generate the value with `openssl rand -base64 32` or `openssl rand -hex 32`,
then open `http://localhost:8080`. Reaching the container on another address -
`127.0.0.1`, a LAN IP, a published port - needs no further configuration:
device-linking URLs and unpinned OIDC/WebAuthn addresses follow the address you
used.

Set `OPENTV_PUBLIC_URL` once a reverse proxy sits in front, or when you configure
OIDC or passkeys, since those want one fixed address:
`-p 127.0.0.1:9090:8080 -e OPENTV_PUBLIC_URL=http://localhost:9090`.

:::note Bind mounts

The container runs as an unprivileged user (uid `10001`). A named volume inherits the
right ownership automatically, but a **bind mount** of a host directory does not: if the
directory is owned by root the server cannot create its databases and the container exits
immediately. Give it to the right user first:

```bash
mkdir -p ./opentv-data && sudo chown 10001:10001 ./opentv-data
```

:::

Playlists, guide data, users, grants, favorites, resume points and downloads all
live in one database at `/data/opentv.db`.

:::danger Schema upgrades currently reset accounts too

`/data/opentv.db` uses destructive recreation when its Room schema version
changes. Because accounts and catalog now share that file, such an upgrade
removes **everything**: playlists and guide data as before, and also users,
sessions, grants, favorites, resume points and download associations. You would
bootstrap a new administrator and re-add playlists afterwards.

Back up the entire `/data` volume before upgrading, and treat a version bump as
a re-provisioning event rather than a migration. This is a deliberate
pre-1.0 choice while the schema is still moving; the database exports its
schema, so real migrations can replace destructive recreation without another
redesign.

:::

## docker-compose, behind Caddy TLS

```yaml
services:
  opentv-web:
    image: ghcr.io/buco7854/opentv-web:latest
    restart: unless-stopped
    volumes:
      - opentv-data:/data
    environment:
      OPENTV_PUBLIC_URL: https://tv.example.com
      OPENTV_AUTH_ENCRYPTION_KEY: ${OPENTV_AUTH_ENCRYPTION_KEY}
    # no ports: only the proxy talks to it

  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy-data:/data

volumes:
  opentv-data:
  caddy-data:
```

```text
tv.example.com {
    reverse_proxy opentv-web:8080
}
```

## Configuration

| Environment variable       | Default    | Meaning                                                                                                                              |
| -------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `PORT`                     | `8080`     | HTTP port the server listens on                                                                                                      |
| `OPENTV_DATA`              | `./data`   | Directory for the SQLite databases. The Docker image sets it to `/data`; running the tarball without it writes into the current working directory |
| `OPENTV_PAGE_SIZE`         | `50`       | Items per page in the web client's lists (10-1000; outside that range the server refuses to start)                                    |
| `OPENTV_VIDEO_ENCODER`     | `libx264`  | Encoder for non-H.264 video (HEVC and friends). Set `copy` to turn transcoding off, or a hardware encoder like `h264_qsv` / `h264_nvenc` with a GPU |
| `OPENTV_X264_PRESET`       | `veryfast` | Software encode speed vs size (`ultrafast` to `slow`); only used by the default `libx264` encoder                                    |
| `OPENTV_PROVIDER_CONNECTIONS` | `1`     | How many concurrent provider reads to allow when a panel does not report its own `max_connections`. Playback and downloads share this budget |
| `OPENTV_TRUSTED_PROXIES`   | (unset)    | Comma-separated proxy IPs and CIDRs (e.g. `127.0.0.1,10.0.0.0/8`). When a request comes from one of these, the real viewer IP is read from `X-Forwarded-For` for the [Now watching](/guide/webclient-now-watching) page |
| `OPENTV_PUBLIC_URL`        | derived per request | External browser address, including a non-default port. Device-link URLs, WebAuthn, the OIDC callback, HSTS, and the OIDC transaction cookie derive from it when set and otherwise follow each request. Set it behind a reverse proxy, and for OIDC (the callback must be registered at the provider) |
| `OPENTV_ALLOW_INSECURE_HTTP` | `false` | Development-only escape hatch for non-loopback HTTP. It does not make bearer tokens or credentials safe on an untrusted network. |

Authentication, OIDC, WebAuthn, initial administrator, and recovery variables
are documented in [Server authentication and user data](/guide/server-authentication).
Your reverse proxy must serve HTTPS at exactly `OPENTV_PUBLIC_URL`. Set it whenever
the proxy rewrites `Host` to its upstream, so generated OIDC, device-link, and
WebAuthn addresses remain the public ones. `X-Forwarded-Proto` and
`X-Forwarded-Host` are read only from peers listed in
`OPENTV_TRUSTED_PROXIES`. Register
`${OPENTV_PUBLIC_URL}/api/v1/auth/oidc/callback` exactly at the identity
provider.

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
  keyless TVMaze/iTunes/Wikidata enrichment, Wikimedia Commons cast portraits,
  cast rows and ratings, identical logic to the app.
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

- **Codecs and tracks**: each playback lease reports the codecs that client can
  decode. The web client reports the browser baseline (H.264 plus common browser
  audio codecs) and adds HEVC only when that browser's `MediaSource` says it can
  play it. There is no `hevc=1` URL switch or server-side browser table.
  Browsers cannot select every muxed track in band, so multi-audio or subtitled
  VOD is remuxed into fMP4 HLS even when its codecs are otherwise playable.
  Unsupported selected audio is converted to AAC; unsupported video is
  transcoded to H.264. Copying or direct play is cheap, while video transcoding
  is CPU heavy. On a box without a GPU, lower the cost with
  `OPENTV_X264_PRESET` or turn it off with `OPENTV_VIDEO_ENCODER=copy`. With a
  GPU, use a hardware encoder such as `h264_qsv` or `h264_nvenc`. Live channels
  rely on the browser plus an on-demand audio-only transcode, so an unusual live
  video codec may still fail there.
- **Downloads** are stored on the server (the `/data` volume), not on the
  browser's device. Use the save button on a finished download to copy it to the
  device you are browsing from.
- The **User-Agent** and download settings are server-wide (they affect how the
  server talks to your provider), not per-browser.

The limits above are browser limits, not server ones. The Android app can
connect to this server as a client. It reports the device's actual decoders and
that ExoPlayer selects audio and subtitle tracks in band. When the video and
every audio stream are decodable, Android direct-plays multi-audio and subtitled
content that a browser still has remuxed. A server used mainly from Android
therefore needs far less CPU than the same server used from browsers. A
watch-together room uses the intersection of all members' reports, so a mixed
room uses a common remux only while a member needs one; a fully capable native
room direct-plays and creates no room remux. See
[Android app with your server](./android-with-server.md).

For raw-TS live channels, a watch-together room uses one server relay and one
upstream read. Playlist-only `.m3u8` rooms use a bounded shared-HLS cache:
manifests and media resources are fetched once for the room's share group and
then rewritten with each viewer's lease capability. Neither path opens a
separate provider read for every viewer.

Android clients also store their downloads on the device. The server starts the
provider fetch, and the app begins pulling fixed snapshots as soon as the
growing server file has bytes, so the two transfers are pipelined. A per-server
option can remove the Android user's server download association after the
local file completes; it is off by default, and a blob shared by another user
is retained.

## Running without Docker

```bash
./gradlew :server:installDist
PORT=8080 OPENTV_DATA=./runtime-data \
  OPENTV_AUTH_ENCRYPTION_KEY='PASTE_32_BYTE_KEY' \
  ./server/build/install/server/bin/server
```

Requires JDK 25+, Node.js 24.18+, and `ffmpeg` / `ffprobe` on `PATH` (they power
the remux that exposes tracks and transcodes non-browser audio and video; the
Docker image bundles them). The Gradle build compiles the React client in
`server/webapp` into the server's resources; the Docker build does this in its
own stage. For UI work, `cd server/webapp && npm run dev` serves the client on
:5173 with `/api/v1` proxied to a server on :8080. See `server/webapp/README.md`
for the design system.
