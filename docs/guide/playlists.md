# Add a playlist

OpenTV is a player only. It does not provide any channels or content. You bring your own M3U playlist or Xtream login from your provider.

Open the app, tap **Add**, and choose one of the three modes.

## Xtream login

This is the recommended mode when your provider gives you a server, a username and a password. It talks to the panel API directly, so you get clean server-side Live, Movies and Series categories with no guessing, the full series catalog with details, catch-up and an automatically wired EPG.

Fill in:

- **Name**: any label you like.
- **Server**: the host and port, for example `http://example.com:8080`.
- **Username** and **Password**: your account credentials. These fields support your password manager.

A full refresh of an Xtream account costs only a handful of requests, so it stays gentle on your provider.

::: tip
If login fails with an HTTP 404, double check the server address and port. Some panels also reject unknown apps. You can change the request User-Agent under [Settings](/guide/settings#network).
:::

## M3U or M3U8 URL

Enter the playlist URL, and optionally an XMLTV EPG URL. If you paste a `get.php` link, OpenTV recognizes that it is served by an Xtream panel and offers to upgrade it to the richer Xtream mode automatically.

For flat M3U playlists, OpenTV classifies content using layered signals so that series served as plain streams do not pollute the Live tab and separator rows are dropped. If a category is mis-tagged, you can re-type it by hand.

## Local file

Pick a `.m3u` or `.m3u8` file from your device storage.

## Refreshing

Playlists refresh on their own schedule and are throttled to avoid hammering your provider. You can pull to refresh when you want the latest, which is also rate limited for safety.
