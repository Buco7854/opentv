# OpenTV

A clean, fast IPTV player for Android. Built with Kotlin, Jetpack Compose (Material 3),
Media3/ExoPlayer and Room.

## Features (v0.1)

- **M3U / M3U8 playlists** — add a remote URL from your provider, or import a local
  `.m3u`/`.m3u8` file from device storage.
- **Categories & search** — `group-title` tags become browsable folders, split into
  **Live / Movies / Series** tabs. A global search bar scans every category at once.
- **Smart VOD detection** — entries are classified by Xtream-style URL segments
  (`/live/`, `/movie/`, `/series/`), file extension, and `SxxExx` patterns in titles,
  so series episodes are grouped per show and sorted by season/episode.
- **VOD downloads** — save movies and episodes to local storage for offline viewing,
  with resume support, progress notifications, and a downloads manager.
- **Connection monitoring** — for Xtream-based playlists the app reads
  `player_api.php` and shows **active / maximum concurrent connections** (plus plan
  expiry), so you never trip your provider's connection limit.
- **Hardware-accelerated playback** — Media3/ExoPlayer with the device's MediaCodec
  hardware decoders (HLS, TS, MP4, MKV).
- **Basic EPG (XMLTV)** — add an XMLTV URL (auto-detected from `url-tvg` when
  present); live channel lists show what's airing now with a progress bar, and a
  per-channel guide sheet shows the upcoming timeline. Gzipped EPG files supported.

## Designed to be gentle on your provider

Many providers blacklist clients that hammer their servers. OpenTV is deliberately
frugal with requests:

- **Conditional GETs** — playlist and EPG refreshes send `If-None-Match` /
  `If-Modified-Since`; unchanged files cost a 304 with no body transfer.
- **Refresh throttling** — playlists refresh at most every 6 h, EPG every 12 h,
  unless you explicitly force a refresh.
- **Single-flight refreshes** — concurrent triggers collapse into one request.
- **Account status caching** — the connection monitor polls `player_api.php` at most
  once per minute, and only while you're looking at it.
- **One shared HTTP client** — connection pooling plus a 32 MB disk cache; channel
  logos are cached on disk by Coil so each logo is fetched once.
- **Resumable downloads** — interrupted VOD downloads resume with `Range` requests
  instead of restarting from zero.
- **Streaming parsers** — M3U and XMLTV are parsed as streams in a single pass
  (batched into Room), so 50k-entry playlists don't blow up memory or require
  re-fetching.

## Building

```bash
./gradlew :app:assembleDebug
```

Requires JDK 17+ and the Android SDK (platform 35).

## Notes

- Connection monitoring requires an Xtream-style playlist URL
  (`http://host:port/get.php?username=...&password=...`) — credentials are detected
  automatically and the matching `player_api.php` endpoint is used.
- EPG data is matched against channels' `tvg-id` attributes and kept inside a
  rolling window (-3 h … +48 h) to stay small.
