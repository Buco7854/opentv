import { act, fireEvent, render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, Session } from '../api';
import { SessionsScreen } from './SessionsScreen';

vi.mock('../api', () => ({
  api: {
    adminPlayback: vi.fn(),
    adminPlaybackCommand: vi.fn(),
    adminPlaybackEnd: vi.fn(),
  },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

const activeSession: Session = {
  id: 'lease-1',
  userId: 'user-1',
  username: 'alice',
  displayName: 'Alice',
  clientKind: 'BROWSER',
  ip: '127.0.0.1',
  userAgent: 'Test browser',
  playlistName: 'Movies',
  title: 'Test movie',
  kind: 'movie',
  logo: null,
  positionMs: 1_000,
  durationMs: 10_000,
  paused: false,
  live: false,
  startedAtMs: 1,
  lastSeenMs: 1,
  stream: {
    engine: 'native',
    direct: true,
    audioTranscoded: false,
    preparing: false,
    remux: null,
  },
  roomId: null,
  roomSize: 0,
};

describe('SessionsScreen polling', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(api.adminPlayback).mockReset();
  });

  it('does not schedule another protected poll after unmount', async () => {
    const request = deferred<[]>();
    vi.mocked(api.adminPlayback).mockReturnValue(request.promise);
    const screen = render(<SessionsScreen />);
    expect(api.adminPlayback).toHaveBeenCalledTimes(1);

    screen.unmount();
    await act(async () => request.resolve([]));
    await vi.advanceTimersByTimeAsync(10_000);

    expect(api.adminPlayback).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });

  it('drops an optimistic pause once the lease it belonged to has ended', async () => {
    vi.mocked(api.adminPlayback).mockResolvedValue([activeSession]);
    vi.mocked(api.adminPlaybackCommand).mockResolvedValue(null);
    const view = render(<SessionsScreen />);
    await act(async () => {});

    fireEvent.click(view.getByRole('button', { name: 'Pause playback' }));
    await act(async () => {});
    expect(view.getByText(/Paused/)).toBeTruthy();

    vi.mocked(api.adminPlayback).mockResolvedValue([]);
    await act(async () => { await vi.advanceTimersByTimeAsync(800); });
    vi.mocked(api.adminPlayback).mockResolvedValue([activeSession]);
    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });

    expect(view.queryByText(/Paused/)).toBeNull();
    view.unmount();
    vi.useRealTimers();
  });

  it('keeps a session visible when force-stop fails', async () => {
    vi.mocked(api.adminPlayback).mockResolvedValue([activeSession]);
    vi.mocked(api.adminPlaybackEnd).mockRejectedValue(new Error('failed'));
    const view = render(<SessionsScreen />);
    await act(async () => {});

    fireEvent.click(view.getByRole('button', { name: 'Stop playback' }));
    await act(async () => {});

    expect(view.getByText('Test movie')).toBeTruthy();
    view.unmount();
    vi.useRealTimers();
  });
});
