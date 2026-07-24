export type Theme = 'light' | 'dark' | 'system';

/**
 * Browser-local presentation preferences.
 *
 * Server-owned settings and data deliberately do not live here. Keeping this
 * adapter separate from the API client makes a future authenticated API client
 * replaceable without coupling it to localStorage.
 */
export const prefs = {
  get gridBrowse() { return localStorage.getItem('gridBrowse') !== '0'; },
  set gridBrowse(value: boolean) { localStorage.setItem('gridBrowse', value ? '1' : '0'); },
  get seekSeconds() { return Number(localStorage.getItem('seekSeconds')) || 10; },
  set seekSeconds(value: number) { localStorage.setItem('seekSeconds', String(value)); },
  get resizeMode() { return localStorage.getItem('resizeMode') ?? 'fit'; },
  set resizeMode(value: string) { localStorage.setItem('resizeMode', value); },
  get volume() {
    const value = Number(localStorage.getItem('volume'));
    return isFinite(value) && value > 0 ? Math.min(1, value) : 1;
  },
  set volume(value: number) { localStorage.setItem('volume', String(value)); },
  get muted() { return localStorage.getItem('muted') === '1'; },
  set muted(value: boolean) { localStorage.setItem('muted', value ? '1' : '0'); },
  get subScale() {
    const value = Number(localStorage.getItem('subScale'));
    return isFinite(value) && value > 0 ? Math.min(2, Math.max(0.5, value)) : 1;
  },
  set subScale(value: number) { localStorage.setItem('subScale', String(value)); },
  get subStyle() { return localStorage.getItem('subStyle') === 'background' ? 'background' : 'outline'; },
  set subStyle(value: string) { localStorage.setItem('subStyle', value); },
  get subBold() { return localStorage.getItem('subBold') === '1'; },
  set subBold(value: boolean) { localStorage.setItem('subBold', value ? '1' : '0'); },
  get theme(): Theme {
    const value = localStorage.getItem('theme');
    return value === 'light' || value === 'dark' ? value : 'system';
  },
  set theme(value: Theme) { localStorage.setItem('theme', value); },
  /** Playlist the dock's Live/Movies/Series/Favorites/Search actions target. */
  get activePlaylist(): number | null {
    const value = Number(localStorage.getItem('activePlaylist'));
    return Number.isFinite(value) && value > 0 ? value : null;
  },
  set activePlaylist(value: number | null) {
    if (value == null) localStorage.removeItem('activePlaylist');
    else localStorage.setItem('activePlaylist', String(value));
  },
};

/** data-theme drives the CSS design tokens. */
export function applyTheme(theme = prefs.theme) {
  document.documentElement.setAttribute('data-theme', theme);
}
