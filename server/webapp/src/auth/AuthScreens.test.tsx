import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from './api';
import { ActivateScreen, SecurityScreen } from './AuthScreens';
import {
  AuthCapabilities, AuthFlow, CurrentUser, TotpStatus, WebAuthnCredential,
} from './types';

const acceptFlow = vi.fn();
const session = vi.fn();

vi.mock('./AuthProvider', () => ({ useAuth: () => session() }));
vi.mock('./api', () => ({
  authApi: {
    activate: vi.fn(),
    webAuthnCredentials: vi.fn(),
    totpStatus: vi.fn(),
    startTotpAdd: vi.fn(),
    completeTotpAdd: vi.fn(),
  },
}));
vi.mock('qrcode', () => ({ toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,AA') }));
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
  clientKind: 'BROWSER',
  playlistIds: [],
});

const authenticated = (): AuthFlow => ({
  status: 'AUTHENTICATED',
  code: null,
  challenge: null,
  methods: [],
  expiresAtMs: null,
  user: user('PASSWORD'),
  sessionToken: 'session-token',
  recoveryCodes: [],
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

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

  it('does not let an older factor-list request overwrite a post-enrollment refresh', async () => {
    const staleKeys = deferred<WebAuthnCredential[]>();
    const staleTotp = deferred<TotpStatus>();
    const fresh: WebAuthnCredential = {
      id: 'fresh-key',
      label: 'Fresh key',
      createdAtMs: Date.now(),
      lastUsedAtMs: null,
      backedUp: false,
    };
    session.mockReturnValue({
      phase: 'authenticated',
      capabilities,
      user: user('PASSWORD', true),
      acceptFlow,
      logout: vi.fn(),
    });
    vi.mocked(authApi.webAuthnCredentials)
      .mockReset()
      .mockResolvedValueOnce([])
      .mockReturnValueOnce(staleKeys.promise)
      .mockResolvedValueOnce([fresh]);
    vi.mocked(authApi.totpStatus)
      .mockReset()
      .mockResolvedValueOnce({ enrolled: false, confirmedAtMs: null })
      .mockReturnValueOnce(staleTotp.promise)
      .mockResolvedValueOnce({ enrolled: true, confirmedAtMs: Date.now() });
    vi.mocked(authApi.startTotpAdd).mockResolvedValue({
      challenge: 'challenge',
      secret: 'secret',
      uri: 'otpauth://totp/OpenTV:test',
      expiresAtMs: Date.now() + 60_000,
    });
    vi.mocked(authApi.completeTotpAdd).mockResolvedValue(authenticated());
    render(<MemoryRouter initialEntries={['/security']}><SecurityScreen /></MemoryRouter>);

    const enroll = async (completed: number) => {
      fireEvent.click(await screen.findByRole('button', { name: 'Set up authenticator app' }));
      fireEvent.change(await screen.findByLabelText('6-digit code'), {
        target: { value: '123456' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Verify' }));
      await waitFor(() => expect(authApi.completeTotpAdd).toHaveBeenCalledTimes(completed));
    };
    await enroll(1);
    await waitFor(() => expect(authApi.webAuthnCredentials).toHaveBeenCalledTimes(2));
    await enroll(2);
    expect(await screen.findByText('Fresh key')).toBeTruthy();

    staleKeys.resolve([]);
    staleTotp.resolve({ enrolled: false, confirmedAtMs: null });
    await waitFor(() => expect(authApi.webAuthnCredentials).toHaveBeenCalledTimes(3));

    expect(screen.getByText('Fresh key')).toBeTruthy();
  });
});
