import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCESS_TOKEN_STORAGE_KEY, ApiError, TRANSPORT_STATUS } from '../api/http';
import { authApi } from './api';
import {
  AuthProvider, AuthReturnHandler, RequireAuth, useAuth,
} from './AuthProvider';
import { AuthCapabilities, CurrentUser } from './types';

vi.mock('./api', () => ({
  authApi: { capabilities: vi.fn(), me: vi.fn() },
}));

const capabilities = (overrides: Partial<AuthCapabilities> = {}): AuthCapabilities => ({
  passwordEnabled: true,
  oidcEnabled: false,
  bootstrapRequired: false,
  webAuthnRpId: 'localhost',
  oidcStartUrl: null,
  passkeyLoginEnabled: false,
  deviceLinkEnabled: false,
  ...overrides,
});

const signedOut = () => Object.assign(new ApiError('Unauthorized', 401), { status: 401 });

const offline = () => new ApiError('offline', TRANSPORT_STATUS, 'network');

const currentUser = (overrides: Partial<CurrentUser> = {}): CurrentUser => ({
  id: 'user-1',
  username: 'alex',
  displayName: 'Alex Moreau',
  role: 'USER',
  authMethod: 'PASSWORD',
  hasPassword: true,
  authSessionId: 'session-1',
  clientKind: 'BROWSER',
  playlistIds: [],
  ...overrides,
});

function Probe() {
  const { refresh, user } = useAuth();
  return (
    <button onClick={() => void refresh()}>{user?.username ?? 'nobody'}</button>
  );
}

function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/downloads']}>
      <AuthProvider>
        <AuthReturnHandler />
        <Routes>
          <Route path="/login" element={<p>login screen</p>} />
          <Route path="/setup" element={<p>setup screen</p>} />
          <Route path="*" element={<RequireAuth><Probe /></RequireAuth>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('RequireAuth', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState(null, '', '/');
    vi.mocked(authApi.me).mockReset().mockRejectedValue(signedOut());
  });

  it('consumes an OIDC fragment and persists its bearer token before refreshing the user', async () => {
    const handoff = 'expected-handoff-correlation-value';
    sessionStorage.setItem(
      'auth.oidcPendingAt',
      JSON.stringify({ handoff, startedAt: Date.now() }),
    );
    window.history.replaceState(null, '', `/#session=returned-token&handoff=${handoff}`);
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me).mockResolvedValue(currentUser());

    renderApp();

    expect(await screen.findByRole('button', { name: 'alex' })).toBeTruthy();
    expect(localStorage.getItem('opentv.accessToken')).toBe('returned-token');
    expect(window.location.hash).toBe('');
  });

  it('rejects an unsolicited session fragment instead of fixing the browser to another account', async () => {
    window.history.replaceState(null, '', '/#session=attacker-session');
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me).mockImplementation(() => (
      localStorage.getItem('opentv.accessToken')
        ? Promise.resolve(currentUser())
        : Promise.reject(signedOut())
    ));

    renderApp();

    expect(await screen.findByText('login screen')).toBeTruthy();
    expect(localStorage.getItem('opentv.accessToken')).toBeNull();
    expect(window.location.hash).toBe('');
  });

  it('rejects a session fragment that does not match the OIDC flow started by this tab', async () => {
    sessionStorage.setItem(
      'auth.oidcPendingAt',
      JSON.stringify({ handoff: 'expected-handoff', startedAt: Date.now() }),
    );
    window.history.replaceState(
      null,
      '',
      '/#session=attacker-session&handoff=attacker-handoff',
    );
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me).mockImplementation(() => (
      localStorage.getItem('opentv.accessToken')
        ? Promise.resolve(currentUser())
        : Promise.reject(signedOut())
    ));

    renderApp();

    expect(await screen.findByText('login screen')).toBeTruthy();
    expect(localStorage.getItem('opentv.accessToken')).toBeNull();
    expect(window.location.hash).toBe('');
  });

  it('sends a signed-out visitor to the sign-in screen', async () => {
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    renderApp();
    expect(await screen.findByText('login screen')).toBeTruthy();
  });

  it('still lands on sign-in while the server has no administrator', async () => {
    // Setup is offered from there. Redirecting to it took away single sign-on, which is
    // how an OIDC-first server is meant to be entered.
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities({ bootstrapRequired: true }));
    renderApp();
    expect(await screen.findByText('login screen')).toBeTruthy();
    expect(screen.queryByText('setup screen')).toBeNull();
  });

  it('keeps a signed-in viewer signed in when the connection drops', async () => {
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me)
      .mockResolvedValueOnce(currentUser())
      .mockRejectedValue(offline());
    renderApp();

    const probe = await screen.findByRole('button', { name: 'alex' });
    fireEvent.click(probe);

    await waitFor(() => expect(vi.mocked(authApi.me).mock.calls.length).toBe(2));
    expect(screen.getByRole('button', { name: 'alex' })).toBeTruthy();
    expect(screen.queryByText('login screen')).toBeNull();
  });

  it('keeps the bearer available for retry after a temporary current-user server error', async () => {
    localStorage.setItem('opentv.accessToken', 'still-valid-session');
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me).mockRejectedValue(new ApiError('temporary failure', 503));

    renderApp();

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeTruthy();
    expect(localStorage.getItem('opentv.accessToken')).toBe('still-valid-session');
  });

  it('keeps a non-OIDC session usable when session storage is unavailable', async () => {
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me).mockResolvedValue(currentUser());
    const get = vi.spyOn(Storage.prototype, 'getItem')
      .mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    try {
      renderApp();
      expect(await screen.findByRole('button', { name: 'alex' })).toBeTruthy();
    } finally {
      get.mockRestore();
    }
  });

  it('signs out immediately when another tab removes the bearer', async () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'shared-session');
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me).mockResolvedValue(currentUser());
    renderApp();
    expect(await screen.findByRole('button', { name: 'alex' })).toBeTruthy();

    localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    window.dispatchEvent(new StorageEvent('storage', {
      key: ACCESS_TOKEN_STORAGE_KEY,
      oldValue: 'shared-session',
      newValue: null,
      storageArea: localStorage,
    }));

    expect(await screen.findByText('login screen')).toBeTruthy();
  });

  it('signs out when another tab clears all local storage', async () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'shared-session');
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me).mockResolvedValue(currentUser());
    renderApp();
    expect(await screen.findByRole('button', { name: 'alex' })).toBeTruthy();

    localStorage.clear();
    window.dispatchEvent(new StorageEvent('storage', {
      key: null,
      oldValue: null,
      newValue: null,
      storageArea: localStorage,
    }));

    expect(await screen.findByText('login screen')).toBeTruthy();
  });

  it('adopts another tab replacement bearer without an older refresh erasing it', async () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'old-session');
    let rejectOld!: (cause: unknown) => void;
    const oldRefresh = new Promise<CurrentUser>((_resolve, reject) => { rejectOld = reject; });
    vi.mocked(authApi.capabilities).mockResolvedValue(capabilities());
    vi.mocked(authApi.me)
      .mockResolvedValueOnce(currentUser())
      .mockReturnValueOnce(oldRefresh)
      .mockResolvedValueOnce(currentUser({ id: 'user-2', username: 'sam', displayName: 'Sam' }));
    renderApp();

    fireEvent.click(await screen.findByRole('button', { name: 'alex' }));
    await waitFor(() => expect(authApi.me).toHaveBeenCalledTimes(2));
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'replacement-session');
    window.dispatchEvent(new StorageEvent('storage', {
      key: ACCESS_TOKEN_STORAGE_KEY,
      oldValue: 'old-session',
      newValue: 'replacement-session',
      storageArea: localStorage,
    }));

    expect(await screen.findByRole('button', { name: 'sam' })).toBeTruthy();
    rejectOld(signedOut());
    await Promise.resolve();

    expect(screen.getByRole('button', { name: 'sam' })).toBeTruthy();
    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBe('replacement-session');
  });

  it('recovers from an unreachable capabilities endpoint on retry', async () => {
    vi.mocked(authApi.capabilities)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValue(capabilities());
    renderApp();

    const retry = await screen.findByRole('button');
    retry.click();
    await waitFor(() => expect(screen.getByText('login screen')).toBeTruthy());
  });
});
