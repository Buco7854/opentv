# Privacy Policy

_Last updated: 2026-07-29_

OpenTV ("the app") is an IPTV player for Android. This policy explains what the
app does and does not do with your data. The short version: **the developer runs
no servers, receives nothing, and there is no analytics, advertising, telemetry
or crash reporting. Nothing you enter is sent to us.**

OpenTV can optionally connect to an **OpenTV server** that you or someone you
trust runs. That server does have accounts, and it stores some of your data. It
is still not ours — but it is not your device either, so it gets its own section
below. Everything above and below it applies whether or not you connect one.

## What the app stores on your device

- **Playlists and provider credentials.** When you add an M3U URL or sign in
  with Xtream credentials, the URL/username/password are saved locally so the
  app can fetch your channels. They are used only to talk to **your** provider.
- **Channel, movie, series, EPG and metadata caches**, favourites, category
  corrections, and your settings.
- **Downloaded videos**, saved to app storage or to a folder you choose. This
  includes downloads that came from an OpenTV server: they are stored on your
  device like any other.
- **Server sign-in tokens**, if you connect an OpenTV server. These are held in
  a separate store, encrypted with a key held by the Android Keystore that
  cannot be exported from the device.

Device backup is disabled for the app (`allowBackup="false"`), and the catalog
database, the token store and your preferences are additionally excluded from
Android 12+ device-to-device transfer. They are not copied into cloud backups or
carried to a new phone; after switching devices you sign in again.

## If you connect an OpenTV server

Connecting is optional. The app works fully without it. When you do connect one:

- **You have an account on that server**, with a username and password and
  optionally two-factor authentication or single sign-on.
- **Some of your data lives there, not on your device.** Your favourites and
  watch progress for that server's playlists are stored server-side so they
  follow you between devices. Your local playlists keep their favourites and
  progress on the device as before.
- **The server records your activity while you watch.** For anything you play
  from it, the server knows what you are watching, your position, your IP
  address and your device's user agent, and keeps a list of your sign-in
  sessions.
- **An administrator of that server can see and act on this.** They can see who
  is watching what in real time, pause your playback, send you a message, end
  your stream, and revoke your sessions. If you are not the administrator, you
  are trusting whoever is.
- **Provider credentials stay on the server.** The server talks to the IPTV
  provider on your behalf; it does not hand your provider's URLs or logins to
  the app.

If you run the server yourself, all of that stays on hardware you control. If
someone else runs it, their policies apply in addition to this one.

## Network connections the app makes

OpenTV only contacts:

1. **Your IPTV provider** — the server in your playlist/Xtream login — to load
   channels, the guide, account status, and to stream or download content.
2. **Your OpenTV server**, if you have connected one, for its playlists, guide,
   playback, favourites, watch progress and downloads.
3. **Optional public metadata services**, to enrich movie/series pages:
   **TVMaze** and the **iTunes Search API**. These receive only a cleaned title
   to look up (e.g. "Oppenheimer"); no account information or identifiers. They
   are used only when you open a detail page, and results are cached.
4. **Image hosts** referenced by your provider or the metadata services, to
   display logos and posters. Artwork from an OpenTV server is fetched through
   that server.

The app sends **no data to the developer** — there is no OpenTV backend that we
operate, and no telemetry, crash reporting, or tracking SDK of any kind.

## Diagnostics

Errors are recorded in an **on-device error log** you can view and clear in the
app. Provider credentials, server sign-in tokens and playback capability tokens
are redacted from everything the log records before it is stored. Nothing from
the log is transmitted anywhere unless you choose to copy and share it yourself.

## Permissions

- **Internet / network state** — to load and play your streams.
- **Notifications** — to show download progress. Declining only hides the
  notification; downloads still run.
- **Foreground service / wake lock** — to keep downloads running.

## Children

The app is a media player; the content available depends entirely on the
playlist you provide, or on the server you connect to. OpenTV itself collects
nothing.

## Contact

Questions: github@grimbert.net
