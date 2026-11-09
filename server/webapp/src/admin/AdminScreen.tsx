import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { Icon, IconName } from '../components/Icons';
import { ScreenHeader } from '../components/Primitives';
import { WorkspaceNavigation } from '../components/WorkspaceNavigation';
import { AdminUser, adminApi } from './api';
import type { AdminTextKey } from './copy';
import { adminText as c } from './copy';
import { useAdminSection } from './AdminShared';
import { DownloadsPanel, OidcPanel, TemplatePanel } from './AdminPanels';
import { canProvisionLocalAccounts } from './localAccounts';
import { CreateUserDialog, UserDetail, UsersPanel } from './UserManagement';
import './admin.css';

type Section = 'users' | 'template' | 'oidc' | 'downloads';

const SECTIONS: { id: Section; icon: IconName; label: AdminTextKey }[] = [
  { id: 'users', icon: 'users', label: 'users' },
  { id: 'template', icon: 'playlist', label: 'defaultPlaylists' },
  { id: 'oidc', icon: 'link', label: 'pendingSso' },
  { id: 'downloads', icon: 'download', label: 'sharedDownloads' },
];

export function AdminScreen() {
  const { capabilities } = useAuth();
  const localAccountProvisioning = canProvisionLocalAccounts(capabilities);
  const [search, setSearch] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const requestedSection = search.get('section');
  const section = SECTIONS.find(({ id }) => id === requestedSection)?.id ?? 'users';
  const users = useAdminSection(adminApi.users);
  const playlists = useAdminSection(adminApi.playlists);
  const template = useAdminSection(adminApi.playlistTemplate, section === 'template');
  const pending = useAdminSection(adminApi.pendingOidc, section === 'oidc');
  const downloads = useAdminSection(adminApi.downloads, section === 'downloads');
  const [createOpen, setCreateOpen] = useState(false);
  const openUserId = section === 'users' ? search.get('user') : null;
  const setOpenUserId = (id: string | null, replace = false) => {
    const next = new URLSearchParams(search);
    next.set('section', 'users');
    if (id) next.set('user', id); else next.delete('user');
    setSearch(next, { replace, state: id ? { adminUserFromList: true } : null });
  };
  const userOpener = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (openUserId !== null || section !== 'users' || !userOpener.current) return;
    const frame = requestAnimationFrame(() => userOpener.current?.focus({ preventScroll: true }));
    return () => cancelAnimationFrame(frame);
  }, [openUserId, section]);

  const replaceUser = (next: AdminUser) =>
    users.set((current) => current?.map((user) => user.id === next.id ? next : user) ?? current);
  const upsertUser = (next: AdminUser) => {
    // An incomplete list cannot be patched into a complete one. Supersede its pending
    // read with a fresh list so early account creation also retains the existing users.
    if (users.data === null) { users.reload(); return; }
    users.set((current) => current?.some((user) => user.id === next.id)
      ? current.map((user) => user.id === next.id ? next : user)
      : [...(current ?? []), next]);
  };

  const openUser = users.data?.find((user) => user.id === openUserId) ?? null;
  const activeState = { users, template, oidc: pending, downloads }[section];
  const description = {
    users: 'usersHelp', template: 'templateSummary', oidc: 'oidcHelp', downloads: 'downloadsHelp',
  } as const;

  return (
    <div className="admin-workspace">
      <ScreenHeader
        compact
        title={c('title')}
        subtitle={<span className="subtitle">{c('subtitle')}</span>}
      />
      <div className="admin-layout">
        <WorkspaceNavigation label={c('title')} active={section} className="admin-navigation"
          destinations={SECTIONS.map(({ id, icon, label }) => ({ id, icon, label: c(label), to: id === 'users' ? '/admin' : `/admin?section=${id}`, count: id === 'oidc' ? pending.data?.length : undefined }))} />
        <div className="admin-main">
          {openUser && (
            <UserDetail
              key={openUser.id}
              user={openUser}
              playlists={playlists}
              localAccountProvisioning={localAccountProvisioning}
              onBack={() => { if (location.state?.adminUserFromList) navigate(-1); else setOpenUserId(null, true); }}
              onChanged={replaceUser}
              onDeleted={() => {
                users.set((current) => current?.filter((user) => user.id !== openUser.id) ?? current);
                setOpenUserId(null, true);
              }}
            />
          )}
          {openUserId !== null && users.data !== null && !openUser && <div className="resource-feedback" role="status">
            <p>{c('errorNotFound')}</p><button className="btn text" onClick={() => setOpenUserId(null, true)}>{c('backToUsers')}</button>
          </div>}
          <div hidden={openUser !== null}>
            <header className="admin-section-heading">
              <div>
                <h2>{c(SECTIONS.find(({ id }) => id === section)!.label)}</h2>
                <p className="admin-hint">{c(description[section])}</p>
              </div>
              <div className="admin-heading-actions">
                <button className="btn text" onClick={activeState.reload}><Icon name="refresh" /><span>{c('refresh')}</span></button>
                {section === 'users' && localAccountProvisioning && (
                  <button className="btn" onClick={() => setCreateOpen(true)}><Icon name="add" />{c('createUser')}</button>
                )}
              </div>
            </header>
            <div hidden={section !== 'users'}>
              {!openUser && !localAccountProvisioning && (
                <p className="admin-hint admin-provisioning-note">
                  {c('localProvisioningOff')}
                </p>
              )}
              <UsersPanel users={users} playlists={playlists.data ?? []}
                          onOpen={(user) => { userOpener.current = document.activeElement as HTMLElement; setOpenUserId(user.id); }} />
            </div>
          {section === 'template' && (
            <TemplatePanel playlists={playlists} template={template} />
          )}
            {section === 'oidc' && (
            <OidcPanel
              pending={pending}
              users={users}
              onApproved={(identity, user) => {
                pending.set((current) => current?.filter((item) =>
                  item.issuer !== identity.issuer || item.subject !== identity.subject) ?? current);
                upsertUser(user);
              }}
            />
          )}
          {section === 'downloads' && (
            <DownloadsPanel downloads={downloads} users={users.data ?? []} />
          )}
          </div>
        </div>
      </div>

      {localAccountProvisioning && createOpen && (
        <CreateUserDialog
          onDismiss={() => setCreateOpen(false)}
          onCreated={upsertUser}
        />
      )}
    </div>
  );
}
