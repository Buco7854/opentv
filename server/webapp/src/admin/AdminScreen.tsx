import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { Icon, IconName } from '../components/Icons';
import { ScreenHeader } from '../components/Primitives';
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
  const [section, setSection] = useState<Section>('users');
  const users = useAdminSection(adminApi.users);
  const playlists = useAdminSection(adminApi.playlists);
  const template = useAdminSection(adminApi.playlistTemplate, section === 'template');
  const pending = useAdminSection(adminApi.pendingOidc, section === 'oidc');
  const downloads = useAdminSection(adminApi.downloads, section === 'downloads');
  const [createOpen, setCreateOpen] = useState(false);
  const [openUserId, setOpenUserId] = useState<string | null>(null);
  const userOpener = useRef<HTMLElement | null>(null);
  useEffect(() => { if (openUserId === null) userOpener.current?.focus({ preventScroll: true }); }, [openUserId]);

  const replaceUser = (next: AdminUser) =>
    users.set((current) => current?.map((user) => user.id === next.id ? next : user) ?? current);

  const openUser = users.data?.find((user) => user.id === openUserId) ?? null;
  const selectSection = (next: Section) => { setOpenUserId(null); setSection(next); };
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
        <nav className="admin-nav" aria-label={c('title')}>
          {SECTIONS.map(({ id, icon, label }) => (
            <button key={id} className={`admin-nav-item${section === id ? ' selected' : ''}`}
                    aria-current={section === id ? 'page' : undefined}
                    onClick={() => selectSection(id)}>
              <Icon name={icon} />
              <span>{c(label)}</span>
              {id === 'oidc' && (pending.data?.length ?? 0) > 0 && <span className="pill">{pending.data!.length}</span>}
            </button>
          ))}
        </nav>
        <label className="admin-mobile-nav">
          <span>{c('administrationSection')}</span>
          <select aria-label={c('administrationSection')} value={section} onChange={(event) => selectSection(event.target.value as Section)}>
            {SECTIONS.map(({ id, label }) => <option key={id} value={id}>{c(label)}</option>)}
          </select>
        </label>
        <div className="admin-main">
          {openUser && (
            <UserDetail
              key={openUser.id}
              user={openUser}
              playlists={playlists}
              localAccountProvisioning={localAccountProvisioning}
              onBack={() => setOpenUserId(null)}
              onChanged={replaceUser}
              onDeleted={() => {
                users.set((current) => current?.filter((user) => user.id !== openUser.id) ?? current);
                setOpenUserId(null);
              }}
            />
          )}
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
            {section === 'users' && (
            <>
              {!openUser && !localAccountProvisioning && (
                <p className="admin-hint admin-provisioning-note">
                  {c('localProvisioningOff')}
                </p>
              )}
              <UsersPanel users={users} playlists={playlists.data ?? []}
                          onOpen={(user) => { userOpener.current = document.activeElement as HTMLElement; setOpenUserId(user.id); }} />
            </>
          )}
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
                users.set((current) => current?.some((item) => item.id === user.id)
                  ? current.map((item) => item.id === user.id ? user : item)
                  : [...(current ?? []), user]);
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
          onCreated={(user) => users.set((current) => current ? [...current, user] : [user])}
        />
      )}
    </div>
  );
}
