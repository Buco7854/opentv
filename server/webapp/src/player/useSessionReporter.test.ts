import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, SessionCommand } from '../api';
import { PlaybackSnapshot, useSessionReporter } from './useSessionReporter';

class FakeSocket {
  static readonly OPEN = 1;
  static instances: FakeSocket[] = [];

  readonly send = vi.fn();
  readonly close = vi.fn(() => { this.readyState = 3; });
  readyState = FakeSocket.OPEN;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(readonly url: string) {
    FakeSocket.instances.push(this);
  }

  deliver(command: SessionCommand) {
    this.onmessage?.({ data: JSON.stringify(command) } as MessageEvent);
  }

  disconnect() {
    this.readyState = 3;
    this.onclose?.({ code: 1006, reason: '' } as CloseEvent);
  }
}

const snapshot: PlaybackSnapshot = {
  title: 'Movie',
  kind: 'movie',
  logo: null,
  live: false,
  durationSec: 3600,
  engine: 'remux',
  direct: true,
  audioTranscoded: false,
  preparing: false,
  remuxId: 'remux-1',
};

const command = (sequence: number, type: SessionCommand['type'] = 'message'): SessionCommand => ({
  type,
  sequence,
  text: type === 'message' ? `message-${sequence}` : null,
  peerId: null,
  peerName: null,
  requestId: null,
  accepted: null,
  quiet: false,
  sync: null,
  members: null,
  audioIndex: null,
  generation: null,
});

describe('session reporter delivery order', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    FakeSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeSocket);
    vi.spyOn(api, 'playbackSocketUrl')
      .mockImplementation(async (id) => `ws://localhost/api/v1/playback/${id}/ws?ws_token=short`);
    vi.spyOn(api, 'playbackHeartbeat').mockResolvedValue({ commands: [] });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('keeps the high-water mark across reconnect and resets it for a replacement lease', async () => {
    const video = document.createElement('video');
    const onCommand = vi.fn();
    const view = renderHook(
      ({ leaseId }) => useSessionReporter(
        leaseId,
        snapshot,
        { current: video },
        onCommand,
      ),
      { initialProps: { leaseId: 'lease-1' } },
    );
    await vi.waitFor(() => expect(FakeSocket.instances).toHaveLength(1));

    FakeSocket.instances[0]!.deliver(command(5));
    expect(onCommand).toHaveBeenLastCalledWith(expect.objectContaining({ sequence: 5 }));

    FakeSocket.instances[0]!.disconnect();
    await act(() => vi.advanceTimersByTimeAsync(3000));
    await vi.waitFor(() => expect(FakeSocket.instances).toHaveLength(2));
    FakeSocket.instances[1]!.deliver(command(4));
    expect(onCommand).toHaveBeenCalledTimes(1);
    FakeSocket.instances[1]!.deliver(command(6));
    expect(onCommand).toHaveBeenCalledTimes(2);

    view.rerender({ leaseId: 'lease-2' });
    await vi.waitFor(() => expect(FakeSocket.instances).toHaveLength(3));
    FakeSocket.instances[2]!.deliver(command(1));

    expect(onCommand).toHaveBeenCalledTimes(3);
    expect(onCommand).toHaveBeenLastCalledWith(expect.objectContaining({ sequence: 1 }));
  });

  it('does not let a late error from an old socket close its replacement', async () => {
    const video = document.createElement('video');
    renderHook(() => useSessionReporter('lease-1', snapshot, { current: video }));
    await vi.waitFor(() => expect(FakeSocket.instances).toHaveLength(1));
    const old = FakeSocket.instances[0]!;

    old.disconnect();
    await act(() => vi.advanceTimersByTimeAsync(3000));
    await vi.waitFor(() => expect(FakeSocket.instances).toHaveLength(2));
    const replacement = FakeSocket.instances[1]!;

    old.onerror?.();

    expect(replacement.close).not.toHaveBeenCalled();
  });
});
