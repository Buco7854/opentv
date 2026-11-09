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

## Home and navigation

Home lists your playlists and unfinished viewing without starting a provider refresh.
Both clients use Home, Browse, Favorites, Search and More; Browse contains the Live,
Movies and Series tabs. The source switcher chooses an existing playlist; More
contains management actions. Add playlist is a separate labeled library action.

<figure className="doc-screen">
  <ThemedImage
    alt="Library home with continue watching and playlists"
    sources={{ light: useBaseUrl('/img/web/home-light.png'), dark: useBaseUrl('/img/web/home-dark.png') }}
  />
</figure>

<figure className="doc-screen">
  <ThemedImage
    alt="Playlist chooser containing only existing playlists"
    sources={{ light: useBaseUrl('/img/web/playlist-chooser-light.png'), dark: useBaseUrl('/img/web/playlist-chooser-dark.png') }}
  />
  <figcaption>Switch an existing playlist here; adding and editing have separate actions.</figcaption>
</figure>

## Signing in from Android

When Android opens your server in a browser, sign in and review the requesting
device and account. Approve the request, then return to OpenTV to finish connecting.

<figure className="doc-screen">
  <ThemedImage
    alt="Review and approve an Android sign-in request on a phone"
    sources={{ light: useBaseUrl('/img/web/device-approval-light.png'), dark: useBaseUrl('/img/web/device-approval-dark.png') }}
  />
</figure>

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

Search runs across live, movies and series at once. Favorites are yours rather
than any one playlist's, so the page lists them from every playlist you have
access to, grouped by type, with chips to narrow to a single playlist. Entries
stay keyed to the playlist they came from, so the same title favorited in two
playlists remains two entries and survives refreshes.

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

**More** opens playlist management, where you add, refresh and edit playlists
and reach Now watching, downloads and settings. Use the playlist selector to
switch between existing playlists.

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

## Administration

Administrators can open **Administration** from **More**. Search the account list
or filter by status, then open an account to edit its profile, playlist access,
sessions or watch progress. Returning to the list keeps your search and filter.

The administration sidebar also contains default playlist assignments, pending
single sign-on accounts and shared downloads. On a phone, choose the section
from the selector above the page.

## On a phone

On a phone, the five navigation destinations move into a labeled bottom bar.

<figure className="doc-screen doc-screen--phone">
  <ThemedImage
    alt="The web client on a phone"
    sources={{ light: useBaseUrl('/img/web/phone-live-light.png'), dark: useBaseUrl('/img/web/phone-live-dark.png') }}
  />
</figure>
