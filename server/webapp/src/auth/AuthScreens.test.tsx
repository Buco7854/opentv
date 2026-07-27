import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from './api';
import { ActivateScreen, SecurityScreen } from './AuthScreens';
import { AuthCapabilities, AuthFlow, CurrentUser } from './types';

const acceptFlow = vi.fn();
const session = vi.fn();

vi.mock('./AuthProvider', () => ({ useAuth: () => session() }));
vi.mock('./api', () => ({
  authApi: {
    activate: vi.fn(),
    webAuthnCredentials: vi.fn(),
    totpStatus: vi.fn(),
  },
}));
vi.mock('./webauthn', async (importOriginal) => ({
  ...await importOriginal<typeof import('./webauthn')>(),
  webAuthnSupported: () => true,
}));

const capabilities: AuthCapabilities = {
  passwordEnabled: true,
  oidcEnabled: false,
  bootstrapRequired: false,
  webAuthnRpId: 'localhost',
  oidcStartUrl: null,
  passkeyLoginEnabled: true,
  deviceLinkEnabled: true,
};

const user = (authMethod: string, hasPassword = false): CurrentUser => ({
  id: 'user-1',
  username: 'alex',
  displayName: 'Alex Moreau',
  role: 'USER',
  authMethod,
  hasPassword,
  authSessionId: 'session-1',
  clientKind: 'WEB',
  playlistIds: [],
  csrfToken: 'csrf',
});

const authenticated = (): AuthFlow => ({
  status: 'AUTHENTICATED',
  code: null,
  challenge: null,
  methods: [],
  expiresAtMs: null,
  user: user('PASSWORD'),
  csrfToken: 'csrf',
  recoveryCodes: [],
});

describe('ActivateScreen', () => {
  beforeEach(() => {
    session.mockReturnValue({ phase: 'unauthenticated', capabilities, acceptFlow });
    vi.mocked(authApi.activate).mockResolvedValue(authenticated());
  });

  afterEach(cleanup);

  it('takes the activation token from the URL fragment, never the query string', async () => {
    window.location.hash = '#token=activation-secret';
    render(<MemoryRouter initialEntries={['/activate']}><ActivateScreen /></MemoryRouter>);

    expect(screen.getByLabelText<HTMLInputElement>('Activation token').value)
      .toBe('activation-secret');

    fireEvent.change(screen.getByLabelText('Choose password'), {
      target: { value: 'a long enough password' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

    await waitFor(() => expect(authApi.activate)
      .toHaveBeenCalledWith('activation-secret', 'a long enough password'));
  });
});

describe('SecurityScreen', () => {
  beforeEach(() => {
    session.mockReturnValue({
      phase: 'authenticated',
      capabilities,
      user: user('WEBAUTHN'),
      acceptFlow,
      logout: vi.fn(),
    });
    vi.mocked(authApi.webAuthnCredentials).mockResolvedValue([]);
    vi.mocked(authApi.totpStatus).mockResolvedValue({ enrolled: false, confirmedAtMs: null });
  });

  afterEach(cleanup);

  it('offers only passkeys to an account with no password', async () => {
    // An authenticator is only ever asked for during password sign-in, and recovery codes
    // only recover that step, so neither can be reached without a password. A passkey is
    // also a primary sign-in method, so it stays.
    render(<MemoryRouter initialEntries={['/security']}><SecurityScreen /></MemoryRouter>);

    expect(await screen.findByRole('button', { name: 'Add security key' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Set up authenticator app' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Generate new codes' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Change password' })).toBeNull();
  });

  it('offers the password and authenticator to an account that has a password', async () => {
    // Signed in with a passkey, but the account owns a password: what it can do follows the
    // account, not this session.
    session.mockReturnValue({
      phase: 'authenticated',
      capabilities,
      user: user('WEBAUTHN', true),
      acceptFlow,
      logout: vi.fn(),
    });
    render(<MemoryRouter initialEntries={['/security']}><SecurityScreen /></MemoryRouter>);

    expect(await screen.findByRole('button', { name: 'Change password' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Set up authenticator app' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Generate new codes' })).toBeTruthy();
  });

  it('does not offer a key form on an address that cannot be a relying party', async () => {
    // The server reports this: reached by IP or over plain HTTP off localhost, no browser
    // would complete the ceremony.
    session.mockReturnValue({
      phase: 'authenticated',
      capabilities: { ...capabilities, passkeyLoginEnabled: false },
      user: user('WEBAUTHN'),
      acceptFlow,
      logout: vi.fn(),
    });
    render(<MemoryRouter initialEntries={['/security']}><SecurityScreen /></MemoryRouter>);

    expect(await screen.findByText(/Passkeys need an HTTPS address/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add security key' })).toBeNull();
  });
});
