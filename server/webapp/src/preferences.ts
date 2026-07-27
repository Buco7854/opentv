export type Theme = 'light' | 'dark' | 'system';

const fallback = new Map<string, string>();

export const storage = {
  read(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return fallback.get(key) ?? null;
    }
  },
  write(key: string, value: string) {
    fallback.set(key, value);
    try {
      localStorage.setItem(key, value);
    } catch {}
  },
  remove(key: string) {
    fallback.delete(key);
    try {
      localStorage.removeItem(key);
    } catch {}
  },
};

/**
 * Browser-local presentation preferences.
 *
 * Server-owned settings and data deliberately do not live here. Keeping this
 * adapter separate from the API client makes a future authenticated API client
 * replaceable without coupling it to localStorage.
 */
export const prefs = {
  get gridBrowse() { return storage.read('gridBrowse') !== '0'; },
  set gridBrowse(value: boolean) { storage.write('gridBrowse', value ? '1' : '0'); },
  get seekSeconds() { return Number(storage.read('seekSeconds')) || 10; },
  set seekSeconds(value: number) { storage.write('seekSeconds', String(value)); },
  get resizeMode() { return storage.read('resizeMode') ?? 'fit'; },
  set resizeMode(value: string) { storage.write('resizeMode', value); },
  get volume() {
    const stored = storage.read('volume');
    const value = Number(stored);
    return stored !== null && isFinite(value) ? Math.min(1, Math.max(0, value)) : 1;
  },
  set volume(value: number) { storage.write('volume', String(value)); },
  get muted() { return storage.read('muted') === '1'; },
  set muted(value: boolean) { storage.write('muted', value ? '1' : '0'); },
  get subScale() {
    const value = Number(storage.read('subScale'));
    return isFinite(value) && value > 0 ? Math.min(2, Math.max(0.5, value)) : 1;
  },
  set subScale(value: number) { storage.write('subScale', String(value)); },
  get subStyle() { return storage.read('subStyle') === 'background' ? 'background' : 'outline'; },
  set subStyle(value: string) { storage.write('subStyle', value); },
  get subBold() { return storage.read('subBold') === '1'; },
  set subBold(value: boolean) { storage.write('subBold', value ? '1' : '0'); },
  get theme(): Theme {
    const value = storage.read('theme');
    return value === 'light' || value === 'dark' ? value : 'system';
  },
  set theme(value: Theme) { storage.write('theme', value); },
  /** Playlist the dock's Live/Movies/Series/Favorites/Search actions target. */
  get activePlaylist(): number | null {
    const value = Number(storage.read('activePlaylist'));
    return Number.isFinite(value) && value > 0 ? value : null;
  },
  set activePlaylist(value: number | null) {
    if (value == null) storage.remove('activePlaylist');
    else storage.write('activePlaylist', String(value));
  },
};

/** data-theme drives the CSS design tokens. */
export function applyTheme(theme = prefs.theme) {
  document.documentElement.setAttribute('data-theme', theme);
}
