import { lazy, ReactNode, Suspense, useSyncExternalStore } from 'react';
import {
  BrowserRouter, Route, Routes, useNavigate, useParams,
} from 'react-router-dom';
import { EmptyState } from './components/Common';
import { Dock } from './components/Dock';
import { Icon } from './components/Icons';
import { Spinner } from './components/Primitives';
import { localeStore, t } from './i18n';
import { LibraryProvider, useLibrary } from './library';
import { PlayerNavigationProvider } from './player/PlayerNavigation';

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

const MovieDetailScreen = lazy(() => import('./screens/DetailScreens').then((m) => ({ default: m.MovieDetailScreen })));
const EpisodeDetailScreen = lazy(() => import('./screens/DetailScreens').then((m) => ({ default: m.EpisodeDetailScreen })));
const SeriesDetailScreen = lazy(() => import('./screens/DetailScreens').then((m) => ({ default: m.SeriesDetailScreen })));

const WatchChannelScreen = lazy(() => import('./screens/WatchScreen').then((m) => ({ default: m.WatchChannelScreen })));
const WatchCatchupScreen = lazy(() => import('./screens/WatchScreen').then((m) => ({ default: m.WatchCatchupScreen })));
const WatchDownloadScreen = lazy(() => import('./screens/WatchScreen').then((m) => ({ default: m.WatchDownloadScreen })));

function RouteFallback() {
  return <div className="spinner" role="status" aria-label="Loading" />;
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
        action={
          <button className="btn" onClick={() => setPlaylistPanelOpen(true)}>
            <Icon name="add" />{t('playlists.add')}
          </button>
        }
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
          <button className="btn tonal" onClick={() => navigate(`/browse/${playlists[0].id}`, { replace: true })}>
            <Icon name="playlist" />{t('playlists.openAvailable')}
          </button>
        }
      />
    );
  }
  return children;
}

const forPlaylist = (screen: ReactNode) => <PlaylistRoute>{screen}</PlaylistRoute>;

export function App() {
  // Remount on language change so plain t() calls re-render.
  const locale = useSyncExternalStore(localeStore.subscribe, localeStore.get);
  return (
    <BrowserRouter key={locale}>
      <LibraryProvider>
        <PlayerNavigationProvider>
          <main className="shell-content">
            <Suspense fallback={<RouteFallback />}>
              <Routes>
                <Route path="/" element={<HomeScreen />} />
                <Route path="/browse/:playlistId" element={forPlaylist(<BrowseScreen />)} />
                <Route path="/search/:playlistId" element={forPlaylist(<SearchScreen />)} />
                <Route path="/favorites/:playlistId" element={forPlaylist(<FavoritesScreen />)} />
                <Route path="/movie/:channelId" element={<MovieDetailScreen />} />
                <Route path="/episode/:channelId" element={<EpisodeDetailScreen />} />
                <Route path="/series/:playlistId/:seriesKey" element={forPlaylist(<SeriesDetailScreen />)} />
                <Route path="/xseries/:playlistId/:seriesId" element={forPlaylist(<XtreamSeriesScreen />)} />
                <Route path="/downloads" element={<DownloadsScreen />} />
                <Route path="/sessions" element={<SessionsScreen />} />
                <Route path="/watch/:channelId" element={<WatchChannelScreen />} />
                <Route path="/watch/catchup/:channelId/:startMs/:endMs" element={<WatchCatchupScreen />} />
                <Route path="/watch/download/:downloadId" element={<WatchDownloadScreen />} />
                <Route path="/settings" element={<SettingsScreen />} />
                <Route path="/account/:playlistId" element={forPlaylist(<AccountScreen />)} />
                <Route path="*" element={<HomeScreen />} />
              </Routes>
            </Suspense>
          </main>
          <Dock />
        </PlayerNavigationProvider>
      </LibraryProvider>
    </BrowserRouter>
  );
}
