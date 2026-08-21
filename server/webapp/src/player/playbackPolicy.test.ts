import { describe, expect, it, vi } from 'vitest';
import {
  hlsAudioNeedsServerNormalization,
  playbackCapabilities,
  reportedEngine,
  supportsHevc,
} from './playbackPolicy';

describe('playback capability report', () => {
  it('reports the exact browser baseline when HEVC is unavailable', () => {
    expect(playbackCapabilities(undefined)).toEqual({
      videoCodecs: ['h264'],
      audioCodecs: ['aac', 'mp3', 'opus', 'flac', 'vorbis'],
    });
  });

  it('appends HEVC only when MediaSource reports support', () => {
    const mediaSource = {
      isTypeSupported: vi.fn((type: string) => type.includes('hvc1.1.6.L93.90')),
    } as unknown as typeof MediaSource;

    expect(supportsHevc(mediaSource)).toBe(true);
    expect(playbackCapabilities(mediaSource)).toEqual({
      videoCodecs: ['h264', 'hevc'],
      audioCodecs: ['aac', 'mp3', 'opus', 'flac', 'vorbis'],
    });
  });

  it('reports a shared HLS room as HLS while TS rooms remain MPEG-TS', () => {
    expect(reportedEngine('hls', true, false)).toBe('hls');
    expect(reportedEngine('livets', true, false)).toBe('mpegts');
    expect(reportedEngine('ts', true, false)).toBe('mpegts');
    expect(reportedEngine('hls', true, true)).toBe('remux');
  });
});

describe('HLS audio capability', () => {
  it('keeps an audio track whose exact MSE codec is supported', () => {
    const mediaSource = { isTypeSupported: vi.fn(() => true) };

    expect(hlsAudioNeedsServerNormalization(
      { container: 'audio/mp4', codec: 'mp4a.40.2', levelCodec: 'ec-3' },
      mediaSource,
    )).toBe(false);
    expect(mediaSource.isTypeSupported).toHaveBeenCalledWith(
      'audio/mp4; codecs="mp4a.40.2"',
    );
  });

  it('asks for AAC normalization when MSE rejects the parsed audio codec', () => {
    const mediaSource = { isTypeSupported: vi.fn(() => false) };

    expect(hlsAudioNeedsServerNormalization(
      { container: 'audio/mp4', codec: 'ec-3' },
      mediaSource,
    )).toBe(true);
  });

  it('does not trust an MSE false-positive for audio outside the browser baseline', () => {
    const mediaSource = { isTypeSupported: vi.fn(() => true) };

    expect(hlsAudioNeedsServerNormalization(
      { container: 'audio/mp4', codec: 'ec-3' },
      mediaSource,
    )).toBe(true);
    expect(mediaSource.isTypeSupported).not.toHaveBeenCalled();
  });

  it('does not transcode merely because a provider omitted codec metadata', () => {
    const mediaSource = { isTypeSupported: vi.fn(() => false) };

    expect(hlsAudioNeedsServerNormalization({ container: 'audio/mp4' }, mediaSource)).toBe(false);
    expect(hlsAudioNeedsServerNormalization(null, mediaSource)).toBe(false);
    expect(mediaSource.isTypeSupported).not.toHaveBeenCalled();
  });
});
