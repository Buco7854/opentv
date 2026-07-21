---
title: Web client
---

# Web client

OpenTV also runs as a self-hosted web app: the same browsing, search, EPG,
catch-up, favorites and resume as the Android app, from any browser. It ships as
a single Docker image with a small server that stores your playlists in SQLite
and proxies streams so the browser can play them.

Under the hood it is the same app. The Android client and the web server share
the same core modules (M3U parsing, content classification, the Xtream client,
EPG, catch-up, metadata and the SQLite data layer). Only the UI differs.

import ThemedImage from '@theme/ThemedImage';
import useBaseUrl from '@docusaurus/useBaseUrl';

<figure className="doc-screen">
  <ThemedImage
    alt="Browsing a movies category as a poster grid"
    sources={{ light: useBaseUrl('/img/web/browse-movies-light.png'), dark: useBaseUrl('/img/web/browse-movies-dark.png') }}
  />
  <figcaption>Movies as a poster grid, with quality badges and the bottom dock. Screenshots follow your theme.</figcaption>
</figure>

The web UI is a touch-first cockpit: a bottom dock with the active playlist's
Live / Movies / Series / Favorites / Search, and a panel (the burger) that holds
your playlists plus Now watching, downloads and settings. Playback opens as a
fullscreen player. It ships dark, light and system-following themes and works on
big landscape touchscreens (including in-car browsers) down to phones, where the
dock becomes a tab bar.

:::danger[No authentication: read this before deploying]
The web server has **no authentication and no user accounts**. Anyone who can
reach it can browse your playlists, play streams, and read your Xtream
credentials (they are embedded in stream URLs and editable in the UI).

Run it **behind an authenticating reverse proxy** (Authelia, Caddy
`basic_auth`, nginx `auth_basic`, a VPN, or a tailnet) and never expose the port
directly to the internet. The [self-hosting guide](/guide/webclient-hosting) has
a Caddy example.
:::

## Where to next

- [A visual tour](/guide/webclient-tour) walks through every screen.
- [Now watching](/guide/webclient-now-watching) shows who is watching what, with
  remote controls and live stream diagnostics.
- [Self-hosting](/guide/webclient-hosting) covers Docker, the reverse proxy and
  every configuration option.
