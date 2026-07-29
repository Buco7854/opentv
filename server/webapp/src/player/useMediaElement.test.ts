import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PlaybackStatusActions } from './playbackStatus';
import { useMediaElement } from './useMediaElement';

const actions = (): PlaybackStatusActions => ({
  setError: vi.fn(),
  setPaused: vi.fn(),
  setBuffering: vi.fn(),
  setBufferedEnd: vi.fn(),
  setAudioTranscoded: vi.fn(),
  setTime: vi.fn(),
  setTracks: vi.fn(),
  setCueText: vi.fn(),
});

describe('native media tracks', () => {
  it('reports a hidden subtitle track as selected because the app renders its cues', () => {
    const video = document.createElement('video');
    const track = {
      kind: 'subtitles',
      label: 'English',
      language: 'en',
      mode: 'hidden',
      cues: null,
    };
    Object.defineProperty(video, 'textTracks', {
      configurable: true,
      value: Object.assign([track], {
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }),
    });
    const status = actions();
    const view = renderHook(() => useMediaElement({
      videoRef: { current: video },
      hlsRef: { current: null },
      mpegtsRef: { current: null },
      remuxRef: { current: null },
      activeUrl: '/movie.mp4',
      actions: status,
      onEnded: vi.fn(),
      onPlaying: vi.fn(),
      onRemuxDied: vi.fn(),
      onNativeError: vi.fn(),
    }));

    video.dispatchEvent(new Event('loadedmetadata'));

    expect(status.setTracks).toHaveBeenLastCalledWith(expect.objectContaining({
      subs: { names: ['English'], current: 0 },
    }));
    view.unmount();
  });
});
