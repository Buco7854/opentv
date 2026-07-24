// Typed facade for the OpenTV server API. Types mirror the server-owned Kotlin DTOs.
// HTTP/authentication policy lives in api/http.ts; browser preferences do not
// belong to this server-data boundary.

import { API_PREFIX, browserApiHttp, post, put } from './api/http';

export { ApiError } from './api/http';

export const ChannelKind = { LIVE: 0, MOVIE: 1, SERIES: 2 } as const;

export const DownloadStatus = {
  QUEUED: 0, RUNNING: 1, DONE: 2, FAILED: 3, CANCELLED: 4, PAUSED: 5,
} as const;

export type PlaylistMode = 'xtream' | 'url' | 'file';

/** Credential-free playlist listing. Stored provider details never return to the browser. */
export interface Playlist {
  id: number;
  name: string;
  mode: PlaylistMode;
  hasXtreamPanel: boolean;
  lastRefreshedMs: number;
  channelCount: number;
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
  position: number;
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
  episodesFetchedAtMs: number;
}

export interface Programme {
  id: number;
  playlistId: number;
  tvgId: string;
  title: string;
  description: string | null;
  startMs: number;
  endMs: number;
}
export interface GuideEntry { title: string; description: string | null; startMs: number; endMs: number; replayable: boolean }

export interface AccountInfo {
  activeConnections: number;
  maxConnections: number;
  status: string;
  expiresAtMs: number | null;
  username: string | null;
  isTrial: boolean;
  createdAtMs: number | null;
  timezone: string | null;
}

export interface Metadata {
  cacheKey: string;
  title: string | null;
  year: string | null;
  overview: string | null;
  rating: number | null;
  castNames: string | null;
  castJson: string | null;
  posterUrl: string | null;
  infoLine: string | null;
  sourceId: number | null;
  fetchedAtMs: number;
}

export interface Favorite { playlistId: number; key: string; kind: number; addedMs: number }
export interface ResumePoint { url: string; positionMs: number; durationMs: number; updatedMs: number }

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
  /** Server is still probing the file to choose remux vs transcode; mode is undecided. */
  preparing: boolean;
  remuxId: string | null;
  /** Stable id of the content, so the server can spot two viewers of the same thing. */
  contentKey: string;
  /** Friendly device label, shown in the watch-together roster. */
  name: string;
}

/** Driver playback state mirrored to a watch-together room's other members.
 *  `seek` marks a deliberate jump (apply exactly) vs. a periodic anchor (only fix big drift). */
export interface SyncState { positionMs: number; paused: boolean; rate: number; seek: boolean }

/** One viewer in a watch-together room. */
export interface RoomMember { id: string; name: string; host: boolean; controller: boolean }

export type SessionCommandType =
  | 'pause' | 'play' | 'message' | 'join-request' | 'join-response'
  | 'control-request' | 'control-response' | 'sync' | 'room-state'
  | 'room-ended' | 'room-audio' | 'room-go';

/** Complete server-to-client command shape; Kotlin serialization emits every defaulted field. */
export interface SessionCommand {
  type: SessionCommandType;
  text: string | null;
  peerId: string | null;
  peerName: string | null;
  accepted: boolean | null;
  quiet: boolean;
  sync: SyncState | null;
  members: RoomMember[] | null;
  audioIndex: number | null;
}

/** Sparse client-to-server command; omitted fields use the Kotlin DTO defaults. */
export interface SessionCommandInput {
  type: SessionCommandType;
  text?: string;
  peerId?: string;
  peerName?: string;
  accepted?: boolean;
  quiet?: boolean;
  sync?: SyncState;
  members?: RoomMember[];
  audioIndex?: number;
}

/** A viewer already on this content, offered as someone to watch together with. */
export interface WatchIntentPeer { id: string; name: string }
/** Who else is on this content, and whether the provider's connections are all in use. */
export interface WatchIntent { sameContent: WatchIntentPeer[]; full: boolean; limit: number }

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
  preparing: boolean;
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
  /** Set when the viewer is in a watch-together room; roomSize counts its members. */
  roomId: string | null;
  roomSize: number;
}

export interface XtreamSeriesDetail { series: XtreamSeries; episodes: Channel[]; error: string | null }
export interface FavoritesResolved { live: Channel[]; movies: Channel[]; series: SeriesHit[] }
export interface Settings { userAgent: string; downloadLimit: number; pageSize: number }

const j = <T>(path: string, options?: RequestInit) =>
  browserApiHttp.json<T>(path, options);
const apiFetch = (path: string, options?: RequestInit) =>
  browserApiHttp.raw(path, options);

export const api = {
  playlists: () => j<Playlist[]>('/playlists'),
  addPlaylist: (req: PlaylistUpsertRequest) => j<Playlist>('/playlists', post(req)),
  updatePlaylist: (id: number, req: PlaylistUpsertRequest) => j<Playlist>(`/playlists/${id}`, put(req)),
  deletePlaylist: (id: number) => j<null>(`/playlists/${id}`, { method: 'DELETE' }),
  refreshPlaylist: (id: number, force: boolean) => j<Playlist>(`/playlists/${id}/refresh?force=${force}`, { method: 'POST' }),
  clearProgress: (id: number) => j<null>(`/playlists/${id}/clear-progress`, { method: 'POST' }),
  playlistDetail: (id: number) => j<PlaylistDetail>(`/playlists/${id}`),
  groups: (id: number, kind: number) => j<GroupCount[]>(`/playlists/${id}/groups?kind=${kind}`),
  channels: (id: number, kind: number, group: string) =>
    j<Channel[]>(`/playlists/${id}/channels?kind=${kind}&group=${encodeURIComponent(group)}`),
  seriesGroups: (id: number, group: string) =>
    j<SeriesGroup[]>(`/playlists/${id}/series-groups?group=${encodeURIComponent(group)}`),
  xtreamSeries: (id: number, category: string) =>
    j<XtreamSeries[]>(`/playlists/${id}/xtream-series?category=${encodeURIComponent(category)}`),
  nowAiring: (id: number) => j<Record<string, Programme>>(`/playlists/${id}/now-airing`),
  guideIds: (id: number) => j<string[]>(`/playlists/${id}/guide-ids`),
  search: (id: number, q: string) => j<SearchResults>(`/playlists/${id}/search?q=${encodeURIComponent(q)}`),
  account: (id: number, force: boolean) => j<AccountInfo>(`/playlists/${id}/account?force=${force}`),
  setGroupKind: (id: number, groupTitle: string, kind: number | null) =>
    j<null>(`/playlists/${id}/group-kind`, put({ groupTitle, kind })),
  favorites: (id: number) => j<Favorite[]>(`/playlists/${id}/favorites`),
  addFavorite: (id: number, key: string, kind: number) =>
    j<null>(`/playlists/${id}/favorites`, put({ key, kind })),
  removeFavorite: (id: number, key: string) =>
    j<null>(`/playlists/${id}/favorites?key=${encodeURIComponent(key)}`, { method: 'DELETE' }),
  favoritesResolved: (id: number) => j<FavoritesResolved>(`/playlists/${id}/favorites/resolved`),
  episodes: (id: number, seriesKey: string) =>
    j<Channel[]>(`/playlists/${id}/series/${encodeURIComponent(seriesKey)}/episodes`),
  xseries: (id: number, seriesId: number) => j<XtreamSeriesDetail>(`/playlists/${id}/xseries/${seriesId}`),
  channel: (id: number) => j<Channel>(`/channels/${id}`),
  guide: (id: number) => j<GuideEntry[]>(`/channels/${id}/guide`),
  catchupUrl: (id: number, start: number, end: number) =>
    j<{ url: string | null }>(`/channels/${id}/catchup-url?start=${start}&end=${end}`),
  vodInfo: (id: number) => j<Metadata>(`/channels/${id}/vod-info`),
  meta: (type: 'movie' | 'series', title: string) =>
    j<Metadata>(`/meta?type=${type}&title=${encodeURIComponent(title)}`),
  metaEpisode: (series: string, season: number, episode: number) =>
    j<Metadata>(`/meta/episode?series=${encodeURIComponent(series)}&season=${season}&episode=${episode}`),
  remuxAvailable: () => j<{ available: boolean }>('/remux/available'),
  /** Prepare an HLS session for a file; returns its VOD playlist URL and track lists. [sid]
   *  groups the provider read: alone it's this tab's, in a watch-together room it's the room's. */
  remuxStart: (u: string, audio = 0, force = false, hevc = false, sid = '') =>
    j<{ id: string; playlistUrl: string; duration: number | null; audioTracks: string[]; subtitleTracks: string[]; nativeVideoCopy: boolean; audio: number }>(
      `/remux/start?u=${encodeURIComponent(u)}&audio=${audio}${force ? '&force=1' : ''}${hevc ? '&hevc=1' : ''}${sid ? `&sid=${encodeURIComponent(sid)}` : ''}`,
    ),
  /** Release a remux session (and its provider connection) when playback ends. */
  remuxStop: (id: string) => apiFetch(`/remux/${id}`, { method: 'DELETE', keepalive: true }).catch(() => {}),
  sessions: () => j<Session[]>('/sessions'),
  sessionSocketUrl: (id: string) =>
    browserApiHttp.socketUrl(`/sessions/${encodeURIComponent(id)}/ws`),
  /** keepalive so a heartbeat still fires from the player's unmount/unload. */
  sessionHeartbeat: (body: SessionHeartbeat) =>
    apiFetch('/sessions/heartbeat', { ...post(body), keepalive: true })
      .then((r) => (r.ok ? (r.json() as Promise<{ commands: SessionCommand[] }>) : { commands: [] }))
      .catch(() => ({ commands: [] as SessionCommand[] })),
  sessionCommand: (id: string, command: SessionCommandInput) =>
    j<null>(`/sessions/${encodeURIComponent(id)}/command`, post(command)),
  sessionEnd: (id: string) =>
    apiFetch(`/sessions/${encodeURIComponent(id)}`, { method: 'DELETE', keepalive: true }).catch(() => {}),
  /** Who else is watching this content, and whether the provider is at its limit. */
  watchIntent: (selfId: string, contentKey: string, source: string | null, playlistId: number | null) =>
    j<WatchIntent>('/sessions/intent', post({ selfId, contentKey, source, playlistId })),
  /** Ask [hostId]'s viewer to admit us into a watch-together room. */
  joinRequest: (hostId: string, peerId: string, peerName: string, contentKey: string) =>
    j<null>(`/sessions/${encodeURIComponent(hostId)}/join-request`, post({ peerId, peerName, contentKey })),
  /** The host's answer to a pending join request. */
  joinAnswer: (hostId: string, peerId: string, hostName: string, contentKey: string, accept: boolean) =>
    j<null>(`/sessions/${encodeURIComponent(hostId)}/join-answer`, post({ peerId, hostName, contentKey, accept })),
  /** Host pushes its playback state to the room. keepalive so a final pause still lands. */
  sessionSync: (id: string, state: SyncState) =>
    apiFetch(`/sessions/${encodeURIComponent(id)}/sync`, { ...post(state), keepalive: true }).catch(() => {}),
  /** A guest asks the room's host to let it control playback too. */
  requestControl: (id: string, peerName: string) =>
    j<null>(`/sessions/${encodeURIComponent(id)}/request-control`, post({ peerName })),
  /** Host grants or refuses a guest's control request. */
  grantControl: (hostId: string, peerId: string, grant: boolean) =>
    j<null>(`/sessions/${encodeURIComponent(hostId)}/grant-control`, post({ peerId, grant })),
  /** Host hands a member control (or takes it back) directly, no request needed. */
  setControl: (hostId: string, targetId: string, grant: boolean) =>
    j<null>(`/sessions/${encodeURIComponent(hostId)}/set-control`, post({ targetId, grant })),
  /** A controller sets the room's shared audio track; every member re-requests the remux with it. */
  roomAudio: (id: string, audioIndex: number) =>
    j<null>(`/sessions/${encodeURIComponent(id)}/room-audio`, post({ audioIndex })),
  /** A member reports it finished reloading after a track change; the room resumes once all have. */
  sessionReady: (id: string) =>
    apiFetch(`/sessions/${encodeURIComponent(id)}/ready`, { method: 'POST', keepalive: true }).catch(() => {}),
  /** Host removes a member from the room. */
  kick: (hostId: string, targetId: string) =>
    j<null>(`/sessions/${encodeURIComponent(hostId)}/kick`, post({ targetId })),
  sessionLeave: (id: string) =>
    apiFetch(`/sessions/${encodeURIComponent(id)}/leave`, { method: 'POST', keepalive: true }).catch(() => {}),
  resumeAll: () => j<ResumePoint[]>('/resume'),
  saveResume: (url: string, positionMs: number, durationMs: number) =>
    j<null>('/resume', put({ url, positionMs, durationMs, updatedMs: Date.now() })),
  settings: () => j<Settings>('/settings'),
  saveSettings: (s: Settings) => j<null>('/settings', put(s)),
  downloads: () => j<Download[]>('/downloads'),
  enqueueDownload: (channelId: number) => j<{ message: string }>('/downloads', post({ channelId })),
  pauseDownload: (id: number) => j<null>(`/downloads/${id}/pause`, { method: 'POST' }),
  resumeDownload: (id: number) => j<null>(`/downloads/${id}/resume`, { method: 'POST' }),
  retryDownload: (id: number) => j<null>(`/downloads/${id}/retry`, { method: 'POST' }),
  deleteDownload: (id: number) => j<null>(`/downloads/${id}`, { method: 'DELETE' }),
};

/** Proxy every provider URL (CORS, mixed content). `u` is an opaque token, never a raw URL;
 *  `hls` requests the HLS variant of an Xtream live `.ts` source. `sid` (the player's session)
 *  lets the server count this stream against the provider's concurrent-connection cap. */
export const streamUrl = (u: string, hls = false, sid?: string) =>
  `${API_PREFIX}/stream?u=${encodeURIComponent(u)}${hls ? '&hls=1' : ''}${sid ? `&sid=${encodeURIComponent(sid)}` : ''}`;
/** Audio re-encoded to AAC (video copied); live-playback fallback when the browser can't decode the codec. */
export const transcodeUrl = (u: string, sid?: string) =>
  `${API_PREFIX}/transcode?u=${encodeURIComponent(u)}${sid ? `&sid=${encodeURIComponent(sid)}` : ''}`;
/** Watch-together live: the room's shared upstream (one provider connection), keyed by the
 *  viewer's session so the server groups the whole room onto a single read. */
export const relayUrl = (u: string, sid: string) =>
  `${API_PREFIX}/relay?u=${encodeURIComponent(u)}&sid=${encodeURIComponent(sid)}`;
export const imgUrl = (u: string) => `${API_PREFIX}/img?u=${encodeURIComponent(u)}`;
export const downloadFileUrl = (id: number, save = false) =>
  `${API_PREFIX}/downloads/${id}/file${save ? '?save=1' : ''}`;
