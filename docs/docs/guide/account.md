# Account and privacy

## Account monitor

For Xtream logins, a dedicated account page shows your active and maximum connections and your plan expiry, so you can see at a glance whether you have a free connection slot.

The reading is kept for about a minute, so opening the page again within that window reuses it instead of querying your provider. The Refresh button works differently: it forces a live check and always makes a request. So mashing Refresh will query your provider on every tap. Use it when you genuinely want an up-to-date number, and let the cached value stand the rest of the time.

## Error log

OpenTV keeps an in-app error log with full stack traces, including crashes from a previous session. Credentials are redacted, so you can share a log safely when reporting an issue.

## Privacy

OpenTV has no servers, no accounts, no analytics and no ads. Your credentials and data stay on your device. The app only talks to:

- your provider, to fetch playlists, the guide and streams, and
- optional keyless metadata sources, to show posters, synopsis and cast.

Because there is no backend, nothing about your viewing leaves your device except the requests you make to your own provider. The full source code is available on [GitHub](https://github.com/Buco7854/opentv).
