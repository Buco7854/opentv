import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, PlaybackLease, RemuxStart } from '../api';
import { ApiError } from '../api/http';
import { toast } from '../components/Primitives';
import { PlaybackStatusActions } from './playbackStatus';
import { useRemuxSession } from './useRemuxSession';

vi.mock('../components/Primitives', () => ({ toast: vi.fn() }));

const lease: PlaybackLease = {
  id: 'lease-1',
  contentId: 'content-1',
  playlistId: 1,
  mediaGrant: 'grant-1',
  mediaGrantExpiresAtMs: Date.now() + 600_000,
  streamUrl: '/api/v1/stream?u=d.token&sid=lease-1&g=grant-1',
  sharedHlsUrl: null,
  relayUrl: null,
  transcodeUrl: null,
  remuxStartUrl: '/api/v1/remux/start?u=d.token&sid=lease-1&g=grant-1',
  downloadFileUrl: null,
};

const started = (overrides: Partial<RemuxStart> = {}): RemuxStart => ({
  id: 'remux-1',
  playlistUrl: '/api/v1/remux/remux-1/main.m3u8?sid=lease-1&g=grant-1',
  duration: 3600,
  audioTracks: ['English'],
  subtitleTracks: [],
  nativeVideoCopy: false,
  audio: 0,
  ...overrides,
});

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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function mountSession(
  video: HTMLVideoElement,
  recoverMediaGrant = vi.fn().mockResolvedValue(true),
) {
  const stable = {
    lease,
    leaseRef: { current: lease },
    contentId: lease.contentId,
    sourceKey: '/api/v1/stream?u=d.token&sid=lease-1',
    catchup: false,
    eligible: true,
    checking: false,
    blocked: false,
    inRoom: false,
    trackMenuOpen: false,
    videoRef: { current: video },
    chosenTracks: { current: { audio: -1, subs: null } },
    actions: actions(),
    recoverMediaGrant,
    onTerminate: vi.fn(),
  };
  return renderHook(() => useRemuxSession(stable));
}

describe('remux session lifecycle', () => {
  beforeEach(() => {
    vi.mocked(toast).mockClear();
    vi.spyOn(api, 'remuxAvailable').mockResolvedValue({ available: true });
    vi.spyOn(api, 'resumeAll').mockResolvedValue([]);
    vi.spyOn(api, 'remuxStop').mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('reads "no extra tracks" from the error code, not from the message', async () => {
    const prepare = vi.spyOn(api, 'remuxStart').mockRejectedValue(
      new ApiError('Rien de plus à exposer', 404, 'no_extra_tracks'),
    );
    const { result } = mountSession(document.createElement('video'));

    await waitFor(() => expect(result.current.state).toBe('none'));
    expect(prepare).toHaveBeenCalledOnce();
    expect(prepare).toHaveBeenCalledWith(lease.remuxStartUrl, 0, false);
    expect(toast).not.toHaveBeenCalled();
  });

  it('surfaces any other prepare failure and retries it', async () => {
    vi.spyOn(api, 'remuxStart').mockRejectedValue(new ApiError('ffmpeg is busy', 503, 'busy'));
    const { result } = mountSession(document.createElement('video'));

    await waitFor(() => expect(result.current.state).toBe('failed'));
    expect(toast).toHaveBeenCalledWith('ffmpeg is busy', { tone: 'error' });
  });

  it('retries a failed prepare from where the element is playing, not from zero', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const video = document.createElement('video');
    const prepare = vi.spyOn(api, 'remuxStart')
      .mockRejectedValueOnce(new ApiError('provider busy', 503, 'busy'))
      .mockResolvedValue(started());
    const { result } = mountSession(video);

    await waitFor(() => expect(result.current.state).toBe('failed'));
    video.currentTime = 615;
    await vi.advanceTimersByTimeAsync(2000);

    await waitFor(() => expect(result.current.session?.startAt).toBe(615));
    expect(prepare).toHaveBeenCalledTimes(2);
  });

  it('keeps the newest track prepare when concurrent responses arrive out of order', async () => {
    const firstSwitch = deferred<RemuxStart>();
    const secondSwitch = deferred<RemuxStart>();
    const prepare = vi.spyOn(api, 'remuxStart')
      .mockResolvedValueOnce(started())
      .mockImplementationOnce(() => firstSwitch.promise)
      .mockImplementationOnce(() => secondSwitch.promise);
    const { result } = mountSession(document.createElement('video'));
    await waitFor(() => expect(result.current.session?.id).toBe('remux-1'));

    act(() => {
      result.current.start(1, 10);
      result.current.start(2, 20);
    });
    await waitFor(() => expect(prepare).toHaveBeenCalledTimes(3));
    await act(async () => secondSwitch.resolve(started({
      id: 'remux-2',
      playlistUrl: '/api/v1/remux/remux-2/main.m3u8',
      audio: 2,
    })));
    await waitFor(() => expect(result.current.session?.id).toBe('remux-2'));

    await act(async () => firstSwitch.resolve(started({
      id: 'remux-stale',
      playlistUrl: '/api/v1/remux/remux-stale/main.m3u8',
      audio: 1,
    })));

    expect(result.current.session?.id).toBe('remux-2');
    expect(result.current.session?.audio).toBe(2);
    expect(api.remuxStop).toHaveBeenCalledWith(
      'remux-stale',
      lease.id,
      lease.mediaGrant,
    );
  });

  it('refreshes an expired grant and retries the remux prepare', async () => {
    const recover = vi.fn().mockResolvedValue(true);
    const prepare = vi.spyOn(api, 'remuxStart')
      .mockRejectedValueOnce(new ApiError('expired', 410, 'playback_revoked'))
      .mockResolvedValueOnce(started());
    const { result } = mountSession(document.createElement('video'), recover);

    await waitFor(() => expect(result.current.session?.id).toBe('remux-1'));

    expect(recover).toHaveBeenCalledOnce();
    expect(prepare).toHaveBeenCalledTimes(2);
  });
});
