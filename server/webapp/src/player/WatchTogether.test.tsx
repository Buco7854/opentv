import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, SessionCommand, WatchIntent } from '../api';
import type { SessionCommandInput } from '../api';
import * as errors from '../errors';
import { useWatchTogether } from './WatchTogether';

const command = (overrides: Partial<SessionCommand>): SessionCommand => ({
  type: 'room-audio',
  sequence: 1,
  text: null,
  peerId: null,
  peerName: null,
  requestId: null,
  accepted: null,
  quiet: false,
  sync: null,
  members: null,
  audioIndex: 0,
  generation: 1,
  ...overrides,
});

describe('watch-together capacity preflight', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('ignores an intent response that arrives after fail-open released playback', async () => {
    vi.useFakeTimers();
    let resolveIntent!: (intent: WatchIntent) => void;
    vi.spyOn(api, 'playbackIntent').mockReturnValue(
      new Promise((resolve) => { resolveIntent = resolve; }),
    );
    const video = { current: document.createElement('video') };
    const send = { current: null };
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video,
      active: true,
      live: true,
      sharesRoomRead: true,
      contentId: 'content-1',
      send,
    }));

    await act(async () => {});
    expect(view.result.current.checking).toBe(true);

    act(() => vi.advanceTimersByTime(4_000));
    expect(view.result.current.checking).toBe(false);

    await act(async () => resolveIntent({
      sameContent: [{ id: 'peer-1', name: 'Peer', sameAccount: true }],
      full: true,
      limit: 1,
      requiresJoin: true,
    }));

    expect(view.result.current.blocked).toBe(false);
    expect(view.result.current.choosing).toBe(false);
    expect(view.result.current.peers).toEqual([]);
  });

  it('does not let a stale room-go release a newer barrier', async () => {
    vi.spyOn(api, 'playbackIntent').mockResolvedValue({
      sameContent: [], full: false, limit: 1, requiresJoin: false,
    });
    const element = document.createElement('video');
    vi.spyOn(element, 'pause').mockImplementation(() => {});
    vi.spyOn(element, 'play').mockResolvedValue();
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video: { current: element },
      active: true,
      live: false,
      sharesRoomRead: true,
      contentId: 'content-1',
      send: { current: null },
      onRoomAudio: vi.fn(),
    }));
    await act(async () => {});

    act(() => view.result.current.onCommand(command({ generation: 8, sequence: 8 })));
    expect(view.result.current.loading).toBe(true);
    act(() => view.result.current.onCommand(command({
      type: 'room-go', generation: 7, sequence: 9, audioIndex: null,
    })));
    expect(view.result.current.loading).toBe(true);
    act(() => view.result.current.onCommand(command({
      type: 'room-go', generation: 8, sequence: 10, audioIndex: null,
    })));
    expect(view.result.current.loading).toBe(false);
  });

  it('does not clear provider-full when the lease has no real shared room transport', async () => {
    vi.spyOn(api, 'playbackIntent').mockResolvedValue({
      sameContent: [{ id: 'peer-1', name: 'Peer', sameAccount: false }],
      full: true,
      limit: 1,
      requiresJoin: false,
    });
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video: { current: document.createElement('video') },
      active: true,
      live: true,
      sharesRoomRead: false,
      contentId: 'content-1',
      send: { current: null },
    }));
    await act(async () => {});
    expect(view.result.current.blocked).toBe(true);

    act(() => view.result.current.onCommand(command({
      type: 'room-state',
      members: [
        { id: 'lease-1', name: 'Me', host: false, controller: false },
        { id: 'peer-1', name: 'Peer', host: true, controller: true },
      ],
    })));

    expect(view.result.current.inRoom).toBe(true);
    expect(view.result.current.blocked).toBe(true);
  });

  it('reports ready only for the current barrier generation', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.spyOn(api, 'playbackIntent').mockResolvedValue({
      sameContent: [], full: false, limit: 1, requiresJoin: false,
    });
    const ready = vi.spyOn(api, 'sessionReady').mockResolvedValue(undefined);
    const element = document.createElement('video');
    vi.spyOn(element, 'pause').mockImplementation(() => {});
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video: { current: element },
      active: true,
      live: false,
      sharesRoomRead: true,
      contentId: 'content-1',
      send: { current: null },
      onRoomAudio: vi.fn(),
    }));
    await act(async () => {});

    act(() => view.result.current.onCommand(command({ generation: 8, sequence: 8 })));
    await act(() => vi.advanceTimersByTimeAsync(1000));
    act(() => view.result.current.onCommand(command({ generation: 9, sequence: 9 })));
    await act(() => vi.advanceTimersByTimeAsync(4000));

    expect(ready).toHaveBeenCalledTimes(1);
    expect(ready).toHaveBeenCalledWith('lease-1', 9);
  });

  it('does not let host handoff echo an in-flight seek from a stale position', async () => {
    vi.useFakeTimers();
    vi.spyOn(api, 'playbackIntent').mockResolvedValue({
      sameContent: [], full: false, limit: 1, requiresJoin: false,
    });
    const element = document.createElement('video');
    element.currentTime = 10;
    Object.defineProperty(element, 'readyState', { configurable: true, value: 4 });
    vi.spyOn(element, 'play').mockResolvedValue();
    vi.spyOn(element, 'pause').mockImplementation(() => {});
    const sendFrame = vi.fn((_frame: SessionCommandInput) => true);
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video: { current: element },
      active: true,
      live: false,
      sharesRoomRead: true,
      contentId: 'content-1',
      send: { current: sendFrame },
    }));
    await act(async () => {});
    act(() => view.result.current.onCommand(command({
      type: 'room-state',
      members: [
        { id: 'old-host', name: 'Host', host: true, controller: true },
        { id: 'lease-1', name: 'Me', host: false, controller: true },
        { id: 'peer-2', name: 'Peer', host: false, controller: true },
      ],
    })));
    act(() => view.result.current.onCommand(command({
      type: 'sync',
      sync: { positionMs: 60_000, paused: false, rate: 1, seek: true },
    })));
    // A real player can still report its pre-seek position while the seek is landing.
    element.currentTime = 10;
    sendFrame.mockClear();

    act(() => view.result.current.onCommand(command({
      type: 'room-state',
      members: [
        { id: 'lease-1', name: 'Me', host: true, controller: true },
        { id: 'peer-2', name: 'Peer', host: false, controller: true },
      ],
    })));

    expect(sendFrame).not.toHaveBeenCalled();

    // A simultaneous join grows the promoted host's roster. That still must not turn its
    // pre-seek position into a new deliberate seek while the old command is landing.
    act(() => view.result.current.onCommand(command({
      type: 'room-state',
      members: [
        { id: 'lease-1', name: 'Me', host: true, controller: true },
        { id: 'peer-2', name: 'Peer', host: false, controller: true },
        { id: 'peer-3', name: 'New peer', host: false, controller: false },
      ],
    })));

    expect(sendFrame).not.toHaveBeenCalled();

    element.currentTime = 60;
    await act(() => vi.advanceTimersByTimeAsync(2000));
    expect(sendFrame).toHaveBeenCalledTimes(1);
    const [firstFrame] = sendFrame.mock.calls[0] ?? [];
    expect(firstFrame?.sync).toMatchObject({ positionMs: 60_000, seek: true });
  });

  it('keeps a deliberately paused room paused when a reload barrier finishes', async () => {
    vi.spyOn(api, 'playbackIntent').mockResolvedValue({
      sameContent: [], full: false, limit: 1, requiresJoin: false,
    });
    let paused = true;
    const element = document.createElement('video');
    Object.defineProperty(element, 'paused', { configurable: true, get: () => paused });
    const play = vi.spyOn(element, 'play').mockImplementation(async () => { paused = false; });
    vi.spyOn(element, 'pause').mockImplementation(() => { paused = true; });
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video: { current: element },
      active: true,
      live: false,
      sharesRoomRead: true,
      contentId: 'content-1',
      send: { current: null },
      onRoomAudio: vi.fn(),
    }));
    await act(async () => {});

    act(() => view.result.current.onCommand(command({ generation: 4, sequence: 4 })));
    act(() => view.result.current.onCommand(command({
      type: 'room-go', generation: 4, sequence: 5, audioIndex: null,
    })));

    expect(view.result.current.loading).toBe(false);
    expect(paused).toBe(true);
    expect(play).not.toHaveBeenCalled();
  });

  it('does not report a failed own-device request after another request already joined it', async () => {
    vi.spyOn(api, 'playbackIntent').mockResolvedValue({
      sameContent: [{ id: 'own-phone', name: 'Phone', sameAccount: true }],
      full: false,
      limit: 2,
      requiresJoin: true,
    });
    let rejectJoin!: (cause: unknown) => void;
    vi.spyOn(api, 'joinRequest').mockReturnValue(new Promise((_, reject) => { rejectJoin = reject; }));
    const reported = vi.spyOn(errors, 'reportError');
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-3',
      video: { current: document.createElement('video') },
      active: true,
      live: false,
      sharesRoomRead: true,
      contentId: 'content-1',
      send: { current: null },
    }));
    await act(async () => {});

    act(() => view.result.current.ask('own-phone'));
    act(() => view.result.current.onCommand(command({
      type: 'room-state',
      members: [
        { id: 'own-phone', name: 'Me', host: true, controller: true },
        { id: 'lease-3', name: 'Me', host: false, controller: true },
      ],
    })));
    await act(async () => rejectJoin(new ApiError('Already joined', 404, 'not_found')));

    expect(view.result.current.inRoom).toBe(true);
    expect(reported).not.toHaveBeenCalled();
  });

  it('turns declining a required own-device join into the typed refusal', async () => {
    vi.spyOn(api, 'playbackIntent').mockResolvedValue({
      sameContent: [
        { id: 'friend', name: 'Friend', sameAccount: false },
        { id: 'own-tv', name: 'My television', sameAccount: true },
      ],
      full: false,
      limit: 2,
      requiresJoin: true,
    });
    const refuse = vi.spyOn(api, 'watchAlone').mockRejectedValue(
      new ApiError(
        'This account is already playing this content on another device',
        409,
        'same_content_already_playing',
      ),
    );
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-2',
      video: { current: document.createElement('video') },
      active: true,
      live: false,
      sharesRoomRead: true,
      contentId: 'content-1',
      send: { current: null },
    }));
    await act(async () => {});

    expect(view.result.current.choosing).toBe(true);
    expect(view.result.current.requiresJoin).toBe(true);
    expect(view.result.current.peers.map((peer) => peer.id)).toEqual(['own-tv']);
    await act(async () => view.result.current.watchAlone());

    expect(refuse).toHaveBeenCalledWith('lease-2');
    expect(view.result.current.choosing).toBe(false);
    expect(view.result.current.refusal).toContain('another device');
  });
});
