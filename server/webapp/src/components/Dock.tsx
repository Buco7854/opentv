// Bottom dock: burger opens the playlists panel; center icons are the active
// playlist's apps. Burger shows a green dot while downloads run.

import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import {
  api, DownloadStatus, Playlist, PlaylistCapabilities, PlaylistOperation,
} from '../api';
import { reportError, reportErrorAs, reportSuccess } from '../errors';
import { useDownloads } from '../hooks';
import { getLocale, t } from '../i18n';
import { useLibrary } from '../library';
import { prefs } from '../preferences';
import { Icon, IconName } from './Icons';
import { prefetchScreen, ScreenName } from '../prefetch';
import { ConfirmDialog, IconBtn, Menu, MenuOption, toast } from './Primitives';
import { PlaylistDialog } from './PlaylistDialog';
import { useAuth } from '../auth/AuthProvider';

function DockButton({ icon, label, active, disabled, dot, prefetch, onClick }: {
  icon: IconName;
  label: string;
  active?: boolean;
  disabled?: boolean;
  dot?: 'accent' | 'good';
  /** Screen this button leads to, fetched on pointer down rather than on click. */
  prefetch?: ScreenName;
  onClick: () => void;
}) {
  return (
    <button
      className={`dock-btn${active ? ' active' : ''}`}
      aria-label={label}
      title={label}
      disabled={disabled}
      onPointerDown={prefetch ? () => prefetchScreen(prefetch) : undefined}
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
  const {
    playlists, playlistPanelOpen: panelOpen, setPlaylistPanelOpen: setPanelOpen,
  } = useLibrary();
  // The fullscreen player covers the dock; don't keep polling downloads underneath it.
  const downloads = useDownloads(!pathname.startsWith('/watch'));
  const downloading = downloads.list.some(
    (d) => d.active && (d.status === DownloadStatus.QUEUED || d.status === DownloadStatus.RUNNING),
  );

  // Active playlist: a valid URL id, else a valid last-used id, else first.
  const urlId = pathname.match(/^\/(?:browse|search|account|series|xseries)\/(\d+)/)?.[1];
  const requestedId = urlId ? Number(urlId) : null;
  const active = playlists?.find((playlist) => playlist.id === requestedId)?.id
    ?? playlists?.find((playlist) => playlist.id === prefs.activePlaylist)?.id
    ?? playlists?.[0]?.id
    ?? null;
  useEffect(() => {
    if (playlists !== null && prefs.activePlaylist !== active) prefs.activePlaylist = active;
  }, [active, playlists]);

  const tab = new URLSearchParams(search).get('t') ?? '0';
  const inBrowse = pathname.startsWith('/browse/');
  const goBrowse = (target: number) => navigate(`/browse/${active}?t=${target}`);
  const managePlaylist = new URLSearchParams(search).get('manage') === 'playlist';
  useEffect(() => {
    if (!managePlaylist || active == null) return;
    setPanelOpen(true);
    const next = new URLSearchParams(search);
    next.delete('manage');
    navigate({ pathname, search: next.size ? `?${next}` : '' }, { replace: true });
  }, [active, managePlaylist, navigate, pathname, search, setPanelOpen]);

  return (
    <>
      <nav className="dock">
        <DockButton icon={panelOpen ? 'close' : 'more'} label={t('nav.playlists')}
                    dot={downloading ? 'good' : undefined}
                    active={panelOpen} onClick={() => setPanelOpen(!panelOpen)} />
        <div className="apps">
          <DockButton prefetch="browse" icon="liveTv" label={t('nav.live')} disabled={!active}
                      active={inBrowse && tab === '0'} onClick={() => goBrowse(0)} />
          <DockButton prefetch="browse" icon="movie" label={t('nav.movies')} disabled={!active}
                      active={inBrowse && tab === '1'} onClick={() => goBrowse(1)} />
          <DockButton prefetch="browse" icon="videoLib" label={t('nav.series')} disabled={!active}
                      active={inBrowse && tab === '2'} onClick={() => goBrowse(2)} />
          <DockButton prefetch="favorites" icon="favoriteBorder" label={t('nav.favorites')} disabled={!active}
                      active={pathname === '/favorites'}
                      onClick={() => navigate('/favorites')} />
          <DockButton prefetch="search" icon="search" label={t('nav.search')} disabled={!active}
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
  const { user, logout } = useAuth();
  const admin = user?.role === 'ADMIN';
  const {
    playlists, loading, error, reload, rememberPlaylist, forgetPlaylist,
  } = useLibrary();
  const [dialog, setDialog] = useState<'add' | Playlist | null>(null);
  const [pendingDelete, setPendingDelete] = useState<Playlist | null>(null);
  const [pendingClearProgress, setPendingClearProgress] = useState<Playlist | null>(null);
  // Playlist whose actions menu is open, plus its anchor button.
  const [actionsFor, setActionsFor] = useState<{
    playlist: Playlist;
    anchor: HTMLElement;
    capabilities: PlaylistCapabilities;
  } | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);

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
          {admin && <IconBtn name="add" label={t('playlists.add')} onClick={() => setDialog('add')} />}
        </div>
        <div className="panel-body">
          {playlists === null && loading && <div className="spinner" />}
          {error && playlists === null && (
            <div className="px-3 py-5 text-center">
              <p className="type-body-medium text-error">{t('playlists.loadFailedTitle')}</p>
              <button className="btn text mt-2 w-auto" onClick={() => void reload()}>
                <Icon name="refresh" />{t('common.retry')}
              </button>
            </div>
          )}
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
              <div key={p.id} className="panel-row-group">
                <button
                  type="button"
                  className={`panel-row${p.id === activeId ? ' selected' : ''}`}
                  aria-current={p.id === activeId ? 'true' : undefined}
                  onClick={() => open(p)}
                >
                  <Icon name="playlist" />
                  <div className="body">
                    <div className="name">{p.name}</div>
                    <div className="sub">{meta}</div>
                  </div>
                </button>
                <IconBtn name="more" label={t('playlists.actions')} className="muted"
                         onClick={(e) => {
                           const anchor = e.currentTarget as HTMLElement;
                           if (actionsFor?.playlist.id === p.id) {
                             setActionsFor(null);
                             return;
                           }
                           setActionsFor(null);
                           void api.playlistCapabilities(p.id)
                             .then((capabilities) => setActionsFor({ playlist: p, anchor, capabilities }))
                             .catch(reportError);
                         }} />
              </div>
            );
          })}
          <div className="panel-aux">
            <div className="divider mx-1 my-2" />
            <button className="panel-row" onClick={() => { onClose(); navigate('/downloads'); }}>
              <Icon name="download" />
              <div className="body">
                <div className="name">{downloading ? t('playlists.downloadsActive') : t('nav.downloads')}</div>
              </div>
            </button>
            {admin && (
              <>
                <button className="panel-row" onClick={() => { onClose(); navigate('/sessions'); }}>
                  <Icon name="activity" />
                  <div className="body"><div className="name">{t('nav.activity')}</div></div>
                </button>
                <button className="panel-row" onClick={() => { onClose(); navigate('/admin'); }}>
                  <Icon name="person" />
                  <div className="body"><div className="name">{t('nav.administration')}</div></div>
                </button>
              </>
            )}
            <button className="panel-row" onClick={() => { onClose(); navigate('/settings'); }}>
              <Icon name="settings" />
              <div className="body"><div className="name">{t('nav.settings')}</div></div>
            </button>
            <button className="panel-row" onClick={() => { onClose(); navigate('/security'); }}>
              <Icon name="person" />
              <div className="body"><div className="name">{t('nav.security')}</div></div>
            </button>
            {/* Signing out was reachable only from Account security, two screens deep. */}
            <button className="panel-row" onClick={() => { onClose(); void logout(); }}>
              <Icon name="logout" />
              <div className="body">
                <div className="name">{t('nav.logout')}</div>
                <div className="sub">{user?.displayName || user?.username}</div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {actionsFor && (() => {
        const p = actionsFor.playlist;
        const available = new Set(
          actionsFor.capabilities.operations.map((capability) => capability.operation),
        );
        const options: MenuOption[] = [];
        if (available.has(PlaylistOperation.VIEW_PROVIDER_ACCOUNT)) {
          options.push({ icon: 'person', label: t('playlists.account'),
                         onSelect: () => { onClose(); navigate(`/account/${p.id}`); } });
        }
        if (available.has(PlaylistOperation.REFRESH)) {
          options.push({ icon: 'refresh', label: t('playlists.refresh'), onSelect: async () => {
            toast(t('playlists.refreshing'));
            try {
              await api.refreshPlaylist(p.id, true);
              reportSuccess(t('playlists.refreshed'));
              void reload();
            } catch (e) {
              reportErrorAs((message) => t('playlists.refreshFailed', { message }), e);
            }
          } });
        }
        if (available.has(PlaylistOperation.EDIT)) {
          options.push({ icon: 'edit', label: t('playlists.edit.action'), onSelect: () => setDialog(p) });
        }
        if (available.has(PlaylistOperation.CLEAR_WATCH_PROGRESS)) {
          options.push({ icon: 'replay', label: t('playlists.clearProgress.action'),
                         onSelect: () => setPendingClearProgress(p) });
        }
        if (available.has(PlaylistOperation.DELETE)) {
          options.push({ icon: 'del', label: t('playlists.delete.action'), danger: true,
                         onSelect: () => setPendingDelete(p) });
        }
        return <Menu anchor={actionsFor.anchor} options={options} onDismiss={() => setActionsFor(null)} />;
      })()}

      {dialog && (
        <PlaylistDialog
          editing={dialog === 'add' ? null : dialog}
          onDismiss={() => setDialog(null)}
          onDone={(saved) => {
            setDialog(null);
            rememberPlaylist(saved);
            void reload();
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
            try {
              await api.deletePlaylist(pendingDelete.id);
              if (prefs.activePlaylist === pendingDelete.id) prefs.activePlaylist = null;
              forgetPlaylist(pendingDelete.id);
              void reload();
            } catch (cause) {
              reportError(cause);
            }
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
              reportSuccess(t('playlists.clearProgress.done'));
            } catch (e) { reportError(e); }
          }}
          onDismiss={() => setPendingClearProgress(null)}
        />
      )}
    </>
  );
}
