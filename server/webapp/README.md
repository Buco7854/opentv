# OpenTV web client

React + TypeScript + Vite + Tailwind v4 client for the OpenTV server. It talks
to the REST API (`/api/v1/*`) and is compiled into `server/src/main/resources/web`,
which the server ships as an SPA (any path falls back to `index.html`, so URLs
like `/browse/3` are real paths).

- `npm run dev` — dev server on :5173, proxying `/api/v1` to a server on :8080.
- `npm run build` — typecheck + production build into the server resources.
- The Gradle `:server` build runs `npm run ci-build` automatically; pass
  `-PwebappPrebuilt` to skip it when the output already exists (Docker/CI).

## UX model

A cockpit shell, built for large landscape touchscreens (including in-car
browsers) and scaling down to phones, where the dock doubles as a tab bar:

- A **persistent bottom dock**: a burger opens the floating **playlists
  panel** (switch, add, edit, refresh, account); the center icons are the
  active playlist's "apps" — Live, Movies, Series, Favorites, Search; the
  right side holds
  Downloads and Settings (moved into the panel on narrow screens). `/` forwards into the
  active playlist.
- A **mini player**: navigating while something plays docks the video into a
  floating media card above the dock (title, transport, progress) instead of
  stopping it; tap to go fullscreen again. Fullscreen has the full transport
  chrome on a floating translucent bar.

## Design system

Three themes — dark (pure black canvas), light (white canvas), and system
(default, follows the OS) — driven entirely by runtime CSS variables switched
on `<html data-theme>`:

- **Canvas**: black or white; content floats on quiet gray panels with
  hairline borders and backdrop blur for overlays.
- **Color**: neutral grays plus exactly three accents — blue for the primary
  action, green for success/live/now-airing, red for errors.
- **Type**: Inter Variable for UI; Barlow for display titles, media-card
  titles and media-card titles (spaced caps).
- **Icons**: thin 2px line glyphs (Lucide); media transport and the active
  favorite heart are solid fills. See `src/components/Icons.tsx`.
- **Shape**: 8–16px corners; 44px minimum touch targets; segmented controls
  where the selected segment is a solid white pill; inset small labels on
  filled text fields; selected list rows get a soft pill.

All tokens live in the `:root` / `[data-theme]` blocks at the top of
`src/index.css` (Tailwind utilities map onto them via `@theme inline`); each
component's styles are one labelled block in the same file, paired with one
React file in `src/components/`. Change the look in the token blocks; change a
component in its block — nothing is styled inline in screens.

Structure and boundaries:

- `src/api.ts` is the typed `/api/v1` facade. `src/api/http.ts` owns HTTP,
  structured errors, same-origin credentials, and the single future bearer
  token seam. Keep authentication policy there rather than in screens.
- `src/preferences.ts` is browser-local presentation state. Do not mix it with
  server-owned settings or API caching.
- `src/hooks.ts` owns reusable server-state behavior. Downloads use one shared
  polling store, so screens must use `useDownloads` rather than starting their
  own timers.
- `src/components/` — Material.tsx (bars, buttons, dialogs, sheets, fields,
  segmented controls), plus one file per widget (rows, grids, badges, cast,
  guide, download states).
- `src/screens/` contains route adapters. All screens are lazy route
  boundaries; keep playback-only dependencies out of the app shell.
- `src/player/PlayerNavigation.tsx` owns token-free player navigation.
  `PlayerProvider.tsx` owns playback orchestration, while presentation and
  deterministic source policy live in `PlaybackSheets.tsx` and
  `playbackPolicy.ts`.
- `src/App.tsx` is composition and routing only.
