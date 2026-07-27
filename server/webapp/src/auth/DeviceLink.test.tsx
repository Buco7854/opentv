import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from './api';
import { DeviceLinkScreen } from './DeviceLink';
import { AuthFlow, DeviceLinkStart, DeviceLinkState } from './types';

const acceptFlow = vi.fn();

vi.mock('./AuthProvider', () => ({ useAuth: () => ({ acceptFlow }) }));
vi.mock('qrcode', () => ({ toDataURL: () => Promise.resolve('data:image/png;base64,AA') }));
vi.mock('./api', () => ({ authApi: { linkStart: vi.fn(), linkPoll: vi.fn() } }));

const started = (): DeviceLinkStart => ({
  pollToken: 'poll-token',
  linkToken: 'link-token',
  verificationUriComplete: 'https://tv.example.com/link#t=link-token',
  expiresAtMs: Date.now() + 300_000,
  intervalMs: 1000,
});

const authenticated = (): AuthFlow => ({
  status: 'AUTHENTICATED',
  code: null,
  challenge: null,
  methods: [],
  expiresAtMs: null,
  user: null,
  csrfToken: 'csrf',
  recoveryCodes: [],
});

const preview = { displayName: 'Alex Moreau', username: 'admin' };

const status = (state: DeviceLinkState, flow: AuthFlow | null = null) => ({
  status: state,
  preview: state === 'PENDING' ? null : preview,
  flow,
  intervalMs: 1000,
  expiresAtMs: Date.now() + 300_000,
});

/** Requests resolve off promises, so step the clock until the screen catches up. */
const until = async (ready: () => boolean, steps = 40) => {
  for (let step = 0; step < steps && !ready(); step += 1) {
    await vi.advanceTimersByTimeAsync(250);
  }
};

const showing = (text: string) => () => screen.queryByText(text) !== null;

function renderScreen() {
  return render(
    <MemoryRouter initialEntries={['/login/device']}>
      <Routes>
        <Route path="/" element={<p>signed in</p>} />
        <Route path="/login/device" element={<DeviceLinkScreen />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('DeviceLinkScreen', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    acceptFlow.mockClear();
    vi.mocked(authApi.linkStart).mockResolvedValue(started());
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.mocked(authApi.linkPoll).mockReset();
  });

  it('names the account that scanned, then adopts the session and stops polling', async () => {
    vi.mocked(authApi.linkPoll)
      .mockResolvedValueOnce(status('SCANNED'))
      .mockResolvedValueOnce(status('APPROVED', authenticated()));
    renderScreen();

    await until(showing('Alex Moreau'));
    expect(screen.getByText('@admin')).toBeTruthy();
    expect(acceptFlow).not.toHaveBeenCalled();

    await until(showing('signed in'));
    expect(acceptFlow).toHaveBeenCalledTimes(1);

    const calls = vi.mocked(authApi.linkPoll).mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(vi.mocked(authApi.linkPoll).mock.calls.length).toBe(calls);
  });

  it('never names an account while the request is untouched', async () => {
    vi.mocked(authApi.linkPoll).mockResolvedValue(status('PENDING'));
    renderScreen();

    await until(() => vi.mocked(authApi.linkPoll).mock.calls.length > 1);
    expect(screen.queryByText('Alex Moreau')).toBeNull();
    expect(document.body.textContent).toContain('Waiting for approval');
  });

  it('offers a new code when the request expires', async () => {
    vi.mocked(authApi.linkPoll).mockResolvedValue(status('EXPIRED'));
    renderScreen();

    await until(() => screen.queryByRole('button', { name: 'Show a new code' }) !== null);
    expect(acceptFlow).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Show a new code' })).toBeTruthy();
  });
});
