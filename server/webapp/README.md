# OpenTV web client

React + TypeScript + Vite + Tailwind v4 client for the OpenTV server. It talks
to the REST API (`/api/*`) and is compiled into `server/src/main/resources/web`,
which the server ships as an SPA (any path falls back to `index.html`, so URLs
like `/browse/3` are real paths).

- `npm run dev` — dev server on :5173, proxying `/api` to a server on :8080.
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

Structure:

- `src/api.ts` — typed REST client; `src/hooks.ts` — polling/optimistic-state
  hooks; `src/lib/format.ts` — formatting helpers.
- `src/components/` — Material.tsx (bars, buttons, dialogs, sheets, fields,
  segmented controls), plus one file per widget (rows, grids, badges, cast,
  guide, download states).
- `src/screens/` — one file per page; `src/player/PlayerProvider.tsx` — the
  playback overlay (hls.js / mpegts.js / native).
- `src/App.tsx` — the route table.
