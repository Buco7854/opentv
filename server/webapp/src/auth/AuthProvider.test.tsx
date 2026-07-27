import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, TRANSPORT_STATUS } from '../api/http';
import { authApi } from './api';
import { AuthProvider, RequireAuth, useAuth } from './AuthProvider';
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

const currentUser = (): CurrentUser => ({
  id: 'user-1',
  username: 'alex',
  displayName: 'Alex Moreau',
  role: 'USER',
  authMethod: 'PASSWORD',
  hasPassword: true,
  authSessionId: 'session-1',
  clientKind: 'WEB',
  playlistIds: [],
  csrfToken: 'csrf',
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
    vi.mocked(authApi.me).mockReset().mockRejectedValue(signedOut());
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
