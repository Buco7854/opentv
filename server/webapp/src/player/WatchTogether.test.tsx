import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, SessionCommand, WatchIntent } from '../api';
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
      remuxEligible: false,
      sharesRoomRead: true,
      contentId: 'content-1',
      send,
    }));

    await act(async () => {});
    expect(view.result.current.checking).toBe(true);

    act(() => vi.advanceTimersByTime(4_000));
    expect(view.result.current.checking).toBe(false);

    await act(async () => resolveIntent({
      sameContent: [{ id: 'peer-1', name: 'Peer' }],
      full: true,
      limit: 1,
    }));

    expect(view.result.current.blocked).toBe(false);
    expect(view.result.current.choosing).toBe(false);
    expect(view.result.current.peers).toEqual([]);
  });

  it('does not let a stale room-go release a newer barrier', async () => {
    vi.spyOn(api, 'playbackIntent').mockResolvedValue({
      sameContent: [], full: false, limit: 1,
    });
    const element = document.createElement('video');
    vi.spyOn(element, 'pause').mockImplementation(() => {});
    vi.spyOn(element, 'play').mockResolvedValue();
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video: { current: element },
      active: true,
      live: false,
      remuxEligible: true,
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
      sameContent: [{ id: 'peer-1', name: 'Peer' }], full: true, limit: 1,
    });
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video: { current: document.createElement('video') },
      active: true,
      live: true,
      remuxEligible: false,
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
      sameContent: [], full: false, limit: 1,
    });
    const ready = vi.spyOn(api, 'sessionReady').mockResolvedValue(undefined);
    const element = document.createElement('video');
    vi.spyOn(element, 'pause').mockImplementation(() => {});
    const view = renderHook(() => useWatchTogether({
      selfId: 'lease-1',
      video: { current: element },
      active: true,
      live: false,
      remuxEligible: true,
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
});
