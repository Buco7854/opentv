# Account and privacy

## Your OpenTV account

In the web client, open **More → Your account** to manage security, watch
progress and signed-in devices. From Android, open **More → Connected servers**,
choose the server, then **Your account**. These server account controls open in
a browser; a TV offers a QR code instead.

**Watch progress** lists your saved positions by title. Remove a position to
start that title from the beginning, or clear all saved progress. Entries whose
content has disappeared can still be removed. This changes only your account's
progress on that server.

**Sessions** lists the browsers and apps signed in to your account, with their
last activity and expiry. The current browser is marked. You can sign out one
device, every other session, or all sessions. Signing out an Android app session
ends its server playback and asks that app to sign in again; it does not remove
its standalone playlists.

## Account monitor

For Xtream logins, a dedicated account page shows your active and maximum connections and your plan expiry, so you can see at a glance whether you have a free connection slot.

The reading is kept for about a minute, so opening the page again within that window reuses it instead of querying your provider. The Refresh button works differently: it forces a live check and always makes a request. So mashing Refresh will query your provider on every tap. Use it when you genuinely want an up-to-date number, and let the cached value stand the rest of the time.

## Error log

OpenTV keeps an in-app error log with full stack traces, including crashes from a previous session. Credentials are redacted, so you can share a log safely when reporting an issue.

## Privacy

The OpenTV project runs no hosted service and includes no analytics or ads.
With standalone sources, your provider credentials and viewing state stay on
your device. The app talks to:

- your provider, to fetch playlists, the guide and streams, and
- optional keyless metadata sources: TVMaze for series, iTunes for movie
  details, and Wikidata/Wikimedia Commons for movie cast portraits. OpenTV
  sends a cleaned title and, when known, its year; it never sends provider
  credentials or viewing history to those services.

If you add your own OpenTV server as a source, the app also talks to that
server. The server holds the account, granted playlists, favorites, progress,
playback leases, and server-side part of hub downloads; its provider credentials
remain on the server. The Android session token is encrypted under Android
Keystore and excluded from backup and device-to-device transfer.

The full source code is available on [GitHub](https://github.com/Buco7854/opentv).
