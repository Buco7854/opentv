import { lazy, Suspense, useSyncExternalStore } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { Dock } from './components/Dock';
import { localeStore } from './i18n';
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

export function App() {
  // Remount on language change so plain t() calls re-render.
  const locale = useSyncExternalStore(localeStore.subscribe, localeStore.get);
  return (
    <BrowserRouter key={locale}>
      <PlayerNavigationProvider>
        <main className="shell-content">
          <Suspense fallback={<RouteFallback />}>
            <Routes>
              <Route path="/" element={<HomeScreen />} />
              <Route path="/browse/:playlistId" element={<BrowseScreen />} />
              <Route path="/search/:playlistId" element={<SearchScreen />} />
              <Route path="/favorites/:playlistId" element={<FavoritesScreen />} />
              <Route path="/movie/:channelId" element={<MovieDetailScreen />} />
              <Route path="/episode/:channelId" element={<EpisodeDetailScreen />} />
              <Route path="/series/:playlistId/:seriesKey" element={<SeriesDetailScreen />} />
              <Route path="/xseries/:playlistId/:seriesId" element={<XtreamSeriesScreen />} />
              <Route path="/downloads" element={<DownloadsScreen />} />
              <Route path="/sessions" element={<SessionsScreen />} />
              <Route path="/watch/:channelId" element={<WatchChannelScreen />} />
              <Route path="/watch/catchup/:channelId/:startMs/:endMs" element={<WatchCatchupScreen />} />
              <Route path="/watch/download/:downloadId" element={<WatchDownloadScreen />} />
              <Route path="/settings" element={<SettingsScreen />} />
              <Route path="/account/:playlistId" element={<AccountScreen />} />
              <Route path="*" element={<HomeScreen />} />
            </Routes>
          </Suspense>
        </main>
        <Dock />
      </PlayerNavigationProvider>
    </BrowserRouter>
  );
}
