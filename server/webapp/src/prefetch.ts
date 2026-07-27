// Screens are lazy route boundaries, so the first visit to each pays a network round trip
// before anything renders - the "I clicked and nothing happened" second. The modules are
// small and the bundler caches them, so fetching the reachable ones while the browser is
// idle turns that visit into a local lookup.
//
// The player is the exception in both directions: it carries hls.js and mpegts.js, so it is
// the biggest win and the biggest download. It waits for a real signal of intent, and never
// loads itself on a connection the user is paying for by the megabyte.

type Loader = () => Promise<unknown>;

const loaders = {
  home: () => import('./screens/HomeScreen'),
  browse: () => import('./screens/BrowseScreen'),
  search: () => import('./screens/SearchScreen'),
  favorites: () => import('./screens/FavoritesScreen'),
  downloads: () => import('./screens/DownloadsScreen'),
  settings: () => import('./screens/SettingsScreen'),
  detail: () => import('./screens/DetailScreens'),
  watch: () => import('./screens/WatchScreen'),
} satisfies Record<string, Loader>;

export type ScreenName = keyof typeof loaders;

const started = new Set<ScreenName>();

/** Idempotent, and never rejects: a prefetch that fails just leaves the normal load to retry. */
export function prefetchScreen(name: ScreenName) {
  if (started.has(name)) return;
  started.add(name);
  void loaders[name]().catch(() => started.delete(name));
}

interface SaveDataConnection {
  saveData?: boolean;
  effectiveType?: string;
}

/** Data saver, or a connection too slow to spend a megabyte speculatively. */
function frugal(): boolean {
  const connection = (navigator as Navigator & { connection?: SaveDataConnection }).connection;
  if (!connection) return false;
  return connection.saveData === true
    || ['slow-2g', '2g'].includes(connection.effectiveType ?? '');
}

const whenIdle = (run: () => void) =>
  typeof requestIdleCallback === 'function'
    ? requestIdleCallback(run, { timeout: 3000 })
    : setTimeout(run, 1200);

/** Warms the screens the dock can reach, once the app itself has settled. */
export function prefetchReachableScreens() {
  if (frugal()) return;
  whenIdle(() => {
    (['browse', 'search', 'favorites', 'detail', 'downloads', 'settings'] as const)
      .forEach(prefetchScreen);
  });
}
