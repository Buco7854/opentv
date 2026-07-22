# Documentation screenshots

Regenerates the web-client screenshots under `../static/img/web` (dark and light).
Screens are captured from the real web client running against a mock API that
serves fake, illustrative data, then framed in a window on a wallpaper backdrop.

## Prerequisites

- Node 20+
- `npm install` here
- `npx playwright install chromium` (first run)

## Run

In three terminals:

1. Mock API: `node mock-server.mjs` (serves `:8080`)
2. Web client: from `../../server/webapp`, `npm run dev` (serves `:5173`, proxies `/api` to `:8080`)
3. Capture and frame: `npm run shots`

`shots` captures raw shots into `raw/`, frames them, and writes the compressed
result into `../static/img/web`. The steps also run on their own: `npm run
capture`, `npm run frame`, `npm run compress`.

The data is fictional: the playlists, channels, movies and series exist only in
`mock-server.mjs`.
