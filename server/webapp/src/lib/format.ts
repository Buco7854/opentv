// Formatting helpers; dates and times follow the active UI language.

import { getLocale, t } from '../i18n';

export const fmtTime = (ms: number) =>
  new Date(ms).toLocaleTimeString(getLocale(), { hour: '2-digit', minute: '2-digit' });

export const dayKey = (ms: number) => new Date(ms).toDateString();

/** Today / Yesterday / "Wednesday 16 July". */
export function fmtGuideDay(ms: number): string {
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((startOf(new Date()) - startOf(new Date(ms))) / 86_400_000);
  if (diff === 0) return t('guide.today');
  if (diff === 1) return t('guide.yesterday');
  if (diff === -1) return t('guide.tomorrow');
  return new Date(ms).toLocaleDateString(getLocale(), { weekday: 'long', day: 'numeric', month: 'long' });
}

/** "1h 05min" / "42 min". */
export function fmtDuration(secs: number): string {
  const minutes = Math.floor(secs / 60);
  return minutes >= 60
    ? `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}min`
    : `${minutes} min`;
}

/** "1.2 GB" / "531 MB". */
export function formatBytes(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${Math.round(bytes / 1e6)} MB`;
  if (bytes >= 1e3) return `${Math.round(bytes / 1e3)} kB`;
  return `${bytes} B`;
}

/** "S01E04" / "EP 4". */
export const episodeTag = (ch: { season: number | null; episode: number | null }): string | null =>
  ch.season != null && ch.episode != null
    ? `S${String(ch.season).padStart(2, '0')}E${String(ch.episode).padStart(2, '0')}`
    : ch.episode != null ? `EP ${ch.episode}` : null;

export const starRating = (r: number) => `★ ${r.toFixed(1)}`;
