import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, PlaybackLease, ResumePoint } from '../api';
import { PlaybackStatusActions } from './playbackStatus';
import { usePlaybackEngine } from './usePlaybackEngine';
import { RemuxController, RemuxSession } from './useRemuxSession';

type ErrorData = { fatal: boolean; type: string; details?: string; response?: { code: number } };
type Handler = (event: string, data: ErrorData) => void;

const { FakeHls, FakeMpegtsPlayer, createMpegtsPlayer } = vi.hoisted(() => {
  class FakeHls {
    static isSupported = () => true;
    static Events = {
      ERROR: 'hlsError',
      MANIFEST_PARSED: 'hlsManifestParsed',
      FRAG_BUFFERED: 'hlsFragBuffered',
      AUDIO_TRACKS_UPDATED: 'hlsAudioTracksUpdated',
      SUBTITLE_TRACKS_UPDATED: 'hlsSubtitleTracksUpdated',
      AUDIO_TRACK_SWITCHED: 'hlsAudioTrackSwitched',
      SUBTITLE_TRACK_SWITCH: 'hlsSubtitleTrackSwitch',
    };
    static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
    static last: FakeHls | null = null;

    audioTracks: unknown[] = [];
    subtitleTracks: unknown[] = [];
    audioTrack = -1;
    subtitleTrack = -1;
    subtitleDisplay = true;
    loadSource = vi.fn();
    attachMedia = vi.fn();
    startLoad = vi.fn();
    stopLoad = vi.fn();
    recoverMediaError = vi.fn();
    swapAudioCodec = vi.fn();
    destroy = vi.fn();
    private handlers = new Map<string, ((event: string, data: ErrorData) => void)[]>();

    constructor() { FakeHls.last = this; }

    on(event: string, handler: Handler) {
      this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
    }

    off(event: string, handler: Handler) {
      this.handlers.set(event, (this.handlers.get(event) ?? []).filter((h) => h !== handler));
    }

    emitError(data: ErrorData) {
      this.handlers.get(FakeHls.Events.ERROR)?.forEach((handler) => handler(FakeHls.Events.ERROR, data));
    }
  }
  class FakeMpegtsPlayer {
    attachMediaElement = vi.fn();
    load = vi.fn();
    unload = vi.fn();
    play = vi.fn(() => Promise.resolve());
    destroy = vi.fn();
    private handlers = new Map<string, ((type: string, detail: string, info: unknown) => void)[]>();

    on(event: string, handler: (type: string, detail: string, info: unknown) => void) {
      this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
    }

    emitError(type: string, info: unknown = {}) {
      this.handlers.get('error')?.forEach((handler) => handler(type, '', info));
    }
  }
  const createMpegtsPlayer = vi.fn(() => new FakeMpegtsPlayer());
  return { FakeHls, FakeMpegtsPlayer, createMpegtsPlayer };
});

vi.mock('hls.js', () => ({ default: FakeHls }));
vi.mock('mpegts.js', () => ({
  default: {
    getFeatureList: () => ({ mseLivePlayback: true }),
    createPlayer: createMpegtsPlayer,
    Events: { ERROR: 'error' },
    ErrorTypes: { NETWORK_ERROR: 'NetworkError', MEDIA_ERROR: 'MediaError' },
  },
}));

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

const session: RemuxSession = {
  id: 'remux-1',
  playlistUrl: '/api/v1/remux/remux-1/main.m3u8?sid=lease-1&g=grant-1',
  duration: 3600,
  startAt: 0,
  nativeCopy: false,
  audio: 2,
};

function mountEngine(
  remuxed: RemuxSession | null = session,
  engineLease: PlaybackLease = lease,
  roomLive = false,
) {
  const video = document.createElement('video');
  const start = vi.fn();
  const remux: RemuxController = {
    session: remuxed,
    state: 'idle',
    available: true,
    ref: { current: remuxed },
    availableRef: { current: true },
    start,
    markPlaying: vi.fn(),
    markDied: vi.fn(),
  };
  const actions: PlaybackStatusActions = {
    setError: vi.fn(),
    setPaused: vi.fn(),
    setBuffering: vi.fn(),
    setBufferedEnd: vi.fn(),
    setAudioTranscoded: vi.fn(),
    setTime: vi.fn(),
    setTracks: vi.fn(),
    setCueText: vi.fn(),
  };
  const recoverMediaGrant = vi.fn().mockResolvedValue(true);
  const onTerminate = vi.fn();
  const opts = {
    lease: engineLease,
    leaseRef: { current: engineLease },
    live: roomLive,
    catchup: false,
    roomLive,
    hold: false,
    contentId: engineLease.contentId,
    downloadId: null,
    activeUrl: remuxed ? remuxed.playlistUrl : engineLease.streamUrl!,
    activeSourceKey: '/api/v1/remux/remux-1/main.m3u8?sid=lease-1',
    sourceKey: '/api/v1/stream?u=d.token&sid=lease-1',
    videoRef: { current: video },
    remux,
    chosenTracks: { current: { audio: -1, subs: null } },
    actions,
    recoverMediaGrant,
    onTerminate,
  };
  const view = renderHook(() => usePlaybackEngine(opts));
  return {
    ...view,
    video,
    actions,
    start,
    recoverMediaGrant,
    onTerminate,
    hls: FakeHls.last as InstanceType<typeof FakeHls>,
  };
}

describe('hls playback engine', () => {
  beforeEach(() => {
    FakeHls.last = null;
    createMpegtsPlayer.mockClear();
    vi.spyOn(api, 'resumeAll').mockResolvedValue([]);
    vi.spyOn(api, 'saveResume').mockResolvedValue(null);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('re-requests an evicted remux session instead of failing playback', () => {
    const { hls, video, start, actions } = mountEngine();
    video.currentTime = 942;

    hls.emitError({ fatal: true, type: FakeHls.ErrorTypes.NETWORK_ERROR, response: { code: 404 } });

    expect(start).toHaveBeenCalledWith(session.audio, 942);
    expect(vi.mocked(actions.setError).mock.calls).toEqual([[null]]);
  });

  it('drops every pending network retry when the engine is torn down', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { hls, unmount } = mountEngine();

    const failure = { fatal: true, type: FakeHls.ErrorTypes.NETWORK_ERROR, response: { code: 500 } };
    hls.emitError(failure);
    hls.emitError(failure);
    unmount();
    await vi.advanceTimersByTimeAsync(5000);

    expect(hls.destroy).toHaveBeenCalled();
    expect(hls.startLoad).not.toHaveBeenCalled();
  });

  it('refreshes an expired media grant instead of treating the lease as gone', () => {
    const { hls, recoverMediaGrant, onTerminate } = mountEngine();

    hls.emitError({
      fatal: true,
      type: FakeHls.ErrorTypes.NETWORK_ERROR,
      response: { code: 410 },
    });

    expect(recoverMediaGrant).toHaveBeenCalledOnce();
    expect(onTerminate).not.toHaveBeenCalled();
  });

  it('destroys hls.js after its fatal network recovery budget is exhausted', () => {
    const { hls, actions } = mountEngine();
    const failure = {
      fatal: true,
      type: FakeHls.ErrorTypes.NETWORK_ERROR,
      response: { code: 500 },
    };

    for (let attempt = 0; attempt < 5; attempt += 1) hls.emitError(failure);

    expect(hls.destroy).toHaveBeenCalledOnce();
    expect(actions.setError).toHaveBeenLastCalledWith(expect.stringContaining('Playback failed'));
  });

  it('keeps HLS direct and loads the advertised shared room path', () => {
    const hlsLease = {
      ...lease,
      streamUrl: '/api/v1/stream?u=h.token&sid=lease-1&g=grant-1',
      sharedHlsUrl: '/api/v1/shared-hls?u=h.token&sid=lease-1&g=grant-1',
    };

    const { hls } = mountEngine(null, hlsLease, true);

    expect(hls.loadSource).toHaveBeenCalledWith(hlsLease.sharedHlsUrl);
    expect(createMpegtsPlayer).not.toHaveBeenCalled();
  });

  it('destroys mpegts.js after its fatal network recovery budget is exhausted', () => {
    const tsLease = {
      ...lease,
      streamUrl: '/api/v1/stream?u=t.token&sid=lease-1&g=grant-1',
      transcodeUrl: '/api/v1/transcode?u=t.token&sid=lease-1&g=grant-1',
    };
    const { actions } = mountEngine(null, tsLease);
    const player = createMpegtsPlayer.mock.results[0]?.value as InstanceType<typeof FakeMpegtsPlayer>;

    for (let attempt = 0; attempt < 4; attempt += 1) {
      player.emitError('NetworkError', { status: 500 });
    }

    expect(player.destroy).toHaveBeenCalledOnce();
    expect(actions.setError).toHaveBeenLastCalledWith(expect.stringContaining('Playback failed'));
  });

  it('never seeks a torn-down source to a resume point that arrived late', async () => {
    let resolveResume: (points: ResumePoint[]) => void = () => {};
    vi.spyOn(api, 'resumeAll').mockReturnValue(new Promise((resolve) => { resolveResume = resolve; }));
    const { video, unmount } = mountEngine(null);

    unmount();
    resolveResume([{
      contentId: lease.contentId, positionMs: 600_000, durationMs: 3_600_000, updatedMs: Date.now(),
    }]);
    await vi.waitFor(() => expect(api.resumeAll).toHaveBeenCalled());
    video.dispatchEvent(new Event('loadedmetadata'));

    expect(video.currentTime).toBe(0);
  });

  it('does not resurrect a destroyed mpegts engine from a queued error callback', () => {
    const tsLease = {
      ...lease,
      streamUrl: '/api/v1/stream?u=t.token&sid=lease-1&g=grant-1',
      transcodeUrl: '/api/v1/transcode?u=t.token&sid=lease-1&g=grant-1',
    };
    const { unmount } = mountEngine(null, tsLease);
    const player = createMpegtsPlayer.mock.results[0]?.value as InstanceType<typeof FakeMpegtsPlayer>;
    expect(createMpegtsPlayer).toHaveBeenCalledOnce();

    unmount();
    player.emitError('MediaError');

    expect(createMpegtsPlayer).toHaveBeenCalledOnce();
  });
});
