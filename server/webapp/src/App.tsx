import { lazy, ReactNode, Suspense, useEffect, useRef, useSyncExternalStore } from 'react';
import {
  BrowserRouter, Route, Routes, useLocation, useNavigate, useNavigationType, useParams,
} from 'react-router';
import { EmptyState } from './components/Common';
import { Dock } from './components/Dock';
import { AppErrorBoundary, RouteErrorBoundary } from './components/ErrorBoundary';
import { Icon } from './components/Icons';
import { Spinner } from './components/Primitives';
import { localeStore, t } from './i18n';
import { LibraryProvider, useLibrary } from './library';
import { prefetchReachableScreens } from './prefetch';
import { PlayerNavigationProvider } from './player/PlayerNavigation';
import {
  AuthProvider, AuthReturnHandler, RequireAdmin, RequireAuth, useAuth,
} from './auth/AuthProvider';

// Screens are feature boundaries. In particular, the watch route owns hls.js,
// mpegts.js and the session stack, so browsing never downloads playback engines.
const HomeScreen = lazy(() => import('./screens/HomeScreen').then((m) => ({ default: m.HomeScreen })));
const BrowseScreen = lazy(() => import('./screens/BrowseScreen').then((m) => ({ default: m.BrowseScreen })));
const SearchScreen = lazy(() => import('./screens/SearchScreen').then((m) => ({ default: m.SearchScreen })));
const FavoritesScreen = lazy(() => import('./screens/FavoritesScreen').then((m) => ({ default: m.FavoritesScreen })));
const DownloadsScreen = lazy(() => import('./screens/DownloadsScreen').then((m) => ({ default: m.DownloadsScreen })));
const SessionsScreen = lazy(() => import('./screens/SessionsScreen').then((m) => ({ default: m.SessionsScreen })));
const SettingsScreen = lazy(() => import('./screens/SettingsScreen').then((m) => ({ default: m.SettingsScreen })));
const AccountScreen = lazy(() => import('./screens/AccountScreen').then((m) => ({ default: m.AccountScreen })));
const XtreamSeriesScreen = lazy(() => import('./screens/XtreamSeriesScreen').then((m) => ({ default: m.XtreamSeriesScreen })));
const AdminScreen = lazy(() => import('./admin/AdminScreen').then((m) => ({ default: m.AdminScreen })));
const LoginScreen = lazy(() => import('./auth/AuthScreens').then((m) => ({ default: m.LoginScreen })));
const SetupScreen = lazy(() => import('./auth/AuthScreens').then((m) => ({ default: m.SetupScreen })));
const ActivateScreen = lazy(() => import('./auth/AuthScreens').then((m) => ({ default: m.ActivateScreen })));
const SecurityScreen = lazy(() => import('./auth/AuthScreens').then((m) => ({ default: m.SecurityScreen })));
const DeviceLinkScreen = lazy(() => import('./auth/DeviceLink').then((m) => ({ default: m.DeviceLinkScreen })));
const LinkLandingScreen = lazy(() => import('./auth/LinkLanding').then((m) => ({ default: m.LinkLandingScreen })));

const MovieDetailScreen = lazy(() => import('./screens/DetailScreens').then((m) => ({ default: m.MovieDetailScreen })));
const EpisodeDetailScreen = lazy(() => import('./screens/DetailScreens').then((m) => ({ default: m.EpisodeDetailScreen })));
const SeriesDetailScreen = lazy(() => import('./screens/DetailScreens').then((m) => ({ default: m.SeriesDetailScreen })));

const WatchChannelScreen = lazy(() => import('./screens/WatchScreen').then((m) => ({ default: m.WatchChannelScreen })));
const WatchCatchupScreen = lazy(() => import('./screens/WatchScreen').then((m) => ({ default: m.WatchCatchupScreen })));
const WatchDownloadScreen = lazy(() => import('./screens/WatchScreen').then((m) => ({ default: m.WatchDownloadScreen })));

function RouteFallback() {
  return <div className="spinner" role="status" aria-label={t('common.working')} />;
}

const scrollOffsets = new Map<string, number>();

function useScreenTransition() {
  const { key } = useLocation();
  const navigationType = useNavigationType();
  const mainRef = useRef<HTMLElement>(null);
  useEffect(() => {
    window.scrollTo({ top: navigationType === 'POP' ? scrollOffsets.get(key) ?? 0 : 0 });
    mainRef.current?.focus({ preventScroll: true });
    const remember = () => scrollOffsets.set(key, window.scrollY);
    window.addEventListener('scroll', remember, { passive: true });
    return () => {
      remember();
      window.removeEventListener('scroll', remember);
    };
  }, [key, navigationType]);
  return mainRef;
}

/**
 * Playlist-owned routes must not mount feature loaders until their playlist is
 * known. This also turns stale bookmarks and fresh installs into actionable
 * states instead of leaving individual screens on permanent spinners.
 */
function PlaylistRoute({ children }: { children: ReactNode }) {
  const requestedId = Number(useParams().playlistId);
  const navigate = useNavigate();
  const {
    playlists, loading, error, reload, setPlaylistPanelOpen,
  } = useLibrary();
  const { user } = useAuth();

  if (playlists === null && loading) return <Spinner />;
  if (playlists === null) {
    return (
      <EmptyState
        title={t('playlists.loadFailedTitle')}
        subtitle={t('playlists.loadFailedSub', { message: error ?? '' })}
        action={
          <button className="btn tonal" onClick={() => void reload()}>
            <Icon name="refresh" />{t('common.retry')}
          </button>
        }
      />
    );
  }
  if (playlists.length === 0) {
    return (
      <EmptyState
        title={t('playlists.requiredTitle')}
        subtitle={t('playlists.requiredSub')}
        action={user?.role === 'ADMIN' ? (
          <button className="btn" onClick={() => setPlaylistPanelOpen(true)}>
            <Icon name="add" />{t('playlists.add')}
          </button>
        ) : undefined}
      >
        <div className="empty-home-art"><Icon name="playlist" /></div>
      </EmptyState>
    );
  }
  if (!Number.isSafeInteger(requestedId) || !playlists.some((playlist) => playlist.id === requestedId)) {
    return (
      <EmptyState
        title={t('playlists.notFoundTitle')}
        subtitle={t('playlists.notFoundSub')}
        action={
          <button className="btn tonal" onClick={() => navigate(`/browse/${playlists[0]?.id}`, { replace: true })}>
            <Icon name="playlist" />{t('playlists.openAvailable')}
          </button>
        }
      />
    );
  }
  return children;
}

const forPlaylist = (screen: ReactNode) => <PlaylistRoute>{screen}</PlaylistRoute>;

function AuthenticatedApp() {
  const mainRef = useScreenTransition();
  // Signed in and rendering: the route chunks a signed-in user reaches are worth having
  // before they are asked for.
  useEffect(prefetchReachableScreens, []);
  return (
    <LibraryProvider>
      <PlayerNavigationProvider>
        <main className="shell-content" ref={mainRef} tabIndex={-1}>
          <RouteErrorBoundary>
            <Suspense fallback={<RouteFallback />}>
              <Routes>
                <Route path="/" element={<HomeScreen />} />
                <Route path="/browse/:playlistId" element={forPlaylist(<BrowseScreen />)} />
                <Route path="/search/:playlistId" element={forPlaylist(<SearchScreen />)} />
                <Route path="/favorites" element={<FavoritesScreen />} />
                <Route path="/movie/:channelId" element={<MovieDetailScreen />} />
                <Route path="/episode/:channelId" element={<EpisodeDetailScreen />} />
                <Route path="/series/:playlistId/:seriesKey" element={forPlaylist(<SeriesDetailScreen />)} />
                <Route path="/xseries/:playlistId/:seriesId" element={forPlaylist(<XtreamSeriesScreen />)} />
                <Route path="/downloads" element={<DownloadsScreen />} />
                <Route path="/sessions" element={<RequireAdmin><SessionsScreen /></RequireAdmin>} />
                <Route path="/admin" element={<RequireAdmin><AdminScreen /></RequireAdmin>} />
                <Route path="/security" element={<SecurityScreen />} />
                <Route path="/watch/:channelId" element={<WatchChannelScreen />} />
                <Route path="/watch/catchup/:channelId/:startMs/:endMs" element={<WatchCatchupScreen />} />
                <Route path="/watch/download/:downloadId" element={<WatchDownloadScreen />} />
                <Route path="/settings" element={<SettingsScreen />} />
                <Route path="/account/:playlistId" element={<RequireAdmin>{forPlaylist(<AccountScreen />)}</RequireAdmin>} />
                <Route path="*" element={<HomeScreen />} />
              </Routes>
            </Suspense>
          </RouteErrorBoundary>
        </main>
        <Dock />
      </PlayerNavigationProvider>
    </LibraryProvider>
  );
}

function AuthenticatedBoundary() {
  const { user } = useAuth();
  const ownershipKey = user
    ? `${user.id}:${user.role}:${[...user.playlistIds].sort((a, b) => a - b).join(',')}`
    : 'signed-out';
  return <AuthenticatedApp key={ownershipKey} />;
}

export function App() {
  // Remount on language change so plain t() calls re-render.
  const locale = useSyncExternalStore(localeStore.subscribe, localeStore.get);
  useEffect(() => { document.documentElement.lang = locale; }, [locale]);
  return (
    <BrowserRouter key={locale}>
      <AuthProvider>
        <AuthReturnHandler />
        <AppErrorBoundary>
          <Suspense fallback={<RouteFallback />}>
            <Routes>
              <Route path="/login" element={<LoginScreen />} />
              <Route path="/setup" element={<SetupScreen />} />
              <Route path="/activate" element={<ActivateScreen />} />
              <Route path="/login/device" element={<DeviceLinkScreen />} />
              <Route path="/link" element={<LinkLandingScreen />} />
              <Route
                path="*"
                element={<RequireAuth><AuthenticatedBoundary /></RequireAuth>}
              />
            </Routes>
          </Suspense>
        </AppErrorBoundary>
      </AuthProvider>
    </BrowserRouter>
  );
}
