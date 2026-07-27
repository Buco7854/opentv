import { useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { Icon, IconName } from '../components/Icons';
import { IconBtn, ScreenHeader, Segmented } from '../components/Primitives';
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
  const template = useAdminSection(adminApi.playlistTemplate);
  const pending = useAdminSection(adminApi.pendingOidc);
  const downloads = useAdminSection(adminApi.downloads);
  const [createOpen, setCreateOpen] = useState(false);
  const [openUserId, setOpenUserId] = useState<string | null>(null);

  const replaceUser = (next: AdminUser) =>
    users.set((current) => current?.map((user) => user.id === next.id ? next : user) ?? current);

  const counts: Record<Section, number | null> = {
    users: users.data?.length ?? null,
    template: template.data?.playlistIds.length ?? null,
    oidc: pending.data?.length ?? null,
    downloads: downloads.data?.length ?? null,
  };

  const openUser = users.data?.find((user) => user.id === openUserId) ?? null;
  if (openUser) {
    return (
      <UserDetail
        user={openUser}
        playlists={playlists.data ?? []}
        localAccountProvisioning={localAccountProvisioning}
        onBack={() => setOpenUserId(null)}
        onChanged={replaceUser}
        onDeleted={() => {
          users.set((current) => current?.filter((user) => user.id !== openUser.id) ?? current);
          setOpenUserId(null);
        }}
      />
    );
  }

  return (
    <>
      <ScreenHeader
        title={c('title')}
        subtitle={<span className="subtitle">{c('subtitle')}</span>}
        actions={section === 'users' && localAccountProvisioning && (
          <IconBtn name="add" label={c('createUser')} onClick={() => setCreateOpen(true)} />
        )}
      />

      <div className="mx-3 mt-1 mb-2 md:hidden">
        <Segmented<Section>
          className="scroll"
          options={SECTIONS.map(({ id, label }): [Section, string] => [
            id,
            counts[id] ? `${c(label)} · ${counts[id]}` : c(label),
          ])}
          selected={section}
          onSelect={setSection}
        />
      </div>

      <div className="mx-auto flex max-w-[1040px] items-start gap-8 px-4">
        <nav className="sticky top-[92px] hidden w-[236px] flex-none flex-col gap-1 self-start pt-4 md:flex">
          {SECTIONS.map(({ id, icon, label }) => (
            <button key={id} className={`panel-row${section === id ? ' selected' : ''}`}
                    onClick={() => setSection(id)}>
              <Icon name={icon} />
              <div className="body"><div className="name">{c(label)}</div></div>
              {counts[id] ? <span className="pill">{counts[id]}</span> : null}
            </button>
          ))}
        </nav>

        <div className="min-w-0 flex-1 pt-4 pb-6">
          <div className="admin-label">{c(SECTIONS.find(({ id }) => id === section)!.label)}</div>

          {section === 'users' && (
            <>
              {!localAccountProvisioning && (
                <p className="admin-hint admin-provisioning-note">
                  {c('localProvisioningOff')}
                </p>
              )}
              <UsersPanel users={users} playlists={playlists.data ?? []}
                          onOpen={(user) => setOpenUserId(user.id)} />
            </>
          )}
          {section === 'template' && (
            <TemplatePanel playlists={playlists} template={template} />
          )}
          {section === 'oidc' && (
            <OidcPanel
              pending={pending}
              users={users.data ?? []}
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
            <DownloadsPanel downloads={downloads} />
          )}
        </div>
      </div>

      {localAccountProvisioning && createOpen && (
        <CreateUserDialog
          onDismiss={() => setCreateOpen(false)}
          onCreated={(user) => users.set((current) => current ? [...current, user] : [user])}
        />
      )}
    </>
  );
}
