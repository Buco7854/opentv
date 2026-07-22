// Mock API for the documentation screenshots: fake, illustrative data for every
// screen, plus placeholder poster/logo/avatar art as SVG for /api/img.
import http from 'node:http';

const PORT = 8080;
const now = Date.now();
const DAY = 86_400_000;

// ---- helpers ----
const hash = (s) => { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; };
const hue = (s) => hash(s) % 360;
const initials = (s) => s.replace(/[^\p{L}\p{N} ]/gu, '').split(/\s+/).filter(Boolean).map((w) => w[0]).join('').slice(0, 3).toUpperCase();

function posterSvg(title) {
  const h = hue(title); const h2 = (h + 40) % 360;
  const mono = initials(title).slice(0, 2);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 600">
  <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="hsl(${h} 55% 42%)"/><stop offset="1" stop-color="hsl(${h2} 60% 22%)"/>
  </linearGradient></defs>
  <rect width="400" height="600" fill="url(#g)"/>
  <text x="200" y="330" font-family="Barlow, Arial, sans-serif" font-size="220" font-weight="700"
    fill="rgba(255,255,255,0.14)" text-anchor="middle">${mono}</text>
  <rect x="0" y="470" width="400" height="130" fill="rgba(0,0,0,0.28)"/>
</svg>`;
}
function logoSvg(name) {
  const h = hue(name);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">
  <rect width="200" height="200" rx="36" fill="hsl(${h} 45% 30%)"/>
  <rect width="200" height="200" rx="36" fill="url(#s)"/>
  <defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="rgba(255,255,255,0.12)"/><stop offset="1" stop-color="rgba(0,0,0,0.12)"/></linearGradient></defs>
  <text x="100" y="100" font-family="Barlow, Arial, sans-serif" font-size="72" font-weight="600"
    fill="#fff" text-anchor="middle" dominant-baseline="central">${initials(name)}</text>
</svg>`;
}
function avatarSvg(name) {
  const h = hue(name);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 300">
  <rect width="300" height="300" fill="hsl(${h} 40% 34%)"/>
  <circle cx="150" cy="120" r="58" fill="rgba(255,255,255,0.85)"/>
  <path d="M52 300c0-62 44-104 98-104s98 42 98 104z" fill="rgba(255,255,255,0.85)"/>
</svg>`;
}

// ---- data ----
const playlists = [
  { id: 1, name: 'Living Room', mode: 'xtream', hasXtreamPanel: true, lastRefreshedMs: now - 2 * 3600_000, channelCount: 4820 },
  { id: 2, name: 'Sports & News', mode: 'url', hasXtreamPanel: false, lastRefreshedMs: now - 26 * 3600_000, channelCount: 640 },
];

const liveGroups = [
  ['United Kingdom', 42], ['Sports', 68], ['United States', 55], ['France', 37], ['Documentary', 24], ['Kids', 19],
];
const movieGroups = [['Action', 320], ['Sci-Fi', 210], ['Comedy', 415], ['Drama', 380], ['Animation', 140], ['Thriller', 260]];
const seriesCats = [['Netflix', 180], ['Apple TV+', 64], ['HBO', 96], ['Drama', 220], ['Comedy', 150]];

const liveNames = [
  'BBC One HD', 'BBC Two HD', 'ITV1 HD', 'Channel 4 HD', 'Sky Sports Main Event', 'Sky Sports Premier League',
  'TNT Sports 1', 'CNN International', 'BBC News', 'Eurosport 1 HD',
];
const movieTitles = [
  'Dune: Part Two (2024) 4K', 'Oppenheimer (2023) FHD', 'The Batman (2022) 4K HDR', 'Blade Runner 2049 4K',
  'Interstellar (2014) FHD', 'Everything Everywhere All at Once', 'Top Gun: Maverick 4K', 'Poor Things (2023) FHD',
  'Sicario (2015) FHD', 'Arrival (2016) 4K', 'Whiplash (2014) FHD', 'Parasite (2019) 4K',
];
const seriesTitles = [
  ['The Bear', 'Comedy · Drama', 8.6], ['Severance', 'Sci-Fi · Thriller', 8.7], ['Shogun', 'Drama · History', 9.0],
  ['Slow Horses', 'Thriller', 8.3], ['Fallout', 'Sci-Fi · Action', 8.5], ['Ted Lasso', 'Comedy', 8.8],
  ['Succession', 'Drama', 8.9], ['The Last of Us', 'Drama · Horror', 8.7], ['True Detective', 'Crime', 8.6],
  ['Andor', 'Sci-Fi', 8.4], ['Dark', 'Sci-Fi · Mystery', 8.7], ['Chernobyl', 'Drama · History', 9.3],
];

// channel id spaces: live 2001+, movie 1001+, episode 3001+
const byId = new Map();
function makeLive(i, group) {
  const name = liveNames[i % liveNames.length];
  const c = {
    id: 2001 + i, playlistId: 1, name, url: `live:${2001 + i}`, logo: `logo:${name}`,
    groupTitle: group, tvgId: `tvg.${2001 + i}`, kind: 0, seriesKey: null, season: null, episode: null,
    xtreamStreamId: 2001 + i, catchupDays: i % 2 === 0 ? 7 : 0, catchupSource: null,
    description: null, durationSecs: null, airDate: null,
  };
  byId.set(c.id, c); return c;
}
function makeMovie(i, group) {
  const name = movieTitles[i % movieTitles.length];
  const c = {
    id: 1001 + i, playlistId: 1, name, url: `s:${1001 + i}`, logo: `poster:${name}`,
    groupTitle: group, tvgId: null, kind: 1, seriesKey: null, season: null, episode: null,
    xtreamStreamId: 1001 + i, catchupDays: 0, catchupSource: null, description: null,
    durationSecs: 7200 + (i % 5) * 600, airDate: null,
  };
  byId.set(c.id, c); return c;
}
const liveChannels = liveNames.map((_, i) => makeLive(i, 'United Kingdom'));
const movies = movieTitles.map((_, i) => makeMovie(i, i % 2 ? 'Sci-Fi' : 'Action'));

function episodesFor(seriesId, title, count = 8) {
  return Array.from({ length: count }, (_, k) => {
    const id = 3000 + seriesId * 20 + k;
    const c = {
      id, playlistId: 1, name: `Episode ${k + 1}`, url: `s:${id}`, logo: `poster:${title} ${k}`,
      groupTitle: 'Series', tvgId: null, kind: 2, seriesKey: `xs:${seriesId}`,
      season: 1, episode: k + 1, xtreamStreamId: id, catchupDays: 0, catchupSource: null,
      description: `In episode ${k + 1}, the story deepens as the characters face a new turn.`,
      durationSecs: 1800 + (k % 3) * 600, airDate: `2024-0${1 + (k % 8)}-1${k % 9}`,
    };
    byId.set(id, c); return c;
  });
}
const xtreamSeries = seriesTitles.map(([name, genre, rating], i) => ({
  playlistId: 1, seriesId: 5001 + i, name, categoryName: i % 2 ? 'Apple TV+' : 'Netflix',
  cover: `poster:${name}`, plot: `${name} follows a sharp, character-driven story that critics and audiences keep coming back to.`,
  castNames: 'Ayo Edebiri, Jeremy Allen White, Ebon Moss-Bachrach', genre, rating,
}));
seriesTitles.forEach(([name], i) => episodesFor(5001 + i, name, 8));

const castJson = JSON.stringify([
  { n: 'Timothée Chalamet', p: 'avatar:Timothee Chalamet' },
  { n: 'Zendaya', p: 'avatar:Zendaya' },
  { n: 'Rebecca Ferguson', p: 'avatar:Rebecca Ferguson' },
  { n: 'Javier Bardem', p: null },
  { n: 'Josh Brolin', p: 'avatar:Josh Brolin' },
]);
function movieMeta(c) {
  return {
    title: c.name.replace(/\s*\(.*?\)\s*/g, ' ').replace(/\b(4K|FHD|HDR|UHD)\b/g, '').trim(),
    year: (c.name.match(/\((\d{4})\)/) || [])[1] ?? '2024', overview:
      'A sweeping, visually staggering epic that pairs blockbuster spectacle with real emotional weight, and rarely lets up across its runtime.',
    rating: 8.4, castNames: 'Timothée Chalamet, Zendaya, Rebecca Ferguson', castJson,
    posterUrl: c.logo, infoLine: '2h 46m · Sci-Fi · PG-13',
  };
}

const sessions = [
  {
    id: 'a', ip: '192.168.1.42', userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36',
    playlistName: 'Living Room', title: 'Dune: Part Two (2024)', kind: 'movie', logo: 'poster:Dune: Part Two (2024) 4K',
    positionMs: 2_640_000, durationMs: 9_960_000, paused: false, live: false, startedAtMs: now - 44 * 60_000, lastSeenMs: now,
    stream: {
      engine: 'remux', direct: true, audioTranscoded: false, preparing: false,
      remux: { videoCodec: 'hevc', transcodeVideo: true, videoEncoder: 'libx264', nativeVideoCopy: false, audioCodec: 'eac3', audioChannels: 6, audioLabel: 'English', subtitleCount: 2, segmentCount: 3320, timeshift: false, providerKey: 'line.provider.tv:8080', connectionLimit: 2, ffmpegRunning: true, durationSec: 9960, lastLog: null },
    },
  },
  {
    id: 'b', ip: '192.168.1.15', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0',
    playlistName: 'Living Room', title: 'The Bear · S03E01', kind: 'series', logo: 'poster:The Bear',
    positionMs: 540_000, durationMs: 1_980_000, paused: true, live: false, startedAtMs: now - 9 * 60_000, lastSeenMs: now,
    stream: {
      engine: 'remux', direct: true, audioTranscoded: false, preparing: false,
      remux: { videoCodec: 'h264', transcodeVideo: false, videoEncoder: 'libx264', nativeVideoCopy: false, audioCodec: 'aac', audioChannels: 2, audioLabel: 'English', subtitleCount: 1, segmentCount: 330, timeshift: false, providerKey: 'line.provider.tv:8080', connectionLimit: 2, ffmpegRunning: false, durationSec: 1980, lastLog: null },
    },
  },
  {
    id: 'c', ip: '10.0.0.7', userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15',
    playlistName: 'Living Room', title: 'Sky Sports Main Event', kind: 'live', logo: 'logo:Sky Sports Main Event',
    positionMs: 0, durationMs: 0, paused: false, live: true, startedAtMs: now - 3 * 60_000, lastSeenMs: now,
    stream: { engine: 'hls', direct: false, audioTranscoded: false, preparing: false, remux: null },
  },
  {
    id: 'd', ip: '100.64.0.3', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36 Edg/126',
    playlistName: 'Living Room', title: 'Oppenheimer (2023)', kind: 'movie', logo: 'poster:Oppenheimer (2023) FHD',
    positionMs: 0, durationMs: 0, paused: false, live: false, startedAtMs: now - 5_000, lastSeenMs: now,
    stream: { engine: 'native', direct: false, audioTranscoded: false, preparing: true, remux: null },
  },
];

const downloads = [
  { id: 1, title: 'Dune: Part Two (2024)', url: 's:1001', filePath: '/data/dl/1', status: 1, totalBytes: 8_400_000_000, downloadedBytes: 5_500_000_000, error: null, createdMs: now - 20 * 60_000 },
  { id: 2, title: 'The Bear S03E01', url: 's:3001', filePath: '/data/dl/2', status: 2, totalBytes: 1_200_000_000, downloadedBytes: 1_200_000_000, error: null, createdMs: now - 3 * DAY },
  { id: 3, title: 'Interstellar (2014)', url: 's:1005', filePath: '/data/dl/3', status: 5, totalBytes: 6_100_000_000, downloadedBytes: 2_200_000_000, error: null, createdMs: now - DAY },
  { id: 4, title: 'Arrival (2016)', url: 's:1010', filePath: '/data/dl/4', status: 0, totalBytes: 0, downloadedBytes: 0, error: null, createdMs: now - 60_000 },
];

const guideEntries = () => {
  const base = now - 4 * 3600_000; const out = []; let t0 = base;
  const titles = ['Morning News', 'The Big Match: Highlights', 'Documentary: Deep Ocean', 'Live: Premier League', 'Evening Bulletin', 'Late Film: Heist', 'Midnight Movie'];
  for (let i = 0; i < titles.length; i++) {
    const len = (60 + (i % 3) * 30) * 60_000;
    out.push({ title: titles[i], description: i % 2 ? 'A closer look at tonight’s schedule, with analysis and interviews from the team.' : null, startMs: t0, endMs: t0 + len, replayable: t0 + len < now });
    t0 += len;
  }
  return out;
};

const account = { activeConnections: 1, maxConnections: 2, status: 'Active', expiresAtMs: now + 214 * DAY, username: 'living_room', isTrial: false, createdAtMs: now - 400 * DAY };

// ---- routing ----
function send(res, body, type = 'application/json') {
  res.writeHead(200, { 'Content-Type': type, 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' });
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname;
  const q = (k) => u.searchParams.get(k);
  const seg = p.split('/').filter(Boolean); // ["api", ...]

  if (req.method !== 'GET') { res.writeHead(200, { 'Access-Control-Allow-Origin': '*' }); return res.end('{}'); }

  if (p === '/api/img') {
    const raw = decodeURIComponent(q('u') || '');
    const [kind, ...rest] = raw.split(':'); const label = rest.join(':') || raw;
    const svg = kind === 'logo' ? logoSvg(label) : kind === 'avatar' ? avatarSvg(label) : posterSvg(label);
    return send(res, svg, 'image/svg+xml');
  }
  if (p === '/api/playlists') return send(res, playlists);
  if (p === '/api/remux/available') return send(res, { available: true });
  if (p === '/api/settings') return send(res, { userAgent: '', downloadLimit: 1, pageSize: 50 });
  if (p === '/api/downloads') return send(res, downloads);
  if (p === '/api/sessions') return send(res, sessions);
  if (p === '/api/resume') return send(res, [{ url: 's:1001', positionMs: 2_640_000, durationMs: 9_960_000, updatedMs: now }]);

  if (seg[1] === 'playlists' && seg[2]) {
    const id = Number(seg[2]); const pl = playlists.find((x) => x.id === id) || playlists[0];
    const sub = seg[3];
    if (!sub) return send(res, { playlist: pl, isXtreamNative: pl.hasXtreamPanel, liveCount: 1240, movieCount: 3150, seriesCount: 430 });
    if (sub === 'account') return send(res, account);
    if (sub === 'credentials') return send(res, { mode: pl.mode, url: null, epgUrl: null, xtreamBase: 'http://line.provider.tv:8080', xtreamUser: 'living_room', xtreamPass: '••••••' });
    if (sub === 'groups') { const k = Number(q('kind')); const g = k === 0 ? liveGroups : k === 1 ? movieGroups : seriesCats; return send(res, g.map(([groupTitle, count]) => ({ groupTitle, count }))); }
    if (sub === 'channels') { const k = Number(q('kind')); return send(res, k === 1 ? movies : liveChannels); }
    if (sub === 'series-groups') return send(res, []);
    if (sub === 'xtream-series') return send(res, xtreamSeries);
    if (sub === 'now-airing') { const m = {}; liveChannels.forEach((c, i) => { m[c.tvgId] = { tvgId: c.tvgId, title: guideEntries()[3].title, description: null, startMs: now - (10 + i) * 60_000, endMs: now + (35 - i) * 60_000 }; }); return send(res, m); }
    if (sub === 'guide-ids') return send(res, liveChannels.map((c) => c.tvgId));
    if (sub === 'favorites' && seg[4] === 'resolved') return send(res, { live: [liveChannels[4]], movies: [movies[0], movies[2], movies[6]], series: xtreamSeries.slice(0, 4).map((s) => ({ seriesKey: s.name, count: 0, logo: s.cover, groupTitle: s.categoryName, xtreamSeriesId: s.seriesId })) });
    if (sub === 'favorites') return send(res, [{ playlistId: 1, key: 's:1001', kind: 1 }, { playlistId: 1, key: 'x:5001', kind: 2 }, { playlistId: 1, key: 'live:2005', kind: 0 }]);
    if (sub === 'search') return send(res, { live: liveChannels.slice(0, 3), movies: movies.slice(0, 4), series: xtreamSeries.slice(0, 3).map((s) => ({ seriesKey: s.name, count: 0, logo: s.cover, groupTitle: s.categoryName, xtreamSeriesId: s.seriesId })) });
    if (sub === 'series' && seg[5] === 'episodes') return send(res, []);
    if (sub === 'xseries') { const sid = Number(seg[4]); const s = xtreamSeries.find((x) => x.seriesId === sid) || xtreamSeries[0]; return send(res, { series: s, episodes: episodesFor(sid, s.name, 8), error: null }); }
  }

  if (seg[1] === 'channels' && seg[2]) {
    const id = Number(seg[2]); const c = byId.get(id) || movies[0]; const sub = seg[3];
    if (!sub) return send(res, c);
    if (sub === 'vod-info') return send(res, movieMeta(c));
    if (sub === 'guide') return send(res, guideEntries());
    if (sub === 'catchup-url') return send(res, { url: 's:catchup' });
  }
  if (p === '/api/meta' || p === '/api/meta/episode') return send(res, { title: null, year: null, overview: null, rating: null, castNames: null, castJson: null, posterUrl: null, infoLine: null });

  // default: empty list / object
  return send(res, p.includes('groups') || p.includes('channels') || p.includes('series') ? [] : {});
});

server.listen(PORT, '127.0.0.1', () => console.log(`mock api on :${PORT}`));
