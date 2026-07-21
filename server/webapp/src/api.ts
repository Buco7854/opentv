// Typed client for the OpenTV server API. Types mirror core/model/Models.kt and server/Routes.kt.

export const ChannelKind = { LIVE: 0, MOVIE: 1, SERIES: 2 } as const;

export const DownloadStatus = {
  QUEUED: 0, RUNNING: 1, DONE: 2, FAILED: 3, CANCELLED: 4, PAUSED: 5,
} as const;

export type PlaylistMode = 'xtream' | 'url' | 'file';

/** Credential-free playlist listing; secrets come only from api.playlistCredentials(). */
export interface Playlist {
  id: number;
  name: string;
  mode: PlaylistMode;
  hasXtreamPanel: boolean;
  lastRefreshedMs: number;
  channelCount: number;
}

export interface PlaylistCredentials {
  mode: PlaylistMode;
  url: string | null;
  epgUrl: string | null;
  xtreamBase: string | null;
  xtreamUser: string | null;
  xtreamPass: string | null;
}

export interface Channel {
  id: number;
  playlistId: number;
  name: string;
  url: string;
  logo: string | null;
  groupTitle: string;
  tvgId: string | null;
  kind: number;
  seriesKey: string | null;
  season: number | null;
  episode: number | null;
  xtreamStreamId: number | null;
  catchupDays: number;
  catchupSource: string | null;
  description: string | null;
  durationSecs: number | null;
  airDate: string | null;
}

export const hasCatchup = (c: Channel) => c.catchupSource != null || c.catchupDays > 0;

/** Xtream channels have a per-channel panel guide; M3U needs stored programme rows. */
export const canShowGuide = (c: Channel, guideIds: Set<string>) =>
  c.xtreamStreamId != null || (c.tvgId != null && guideIds.has(c.tvgId));

export interface GroupCount { groupTitle: string; count: number }
export interface SeriesGroup { seriesKey: string; count: number; logo: string | null; groupTitle: string }

export interface XtreamSeries {
  playlistId: number;
  seriesId: number;
  name: string;
  categoryName: string;
  cover: string | null;
  plot: string | null;
  castNames: string | null;
  genre: string | null;
  rating: number | null;
}

export interface Programme { tvgId: string; title: string; description: string | null; startMs: number; endMs: number }
export interface GuideEntry { title: string; description: string | null; startMs: number; endMs: number; replayable: boolean }

export interface AccountInfo {
  activeConnections: number;
  maxConnections: number;
  status: string;
  expiresAtMs: number | null;
  username: string | null;
  isTrial: boolean;
  createdAtMs: number | null;
}

export interface Metadata {
  title: string | null;
  year: string | null;
  overview: string | null;
  rating: number | null;
  castNames: string | null;
  castJson: string | null;
  posterUrl: string | null;
  infoLine: string | null;
}

export interface Favorite { playlistId: number; key: string; kind: number }
export interface ResumePoint { url: string; positionMs: number; durationMs: number }

export interface SeriesHit {
  seriesKey: string;
  count: number;
  logo: string | null;
  groupTitle: string;
  xtreamSeriesId: number | null;
}

export interface SearchResults { live: Channel[]; movies: Channel[]; series: SeriesHit[] }

export interface PlaylistDetail {
  playlist: Playlist;
  isXtreamNative: boolean;
  liveCount: number;
  movieCount: number;
  seriesCount: number;
}

export interface PlaylistUpsertRequest {
  mode: 'xtream' | 'url' | 'file';
  name: string;
  server?: string;
  username?: string;
  password?: string;
  url?: string;
  epgUrl?: string;
  content?: string;
}

export interface Download {
  id: number;
  title: string;
  url: string;
  filePath: string;
  status: number;
  totalBytes: number;
  downloadedBytes: number;
  error: string | null;
  createdMs: number;
}

/** What a player reports about current playback (mirrors server SessionHeartbeatDto). */
export interface SessionHeartbeat {
  id: string;
  playlistId: number | null;
  title: string;
  kind: 'live' | 'movie' | 'series' | 'catchup' | 'download';
  logo: string | null;
  positionMs: number;
  durationMs: number;
  paused: boolean;
  live: boolean;
  engine: 'hls' | 'mpegts' | 'native' | 'remux';
  direct: boolean;
  audioTranscoded: boolean;
  remuxId: string | null;
}

export interface SessionCommand { type: 'pause' | 'play' | 'message'; text?: string }

export interface RemuxDiag {
  videoCodec: string;
  transcodeVideo: boolean;
  videoEncoder: string;
  nativeVideoCopy: boolean;
  audioCodec: string;
  audioChannels: number | null;
  audioLabel: string | null;
  subtitleCount: number;
  segmentCount: number;
  timeshift: boolean;
  providerKey: string;
  connectionLimit: number;
  ffmpegRunning: boolean;
  durationSec: number | null;
  lastLog: string | null;
}

export interface SessionStream {
  engine: 'hls' | 'mpegts' | 'native' | 'remux';
  direct: boolean;
  audioTranscoded: boolean;
  remux: RemuxDiag | null;
}

/** One active viewer on the activity dashboard. */
export interface Session {
  id: string;
  ip: string;
  userAgent: string;
  playlistName: string | null;
  title: string;
  kind: SessionHeartbeat['kind'];
  logo: string | null;
  positionMs: number;
  durationMs: number;
  paused: boolean;
  live: boolean;
  startedAtMs: number;
  lastSeenMs: number;
  stream: SessionStream;
}

export interface XtreamSeriesDetail { series: XtreamSeries; episodes: Channel[]; error: string | null }
export interface FavoritesResolved { live: Channel[]; movies: Channel[]; series: SeriesHit[] }
export interface Settings { userAgent: string; downloadLimit: number; pageSize: number }

async function j<T>(url: string, opts?: RequestInit): Promise<T> {
  const r = await fetch(url, opts);
  if (!r.ok) {
    let message = `HTTP ${r.status}`;
    try { message = ((await r.json()) as { message?: string }).message || message; } catch { /* not json */ }
    throw new Error(message);
  }
  return (r.status === 204 ? null : r.json()) as Promise<T>;
}

const put = (body: unknown): RequestInit =>
  ({ method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const post = (body: unknown): RequestInit =>
  ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

export const api = {
  playlists: () => j<Playlist[]>('/api/playlists'),
  addPlaylist: (req: PlaylistUpsertRequest) => j<Playlist>('/api/playlists', post(req)),
  updatePlaylist: (id: number, req: PlaylistUpsertRequest) => j<Playlist>(`/api/playlists/${id}`, put(req)),
  deletePlaylist: (id: number) => j<null>(`/api/playlists/${id}`, { method: 'DELETE' }),
  refreshPlaylist: (id: number, force: boolean) => j<Playlist>(`/api/playlists/${id}/refresh?force=${force}`, { method: 'POST' }),
  clearProgress: (id: number) => j<null>(`/api/playlists/${id}/clear-progress`, { method: 'POST' }),
  playlistDetail: (id: number) => j<PlaylistDetail>(`/api/playlists/${id}`),
  playlistCredentials: (id: number) => j<PlaylistCredentials>(`/api/playlists/${id}/credentials`),
  groups: (id: number, kind: number) => j<GroupCount[]>(`/api/playlists/${id}/groups?kind=${kind}`),
  channels: (id: number, kind: number, group: string) =>
    j<Channel[]>(`/api/playlists/${id}/channels?kind=${kind}&group=${encodeURIComponent(group)}`),
  seriesGroups: (id: number, group: string) =>
    j<SeriesGroup[]>(`/api/playlists/${id}/series-groups?group=${encodeURIComponent(group)}`),
  xtreamSeries: (id: number, category: string) =>
    j<XtreamSeries[]>(`/api/playlists/${id}/xtream-series?category=${encodeURIComponent(category)}`),
  nowAiring: (id: number) => j<Record<string, Programme>>(`/api/playlists/${id}/now-airing`),
  guideIds: (id: number) => j<string[]>(`/api/playlists/${id}/guide-ids`),
  search: (id: number, q: string) => j<SearchResults>(`/api/playlists/${id}/search?q=${encodeURIComponent(q)}`),
  account: (id: number, force: boolean) => j<AccountInfo>(`/api/playlists/${id}/account?force=${force}`),
  setGroupKind: (id: number, groupTitle: string, kind: number | null) =>
    j<null>(`/api/playlists/${id}/group-kind`, put({ groupTitle, kind })),
  favorites: (id: number) => j<Favorite[]>(`/api/playlists/${id}/favorites`),
  addFavorite: (id: number, key: string, kind: number) =>
    j<null>(`/api/playlists/${id}/favorites`, put({ playlistId: id, key, kind })),
  removeFavorite: (id: number, key: string) =>
    j<null>(`/api/playlists/${id}/favorites?key=${encodeURIComponent(key)}`, { method: 'DELETE' }),
  favoritesResolved: (id: number) => j<FavoritesResolved>(`/api/playlists/${id}/favorites/resolved`),
  episodes: (id: number, seriesKey: string) =>
    j<Channel[]>(`/api/playlists/${id}/series/${encodeURIComponent(seriesKey)}/episodes`),
  xseries: (id: number, seriesId: number) => j<XtreamSeriesDetail>(`/api/playlists/${id}/xseries/${seriesId}`),
  channel: (id: number) => j<Channel>(`/api/channels/${id}`),
  guide: (id: number) => j<GuideEntry[]>(`/api/channels/${id}/guide`),
  catchupUrl: (id: number, start: number, end: number) =>
    j<{ url: string | null }>(`/api/channels/${id}/catchup-url?start=${start}&end=${end}`),
  vodInfo: (id: number) => j<Metadata>(`/api/channels/${id}/vod-info`),
  meta: (type: 'movie' | 'series', title: string) =>
    j<Metadata>(`/api/meta?type=${type}&title=${encodeURIComponent(title)}`),
  metaEpisode: (series: string, season: number, episode: number) =>
    j<Metadata>(`/api/meta/episode?series=${encodeURIComponent(series)}&season=${season}&episode=${episode}`),
  remuxAvailable: () => j<{ available: boolean }>('/api/remux/available'),
  /** Prepare an HLS session for a file; returns its VOD playlist URL and track lists. */
  remuxStart: (u: string, audio = 0, force = false, hevc = false) =>
    j<{ id: string; playlistUrl: string; duration: number | null; audioTracks: string[]; subtitleTracks: string[]; nativeVideoCopy: boolean }>(
      `/api/remux/start?u=${encodeURIComponent(u)}&audio=${audio}${force ? '&force=1' : ''}${hevc ? '&hevc=1' : ''}`,
    ),
  /** Release a remux session (and its provider connection) when playback ends. */
  remuxStop: (id: string) => fetch(`/api/remux/${id}`, { method: 'DELETE', keepalive: true }).catch(() => {}),
  sessions: () => j<Session[]>('/api/sessions'),
  /** keepalive so a heartbeat still fires from the player's unmount/unload. */
  sessionHeartbeat: (body: SessionHeartbeat) =>
    fetch('/api/sessions/heartbeat', { ...post(body), keepalive: true })
      .then((r) => (r.ok ? (r.json() as Promise<{ commands: SessionCommand[] }>) : { commands: [] }))
      .catch(() => ({ commands: [] as SessionCommand[] })),
  sessionCommand: (id: string, command: SessionCommand) =>
    j<null>(`/api/sessions/${encodeURIComponent(id)}/command`, post(command)),
  sessionEnd: (id: string) =>
    fetch(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE', keepalive: true }).catch(() => {}),
  resumeAll: () => j<ResumePoint[]>('/api/resume'),
  saveResume: (url: string, positionMs: number, durationMs: number) =>
    j<null>('/api/resume', put({ url, positionMs, durationMs, updatedMs: Date.now() })),
  settings: () => j<Settings>('/api/settings'),
  saveSettings: (s: Settings) => j<null>('/api/settings', put(s)),
  downloads: () => j<Download[]>('/api/downloads'),
  enqueueDownload: (channelId: number) => j<{ message: string }>('/api/downloads', post({ channelId })),
  pauseDownload: (id: number) => j<null>(`/api/downloads/${id}/pause`, { method: 'POST' }),
  resumeDownload: (id: number) => j<null>(`/api/downloads/${id}/resume`, { method: 'POST' }),
  retryDownload: (id: number) => j<null>(`/api/downloads/${id}/retry`, { method: 'POST' }),
  deleteDownload: (id: number) => j<null>(`/api/downloads/${id}`, { method: 'DELETE' }),
};

/** Proxy every provider URL (CORS, mixed content). `u` is an opaque token, never a raw URL;
 *  `hls` requests the HLS variant of an Xtream live `.ts` source. */
export const streamUrl = (u: string, hls = false) =>
  `/api/stream?u=${encodeURIComponent(u)}${hls ? '&hls=1' : ''}`;
/** Audio re-encoded to AAC (video copied); live-playback fallback when the browser can't decode the codec. */
export const transcodeUrl = (u: string) => `/api/transcode?u=${encodeURIComponent(u)}`;
export const imgUrl = (u: string) => `/api/img?u=${encodeURIComponent(u)}`;
export const downloadFileUrl = (id: number, save = false) => `/api/downloads/${id}/file${save ? '?save=1' : ''}`;

export type Theme = 'light' | 'dark' | 'system';

/** Local (per-browser) UI preferences; shared data lives on the server. */
export const prefs = {
  get gridBrowse() { return localStorage.getItem('gridBrowse') !== '0'; },
  set gridBrowse(v: boolean) { localStorage.setItem('gridBrowse', v ? '1' : '0'); },
  get seekSeconds() { return Number(localStorage.getItem('seekSeconds')) || 10; },
  set seekSeconds(v: number) { localStorage.setItem('seekSeconds', String(v)); },
  get resizeMode() { return localStorage.getItem('resizeMode') ?? 'fit'; },
  set resizeMode(v: string) { localStorage.setItem('resizeMode', v); },
  get volume() { const v = Number(localStorage.getItem('volume')); return isFinite(v) && v > 0 ? Math.min(1, v) : 1; },
  set volume(v: number) { localStorage.setItem('volume', String(v)); },
  get muted() { return localStorage.getItem('muted') === '1'; },
  set muted(v: boolean) { localStorage.setItem('muted', v ? '1' : '0'); },
  /** Subtitle text size multiplier (0.5 .. 2). */
  get subScale() { const v = Number(localStorage.getItem('subScale')); return isFinite(v) && v > 0 ? Math.min(2, Math.max(0.5, v)) : 1; },
  set subScale(v: number) { localStorage.setItem('subScale', String(v)); },
  /** 'outline' = shadowed text; 'background' = translucent box. */
  get subStyle() { return localStorage.getItem('subStyle') === 'background' ? 'background' : 'outline'; },
  set subStyle(v: string) { localStorage.setItem('subStyle', v); },
  get subBold() { return localStorage.getItem('subBold') === '1'; },
  set subBold(v: boolean) { localStorage.setItem('subBold', v ? '1' : '0'); },
  get theme(): Theme {
    const t = localStorage.getItem('theme');
    return t === 'light' || t === 'dark' ? t : 'system';
  },
  set theme(v: Theme) { localStorage.setItem('theme', v); },
  /** Playlist the dock's Live/Movies/Series/Favorites/Search act on. */
  get activePlaylist(): number | null {
    const n = Number(localStorage.getItem('activePlaylist'));
    return Number.isFinite(n) && n > 0 ? n : null;
  },
  set activePlaylist(v: number | null) {
    if (v == null) localStorage.removeItem('activePlaylist');
    else localStorage.setItem('activePlaylist', String(v));
  },
};

/** data-theme drives the CSS tokens. */
export function applyTheme(theme = prefs.theme) {
  document.documentElement.setAttribute('data-theme', theme);
}
