export type StreamKind = 'hls' | 'livets' | 'ts' | 'direct';

/**
 * Selects an engine from an opaque source token or a server-owned media URL.
 * The token is inspected only for its non-secret format tag and is never decoded.
 */
export function streamKind(source: string): StreamKind {
  const tag = /^([hltd])\./.exec(source)?.[1];
  if (tag) {
    return ({ h: 'hls', l: 'livets', t: 'ts', d: 'direct' } as const)[
      tag as 'h' | 'l' | 't' | 'd'
    ];
  }
  const path = source.split('?')[0].toLowerCase();
  if (path.endsWith('.m3u8') || path.endsWith('.m3u')) return 'hls';
  if (path.endsWith('.ts')) return /\/live\//.test(path) ? 'livets' : 'ts';
  return 'direct';
}

/** Browser capability used to choose copy vs transcode during remux setup. */
export function supportsHevc(mediaSource: typeof MediaSource | undefined): boolean {
  return mediaSource != null
    && ['hvc1.1.6.L120.90', 'hvc1.1.6.L93.90', 'hev1.1.6.L93.90']
      .some((codec) => mediaSource.isTypeSupported(`video/mp4; codecs="${codec}"`));
}

export function formatPlaybackTime(seconds: number): string {
  if (!isFinite(seconds)) return '–:––';
  const whole = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const remainder = whole % 60;
  return hours
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`
    : `${minutes}:${String(remainder).padStart(2, '0')}`;
}
