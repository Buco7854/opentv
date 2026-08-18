import { StrictMode } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, PlaybackLease } from '../api';
import { ApiError } from '../api/http';
import { PlayerSurface, PlayRequest } from './PlayerProvider';

class SilentSocket {
  static OPEN = 1;
  readyState = 0;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: (() => void) | null = null;
  send() {}
  close() {}
}

const leaseNumbered = (n: number): PlaybackLease => ({
  id: `lease-${n}`,
  contentId: 'content-1',
  playlistId: 1,
  mediaGrant: `grant-${n}`,
  mediaGrantExpiresAtMs: Date.now() + 600_000,
  streamUrl: `/api/v1/stream?u=t.token&sid=lease-${n}&g=grant-${n}`,
  sharedHlsUrl: null,
  relayUrl: null,
  transcodeUrl: null,
  remuxStartUrl: `/api/v1/remux/start?u=t.token&sid=lease-${n}&g=grant-${n}`,
  downloadFileUrl: null,
});

const request: PlayRequest = { contentId: 'content-1', title: 'Channel One', live: true };

const endedLeases = () => vi.mocked(api.playbackEnd).mock.calls.map(([id]) => id);

const providerCheckNeverAnswers = new Promise<never>(() => {});

describe('playback lease ownership', () => {
  beforeEach(() => {
    vi.stubGlobal('WebSocket', SilentSocket);
    let issued = 0;
    vi.spyOn(api, 'createPlayback').mockImplementation(async () => leaseNumbered(++issued));
    vi.spyOn(api, 'playbackEnd').mockResolvedValue(undefined);
    vi.spyOn(api, 'playbackHeartbeat').mockResolvedValue({ commands: [] });
    vi.spyOn(api, 'remuxAvailable').mockResolvedValue({ available: false });
    vi.spyOn(api, 'resumeAll').mockResolvedValue([]);
    vi.spyOn(api, 'playbackIntent').mockReturnValue(providerCheckNeverAnswers);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('ends the lease once per playback, not on a StrictMode remount', async () => {
    const view = render(
      <StrictMode>
        <PlayerSurface request={request} onClose={vi.fn()} onPlayCatchup={vi.fn()} />
      </StrictMode>,
    );

    await waitFor(() => expect(api.createPlayback).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(api.playbackHeartbeat).toHaveBeenCalled());
    expect(api.createPlayback).toHaveBeenLastCalledWith(expect.objectContaining({
      capabilities: {
        videoCodecs: ['h264'],
        audioCodecs: ['aac', 'mp3', 'opus', 'flac', 'vorbis'],
      },
    }));
    expect(endedLeases()).toEqual(['lease-1']);

    view.unmount();

    expect(endedLeases()).toEqual(['lease-1', 'lease-2']);
  });

  it('announces lease acquisition instead of exposing a silent empty player', () => {
    vi.mocked(api.createPlayback).mockReturnValue(new Promise(() => {}));

    render(<PlayerSurface request={request} onClose={vi.fn()} onPlayCatchup={vi.fn()} />);

    expect(screen.getByRole('status', { name: 'Working…' })).toBeTruthy();
  });

  it('retries a grant rotation the transport refused instead of ending playback', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(api.createPlayback).mockImplementation(async () => ({
      ...leaseNumbered(1), mediaGrantExpiresAtMs: Date.now() + 70_000,
    }));
    const rotate = vi.spyOn(api, 'refreshMediaGrant')
      .mockRejectedValueOnce(new ApiError('Network error', 0, 'network'))
      .mockResolvedValue({ token: 'grant-next', expiresAtMs: Date.now() + 600_000 });
    const { container } = render(
      <PlayerSurface request={request} onClose={vi.fn()} onPlayCatchup={vi.fn()} />,
    );

    await waitFor(() => expect(api.playbackHeartbeat).toHaveBeenCalled());
    await act(() => vi.advanceTimersByTimeAsync(11_000));
    expect(rotate).toHaveBeenCalledOnce();

    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(rotate).toHaveBeenCalledTimes(2);
    expect(container.textContent).not.toContain('Network error');
  });

  it('rotates an already-expired media grant without another ten-second dead window', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(api.createPlayback).mockImplementation(async () => ({
      ...leaseNumbered(1), mediaGrantExpiresAtMs: Date.now() - 1,
    }));
    const rotate = vi.spyOn(api, 'refreshMediaGrant').mockResolvedValue({
      token: 'grant-next',
      expiresAtMs: Date.now() + 600_000,
    });
    render(<PlayerSurface request={request} onClose={vi.fn()} onPlayCatchup={vi.fn()} />);

    await waitFor(() => expect(api.playbackHeartbeat).toHaveBeenCalled());
    await act(() => vi.advanceTimersByTimeAsync(1));

    expect(rotate).toHaveBeenCalledOnce();
  });
});
