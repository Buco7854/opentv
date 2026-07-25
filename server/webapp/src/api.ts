// Typed facade for the OpenTV server API. Types mirror the server-owned Kotlin DTOs.
// HTTP/authentication policy lives in api/http.ts; browser preferences do not
// belong to this server-data boundary.

import { API_PREFIX, browserApiHttp, post, put } from './api/http';

export { ApiError } from './api/http';

export const ChannelKind = { LIVE: 0, MOVIE: 1, SERIES: 2 } as const;

export const DownloadStatus = {
  QUEUED: 'QUEUED',
  RUNNING: 'RUNNING',
  DONE: 'DONE',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
  PAUSED: 'PAUSED',
} as const;
export type DownloadStatus = typeof DownloadStatus[keyof typeof DownloadStatus];

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
  contentId: string;
  id: number;
  playlistId: number;
  name: string;
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
export interface SeriesGroup {
  contentId: string;
  seriesKey: string;
  count: number;
  logo: string | null;
  groupTitle: string;
}

export interface XtreamSeries {
  contentId: string;
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

export interface Favorite {
  contentId: string;
  playlistId: number;
  key: string;
  kind: number;
  addedMs: number;
}
export interface ResumePoint {
  contentId: string;
  positionMs: number;
  durationMs: number;
  updatedMs: number;
}

export interface SeriesHit {
  contentId: string;
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
  id: string;
  contentId: string;
  title: string;
  status: DownloadStatus;
  active: boolean;
  suspended: boolean;
  totalBytes: number;
  downloadedBytes: number;
  error: string | null;
  createdMs: number;
}

export interface PlaybackCreateRequest {
  contentId: string;
  mode?: 'play' | 'catchup' | 'download';
  catchupStartMs?: number;
  catchupDurationMs?: number;
  downloadId?: string;
}

export interface PlaybackLease {
  id: string;
  contentId: string;
  playlistId: number;
  mediaGrant: string;
  mediaGrantExpiresAtMs: number;
  streamUrl: string | null;
  relayUrl: string | null;
  transcodeUrl: string | null;
  remuxStartUrl: string;
}

export interface MediaGrant {
  token: string;
  expiresAtMs: number;
}

export interface RemuxStart {
  id: string;
  playlistUrl: string;
  duration: number | null;
  audioTracks: string[];
  subtitleTracks: string[];
  nativeVideoCopy: boolean;
  audio: number;
}

/** What a player reports about current playback (mirrors server SessionHeartbeatDto). */
export interface SessionHeartbeat {
  id: string;
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
  requestId: string | null;
  accepted: boolean | null;
  quiet: boolean;
  sync: SyncState | null;
  members: RoomMember[] | null;
  audioIndex: number | null;
}

/** Sparse client-to-server command; omitted fields use the Kotlin DTO defaults. */
export interface SessionCommandInput {
  type: 'pause' | 'play' | 'message' | 'sync';
  text?: string;
  sync?: SyncState;
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
  userId: string;
  username: string;
  displayName: string;
  clientKind: string;
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
  addFavorite: (id: number, contentId: string) =>
    j<null>(`/playlists/${id}/favorites`, put({ contentId })),
  removeFavorite: (id: number, contentId: string) =>
    j<null>(`/playlists/${id}/favorites?contentId=${encodeURIComponent(contentId)}`, { method: 'DELETE' }),
  favoritesResolved: (id: number) => j<FavoritesResolved>(`/playlists/${id}/favorites/resolved`),
  episodes: (id: number, seriesKey: string) =>
    j<Channel[]>(`/playlists/${id}/series/${encodeURIComponent(seriesKey)}/episodes`),
  xseries: (id: number, seriesId: number) => j<XtreamSeriesDetail>(`/playlists/${id}/xseries/${seriesId}`),
  channel: (id: number) => j<Channel>(`/channels/${id}`),
  guide: (id: number) => j<GuideEntry[]>(`/channels/${id}/guide`),
  vodInfo: (id: number) => j<Metadata>(`/channels/${id}/vod-info`),
  meta: (type: 'movie' | 'series', title: string) =>
    j<Metadata>(`/meta?type=${type}&title=${encodeURIComponent(title)}`),
  metaEpisode: (series: string, season: number, episode: number) =>
    j<Metadata>(`/meta/episode?series=${encodeURIComponent(series)}&season=${season}&episode=${episode}`),
  remuxAvailable: () => j<{ available: boolean }>('/remux/available'),
  remuxStart: (startUrl: string, audio = 0, timeshift = false, hevc = false) => {
    const url = new URL(startUrl, window.location.origin);
    url.searchParams.set('audio', String(audio));
    if (timeshift) url.searchParams.set('timeshift', '1'); else url.searchParams.delete('timeshift');
    if (hevc) url.searchParams.set('hevc', '1'); else url.searchParams.delete('hevc');
    const path = url.pathname.startsWith(API_PREFIX)
      ? url.pathname.slice(API_PREFIX.length) : url.pathname;
    return j<RemuxStart>(path + url.search, { method: 'POST' });
  },
  /** Release a remux resource using its owning lease and current media grant. */
  remuxStop: (id: string, leaseId: string, grant: string) =>
    apiFetch(
      `/remux/${encodeURIComponent(id)}?sid=${encodeURIComponent(leaseId)}&g=${encodeURIComponent(grant)}`,
      { method: 'DELETE', keepalive: true },
    ).catch(() => {}),
  createPlayback: (request: PlaybackCreateRequest) => j<PlaybackLease>('/playback', post(request)),
  playbackSocketUrl: (id: string) =>
    browserApiHttp.socketUrl(`/playback/${encodeURIComponent(id)}/ws`),
  /** keepalive so a heartbeat still fires from the player's unmount/unload. */
  playbackHeartbeat: (id: string, body: SessionHeartbeat) =>
    j<{ commands: SessionCommand[] }>(
      `/playback/${encodeURIComponent(id)}/heartbeat`,
      { ...post(body), keepalive: true },
    ),
  playbackEnd: (id: string) =>
    apiFetch(`/playback/${encodeURIComponent(id)}`, { method: 'DELETE', keepalive: true }).catch(() => {}),
  refreshMediaGrant: (id: string) =>
    j<MediaGrant>(`/playback/${encodeURIComponent(id)}/media-grant`, { method: 'POST' }),
  /** Who else is watching this content, and whether the provider is at its limit. */
  playbackIntent: (id: string) =>
    j<WatchIntent>(`/playback/${encodeURIComponent(id)}/intent`, { method: 'POST' }),
  /** Ask [hostId]'s viewer to admit us into a watch-together room. */
  joinRequest: (id: string, peerId: string) =>
    j<null>(`/playback/${encodeURIComponent(id)}/join-request`, post({ peerId })),
  /** The host's answer to a pending join request. */
  joinAnswer: (id: string, requestId: string, accept: boolean) =>
    j<null>(`/playback/${encodeURIComponent(id)}/join-answer`, post({ requestId, accept })),
  /** Host pushes its playback state to the room. keepalive so a final pause still lands. */
  sessionSync: (id: string, state: SyncState) =>
    apiFetch(`/playback/${encodeURIComponent(id)}/sync`, { ...post(state), keepalive: true }).catch(() => {}),
  /** A guest asks the room's host to let it control playback too. */
  requestControl: (id: string, requested = true) =>
    j<null>(`/playback/${encodeURIComponent(id)}/request-control`, post({ requested })),
  /** Host grants or refuses a guest's control request. */
  grantControl: (hostId: string, peerId: string, grant: boolean) =>
    j<null>(`/playback/${encodeURIComponent(hostId)}/grant-control`, post({ peerId, grant })),
  /** Host hands a member control (or takes it back) directly, no request needed. */
  setControl: (hostId: string, targetId: string, grant: boolean) =>
    j<null>(`/playback/${encodeURIComponent(hostId)}/set-control`, post({ targetId, grant })),
  /** A controller sets the room's shared audio track; every member re-requests the remux with it. */
  roomAudio: (id: string, audioIndex: number) =>
    j<null>(`/playback/${encodeURIComponent(id)}/room-audio`, post({ audioIndex })),
  /** A member reports it finished reloading after a track change; the room resumes once all have. */
  sessionReady: (id: string) =>
    apiFetch(`/playback/${encodeURIComponent(id)}/ready`, { method: 'POST', keepalive: true }).catch(() => {}),
  /** Host removes a member from the room. */
  kick: (hostId: string, targetId: string) =>
    j<null>(`/playback/${encodeURIComponent(hostId)}/kick`, post({ targetId })),
  sessionLeave: (id: string) =>
    j<null>(`/playback/${encodeURIComponent(id)}/leave`, { method: 'POST', keepalive: true }),
  adminPlayback: () => j<Session[]>('/admin/playback'),
  adminPlaybackCommand: (id: string, command: SessionCommandInput) =>
    j<null>(`/admin/playback/${encodeURIComponent(id)}/command`, post(command)),
  adminPlaybackEnd: (id: string) =>
    j<null>(`/admin/playback/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  resumeAll: () => j<ResumePoint[]>('/resume'),
  saveResume: (contentId: string, positionMs: number, durationMs: number) =>
    j<null>('/resume', put({ contentId, positionMs, durationMs, updatedMs: Date.now() })),
  deleteResume: (contentId: string) =>
    j<null>(`/resume?contentId=${encodeURIComponent(contentId)}`, { method: 'DELETE' }),
  settings: () => j<Settings>('/settings'),
  saveSettings: (s: Settings, keepalive = false) =>
    j<null>('/settings', { ...put(s), keepalive }),
  downloads: () => j<Download[]>('/downloads'),
  enqueueDownload: (contentId: string) => j<{ message: string }>('/downloads', post({ contentId })),
  pauseDownload: (id: string) => j<null>(`/downloads/${encodeURIComponent(id)}/pause`, { method: 'POST' }),
  resumeDownload: (id: string) => j<null>(`/downloads/${encodeURIComponent(id)}/resume`, { method: 'POST' }),
  retryDownload: (id: string) => j<null>(`/downloads/${encodeURIComponent(id)}/retry`, { method: 'POST' }),
  deleteDownload: (id: string) => j<null>(`/downloads/${encodeURIComponent(id)}`, { method: 'DELETE' }),
};

export const imgUrl = (u: string) => `${API_PREFIX}/img?u=${encodeURIComponent(u)}`;
export const downloadFileUrl = (id: string, save = false) =>
  `${API_PREFIX}/downloads/${encodeURIComponent(id)}/file${save ? '?save=1' : ''}`;
