import { useEffect, useMemo, useState } from 'react';
import { asyncFallback, EmptyState } from '../components/Common';
import { Icon } from '../components/Icons';
import { ConfirmDialog, IconBtn, SelectField } from '../components/Primitives';
import { reportSuccess } from '../errors';
import {
  AdminDownload, AdminPlaylist, AdminUser, adminApi, PendingOidcIdentity,
} from './api';
import { adminText as c } from './copy';
import { bytes, dateTime, downloadStatusLabel, initials, reportAdminError } from './format';
import { AdminSection, PlaylistPicker } from './AdminShared';
import { WatchProgressBar } from '../components/WatchProgress';

export function TemplatePanel({ playlists, template }: {
  playlists: AdminSection<AdminPlaylist[]>;
  template: AdminSection<{ playlistIds: number[] }>;
}) {
  const [draft, setDraft] = useState<number[]>([]);
  const [saving, setSaving] = useState(false);
  const loaded = template.data;
  useEffect(() => { if (loaded) setDraft(loaded.playlistIds); }, [loaded]);

  const fallback = asyncFallback(playlists) ?? asyncFallback(template);
  if (fallback) return fallback;

  const save = async () => {
    setSaving(true);
    try {
      await adminApi.savePlaylistTemplate(draft);
      template.set({ playlistIds: draft });
      reportSuccess(c('templateSaved'));
    } catch (error) {
      reportAdminError(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="admin-card">
      <div className="title">{c('templateTitle')}</div>
      <p className="admin-hint">{c('templateHelp')}</p>
      <PlaylistPicker playlists={playlists.data ?? []} selected={draft} onChange={setDraft} />
      <div className="actions end">
        <button className="btn" disabled={saving} onClick={() => void save()}>{c('saveTemplate')}</button>
      </div>
    </section>
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
      reportSuccess(target ? c('oidcLinked') : c('oidcProvisioned'));
    } catch (error) {
      reportAdminError(error);
      setSaving(false);
    }
  };
  const name = identity.displayName || identity.username || c('unnamedIdentity');
  const options: [string, string][] = [
    ['', c('createNewUser')],
    ...users.map((user): [string, string] => [
      user.id,
      c('linkTo', { name: user.displayName || user.username, username: user.username }),
    ]),
  ];
  return (
    <section className="admin-card">
      <div className="admin-row">
        <span className="admin-avatar">{initials({ displayName: name, username: identity.subject })}</span>
        <div className="body">
          <div className="name">{name}</div>
          <div className="sub">{identity.username ? `@${identity.username}` : identity.subject}</div>
          <div className="sub admin-mono">{identity.issuer}</div>
          <div className="sub">
            {`${identity.groups.length > 0 ? c('groups', { groups: identity.groups.join(', ') }) : c('noGroups')} · ${c('seenAt', { date: dateTime(identity.createdAtMs) })}`}
          </div>
        </div>
        {identity.adminMapped && <span className="pill strong">{c('adminGroup')}</span>}
      </div>
      <SelectField label={c('approvalTarget')} options={options} selected={target} onSelect={setTarget} />
      <div className="actions end">
        <button className="btn" disabled={saving} onClick={() => void approve()}>
          {saving ? c('approving') : target ? c('linkIdentity') : c('createApprove')}
        </button>
      </div>
    </section>
  );
}

export function OidcPanel({ pending, users, onApproved }: {
  pending: AdminSection<PendingOidcIdentity[]>;
  users: AdminUser[];
  onApproved: (identity: PendingOidcIdentity, user: AdminUser) => void;
}) {
  const fallback = asyncFallback(pending);
  if (fallback) return fallback;
  const identities = pending.data ?? [];
  if (identities.length === 0) {
    return <EmptyState title={c('noPendingSso')} subtitle={c('noPendingSsoBody')} />;
  }
  return (
    <div className="flex flex-col gap-2">
      <p className="admin-hint">{c('oidcHelp')}</p>
      {identities.map((identity) => (
        <OidcCard
          key={`${identity.issuer} ${identity.subject}`}
          identity={identity}
          users={users}
          onApproved={(user) => onApproved(identity, user)}
        />
      ))}
    </div>
  );
}

interface BlobGroup {
  blobId: string;
  title: string;
  contentId: string;
  status: string;
  totalBytes: number;
  downloadedBytes: number;
  entries: AdminDownload[];
}

export function DownloadsPanel({ downloads }: { downloads: AdminSection<AdminDownload[]> }) {
  const items = downloads.data;
  const groups = useMemo(() => {
    const map = new Map<string, BlobGroup>();
    for (const item of items ?? []) {
      const current = map.get(item.blobId);
      if (current) current.entries.push(item);
      else map.set(item.blobId, {
        blobId: item.blobId,
        title: item.title,
        contentId: item.contentId,
        status: item.status,
        totalBytes: item.totalBytes,
        downloadedBytes: item.downloadedBytes,
        entries: [item],
      });
    }
    return [...map.values()];
  }, [items]);
  const [confirm, setConfirm] = useState<BlobGroup | null>(null);

  const fallback = asyncFallback(downloads);
  if (fallback) return fallback;
  if (groups.length === 0) return <EmptyState title={c('noDownloads')} subtitle={c('noDownloadsBody')} />;

  return (
    <>
      <section className="admin-card">
        <p className="admin-hint">{c('downloadsHelp')}</p>
        <div className="admin-rows">
          {groups.map((group) => {
            const owners = new Set(group.entries.map((entry) => entry.userId));
            const active = group.entries.filter((entry) => entry.active).length;
            const suspended = group.entries.filter((entry) => entry.suspended).length;
            const fraction = group.totalBytes > 0
              ? Math.min(1, group.downloadedBytes / group.totalBytes) : 0;
            return (
              <div className="admin-row" key={group.blobId}>
                <span className="logo-box"><Icon name="download" /></span>
                <div className="body">
                  <div className="name">{group.title}</div>
                  <div className="sub">
                    {`${bytes(group.downloadedBytes)} / ${bytes(group.totalBytes)} · ${c(active > 1 ? 'activeRequests' : 'activeRequest', { count: active })} · ${c(owners.size > 1 ? 'affectedUsers' : 'affectedUser', { count: owners.size })}`}
                  </div>
                  <div className="sub">
                    {[
                      downloadStatusLabel(group.status),
                      suspended > 0
                        && c(suspended > 1 ? 'suspendedRequests' : 'suspendedRequest', { count: suspended }),
                    ].filter(Boolean).join(' · ')}
                  </div>
                  <WatchProgressBar fraction={fraction} />
                </div>
                <IconBtn name="del" className="danger" label={c('cancelBlob')}
                         onClick={() => setConfirm(group)} />
              </div>
            );
          })}
        </div>
      </section>
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
                reportSuccess(c('downloadCancelled', { count: result.affectedUserIds.length }));
                downloads.reload();
              })
              .catch(reportAdminError);
          }}
        />
      )}
    </>
  );
}
