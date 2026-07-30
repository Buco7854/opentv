import { StrictMode } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from './api';
import { DeviceLinkScreen } from './DeviceLink';
import { AuthFlow, DeviceLinkStart, DeviceLinkState } from './types';

const acceptFlow = vi.fn();
const { qr } = vi.hoisted(() => ({ qr: vi.fn() }));

vi.mock('./AuthProvider', () => ({ useAuth: () => ({ acceptFlow }) }));
vi.mock('qrcode', () => ({ toDataURL: qr }));
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
  sessionToken: 'session-token',
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

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

function renderStrictScreen() {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={['/login/device']}>
        <Routes>
          <Route path="/" element={<p>signed in</p>} />
          <Route path="/login/device" element={<DeviceLinkScreen />} />
        </Routes>
      </MemoryRouter>
    </StrictMode>,
  );
}

describe('DeviceLinkScreen', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    acceptFlow.mockClear();
    qr.mockReset().mockResolvedValue('data:image/png;base64,AA');
    vi.mocked(authApi.linkStart).mockReset().mockResolvedValue(started());
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

  it('keeps the link-start result across a StrictMode effect replay', async () => {
    vi.mocked(authApi.linkPoll).mockResolvedValue(status('PENDING'));
    const view = renderStrictScreen();

    await until(() => view.container.querySelector('img') !== null);

    expect(view.container.querySelector('img')?.src).toContain('data:image/png');
    expect(authApi.linkStart).toHaveBeenCalledOnce();
  });

  it('offers a new code when the request expires', async () => {
    vi.mocked(authApi.linkPoll).mockResolvedValue(status('EXPIRED'));
    renderScreen();

    await until(() => screen.queryByRole('button', { name: 'Show a new code' }) !== null);
    expect(acceptFlow).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Show a new code' })).toBeTruthy();
  });

  it('reports a refused request without adopting a session', async () => {
    vi.mocked(authApi.linkPoll).mockResolvedValue(status('DENIED'));
    renderScreen();

    await until(showing('Request refused'));

    expect(screen.getByText('The other device turned this sign-in down.')).toBeTruthy();
    expect(acceptFlow).not.toHaveBeenCalled();
  });

  it('does not let an old QR render overwrite a restarted link request', async () => {
    const oldQr = deferred<string>();
    qr
      .mockReturnValueOnce(oldQr.promise)
      .mockResolvedValueOnce('data:image/png;base64,NEW');
    vi.mocked(authApi.linkStart)
      .mockResolvedValueOnce(started())
      .mockResolvedValueOnce({
        ...started(),
        pollToken: 'poll-token-new',
        linkToken: 'link-token-new',
        verificationUriComplete: 'https://tv.example.com/link#t=link-token-new',
      });
    vi.mocked(authApi.linkPoll)
      .mockResolvedValueOnce(status('EXPIRED'))
      .mockResolvedValue(status('PENDING'));
    const view = renderScreen();

    await until(() => screen.queryByRole('button', { name: 'Show a new code' }) !== null);
    fireEvent.click(screen.getByRole('button', { name: 'Show a new code' }));
    await until(() => view.container.querySelector('img')?.src.endsWith('NEW') === true);
    expect(view.container.querySelector('img')?.src).toContain('NEW');

    oldQr.resolve('data:image/png;base64,OLD');
    await vi.advanceTimersByTimeAsync(0);

    expect(view.container.querySelector('img')?.src).toContain('NEW');
  });
});
