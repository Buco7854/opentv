import { useSyncExternalStore } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { Dock } from './components/Dock';
import { localeStore } from './i18n';
import { PlayerProvider } from './player/PlayerProvider';
import { AccountScreen } from './screens/AccountScreen';
import { BrowseScreen } from './screens/BrowseScreen';
import { EpisodeDetailScreen, MovieDetailScreen, SeriesDetailScreen } from './screens/DetailScreens';
import { DownloadsScreen } from './screens/DownloadsScreen';
import { FavoritesScreen } from './screens/FavoritesScreen';
import { HomeScreen } from './screens/HomeScreen';
import { SearchScreen } from './screens/SearchScreen';
import { SessionsScreen } from './screens/SessionsScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { WatchCatchupScreen, WatchChannelScreen, WatchDownloadScreen } from './screens/WatchScreen';
import { XtreamSeriesScreen } from './screens/XtreamSeriesScreen';

export function App() {
  // Remount on language change so plain t() calls re-render.
  const locale = useSyncExternalStore(localeStore.subscribe, localeStore.get);
  return (
    <BrowserRouter key={locale}>
      <PlayerProvider>
        <main className="shell-content">
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
        </main>
        <Dock />
      </PlayerProvider>
    </BrowserRouter>
  );
}
