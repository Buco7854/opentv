import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { EmptyState } from '../components/Common';
import { Icon } from '../components/Icons';
import {
  ConfirmDialog, Dialog, IconBtn, SearchField, Segmented, Select, SelectField, TextField,
} from '../components/Primitives';
import { reportSuccess } from '../errors';
import { useAuth } from '../auth/AuthProvider';
import { AdminPlaylist, AdminUser, adminApi } from './api';
import { adminText as c } from './copy';
import {
  authMethodLabel, dateTime, initials, reportAdminError, roleLabel,
  statusLabel, statusTone,
} from './format';
import { AdminSection, AdminSectionFeedback, OneTimeTokenDialog, PlaylistPicker, useAdminSection } from './AdminShared';
import { ProgressList, SessionList } from '../components/UserActivity';

type StatusFilter = 'ALL' | AdminUser['status'];
type Section = 'profile' | 'access' | 'sessions' | 'progress';

const displayed = (user: AdminUser) => user.displayName || user.username;

const grantsApply = (user: AdminUser) => user.effectiveRole !== 'ADMIN';

function UserTags({ user, self, statusOnly = false }: { user: AdminUser; self: boolean; statusOnly?: boolean }) {
  return (
    <div className="admin-user-tags">
      {self && !statusOnly && <span className="pill strong">{c('you')}</span>}
      <span className={`pill${statusTone(user.status)}`}>{statusLabel(user.status)}</span>
      {!statusOnly && user.effectiveRole === 'ADMIN' && <span className="pill strong">{c('adminShort')}</span>}
    </div>
  );
}

export function UsersPanel({ users, playlists, onOpen }: {
  users: AdminSection<AdminUser[]>;
  playlists: AdminPlaylist[];
  onOpen: (user: AdminUser) => void;
}) {
  const { user: signedIn } = useAuth();
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<StatusFilter>('ALL');

  if (users.data === null) return <AdminSectionFeedback state={users} />;
  const all = users.data ?? [];
  if (all.length === 0) return <><AdminSectionFeedback state={users} /><EmptyState title={c('noUsers')} subtitle={c('noUsersBody')} /></>;

  const names = new Map(playlists.map((playlist) => [playlist.id, playlist.name]));
  const access = (user: AdminUser) => !grantsApply(user)
    ? c('allPlaylists')
    : user.playlistIds.length === 0
      ? c('noAssignedPlaylists')
      : user.playlistIds.every((id) => names.has(id))
        ? user.playlistIds.map((id) => names.get(id)).join(' · ')
        : c('playlistAccessCount', { count: user.playlistIds.length });
  const needle = query.trim().toLowerCase();
  const visible = all.filter((user) =>
    (status === 'ALL' || user.status === status)
    && (needle.length === 0
      || user.username.toLowerCase().includes(needle)
      || user.displayName.toLowerCase().includes(needle)));

  return (
    <>
      <AdminSectionFeedback state={users} />
      <div className="admin-toolbar">
        <SearchField placeholder={c('searchUsers')} value={query} onChange={setQuery} />
        <Select<StatusFilter>
          ariaLabel={c('filterStatus')}
          options={[
            ['ALL', c('filterAll')],
            ['ACTIVE', c('active')],
            ['INVITED', c('invited')],
            ['DISABLED', c('disabled')],
          ]}
          selected={status}
          onSelect={setStatus}
        />
      </div>
      <p className="admin-results" role="status">{c('usersShown', { count: visible.length, total: all.length })}</p>
      {visible.length === 0
        ? <EmptyState title={c('noMatches')} subtitle={c('noMatchesBody')} />
        : (
          <div className="admin-user-list">
            <div className="admin-user-columns" aria-hidden="true">
              <span>{c('accountSection')}</span><span>{c('accessSection')}</span><span>{c('status')}</span>
            </div>
            {visible.map((user) => (
              <button className="admin-user-row" key={user.id} onClick={() => onOpen(user)}>
                <span className="admin-user-identity">
                  <span className="admin-avatar" aria-hidden="true">{initials(user)}</span>
                  <span className="body">
                    <span className="name">{displayed(user)}</span>
                    <span className="sub admin-identity-meta">
                      <span>{`@${user.username}`}</span>
                      {user.id === signedIn?.id && <span>{c('you')}</span>}
                      {user.effectiveRole === 'ADMIN' && <span>{c('adminShort')}</span>}
                    </span>
                  </span>
                </span>
                <span className="admin-user-access">
                  <span>{access(user)}</span>
                  <span className="sub">{c('lastLogin', { date: dateTime(user.lastLoginAtMs) })}</span>
                </span>
                <UserTags user={user} self={user.id === signedIn?.id} statusOnly />
                <Icon name="chevron" className="sm admin-row-chevron" />
              </button>
            ))}
          </div>
        )}
    </>
  );
}

export function CreateUserDialog({ onDismiss, onCreated }: {
  onDismiss: () => void;
  onCreated: (user: AdminUser) => void;
}) {
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState<'USER' | 'ADMIN'>('USER');
  const [credentials, setCredentials] = useState<'password' | 'link'>('password');
  const [password, setPassword] = useState('');
  const [saving, setSaving] = useState(false);
  const [activationToken, setActivationToken] = useState<string | null>(null);
  const formId = useId();

  const withPassword = credentials === 'password';
  // The server enforces this too; checking here keeps the button honest.
  const passwordUsable = Array.from(password).length >= 12;

  const create = async () => {
    setSaving(true);
    try {
      const result = await adminApi.createUser({
        username,
        displayName,
        role,
        ...(withPassword ? { password } : {}),
      });
      onCreated(result.user);
      if (result.activationToken) setActivationToken(result.activationToken);
      else {
        // Nothing to hand over: the account already works.
        reportSuccess(c('userCreatedActive'));
        onDismiss();
      }
    } catch (error) {
      reportAdminError(error);
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
        path="/activate"
        onDismiss={onDismiss}
      />
    );
  }
  return (
    <Dialog
      className="admin-create-dialog"
      title={c('createLocalUser')}
      onDismiss={onDismiss}
      dismissible={!saving}
      buttons={
        <>
          <button className="btn text" disabled={saving} onClick={onDismiss}>{c('cancel')}</button>
          <button
            className="btn"
            type="submit"
            form={formId}
            disabled={saving || username.trim().length === 0 || (withPassword && !passwordUsable)}
          >
            {saving ? c('creating') : c('create')}
          </button>
        </>
      }
    >
      <form id={formId} className="admin-form" onSubmit={(event) => { event.preventDefault(); if (!saving && username.trim() && (!withPassword || passwordUsable)) void create(); }}>
        <TextField label={c('username')} value={username} onChange={setUsername} autoFocus autoComplete="off" />
        <TextField label={c('displayName')} value={displayName} onChange={setDisplayName} autoComplete="off" />
        <SelectField
          label={c('role')}
          options={[['USER', c('user')], ['ADMIN', c('administrator')]]}
          selected={role}
          onSelect={setRole}
        />
        <fieldset className="admin-credential-options">
          <legend>{c('credentials')}</legend>
          {(['password', 'link'] as const).map((mode) => (
            <label key={mode}>
              <input type="radio" name={`credentials-${formId}`} checked={credentials === mode} onChange={() => setCredentials(mode)} />
              <span>{c(mode === 'password' ? 'setPassword' : 'sendActivation')}</span>
            </label>
          ))}
        </fieldset>
        {withPassword ? (
          <>
            <TextField label={c('password')} value={password} onChange={setPassword}
                       type="password" autoComplete="new-password" />
            <p className="admin-hint">{c('passwordHint')}</p>
          </>
        ) : (
          <p className="admin-hint">{c('invitedHelp')}</p>
        )}
        <p className="admin-hint">{c('credentialsHelp')}</p>
      </form>
    </Dialog>
  );
}

function ProfileSection({
  user, self, busy, localAccountProvisioning, onChanged, onReset, onDelete,
}: {
  user: AdminUser;
  self: boolean;
  busy: boolean;
  localAccountProvisioning: boolean;
  onChanged: (user: AdminUser) => void;
  onReset: () => void;
  onDelete: () => void;
}) {
  const [username, setUsername] = useState(user.username);
  const [displayName, setDisplayName] = useState(user.displayName);
  // An identity-provider mapping may elevate a manually managed USER to ADMIN. The account
  // list shows the effective role, so opening that account must not make the selector appear
  // to demote it. Keep an externally managed role read-only: sending ADMIN merely because the
  // form was saved would silently turn that temporary mapping into a permanent manual role.
  const [role, setRole] = useState(user.effectiveRole);
  const [status, setStatus] = useState(user.status);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setUsername(user.username);
    setDisplayName(user.displayName);
    setRole(user.effectiveRole);
    setStatus(user.status);
  }, [user.id, user.username, user.displayName, user.effectiveRole, user.status]);

  const save = async () => {
    setSaving(true);
    try {
      onChanged(await adminApi.updateUser(user.id, {
        username,
        displayName,
        status,
        ...(role === user.effectiveRole ? {} : { role }),
      }));
      reportSuccess(c('userSaved'));
    } catch (error) {
      reportAdminError(error);
    } finally {
      setSaving(false);
    }
  };

  // An administrator cannot demote, disable or delete their own account - the server
  // refuses all three. Offering them and then explaining the refusal is worse than not
  // offering them: another administrator remains the way out.
  const externallyManagedRole = user.manualRole !== user.effectiveRole;
  const ownAdminAccount = self && user.effectiveRole === 'ADMIN';
  const roleOptions: [AdminUser['manualRole'], string][] = ownAdminAccount || externallyManagedRole
    ? [[user.effectiveRole, roleLabel(user.effectiveRole)]]
    : [['USER', c('user')], ['ADMIN', c('administrator')]];
  const statusOptions = user.settableStatuses.filter((value) => !(self && value === 'DISABLED'));

  return (
    <>
      <form className="admin-card" onSubmit={(event) => { event.preventDefault(); if (!saving && username.trim()) void save(); }}>
        <h3 className="title">{c('accountSection')}</h3>
        <div className="admin-form admin-profile-form">
          <TextField label={c('username')} value={username} onChange={setUsername} />
          <TextField label={c('displayName')} value={displayName} onChange={setDisplayName} />
          <SelectField
            label={c('role')}
            options={roleOptions}
            selected={role}
            onSelect={setRole}
          />
          {/* The server decides which statuses an administrator owns - Invited is set by
              creating or resetting an account and cleared by activation, and the server
              refuses both it and the legacy Pending. A user currently sitting in one of
              those still shows it, so the row is never misrepresented. */}
          <SelectField
            label={c('status')}
            options={[
              ...(statusOptions.includes(status)
                ? []
                : [[status, statusLabel(status)] as [AdminUser['status'], string]]),
              ...statusOptions.map(
                (value): [AdminUser['status'], string] => [value, statusLabel(value)],
              ),
            ]}
            selected={status}
            onSelect={setStatus}
          />
          <p className="admin-hint admin-form-wide">{c('statusHelp')}</p>
          {self && <p className="admin-hint admin-form-wide">{c('selfGuardHelp')}</p>}
          <p className="admin-hint admin-form-wide">{c('effectiveRoleHelp', { role: roleLabel(user.effectiveRole) })}</p>
        </div>
        <div className="actions end">
          <button className="btn" type="submit" disabled={saving || !username.trim()}>
            {c('saveChanges')}
          </button>
        </div>
      </form>
      <section className="admin-card admin-danger-zone">
        <h3 className="title">{c('dangerZone')}</h3>
        <p className="admin-hint">
          {c(localAccountProvisioning ? 'dangerZoneHelp' : 'localProvisioningOff')}
        </p>
        {(localAccountProvisioning || !self) && (
          <div className="actions">
            {localAccountProvisioning && (
              <button className="btn tonal" disabled={busy} onClick={onReset}>
                {c('resetCredentials')}
              </button>
            )}
            {!self && (
              <button className="btn danger-text" disabled={busy} onClick={onDelete}>
                {c('deleteUser')}
              </button>
            )}
          </div>
        )}
      </section>
    </>
  );
}

function AccessSection({ user, playlists, onChanged }: {
  user: AdminUser;
  playlists: AdminSection<AdminPlaylist[]>;
  onChanged: (user: AdminUser) => void;
}) {
  const [grants, setGrants] = useState(user.playlistIds);
  const [saving, setSaving] = useState(false);
  useEffect(() => { setGrants(user.playlistIds); }, [user.id, user.playlistIds]);

  const save = async () => {
    setSaving(true);
    try {
      await adminApi.setUserPlaylists(user.id, grants);
      onChanged({ ...user, playlistIds: grants });
      reportSuccess(c('accessSaved'));
    } catch (error) {
      reportAdminError(error);
    } finally {
      setSaving(false);
    }
  };

  const applies = grantsApply(user);
  return (
    <section className="admin-card">
      <h3 className="title">{c('accessSection')}</h3>
      <p className="admin-hint">{applies ? c('removeAccessHelp') : c('adminAccessHelp')}</p>
      <AdminSectionFeedback state={playlists} />
      {playlists.data !== null && <PlaylistPicker playlists={playlists.data} selected={grants} onChange={setGrants} disabled={!applies} />}
      <div className="actions end">
        <button className="btn" disabled={saving || !applies || playlists.data === null} onClick={() => void save()}>
          {c('saveAccess')}
        </button>
      </div>
    </section>
  );
}

function SessionsSection({ userId, currentSessionId }: {
  userId: string;
  currentSessionId: string | null;
}) {
  const sessions = useAdminSection(useCallback(() => adminApi.sessions(userId), [userId]));
  const [busy, setBusy] = useState(false);
  const [confirmAll, setConfirmAll] = useState(false);

  const revoke = async (sessionId: string) => {
    setBusy(true);
    try {
      await adminApi.revokeSession(userId, sessionId);
      sessions.set((current) => current?.filter((session) => session.id !== sessionId) ?? current);
    } catch (error) {
      reportAdminError(error);
    } finally {
      setBusy(false);
    }
  };

  const revokeAll = async () => {
    setBusy(true);
    try {
      await adminApi.revokeAllSessions(userId);
      sessions.set([]);
      reportSuccess(c('allSessionsRevoked'));
    } catch (error) {
      reportAdminError(error);
    } finally {
      setBusy(false);
    }
  };

  const rows = sessions.data ?? [];
  const revokesMine = rows.some((session) => session.id === currentSessionId);
  return (
    <section className="admin-card">
      <h3 className="title">{c('sessions')}</h3>
      <p className="admin-hint">{c('sessionsHelp')}</p>
      <AdminSectionFeedback state={sessions} />
      {rows.length === 0 && sessions.data !== null && (
        <EmptyState title={c('noActiveSessions')} subtitle={c('sessionsHelp')} />
      )}
      {rows.length > 0 && (
        <>
          <SessionList items={rows} currentSessionId={currentSessionId} titleLevel={4}
            action={(session) => <IconBtn name="logout" className={session.id === currentSessionId ? 'muted' : 'danger'}
              label={c('revoke')} disabled={busy} onClick={() => void revoke(session.id)} />} />
          <div className="actions end">
            <button className="btn danger-text" disabled={busy} onClick={() => setConfirmAll(true)}>
              {c('revokeAllSessions')}
            </button>
          </div>
        </>
      )}
      {confirmAll && (
        <ConfirmDialog
          title={c('revokeAllTitle')}
          message={revokesMine ? `${c('revokeAllBody')} ${c('revokeAllSelfBody')}` : c('revokeAllBody')}
          confirmLabel={c('revokeAllSessions')}
          onDismiss={() => setConfirmAll(false)}
          onConfirm={() => void revokeAll()}
        />
      )}
    </section>
  );
}

function ProgressSection({ userId }: { userId: string }) {
  const progress = useAdminSection(useCallback(() => adminApi.progress(userId), [userId]));
  const [busy, setBusy] = useState(false);

  const remove = async (contentId: string) => {
    setBusy(true);
    try {
      await adminApi.deleteProgress(userId, contentId);
      progress.set((current) => current?.filter((item) => item.contentId !== contentId) ?? current);
    } catch (error) {
      reportAdminError(error);
    } finally {
      setBusy(false);
    }
  };

  const items = progress.data ?? [];
  return (
    <section className="admin-card">
      <h3 className="title">{c('progress')}</h3>
      <p className="admin-hint">{c('progressHelp')}</p>
      <AdminSectionFeedback state={progress} />
      {items.length === 0 && progress.data !== null && (
        <EmptyState title={c('noResume')} subtitle={c('progressHelp')} />
      )}
      {items.length > 0 && (
        <ProgressList items={items} titleLevel={4} action={(item) =>
          <IconBtn name="del" className="danger" label={c('remove')}
            disabled={busy} onClick={() => void remove(item.contentId)} />} />
      )}
    </section>
  );
}

export function UserDetail({
  user, playlists, localAccountProvisioning, onBack, onChanged, onDeleted,
}: {
  user: AdminUser;
  playlists: AdminSection<AdminPlaylist[]>;
  localAccountProvisioning: boolean;
  onBack: () => void;
  onChanged: (user: AdminUser) => void;
  onDeleted: () => void;
}) {
  const { user: signedIn } = useAuth();
  const self = signedIn?.id === user.id;
  const [section, setSection] = useState<Section>('profile');
  const [confirm, setConfirm] = useState<'reset' | 'delete' | null>(null);
  const [busy, setBusy] = useState(false);
  const [resetToken, setResetToken] = useState<string | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus({ preventScroll: true }); }, [user.id]);

  const reset = async () => {
    setBusy(true);
    try {
      const { setupToken } = await adminApi.resetUser(user.id);
      setResetToken(setupToken);
      void adminApi.users()
        .then((items) => items.find((item) => item.id === user.id))
        .then((next) => { if (next) onChanged(next); })
        // The reset itself succeeded and its one-time link is on screen; only the row
        // refresh failed, so report it without throwing the link away.
        .catch(reportAdminError);
    } catch (error) {
      reportAdminError(error);
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await adminApi.deleteUser(user.id);
      onDeleted();
    } catch (error) {
      reportAdminError(error);
      setBusy(false);
    }
  };

  return (
    <>
      <div className="admin-detail">
        <button className="admin-back btn text" onClick={onBack}><Icon name="back" />{c('backToUsers')}</button>
        <div className="admin-hero">
          <span className="admin-avatar">{initials(user)}</span>
          <div className="body">
            <h2 className="name" ref={heading} tabIndex={-1}>{displayed(user)}</h2>
            <div className="sub">{`@${user.username}`}</div>
            <div className="sub">
              {user.authMethods.length > 0
                ? user.authMethods.map(authMethodLabel).join(' · ')
                : c('noAuth')}
            </div>
            <div className="sub">
              {`${c('lastLogin', { date: dateTime(user.lastLoginAtMs) })} · ${c('created', { date: dateTime(user.createdAtMs) })}`}
            </div>
          </div>
          <UserTags user={user} self={self} />
        </div>

        <Segmented<Section>
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
          <ProfileSection
            user={user}
            self={self}
            busy={busy}
            localAccountProvisioning={localAccountProvisioning}
            onChanged={onChanged}
            onReset={() => setConfirm('reset')}
            onDelete={() => setConfirm('delete')}
          />
        )}
        {section === 'access' && (
          <AccessSection user={user} playlists={playlists} onChanged={onChanged} />
        )}
        {section === 'sessions' && (
          <SessionsSection userId={user.id}
                           currentSessionId={self ? signedIn?.authSessionId ?? null : null} />
        )}
        {section === 'progress' && <ProgressSection userId={user.id} />}
      </div>

      {localAccountProvisioning && confirm === 'reset' && (
        <ConfirmDialog
          title={c('resetNamedTitle', { name: displayed(user) })}
          message={self ? `${c('resetBody')} ${c('resetSelfBody')}` : c('resetBody')}
          confirmLabel={c('resetCredentials')}
          onDismiss={() => setConfirm(null)}
          onConfirm={() => void reset()}
        />
      )}
      {confirm === 'delete' && (
        <ConfirmDialog
          title={c('deleteNamedTitle', { name: displayed(user) })}
          message={self ? `${c('deleteUserBody')} ${c('deleteSelfBody')}` : c('deleteUserBody')}
          confirmLabel={c('deleteUser')}
          onDismiss={() => setConfirm(null)}
          onConfirm={() => void remove()}
        />
      )}
      {resetToken && (
        <OneTimeTokenDialog
          title={c('credentialsReset')}
          explanation={c('resetOnce')}
          token={resetToken}
          path="/activate"
          onDismiss={() => setResetToken(null)}
        />
      )}
    </>
  );
}
