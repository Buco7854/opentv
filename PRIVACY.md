# Privacy Policy

_Last updated: 2026-06-21_

OpenTV ("the app") is an IPTV player for Android. This policy explains what the
app does and does not do with your data. The short version: **OpenTV has no
servers, no accounts, no analytics, and no advertising. Nothing you enter is
sent to the developer.**

## What the app stores

Everything the app stores stays **on your device**, in the app's private
storage:

- **Playlists and provider credentials.** When you add an M3U URL or sign in
  with Xtream credentials, the URL/username/password are saved locally so the
  app can fetch your channels. They are used only to talk to **your** provider.
- **Channel, movie, series, EPG and metadata caches**, favourites, category
  corrections, and your settings.
- **Downloaded videos**, saved to app storage or to a folder you choose.

Device backup is disabled for the app (`allowBackup="false"`), so these are not
copied into cloud backups.

## Network connections the app makes

OpenTV only contacts:

1. **Your IPTV provider** — the server in your playlist/Xtream login — to load
   channels, the guide, account status, and to stream or download content.
2. **Optional public metadata services**, to enrich movie/series pages:
   **TVMaze** and the **iTunes Search API**. These receive only a cleaned title
   to look up (e.g. "Oppenheimer"); no account information or identifiers. They
   are used only when you open a detail page, and results are cached.
3. **Image hosts** referenced by your provider or the metadata services, to
   display logos and posters.

The app sends **no data to the developer** — there is no OpenTV backend,
telemetry, crash reporting, or tracking SDK of any kind.

## Diagnostics

Errors are recorded in an **on-device error log** you can view and clear in the
app. Provider credentials are redacted from everything the log records before it
is stored. Nothing from the log is transmitted anywhere unless you choose to
copy and share it yourself.

## Permissions

- **Internet / network state** — to load and play your streams.
- **Notifications** — to show download progress.
- **Foreground service / wake lock** — to keep downloads running.

## Children

The app is a media player; the content available depends entirely on the
playlist you provide. OpenTV itself collects nothing.

## Contact

Questions: github@grimbert.net
