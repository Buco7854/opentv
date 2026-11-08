// Labeled library navigation: top bar on desktop, bottom bar on touch screens.

import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import {
  api, DownloadStatus, Playlist, PlaylistCapabilities, PlaylistDeleteEffect,
  PlaylistDeleteInfo, PlaylistEdit, PlaylistEpgRefreshStatus, PlaylistOperation,
  PlaylistOperationCapability, PlaylistOperationExecution, PlaylistRefreshResult,
} from '../api';
import { reportError, reportErrorAs, reportSuccess } from '../errors';
import { useDownloads } from '../hooks';
import { getLocale, t } from '../i18n';
import { useLibrary } from '../library';
import { prefs } from '../preferences';
import { Icon, IconName } from './Icons';
import { prefetchScreen, ScreenName } from '../prefetch';
import { ConfirmDialog, Dialog, IconBtn, Menu, MenuOption, toast } from './Primitives';
import { PlaylistDialog } from './PlaylistDialog';
import { useAuth } from '../auth/AuthProvider';

const LOCALIZED_DELETE_EFFECTS = Object.values(PlaylistDeleteEffect);

/** Server facts choose the warning; the browser owns its localized wording. */
export function playlistDeleteWarning(info: PlaylistDeleteInfo): string {
  const effects = new Set(info.effects);
  const knownCompleteWarning = effects.size === LOCALIZED_DELETE_EFFECTS.length
    && LOCALIZED_DELETE_EFFECTS.every((effect) => effects.has(effect));
  return knownCompleteWarning
    ? t('playlists.removeMessage', { name: info.name })
    : info.warning;
}

export function playlistRefreshNotice(result: PlaylistRefreshResult): {
  message: string;
  tone: 'success' | 'error';
} {
  if (result.epgStatus === PlaylistEpgRefreshStatus.FAILED) {
    return { message: t('playlists.refreshedGuideFailed'), tone: 'error' };
  }
  if (result.epgStatus === PlaylistEpgRefreshStatus.NOT_CONFIGURED) {
    return { message: t('playlists.refreshedNoGuide'), tone: 'success' };
  }
  return { message: t('playlists.refreshed'), tone: 'success' };
}

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
      aria-current={active ? 'page' : undefined}
      onPointerDown={prefetch ? () => prefetchScreen(prefetch) : undefined}
      onClick={onClick}
    >
      <Icon name={icon} />
      <span className="dock-label">{label}</span>
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

  const inBrowse = pathname.startsWith('/browse/');
  const [choosingPlaylist, setChoosingPlaylist] = useState(false);
  const closeChooser = useCallback(() => setChoosingPlaylist(false), []);

  const managePlaylist = new URLSearchParams(search).get('manage') === 'playlist';
  useEffect(() => {
    if (!managePlaylist || active == null) return;
    setPanelOpen(true);
    const next = new URLSearchParams(search);
    next.delete('manage');
    navigate({ pathname, search: next.size ? `?${next}` : '' }, { replace: true });
  }, [active, managePlaylist, navigate, pathname, search, setPanelOpen]);

  const closePanel = useCallback(() => setPanelOpen(false), [setPanelOpen]);

  return (
    <>
      <header className="library-masthead">
        <button className="library-brand" aria-label={`OpenTV · ${t('nav.home')}`} onClick={() => navigate('/')}>
          <Icon name="liveTv" /><span>OpenTV</span>
        </button>
        <nav className="dock" aria-label={t('nav.navigation')}>
          <DockButton icon="home" label={t('nav.home')} active={pathname === '/'} onClick={() => navigate('/')} />
          <DockButton prefetch="browse" icon="grid" label={t('nav.browse')} disabled={!active}
                      active={inBrowse} onClick={() => navigate(`/browse/${active}`)} />
          <DockButton prefetch="favorites" icon="favoriteBorder" label={t('nav.favorites')}
                      active={pathname === '/favorites'} onClick={() => navigate('/favorites')} />
          <DockButton prefetch="search" icon="search" label={t('nav.search')} disabled={!active}
                      active={pathname.startsWith('/search/')} onClick={() => navigate(`/search/${active}`)} />
          <button className={`dock-btn library-menu${panelOpen ? ' active' : ''}`}
                  aria-label={t('nav.more')} aria-haspopup="dialog" aria-expanded={panelOpen}
                  aria-controls="library-panel" onClick={() => { setChoosingPlaylist(false); setPanelOpen(!panelOpen); }}>
            <Icon name={panelOpen ? 'close' : 'more'} />
            <span className="dock-label">{t('nav.more')}</span>
            {downloading && <span className="dot good" />}
          </button>
        </nav>
        <button className="library-switcher" aria-label={t('playlists.choose')}
                aria-haspopup="dialog" aria-expanded={choosingPlaylist} disabled={!playlists?.length}
                onClick={() => { setPanelOpen(false); setChoosingPlaylist((open) => !open); }}>
          <Icon name="playlist" />
          <span className="library-switcher-label"><small>{t('nav.playlists')}</small>
            <strong>{playlists?.find((playlist) => playlist.id === active)?.name ?? '—'}</strong></span>
          <Icon name="expandMore" />
        </button>
      </header>

      {choosingPlaylist && <PlaylistChooser playlists={playlists} activeId={active}
        onClose={closeChooser} onChoose={(id) => {
          prefs.activePlaylist = id;
          closeChooser();
          if (id !== active || !inBrowse) navigate(`/browse/${id}`);
        }} />}
      {panelOpen && (
        <PlaylistsPanel activeId={active} downloading={downloading} onClose={closePanel} />
      )}
    </>
  );
}

/** Choosing a source never starts creation, editing, or account management. */
function PlaylistChooser({ playlists, activeId, onClose, onChoose }: {
  playlists: Playlist[] | null;
  activeId: number | null;
  onClose: () => void;
  onChoose: (id: number) => void;
}) {
  const { loading, error, reload } = useLibrary();
  return <Dialog title={t('playlists.choose')} onDismiss={onClose} className="playlist-chooser"
    buttons={<button className="btn text" onClick={onClose}>{t('common.close')}</button>}>
    <p className="playlist-chooser-hint">{t('playlists.chooseHint')}</p>
    <div className="playlist-choices">
      {playlists?.map((playlist) => <button key={playlist.id} type="button"
        className="playlist-choice" aria-current={playlist.id === activeId ? 'true' : undefined}
        onClick={() => onChoose(playlist.id)}>
        <Icon name="playlist" />
        <span className="body"><strong>{playlist.name}</strong><small>{t('home.catalogCount', { count: playlist.channelCount })}</small></span>
        {playlist.id === activeId && <Icon name="check" />}
      </button>)}
      {playlists?.length === 0 && <p className="playlist-chooser-hint">{t('playlists.none')}</p>}
      {playlists === null && loading && <div className="spinner" role="status" aria-label={t('common.working')} />}
      {playlists === null && error && <><p role="alert">{t('playlists.loadFailedTitle')}</p>
        <button className="btn tonal" onClick={() => void reload()}>{t('common.retry')}</button></>}
    </div>
  </Dialog>;
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
  const [dialog, setDialog] = useState<'add' | PlaylistEdit | null>(null);
  const [pendingDelete, setPendingDelete] = useState<PlaylistDeleteInfo | null>(null);
  const [pendingClearProgress, setPendingClearProgress] = useState<Playlist | null>(null);
  // Playlist whose actions menu is open, plus its anchor button.
  const [actionsFor, setActionsFor] = useState<{
    playlist: Playlist;
    anchor: HTMLElement;
    capabilities: PlaylistCapabilities;
  } | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  // Menu content is portaled outside this dialog. Tab dismisses it back to its
  // anchor before the dialog's own focus cycle continues.
  useEffect(() => {
    if (!actionsFor) return;
    const onTab = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      event.preventDefault();
      setActionsFor(null);
      actionsFor.anchor.focus();
    };
    document.addEventListener('keydown', onTab);
    return () => document.removeEventListener('keydown', onTab);
  }, [actionsFor]);

  // This is a navigation dialog. Keep focus inside it and return to its opener.
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    const controls = () => Array.from(panel?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href]') ?? []);
    controls()[0]?.focus();
    const trap = (event: KeyboardEvent) => {
      if (event.key !== 'Tab' || !panel?.contains(document.activeElement)) return;
      const items = controls();
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', trap);
    return () => { document.removeEventListener('keydown', trap); opener?.focus(); };
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !dialog && !pendingDelete && !pendingClearProgress && !actionsFor) onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose, dialog, pendingDelete, pendingClearProgress, actionsFor]);


  return (
    <>
      <div className="library-panel-scrim" onClick={onClose} aria-hidden="true" />
      <div ref={panelRef} id="library-panel" className="dock-panel" role="dialog" aria-modal="true" aria-labelledby="library-panel-title">
        <div className="panel-head">
          <h3 id="library-panel-title">{t('nav.playlists')}</h3>
          {admin && <button type="button" className="btn text playlist-add-action" onClick={() => setDialog('add')}>{t('playlists.add')}</button>}
          <IconBtn name="close" label={t('common.close')} onClick={onClose} />
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
              <div key={p.id} className={`panel-row-group${p.id === activeId ? ' selected' : ''}`}>
                <div className="panel-row playlist-identity">
                  <Icon name="playlist" />
                  <div className="body">
                    <div className="name">{p.name}</div>
                    <div className="sub">{meta}</div>
                  </div>
                </div>
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
            <div className="panel-section-label">{t('nav.library')}</div>
            <button className="panel-row" onClick={() => { onClose(); navigate('/downloads'); }}>
              <Icon name="download" />
              <div className="body">
                <div className="name">{downloading ? t('playlists.downloadsActive') : t('nav.downloads')}</div>
              </div>
            </button>
            {admin && (
              <>
                <div className="panel-section-label">{t('nav.server')}</div>
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
            <div className="panel-section-label">{t('nav.account')}</div>
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
        const available = (operation: PlaylistOperation) =>
          actionsFor.capabilities.operations.find((capability) => capability.operation === operation);
        const run = (capability: PlaylistOperationCapability, inApp: () => void) => {
          if (capability.execution === PlaylistOperationExecution.BROWSER) {
            if (!capability.browserPath) {
              reportError(new Error(t('common.unexpectedResponse')));
              return;
            }
            onClose();
            navigate(capability.browserPath);
            return;
          }
          inApp();
        };
        const options: MenuOption[] = [];
        const account = available(PlaylistOperation.VIEW_PROVIDER_ACCOUNT);
        if (account) {
          options.push({ icon: 'person', label: t('playlists.account'),
                         onSelect: () => run(account, () => {
                           onClose();
                           navigate(`/account/${p.id}`);
                         }) });
        }
        const refresh = available(PlaylistOperation.REFRESH);
        if (refresh) {
          options.push({ icon: 'refresh', label: t('playlists.refresh'), onSelect: async () => {
            run(refresh, () => {
              toast(t('playlists.refreshing'));
              void api.refreshPlaylist(p.id, true).then((result) => {
                const notice = playlistRefreshNotice(result);
                toast(notice.message, { tone: notice.tone });
                void reload();
              }).catch((e: unknown) => {
                reportErrorAs((message) => t('playlists.refreshFailed', { message }), e);
              });
            });
          } });
        }
        const edit = available(PlaylistOperation.EDIT);
        if (edit) {
          options.push({ icon: 'edit', label: t('playlists.edit.action'), onSelect: () => {
            run(edit, () => {
              void api.playlistEdit(p.id).then(setDialog).catch(reportError);
            });
          } });
        }
        const clearProgress = available(PlaylistOperation.CLEAR_WATCH_PROGRESS);
        if (clearProgress) {
          options.push({ icon: 'replay', label: t('playlists.clearProgress.action'),
                         onSelect: () => run(clearProgress, () => setPendingClearProgress(p)) });
        }
        const remove = available(PlaylistOperation.DELETE);
        if (remove) {
          options.push({ icon: 'del', label: t('playlists.delete.action'), danger: true,
                         onSelect: () => run(remove, () => {
                           void api.playlistDeleteInfo(p.id).then(setPendingDelete).catch(reportError);
                         }) });
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
            if (dialog === 'add') { prefs.activePlaylist = saved.id; onClose(); navigate(`/browse/${saved.id}`); }
          }}
        />
      )}
      {pendingDelete && (
        <ConfirmDialog
          title={t('playlists.removeTitle')}
          message={playlistDeleteWarning(pendingDelete)}
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
