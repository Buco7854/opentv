import type { ClientCapabilities } from '../api';

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
  const path = (source.split('?')[0] ?? '').toLowerCase();
  if (path.endsWith('.m3u8') || path.endsWith('.m3u')) return 'hls';
  if (path.endsWith('.ts')) return /\/live\//.test(path) ? 'livets' : 'ts';
  return 'direct';
}

/** The engine a source kind is played with, as reported to the activity dashboard. */
const ENGINE_BY_KIND = {
  hls: 'hls', livets: 'hls', ts: 'mpegts', direct: 'native',
} as const;

export type PlaybackEngineName = 'hls' | 'mpegts' | 'native' | 'remux';

export const engineForKind = (kind: StreamKind): PlaybackEngineName => ENGINE_BY_KIND[kind];

/** What the activity dashboard should say for the transport the player actually mounted. */
export function reportedEngine(
  kind: StreamKind,
  roomLive: boolean,
  remuxed: boolean,
): PlaybackEngineName {
  if (remuxed) return 'remux';
  if (roomLive && kind !== 'hls') return 'mpegts';
  return engineForKind(kind);
}

/** Browser capability used to choose copy vs transcode during remux setup. */
export function supportsHevc(mediaSource: typeof MediaSource | undefined): boolean {
  return mediaSource != null
    && ['hvc1.1.6.L120.90', 'hvc1.1.6.L93.90', 'hev1.1.6.L93.90']
      .some((codec) => mediaSource.isTypeSupported(`video/mp4; codecs="${codec}"`));
}

/** The server's browser baseline, extended only by codecs this browser positively reports. */
export function playbackCapabilities(
  mediaSource: typeof MediaSource | undefined,
): ClientCapabilities {
  return {
    videoCodecs: supportsHevc(mediaSource) ? ['h264', 'hevc'] : ['h264'],
    audioCodecs: ['aac', 'mp3', 'opus', 'flac', 'vorbis'],
  };
}

/**
 * Whether an HLS audio track needs the server's AAC rescue on this browser.
 *
 * HLS.js demuxes provider transport streams into an MSE SourceBuffer. A browser can therefore
 * render the video while silently dropping an AC-3/E-AC-3 track: successful video playback is
 * not proof that the complete stream is supported. Prefer the codec hls.js actually parsed over
 * the manifest hint, and ask MSE about the exact container/codec pair it is about to mount.
 * Missing metadata stays on the ordinary HLS path; a provider omission must not itself create
 * an expensive transcode.
 */
export function hlsAudioNeedsServerNormalization(
  track: { container?: string; codec?: string; levelCodec?: string } | null | undefined,
  mediaSource: Pick<typeof MediaSource, 'isTypeSupported'> | undefined,
): boolean {
  if (!track || !mediaSource) return false;
  const codec = track.codec?.trim() || track.levelCodec?.trim();
  if (!codec) return false;
  const container = track.container?.trim() || 'audio/mp4';
  return !mediaSource.isTypeSupported(`${container}; codecs="${codec}"`);
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
