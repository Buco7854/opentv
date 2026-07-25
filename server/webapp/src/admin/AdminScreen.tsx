import { useEffect, useMemo, useState } from 'react';
import {
  AdminAuthSession,
  AdminDownload,
  AdminPlaylist,
  AdminResumePoint,
  AdminUser,
  adminApi,
  PendingOidcIdentity,
} from './api';
import { adminLocale, adminText as c } from './copy';
import type { AdminTextKey } from './copy';
import { ConfirmDialog, Dialog, ScreenHeader, Segmented, SelectField, snackbar, Spinner, TextField } from '../components/Primitives';
import './admin.css';

type Tab = 'users' | 'template' | 'oidc' | 'downloads';

const dateTime = (value: number | null) =>
  value == null ? c('never') : new Intl.DateTimeFormat(adminLocale(), { dateStyle: 'medium', timeStyle: 'short' }).format(value);

const bytes = (value: number) => {
  if (value <= 0) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const unit = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / 1024 ** unit).toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
};

const errorMessage = (error: unknown) => error instanceof Error ? error.message : c('operationFailed');

const STATUS_TEXT: Record<AdminUser['status'], AdminTextKey> = {
  INVITED: 'invited',
  PENDING: 'pending',
  ACTIVE: 'active',
  DISABLED: 'disabled',
};
const AUTH_METHOD_TEXT: Record<string, AdminTextKey> = {
  PASSWORD: 'password',
  OIDC: 'oidc',
  RECOVERY: 'recovery',
  WEBAUTHN: 'webauthn',
};
const PLAYLIST_MODE_TEXT: Record<AdminPlaylist['mode'], AdminTextKey> = {
  xtream: 'modeXtream',
  url: 'modeUrl',
  file: 'modeFile',
};
const DOWNLOAD_STATUS_TEXT: Record<string, AdminTextKey> = {
  QUEUED: 'downloadQueued',
  RUNNING: 'downloadRunning',
  DONE: 'downloadDone',
  FAILED: 'downloadFailed',
  CANCELLED: 'downloadCancelledStatus',
  PAUSED: 'downloadPaused',
};

const roleLabel = (role: AdminUser['effectiveRole']) => c(role === 'ADMIN' ? 'administrator' : 'user');
const statusLabel = (status: AdminUser['status']) => c(STATUS_TEXT[status]);
const authMethodLabel = (method: string) => c(AUTH_METHOD_TEXT[method.toUpperCase()] ?? 'downloadUnknown');
const clientLabel = (kind: string) => kind.toUpperCase() === 'BROWSER' ? c('clientBrowser') : kind;
const playlistModeLabel = (mode: AdminPlaylist['mode']) => c(PLAYLIST_MODE_TEXT[mode]);
const downloadStatusLabel = (status: string) => c(DOWNLOAD_STATUS_TEXT[status.toUpperCase()] ?? 'downloadUnknown');

export function AdminScreen() {
  const [tab, setTab] = useState<Tab>('users');
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [playlists, setPlaylists] = useState<AdminPlaylist[] | null>(null);
  const [template, setTemplate] = useState<number[] | null>(null);
  const [pending, setPending] = useState<PendingOidcIdentity[] | null>(null);
  const [downloads, setDownloads] = useState<AdminDownload[] | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<AdminUser | null>(null);

  const load = async () => {
    const results = await Promise.allSettled([
      adminApi.users(),
      adminApi.playlists(),
      adminApi.playlistTemplate(),
      adminApi.pendingOidc(),
      adminApi.downloads(),
    ]);
    const [userResult, playlistResult, templateResult, pendingResult, downloadResult] = results;
    if (userResult.status === 'fulfilled') setUsers(userResult.value);
    if (playlistResult.status === 'fulfilled') setPlaylists(playlistResult.value);
    if (templateResult.status === 'fulfilled') setTemplate(templateResult.value.playlistIds);
    if (pendingResult.status === 'fulfilled') setPending(pendingResult.value);
    if (downloadResult.status === 'fulfilled') setDownloads(downloadResult.value);
    const rejected = results.find((result) => result.status === 'rejected');
    if (rejected?.status === 'rejected') snackbar(errorMessage(rejected.reason));
  };

  useEffect(() => { void load(); }, []);

  const replaceUser = (next: AdminUser) =>
    setUsers((current) => current?.map((user) => user.id === next.id ? next : user) ?? current);

  return (
    <>
      <ScreenHeader
        title={c('title')}
        subtitle={<span className="subtitle">{c('subtitle')}</span>}
        actions={tab === 'users' && (
          <button className="btn" onClick={() => setCreateOpen(true)}>{c('createUser')}</button>
        )}
      />
      <div className="admin-tabs">
        <Segmented<Tab>
          className="scroll"
          options={[
            ['users', `${c('users')}${users ? ` · ${users.length}` : ''}`],
            ['template', c('defaultPlaylists')],
            ['oidc', `${c('pendingSso')}${pending?.length ? ` · ${pending.length}` : ''}`],
            ['downloads', `${c('sharedDownloads')}${downloads?.length ? ` · ${downloads.length}` : ''}`],
          ]}
          selected={tab}
          onSelect={setTab}
        />
      </div>

      {tab === 'users' && (
        <UsersPanel users={users} playlists={playlists ?? []} onEdit={setEditing} />
      )}
      {tab === 'template' && (
        <TemplatePanel playlists={playlists} selected={template} onSaved={setTemplate} />
      )}
      {tab === 'oidc' && (
        <OidcPanel
          pending={pending}
          users={users ?? []}
          onApproved={(identity, user) => {
            setPending((current) => current?.filter((item) =>
              item.issuer !== identity.issuer || item.subject !== identity.subject) ?? current);
            setUsers((current) => current?.some((item) => item.id === user.id)
              ? current.map((item) => item.id === user.id ? user : item)
              : [...(current ?? []), user]);
          }}
        />
      )}
      {tab === 'downloads' && (
        <DownloadsPanel downloads={downloads} onChanged={() => { adminApi.downloads().then(setDownloads).catch(() => {}); }} />
      )}

      {createOpen && (
        <CreateUserDialog
          onDismiss={() => setCreateOpen(false)}
          onCreated={(user) => setUsers((current) => current ? [...current, user] : [user])}
        />
      )}
      {editing && (
        <UserDialog
          user={editing}
          playlists={playlists ?? []}
          onDismiss={() => setEditing(null)}
          onChanged={(user) => { replaceUser(user); setEditing(user); }}
          onDeleted={() => {
            setUsers((current) => current?.filter((user) => user.id !== editing.id) ?? current);
            setEditing(null);
          }}
        />
      )}
    </>
  );
}

function UsersPanel({ users, playlists, onEdit }: {
  users: AdminUser[] | null;
  playlists: AdminPlaylist[];
  onEdit: (user: AdminUser) => void;
}) {
  if (users === null) return <Spinner />;
  if (users.length === 0) return <AdminEmpty title={c('noUsers')} text={c('noUsersBody')} />;
  const playlistNames = new Map(playlists.map((playlist) => [playlist.id, playlist.name]));
  return (
    <div className="admin-list">
      {users.map((user) => (
        <button className="card admin-user-row" key={user.id} onClick={() => onEdit(user)}>
          <div className="admin-row-main">
            <strong>{user.displayName}</strong>
            <span>@{user.username}</span>
            <small>
              {user.playlistIds.length === 0
                ? c('noAssignedPlaylists')
                : user.playlistIds.map((id) => playlistNames.get(id) ?? c('playlistNumber', { id })).join(' · ')}
            </small>
          </div>
          <div className="admin-tags">
            <span className={`admin-tag ${user.status === 'ACTIVE' ? 'good' : user.status === 'DISABLED' ? 'bad' : ''}`}>
              {statusLabel(user.status)}
            </span>
            <span className="admin-tag">{roleLabel(user.effectiveRole)}</span>
          </div>
        </button>
      ))}
    </div>
  );
}

function CreateUserDialog({ onDismiss, onCreated }: {
  onDismiss: () => void;
  onCreated: (user: AdminUser) => void;
}) {
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState<'USER' | 'ADMIN'>('USER');
  const [saving, setSaving] = useState(false);
  const [activationToken, setActivationToken] = useState<string | null>(null);

  const create = async () => {
    setSaving(true);
    try {
      const result = await adminApi.createUser({ username, displayName, role });
      onCreated(result.user);
      setActivationToken(result.activationToken);
    } catch (error) {
      snackbar(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  if (activationToken) {
    return (
      <OneTimeTokenDialog
        title={c('userCreated')}
        explanation={c('activationOnce')}
        token={activationToken}
        path="/activate?token="
        onDismiss={onDismiss}
      />
    );
  }
  return (
    <Dialog
      title={c('createLocalUser')}
      onDismiss={onDismiss}
      buttons={
        <>
          <button className="btn text" onClick={onDismiss}>{c('cancel')}</button>
          <button className="btn" disabled={saving || username.trim().length === 0} onClick={() => void create()}>
            {saving ? c('creating') : c('create')}
          </button>
        </>
      }
    >
      <div className="admin-form">
        <TextField label={c('username')} value={username} onChange={setUsername} autoFocus autoComplete="off" />
        <TextField label={c('displayName')} value={displayName} onChange={setDisplayName} autoComplete="off" />
        <SelectField
          label={c('role')}
          options={[['USER', c('user')], ['ADMIN', c('administrator')]]}
          selected={role}
          onSelect={setRole}
        />
        <p className="admin-hint">{c('invitedHelp')}</p>
      </div>
    </Dialog>
  );
}

function UserDialog({ user, playlists, onDismiss, onChanged, onDeleted }: {
  user: AdminUser;
  playlists: AdminPlaylist[];
  onDismiss: () => void;
  onChanged: (user: AdminUser) => void;
  onDeleted: () => void;
}) {
  const [section, setSection] = useState<'profile' | 'access' | 'sessions' | 'progress'>('profile');
  const [username, setUsername] = useState(user.username);
  const [displayName, setDisplayName] = useState(user.displayName);
  const [role, setRole] = useState(user.manualRole);
  const [status, setStatus] = useState(user.status);
  const [grants, setGrants] = useState(user.playlistIds);
  const [sessions, setSessions] = useState<AdminAuthSession[] | null>(null);
  const [progress, setProgress] = useState<AdminResumePoint[] | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [resetToken, setResetToken] = useState<string | null>(null);

  // Administrative operations can change status, effective role, and grants
  // while the dialog remains open. Replace the form snapshot with the
  // authoritative response so a later save cannot restore stale values.
  useEffect(() => {
    setUsername(user.username);
    setDisplayName(user.displayName);
    setRole(user.manualRole);
    setStatus(user.status);
    setGrants(user.playlistIds);
  }, [
    user.id, user.username, user.displayName, user.manualRole,
    user.status, user.playlistIds,
  ]);

  useEffect(() => {
    if (section === 'sessions' && sessions === null) {
      adminApi.sessions(user.id)
        .then(setSessions)
        .catch((error) => { snackbar(errorMessage(error)); setSessions([]); });
    }
    if (section === 'progress' && progress === null) {
      adminApi.progress(user.id)
        .then(setProgress)
        .catch((error) => { snackbar(errorMessage(error)); setProgress([]); });
    }
  }, [section, sessions, progress, user.id]);

  const saveProfile = async () => {
    try {
      const next = await adminApi.updateUser(user.id, { username, displayName, role, status });
      onChanged(next);
      snackbar(c('userSaved'));
    } catch (error) {
      snackbar(errorMessage(error));
    }
  };

  const saveGrants = async () => {
    try {
      await adminApi.setUserPlaylists(user.id, grants);
      onChanged({ ...user, playlistIds: grants });
      snackbar(c('accessSaved'));
    } catch (error) {
      snackbar(errorMessage(error));
    }
  };

  const reset = async () => {
    try {
      const result = await adminApi.resetUser(user.id);
      const next = await adminApi.users().then((items) => items.find((item) => item.id === user.id));
      if (next) onChanged(next);
      setResetToken(result.setupToken);
    } catch (error) {
      snackbar(errorMessage(error));
    }
  };

  return (
    <>
      <Dialog title={user.displayName} onDismiss={onDismiss} className="admin-user-dialog">
        <div className="admin-user-summary">
          <span>@{user.username}</span>
          <span>{user.authMethods.length ? user.authMethods.map(authMethodLabel).join(' · ') : c('noAuth')}</span>
          <span>{c('lastLogin', { date: dateTime(user.lastLoginAtMs) })}</span>
        </div>
        <Segmented
          className="scroll"
          options={[
            ['profile', c('profile')],
            ['access', c('playlists')],
            ['sessions', c('sessions')],
            ['progress', c('progress')],
          ]}
          selected={section}
          onSelect={setSection}
        />
        {section === 'profile' && (
          <div className="admin-form">
            <TextField label={c('username')} value={username} onChange={setUsername} />
            <TextField label={c('displayName')} value={displayName} onChange={setDisplayName} />
            <SelectField
              label={c('manualRole')}
              options={[['USER', c('user')], ['ADMIN', c('administrator')]]}
              selected={role}
              onSelect={setRole}
            />
            <SelectField
              label={c('status')}
              options={[
                ['INVITED', c('invited')],
                ['PENDING', c('pending')],
                ['ACTIVE', c('active')],
                ['DISABLED', c('disabled')],
              ]}
              selected={status}
              onSelect={setStatus}
            />
            <p className="admin-hint">
              {c('effectiveRoleHelp', { role: roleLabel(user.effectiveRole) })}
            </p>
            <div className="admin-inline-actions">
              <button className="btn" onClick={() => void saveProfile()}>{c('saveChanges')}</button>
              <button className="btn tonal" onClick={() => void reset()}>{c('resetCredentials')}</button>
              <button className="btn danger-text" onClick={() => setConfirmDelete(true)}>{c('deleteUser')}</button>
            </div>
          </div>
        )}
        {section === 'access' && (
          <div className="admin-form">
            <p className="admin-hint">
              {c('removeAccessHelp')}
            </p>
            <PlaylistChecks playlists={playlists} selected={grants} onChange={setGrants} />
            <button className="btn" onClick={() => void saveGrants()}>{c('saveAccess')}</button>
          </div>
        )}
        {section === 'sessions' && (
          <SessionList
            sessions={sessions}
            onRevoke={async (sessionId) => {
              try {
                await adminApi.revokeSession(user.id, sessionId);
                setSessions((current) => current?.filter((session) => session.id !== sessionId) ?? current);
              } catch (error) { snackbar(errorMessage(error)); }
            }}
            onRevokeAll={async () => {
              try {
                await adminApi.revokeAllSessions(user.id);
                setSessions([]);
                snackbar(c('allSessionsRevoked'));
              } catch (error) { snackbar(errorMessage(error)); }
            }}
          />
        )}
        {section === 'progress' && (
          <ProgressList
            progress={progress}
            onDelete={async (contentId) => {
              try {
                await adminApi.deleteProgress(user.id, contentId);
                setProgress((current) => current?.filter((item) => item.contentId !== contentId) ?? current);
              } catch (error) { snackbar(errorMessage(error)); }
            }}
          />
        )}
      </Dialog>
      {confirmDelete && (
        <ConfirmDialog
          title={c('deleteUserTitle')}
          message={c('deleteUserBody')}
          confirmLabel={c('deleteUser')}
          onDismiss={() => setConfirmDelete(false)}
          onConfirm={() => {
            void adminApi.deleteUser(user.id)
              .then(onDeleted)
              .catch((error) => snackbar(errorMessage(error)));
          }}
        />
      )}
      {resetToken && (
        <OneTimeTokenDialog
          title={c('credentialsReset')}
          explanation={c('resetOnce')}
          token={resetToken}
          path="/activate?token="
          onDismiss={() => setResetToken(null)}
        />
      )}
    </>
  );
}

function SessionList({ sessions, onRevoke, onRevokeAll }: {
  sessions: AdminAuthSession[] | null;
  onRevoke: (id: string) => void;
  onRevokeAll: () => void;
}) {
  if (sessions === null) return <Spinner />;
  return (
    <div className="admin-form">
      <div className="admin-inline-actions">
        <button className="btn danger-text" disabled={sessions.length === 0} onClick={onRevokeAll}>{c('revokeAllSessions')}</button>
      </div>
      {sessions.length === 0 && <p className="admin-hint">{c('noActiveSessions')}</p>}
      {sessions.map((session) => (
        <div className="admin-data-row" key={session.id}>
          <div>
            <strong>{session.deviceName || clientLabel(session.clientKind)}</strong>
            <span>{c('lastSeen', {
              method: authMethodLabel(session.authMethod),
              date: dateTime(session.lastSeenAtMs),
            })}</span>
            <small>{c('expires', { date: dateTime(Math.min(session.idleExpiresAtMs, session.absoluteExpiresAtMs)) })}</small>
          </div>
          <button className="btn danger-text" onClick={() => onRevoke(session.id)}>{c('revoke')}</button>
        </div>
      ))}
    </div>
  );
}

function ProgressList({ progress, onDelete }: {
  progress: AdminResumePoint[] | null;
  onDelete: (contentId: string) => void;
}) {
  if (progress === null) return <Spinner />;
  if (progress.length === 0) return <p className="admin-hint">{c('noResume')}</p>;
  return (
    <div className="admin-form">
      {progress.map((item) => (
        <div className="admin-data-row" key={item.contentId}>
          <div>
            <strong className="admin-mono">{item.contentId}</strong>
            <span>{c('progressMinutes', {
              position: Math.round(item.positionMs / 60_000),
              duration: Math.round(item.durationMs / 60_000),
            })}</span>
            <small>{c('updated', { date: dateTime(item.updatedMs) })}</small>
          </div>
          <button className="btn danger-text" onClick={() => onDelete(item.contentId)}>{c('remove')}</button>
        </div>
      ))}
    </div>
  );
}

function TemplatePanel({ playlists, selected, onSaved }: {
  playlists: AdminPlaylist[] | null;
  selected: number[] | null;
  onSaved: (ids: number[]) => void;
}) {
  const [draft, setDraft] = useState<number[]>([]);
  useEffect(() => { if (selected) setDraft(selected); }, [selected]);
  if (playlists === null || selected === null) return <Spinner />;
  return (
    <div className="admin-section card">
      <h2>{c('templateTitle')}</h2>
      <p className="admin-hint">
        {c('templateHelp')}
      </p>
      <PlaylistChecks playlists={playlists} selected={draft} onChange={setDraft} />
      <button
        className="btn admin-save"
        onClick={() => {
          void adminApi.savePlaylistTemplate(draft)
            .then(() => { onSaved(draft); snackbar(c('templateSaved')); })
            .catch((error) => snackbar(errorMessage(error)));
        }}
      >
        {c('saveTemplate')}
      </button>
    </div>
  );
}

function PlaylistChecks({ playlists, selected, onChange }: {
  playlists: AdminPlaylist[];
  selected: number[];
  onChange: (ids: number[]) => void;
}) {
  if (playlists.length === 0) return <p className="admin-hint">{c('noPlaylists')}</p>;
  return (
    <div className="admin-checks">
      {playlists.map((playlist) => (
        <label key={playlist.id}>
          <input
            type="checkbox"
            checked={selected.includes(playlist.id)}
            onChange={(event) => onChange(event.target.checked
              ? [...selected, playlist.id]
              : selected.filter((id) => id !== playlist.id))}
          />
          <span>
            <strong>{playlist.name}</strong>
            <small>{c('playlistItems', { count: playlist.channelCount, mode: playlistModeLabel(playlist.mode) })}</small>
          </span>
        </label>
      ))}
    </div>
  );
}

function OidcPanel({ pending, users, onApproved }: {
  pending: PendingOidcIdentity[] | null;
  users: AdminUser[];
  onApproved: (identity: PendingOidcIdentity, user: AdminUser) => void;
}) {
  if (pending === null) return <Spinner />;
  if (pending.length === 0) {
    return <AdminEmpty title={c('noPendingSso')} text={c('noPendingSsoBody')} />;
  }
  return (
    <div className="admin-list">
      {pending.map((identity) => (
        <OidcCard
          key={`${identity.issuer}\u0000${identity.subject}`}
          identity={identity}
          users={users}
          onApproved={(user) => onApproved(identity, user)}
        />
      ))}
    </div>
  );
}

function OidcCard({ identity, users, onApproved }: {
  identity: PendingOidcIdentity;
  users: AdminUser[];
  onApproved: (user: AdminUser) => void;
}) {
  const [target, setTarget] = useState('');
  const [saving, setSaving] = useState(false);
  const approve = async () => {
    setSaving(true);
    try {
      onApproved(await adminApi.approveOidc(identity.issuer, identity.subject, target || null));
      snackbar(target ? c('oidcLinked') : c('oidcProvisioned'));
    } catch (error) {
      snackbar(errorMessage(error));
      setSaving(false);
    }
  };
  return (
    <div className="card admin-oidc-card">
      <div className="admin-row-main">
        <strong>{identity.displayName || identity.username || c('unnamedIdentity')}</strong>
        <span>{identity.username ? `@${identity.username}` : identity.subject}</span>
        <small className="admin-mono">{identity.issuer}</small>
        <small>{identity.groups.length ? c('groups', { groups: identity.groups.join(', ') }) : c('noGroups')}</small>
      </div>
      {identity.adminMapped && <span className="admin-tag">{c('adminGroup')}</span>}
      <label className="admin-native-select">
        <span>{c('approvalTarget')}</span>
        <select value={target} onChange={(event) => setTarget(event.target.value)}>
          <option value="">{c('createNewUser')}</option>
          {users.map((user) => (
            <option value={user.id} key={user.id}>
              {c('linkTo', { name: user.displayName, username: user.username })}
            </option>
          ))}
        </select>
      </label>
      <button className="btn" disabled={saving} onClick={() => void approve()}>
        {saving ? c('approving') : target ? c('linkIdentity') : c('createApprove')}
      </button>
    </div>
  );
}

interface BlobGroup {
  blobId: string;
  title: string;
  contentId: string;
  totalBytes: number;
  downloadedBytes: number;
  entries: AdminDownload[];
}

function DownloadsPanel({ downloads, onChanged }: {
  downloads: AdminDownload[] | null;
  onChanged: () => void;
}) {
  const groups = useMemo(() => {
    const map = new Map<string, BlobGroup>();
    for (const item of downloads ?? []) {
      const current = map.get(item.blobId);
      if (current) current.entries.push(item);
      else map.set(item.blobId, {
        blobId: item.blobId,
        title: item.title,
        contentId: item.contentId,
        totalBytes: item.totalBytes,
        downloadedBytes: item.downloadedBytes,
        entries: [item],
      });
    }
    return [...map.values()];
  }, [downloads]);
  const [confirm, setConfirm] = useState<BlobGroup | null>(null);
  if (downloads === null) return <Spinner />;
  if (groups.length === 0) return <AdminEmpty title={c('noDownloads')} text={c('noDownloadsBody')} />;
  return (
    <>
      <div className="admin-list">
        {groups.map((group) => {
          const users = [...new Set(group.entries.map((entry) => entry.userId))];
          const active = group.entries.filter((entry) => entry.active).length;
          return (
            <div className="card admin-download-card" key={group.blobId}>
              <div className="admin-row-main">
                <strong>{group.title}</strong>
                <span>
                  {bytes(group.downloadedBytes)} / {bytes(group.totalBytes)} · {c(
                    active === 1 ? 'activeRequest' : 'activeRequests',
                    { count: active },
                  )}
                </span>
                <small>
                  {c(users.length === 1 ? 'affectedUser' : 'affectedUsers', { count: users.length })}
                  {' · '}
                  {group.entries.map((entry) => downloadStatusLabel(entry.status)).join(', ')}
                </small>
                <small className="admin-mono">{group.contentId}</small>
              </div>
              <button className="btn danger-text" onClick={() => setConfirm(group)}>{c('cancelBlob')}</button>
            </div>
          );
        })}
      </div>
      {confirm && (
        <ConfirmDialog
          title={c('cancelDownloadTitle')}
          message={c('cancelDownloadBody', {
            count: confirm.entries.length,
            ids: [...new Set(confirm.entries.map((entry) => entry.userId))].join(', '),
          })}
          confirmLabel={c('cancelEveryone')}
          onDismiss={() => setConfirm(null)}
          onConfirm={() => {
            void adminApi.cancelDownloadBlob(confirm.blobId)
              .then((result) => {
                snackbar(c('downloadCancelled', { count: result.affectedUserIds.length }));
                onChanged();
              })
              .catch((error) => snackbar(errorMessage(error)));
          }}
        />
      )}
    </>
  );
}

function OneTimeTokenDialog({ title, explanation, token, path, onDismiss }: {
  title: string;
  explanation: string;
  token: string;
  path: string;
  onDismiss: () => void;
}) {
  const link = `${window.location.origin}${path}${encodeURIComponent(token)}`;
  const copy = async (value: string, label: string) => {
    try {
      await navigator.clipboard.writeText(value);
      snackbar(c('copied', { label }));
    } catch {
      snackbar(c('copyFailed'));
    }
  };
  return (
    <Dialog
      title={title}
      onDismiss={onDismiss}
      buttons={<button className="btn" onClick={onDismiss}>{c('savedToken')}</button>}
    >
      <div className="admin-form">
        <p className="admin-hint">{explanation}</p>
        <code className="admin-token">{link}</code>
        <div className="admin-inline-actions">
          <button className="btn tonal" onClick={() => void copy(link, c('activationLink'))}>{c('copyLink')}</button>
          <button className="btn tonal" onClick={() => void copy(token, c('token'))}>{c('copyToken')}</button>
        </div>
      </div>
    </Dialog>
  );
}

function AdminEmpty({ title, text }: { title: string; text: string }) {
  return (
    <div className="admin-empty">
      <h2>{title}</h2>
      <p>{text}</p>
    </div>
  );
}
