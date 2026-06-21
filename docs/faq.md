# FAQ

## What is the difference between the release and dev channels?

The release channel is the latest tagged, stable version. The dev channel is rebuilt from every change merged into the main branch, so it has the newest features but may be less polished. See [Download](/download).

## Will updates wipe my playlists?

No. As long as you install over the existing app without uninstalling, your playlists, favorites, downloads and settings are kept. Both channels are signed with the same key, so they update in place. See [Updating the app](/guide/updating).

## Does OpenTV provide any channels or content?

No. OpenTV is only a player. You bring your own M3U playlist or Xtream subscription from your provider.

## My Xtream login returns HTTP 404. What now?

First check the server address and port. Many panels also reject unknown apps, so try a different User-Agent under [Settings](/guide/settings#network). If your provider uses a portal with a MAC address rather than a username and password, that is a Stalker portal, which is a different protocol that OpenTV does not currently support.

## Is it really free and open source?

Yes. The full source code is on [GitHub](https://github.com/Buco7854/opentv) under the GPL v3.0 license. There are no ads and no tracking.

## Which Android versions are supported?

Android 8.0 (API 26) and newer, including Android TV.

## Why is OpenTV not on the Play Store?

It is distributed as an APK you install yourself. This keeps it free and open without store policies, and lets you choose between the release and dev channels.

## How does OpenTV avoid getting me blacklisted?

It is built to be gentle on your provider. It uses conditional requests so unchanged playlists and guides cost almost nothing, throttles refreshes, collapses duplicate requests, caches account status, and fetches deep guide data only when you open it.
