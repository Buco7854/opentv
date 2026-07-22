---
title: A visual tour
---

# A visual tour

Every screen of the web client, in dark and light. The images follow the theme
you are reading the docs in.

:::note[Illustrative screenshots]
These shots use a mock provider that does not exist. The channels, movies and
series shown are placeholders, for illustration only.
:::

import ThemedImage from '@theme/ThemedImage';
import useBaseUrl from '@docusaurus/useBaseUrl';

## Live TV

Live channels are a list with the now-and-next line and a progress bar pulled
from your EPG. The calendar button opens the guide, and glows when catch-up is
available.

<figure className="doc-screen">
  <ThemedImage
    alt="Live channel list with now and next lines"
    sources={{ light: useBaseUrl('/img/web/browse-live-light.png'), dark: useBaseUrl('/img/web/browse-live-dark.png') }}
  />
</figure>

## Categories and movies

Each tab opens on its categories. Movies and series default to a poster grid,
live to a list, and the toggle is remembered.

<div className="screens-grid">
  <figure className="doc-screen">
    <ThemedImage
      alt="Movie categories"
      sources={{ light: useBaseUrl('/img/web/browse-categories-light.png'), dark: useBaseUrl('/img/web/browse-categories-dark.png') }}
    />
    <figcaption>Categories, with a filter bar.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="Series poster grid"
      sources={{ light: useBaseUrl('/img/web/series-light.png'), dark: useBaseUrl('/img/web/series-dark.png') }}
    />
    <figcaption>The series catalog as posters.</figcaption>
  </figure>
</div>

## Detail pages

Movies, series and episodes get a full page: synopsis, rating, genre and
runtime, cast with photos, a season picker and per-episode progress.

<div className="screens-grid">
  <figure className="doc-screen">
    <ThemedImage
      alt="Movie detail page"
      sources={{ light: useBaseUrl('/img/web/movie-detail-light.png'), dark: useBaseUrl('/img/web/movie-detail-dark.png') }}
    />
    <figcaption>A movie, with resume and the download control.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="Series detail page with seasons and episodes"
      sources={{ light: useBaseUrl('/img/web/series-detail-light.png'), dark: useBaseUrl('/img/web/series-detail-dark.png') }}
    />
    <figcaption>A series, with seasons and episodes.</figcaption>
  </figure>
</div>

## Guide and catch-up

The per-channel guide opens as a sheet. On channels that keep an archive, past
programmes are tappable to replay.

<figure className="doc-screen">
  <ThemedImage
    alt="Per-channel guide sheet with replayable programmes"
    sources={{ light: useBaseUrl('/img/web/guide-sheet-light.png'), dark: useBaseUrl('/img/web/guide-sheet-dark.png') }}
  />
</figure>

## Search and favorites

Search runs across live, movies and series at once. Favorites keep channels,
movies and series in their own place, keyed so they survive refreshes.

<div className="screens-grid">
  <figure className="doc-screen">
    <ThemedImage
      alt="Global search results"
      sources={{ light: useBaseUrl('/img/web/search-light.png'), dark: useBaseUrl('/img/web/search-dark.png') }}
    />
    <figcaption>One search across everything.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="Favorites screen"
      sources={{ light: useBaseUrl('/img/web/favorites-light.png'), dark: useBaseUrl('/img/web/favorites-dark.png') }}
    />
    <figcaption>Favorites, by type.</figcaption>
  </figure>
</div>

## Playlists, downloads, account and settings

The burger opens the playlists panel, where you switch, add, refresh and edit
playlists and reach Now watching, downloads and settings.

<div className="screens-grid">
  <figure className="doc-screen">
    <ThemedImage
      alt="Playlists panel"
      sources={{ light: useBaseUrl('/img/web/playlists-panel-light.png'), dark: useBaseUrl('/img/web/playlists-panel-dark.png') }}
    />
    <figcaption>The playlists panel.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="Downloads manager"
      sources={{ light: useBaseUrl('/img/web/downloads-light.png'), dark: useBaseUrl('/img/web/downloads-dark.png') }}
    />
    <figcaption>Server-side downloads with progress.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="Account page with connection monitor"
      sources={{ light: useBaseUrl('/img/web/account-light.png'), dark: useBaseUrl('/img/web/account-dark.png') }}
    />
    <figcaption>Connection monitor and plan expiry.</figcaption>
  </figure>
  <figure className="doc-screen">
    <ThemedImage
      alt="Settings"
      sources={{ light: useBaseUrl('/img/web/settings-light.png'), dark: useBaseUrl('/img/web/settings-dark.png') }}
    />
    <figcaption>Theme, playback, downloads and network.</figcaption>
  </figure>
</div>

## On a phone

Everything scales down to a phone, where the dock becomes a tab bar.

<figure className="doc-screen doc-screen--phone">
  <ThemedImage
    alt="The web client on a phone"
    sources={{ light: useBaseUrl('/img/web/phone-live-light.png'), dark: useBaseUrl('/img/web/phone-live-dark.png') }}
  />
</figure>
