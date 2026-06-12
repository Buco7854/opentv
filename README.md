# OpenTV

A clean, fast IPTV player for Android. Built with Kotlin, Jetpack Compose (Material 3),
Media3/ExoPlayer and Room.

## Features (v0.1)

- **Xtream login** — add a playlist with server + username + password and the app
  drives the panel API directly: server-side Live/Movies/Series categories (no
  classification guessing), the full series catalog with plot/cast/rating/cover,
  lazily-fetched episode lists (one request per series opened, cached a day),
  panel-provided movie details, auto-wired EPG, and catch-up. A refresh costs
  exactly six API requests.
- **Catch-up TV** — channels with a provider archive (`tv_archive`, or
  `catchup-days` in M3U) show their past programmes in the guide sheet; tap one
  to replay it via the panel's timeshift endpoint.
- **M3U / M3U8 playlists** — add a remote URL from your provider, or import a local
  `.m3u`/`.m3u8` file from device storage.
- **List or poster grid** — browse movies and series as rows or as a portrait
  poster-card grid; one toggle in the browse bar, persisted.
- **Favorites** — heart anything (live channels, movies, series) from rows or
  detail pages; a "★ Favorites" group is pinned on top of each tab. Favorites
  are keyed by stable identity so they survive playlist refreshes.
- **Categories & search** — `group-title` tags become browsable folders, split into
  **Live / Movies / Series** tabs. A global search bar scans every category at once.
- **Smart VOD detection** — M3U is a flat format, so content type is always a guess;
  OpenTV layers signals in order of reliability instead of trusting the file extension
  alone: Xtream URL segments (`/live/`, `/movie/`, `/series/`) first, then episode
  markers in titles (`S01E02`, `1x05`, `EP 7`, `Season 1 Episode 2` — these beat a
  `.ts` extension, so series served as TS streams don't pollute live TV), then
  `group-title` keywords (SERIES/VOD/FILM/…), then extension, then a trailing year
  tag. Decorative separator rows (`#### SPORTS ####`) are dropped instead of being
  counted as channels. Episodes are grouped per show and sorted by season/episode.
  The classifier is covered by unit tests (`ContentClassifierTest`).
- **VOD downloads** — save movies and episodes to local storage for offline viewing,
  with pause/resume (continuing from the same byte), progress notifications, and a
  downloads manager.
- **Connection monitoring** — for Xtream-based playlists the app reads
  `player_api.php` and shows **active / maximum concurrent connections** (plus plan
  expiry), so you never trip your provider's connection limit.
- **Hardware-accelerated playback** — Media3/ExoPlayer with the device's MediaCodec
  hardware decoders (HLS, TS, MP4, MKV).
- **Subtitles & track selection** — embedded subtitle and audio tracks (HLS
  renditions, MKV/TS streams) are selectable from an in-player sheet, including
  "subtitles off" and playback speed. Subtitle appearance is configurable — size
  (50–200%), outline vs. background style, bold — with a live preview, persisted
  across sessions. A scaling button cycles Fit / Zoom / Stretch.
- **Player settings** (gear icon on home) — preferred audio & subtitle language
  (auto-applied when a stream has a matching track), seek step (5/10/30 s),
  buffering profile (fast start / balanced / stable), software decoder fallback,
  default video scaling, and subtitle appearance. All persisted.
- **Basic EPG (XMLTV)** — add an XMLTV URL (auto-detected from `url-tvg` when
  present); live channel lists show what's airing now with a progress bar, and a
  per-channel guide sheet shows the upcoming timeline. Gzipped EPG files supported.
- **Error log** — every failure (playlist refresh, EPG, downloads, playback,
  connection status, even crashes from the previous session) is surfaced as a
  snackbar where relevant and recorded with its full stack trace in an in-app
  error log (bug icon on the home screen), with copy-to-clipboard support.
  Provider credentials are redacted from everything the log records.

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
- **Connection-aware downloads** — download concurrency defaults to Auto: the
  provider's own `max_connections` minus one, keeping a slot reserved for
  watching (configurable to 1–3 in Settings). With a manual limit or an unknown
  provider, downloads wait — or yield mid-transfer and resume later — whenever
  you're streaming from the same provider, so the connection budget is never
  exceeded.
- **Keyless metadata** — synopsis, rating and cast for series via TVMaze, and
  synopsis, genre and director for movies via the iTunes Search API. No API key
  or account needed; lookups are cached for 30 days per title, including
  negative results.
- **Streaming parsers** — M3U and XMLTV are parsed as streams in a single pass
  (batched into Room), so 50k-entry playlists don't blow up memory or require
  re-fetching.

## Building

```bash
./gradlew :app:assembleDebug
```

Requires JDK 17+ and the Android SDK (platform 35).

## Security notes

- Cleartext HTTP is allowed (`usesCleartextTraffic`) because the majority of IPTV
  providers only serve plain HTTP. Use an HTTPS playlist URL when your provider
  offers one.
- Xtream credentials (parsed from the playlist URL) are stored only in the app's
  private database; `allowBackup` is disabled so they are never copied into cloud
  device backups.
- Downloads are written to app-specific external storage (no storage permissions
  needed), with file names sanitized from playlist-controlled titles and URLs.

## Notes

- Connection monitoring requires an Xtream-style playlist URL
  (`http://host:port/get.php?username=...&password=...`) — credentials are detected
  automatically and the matching `player_api.php` endpoint is used.
- EPG data is matched against channels' `tvg-id` attributes and kept inside a
  rolling window (-3 h … +48 h) to stay small.
