// Bottom dock: burger opens the playlists panel; center icons are the active
// playlist's apps. Burger shows a green dot while downloads run.

import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { api, DownloadStatus, Playlist } from '../api';
import { useDownloads } from '../hooks';
import { getLocale, t } from '../i18n';
import { prefs } from '../preferences';
import { Icon } from './Icons';
import { ConfirmDialog, IconBtn, Menu, MenuOption, snackbar } from './Primitives';
import { PlaylistDialog } from './PlaylistDialog';

function DockButton({ icon, label, active, disabled, dot, onClick }: {
  icon: string;
  label: string;
  active?: boolean;
  disabled?: boolean;
  dot?: 'accent' | 'good';
  onClick: () => void;
}) {
  return (
    <button
      className={`dock-btn${active ? ' active' : ''}`}
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
    >
      <Icon name={icon} />
      {dot && <span className={`dot${dot === 'good' ? ' good' : ''}`} />}
    </button>
  );
}

export function Dock() {
  const navigate = useNavigate();
  const { pathname, search } = useLocation();
  const [panelOpen, setPanelOpen] = useState(false);
  // The fullscreen player covers the dock; don't keep polling downloads underneath it.
  const downloads = useDownloads(!pathname.startsWith('/watch'));
  const downloading = downloads.list.some(
    (d) => d.status === DownloadStatus.QUEUED || d.status === DownloadStatus.RUNNING,
  );

  // Active playlist: URL, else last-used, else first on the server.
  const urlId = pathname.match(/^\/(?:browse|search|favorites|account|series|xseries)\/(\d+)/)?.[1];
  const [fallback, setFallback] = useState<number | null>(null);
  const active = urlId ? Number(urlId) : prefs.activePlaylist ?? fallback;
  useEffect(() => { if (urlId) prefs.activePlaylist = Number(urlId); }, [urlId]);
  useEffect(() => {
    if (urlId || prefs.activePlaylist != null) return;
    api.playlists().then((all) => setFallback(all[0]?.id ?? null)).catch(() => {});
  }, [urlId]);

  const tab = new URLSearchParams(search).get('t') ?? '0';
  const inBrowse = pathname.startsWith('/browse/');
  const goBrowse = (target: number) => navigate(`/browse/${active}?t=${target}`);

  return (
    <>
      <nav className="dock">
        <DockButton icon={panelOpen ? 'close' : 'more'} label={t('nav.playlists')}
                    dot={downloading ? 'good' : undefined}
                    active={panelOpen} onClick={() => setPanelOpen(!panelOpen)} />
        <div className="apps">
          <DockButton icon="liveTv" label={t('nav.live')} disabled={!active}
                      active={inBrowse && tab === '0'} onClick={() => goBrowse(0)} />
          <DockButton icon="movie" label={t('nav.movies')} disabled={!active}
                      active={inBrowse && tab === '1'} onClick={() => goBrowse(1)} />
          <DockButton icon="videoLib" label={t('nav.series')} disabled={!active}
                      active={inBrowse && tab === '2'} onClick={() => goBrowse(2)} />
          <DockButton icon="favoriteBorder" label={t('nav.favorites')} disabled={!active}
                      active={pathname.startsWith('/favorites/')}
                      onClick={() => navigate(`/favorites/${active}`)} />
          <DockButton icon="search" label={t('nav.search')} disabled={!active}
                      active={pathname.startsWith('/search/')}
                      onClick={() => navigate(`/search/${active}`)} />
        </div>
        {/* Symmetry spacer opposite the burger. */}
        <div className="w-12 flex-none" />
      </nav>

      {panelOpen && (
        <PlaylistsPanel activeId={active} downloading={downloading} onClose={() => setPanelOpen(false)} />
      )}
    </>
  );
}

function PlaylistsPanel({ activeId, downloading, onClose }: {
  activeId: number | null;
  downloading: boolean;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  const [playlists, setPlaylists] = useState<Playlist[] | null>(null);
  const [dialog, setDialog] = useState<'add' | Playlist | null>(null);
  const [pendingDelete, setPendingDelete] = useState<Playlist | null>(null);
  const [pendingClearProgress, setPendingClearProgress] = useState<Playlist | null>(null);
  // Playlist whose actions menu is open, plus its anchor button.
  const [actionsFor, setActionsFor] = useState<{ playlist: Playlist; anchor: HTMLElement } | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  const reload = () => api.playlists().then(setPlaylists).catch(() => setPlaylists([]));
  useEffect(() => { reload(); }, []);

  // Escape closes.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  const open = (p: Playlist) => {
    prefs.activePlaylist = p.id;
    onClose();
    navigate(`/browse/${p.id}`);
  };

  return (
    <>
      <div ref={panelRef} className="dock-panel">
        <div className="panel-head">
          <h3>{t('nav.playlists')}</h3>
          <IconBtn name="add" label={t('playlists.add')} onClick={() => setDialog('add')} />
        </div>
        <div className="panel-body">
          {playlists === null && <div className="spinner" />}
          {playlists?.length === 0 && (
            <p className="px-3 py-6 text-center type-body-medium text-on-surface-variant">
              {t('playlists.none')}
            </p>
          )}
          {playlists?.map((p) => {
            const meta = p.lastRefreshedMs > 0
              ? t('playlists.meta', {
                  count: p.channelCount,
                  date: new Date(p.lastRefreshedMs).toLocaleString(getLocale(), { dateStyle: 'short', timeStyle: 'short' }),
                })
              : t('playlists.metaNever', { count: p.channelCount });
            return (
              <div key={p.id} className={`panel-row${p.id === activeId ? ' selected' : ''}`}
                   onClick={() => open(p)}>
                <Icon name="playlist" />
                <div className="body">
                  <div className="name">{p.name}</div>
                  <div className="sub">{meta}</div>
                </div>
                <div className="actions" onClick={(e) => e.stopPropagation()}>
                  <IconBtn name="more" label={t('playlists.actions')} className="muted"
                           onClick={(e) => {
                             const anchor = e.currentTarget as HTMLElement;
                             setActionsFor((cur) => cur?.playlist.id === p.id ? null : { playlist: p, anchor });
                           }} />
                </div>
              </div>
            );
          })}
          <div className="panel-aux">
            <div className="mx-1 my-2 h-px" style={{ background: 'var(--c-hairline)' }} />
            <button className="panel-row" onClick={() => { onClose(); navigate('/downloads'); }}>
              <Icon name="download" />
              <div className="body">
                <div className="name">{downloading ? t('playlists.downloadsActive') : t('nav.downloads')}</div>
              </div>
            </button>
            <button className="panel-row" onClick={() => { onClose(); navigate('/sessions'); }}>
              <Icon name="activity" />
              <div className="body"><div className="name">{t('nav.activity')}</div></div>
            </button>
            <button className="panel-row" onClick={() => { onClose(); navigate('/settings'); }}>
              <Icon name="settings" />
              <div className="body"><div className="name">{t('nav.settings')}</div></div>
            </button>
          </div>
        </div>
      </div>

      {actionsFor && (() => {
        const p = actionsFor.playlist;
        const options: MenuOption[] = [];
        if (p.hasXtreamPanel) {
          options.push({ icon: 'person', label: t('playlists.account'),
                         onSelect: () => { onClose(); navigate(`/account/${p.id}`); } });
        }
        // File imports can't be refreshed; M3U-by-URL and Xtream can.
        if (p.mode !== 'file') {
          options.push({ icon: 'refresh', label: t('playlists.refresh'), onSelect: async () => {
            snackbar(t('playlists.refreshing'));
            try {
              await api.refreshPlaylist(p.id, true);
              snackbar(t('playlists.refreshed'));
              reload();
            } catch (e) { snackbar(t('playlists.refreshFailed', { message: (e as Error).message })); }
          } });
        }
        options.push({ icon: 'edit', label: t('playlists.edit.action'), onSelect: () => setDialog(p) });
        options.push({ icon: 'replay', label: t('playlists.clearProgress.action'),
                       onSelect: () => setPendingClearProgress(p) });
        options.push({ icon: 'del', label: t('playlists.delete.action'), danger: true,
                       onSelect: () => setPendingDelete(p) });
        return <Menu anchor={actionsFor.anchor} options={options} onDismiss={() => setActionsFor(null)} />;
      })()}

      {dialog && (
        <PlaylistDialog
          editing={dialog === 'add' ? null : dialog}
          onDismiss={() => setDialog(null)}
          onDone={(saved) => {
            setDialog(null);
            reload();
            if (dialog === 'add') open(saved);
          }}
        />
      )}
      {pendingDelete && (
        <ConfirmDialog
          title={t('playlists.removeTitle')}
          message={t('playlists.removeMessage', { name: pendingDelete.name })}
          confirmLabel={t('common.remove')}
          onConfirm={async () => {
            await api.deletePlaylist(pendingDelete.id).catch(() => {});
            if (prefs.activePlaylist === pendingDelete.id) prefs.activePlaylist = null;
            reload();
          }}
          onDismiss={() => setPendingDelete(null)}
        />
      )}
      {pendingClearProgress && (
        <ConfirmDialog
          title={t('playlists.clearProgress.title')}
          message={t('playlists.clearProgress.message', { name: pendingClearProgress.name })}
          confirmLabel={t('playlists.clearProgress.confirm')}
          onConfirm={async () => {
            try {
              await api.clearProgress(pendingClearProgress.id);
              snackbar(t('playlists.clearProgress.done'));
            } catch (e) { snackbar((e as Error).message); }
          }}
          onDismiss={() => setPendingClearProgress(null)}
        />
      )}
    </>
  );
}
