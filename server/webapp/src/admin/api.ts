import { browserApiHttp, post } from '../api/http';

export interface AdminUser {
  id: string;
  username: string;
  displayName: string;
  status: 'INVITED' | 'PENDING' | 'ACTIVE' | 'DISABLED';
  manualRole: 'USER' | 'ADMIN';
  effectiveRole: 'USER' | 'ADMIN';
  authMethods: string[];
  playlistIds: number[];
  createdAtMs: number;
  lastLoginAtMs: number | null;
}

export interface CreatedUser {
  user: AdminUser;
  activationToken: string;
}

export interface AdminAuthSession {
  id: string;
  authMethod: string;
  clientKind: string;
  deviceName: string | null;
  createdAtMs: number;
  lastSeenAtMs: number;
  idleExpiresAtMs: number;
  absoluteExpiresAtMs: number;
}

export interface AdminResumePoint {
  contentId: string;
  positionMs: number;
  durationMs: number;
  updatedMs: number;
}

export interface PendingOidcIdentity {
  issuer: string;
  subject: string;
  username: string | null;
  displayName: string | null;
  groups: string[];
  adminMapped: boolean;
  createdAtMs: number;
}

export interface AdminDownload {
  userId: string;
  userDownloadId: string;
  blobId: string;
  contentId: string;
  title: string;
  status: string;
  active: boolean;
  suspended: boolean;
  totalBytes: number;
  downloadedBytes: number;
}

export interface AdminPlaylist {
  id: number;
  name: string;
  mode: 'xtream' | 'url' | 'file';
  hasXtreamPanel: boolean;
  lastRefreshedMs: number;
  channelCount: number;
}

export interface AdminUserUpdate {
  username?: string;
  displayName?: string;
  role?: 'USER' | 'ADMIN';
  status?: AdminUser['status'];
}

const j = <T>(path: string, init?: RequestInit) => browserApiHttp.json<T>(path, init);
const remove = (path: string) => j<null>(path, { method: 'DELETE' });
const userPath = (userId: string) => `/admin/users/${encodeURIComponent(userId)}`;

export const adminApi = {
  users: () => j<AdminUser[]>('/admin/users'),
  createUser: (request: { username: string; displayName: string; role: 'USER' | 'ADMIN' }) =>
    j<CreatedUser>('/admin/users', post(request)),
  updateUser: (userId: string, request: AdminUserUpdate) =>
    j<AdminUser>(`${userPath(userId)}/update`, post(request)),
  resetUser: (userId: string) =>
    j<{ setupToken: string }>(`${userPath(userId)}/reset`, post({})),
  revokeAllSessions: (userId: string) =>
    j<null>(`${userPath(userId)}/revoke-sessions`, post({})),
  sessions: (userId: string) =>
    j<AdminAuthSession[]>(`${userPath(userId)}/sessions`),
  revokeSession: (userId: string, sessionId: string) =>
    remove(`${userPath(userId)}/sessions/${encodeURIComponent(sessionId)}`),
  setUserPlaylists: (userId: string, playlistIds: number[]) =>
    j<null>(`${userPath(userId)}/playlists`, post({ playlistIds })),
  progress: (userId: string) =>
    j<AdminResumePoint[]>(`${userPath(userId)}/progress`),
  deleteProgress: (userId: string, contentId: string) =>
    remove(`${userPath(userId)}/progress/${encodeURIComponent(contentId)}`),
  deleteUser: (userId: string) => remove(userPath(userId)),

  playlists: () => j<AdminPlaylist[]>('/playlists'),
  playlistTemplate: () => j<{ playlistIds: number[] }>('/admin/playlist-template'),
  savePlaylistTemplate: (playlistIds: number[]) =>
    j<null>('/admin/playlist-template', post({ playlistIds })),

  pendingOidc: () => j<PendingOidcIdentity[]>('/admin/oidc/pending'),
  approveOidc: (issuer: string, subject: string, userId: string | null) =>
    j<AdminUser>('/admin/oidc/approve', post({ issuer, subject, userId })),

  downloads: () => j<AdminDownload[]>('/admin/downloads'),
  cancelDownloadBlob: (blobId: string) =>
    j<{ affectedUserIds: string[] }>(
      `/admin/downloads/blobs/${encodeURIComponent(blobId)}`,
      { method: 'DELETE' },
    ),
};
