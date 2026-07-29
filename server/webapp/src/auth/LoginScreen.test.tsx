import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginScreen } from './AuthScreens';
import { AuthCapabilities } from './types';

const capabilities = vi.fn();
const supported = vi.fn();
const beginHandoff = vi.fn();

vi.mock('./AuthProvider', () => ({
  useAuth: () => ({ phase: 'unauthenticated', capabilities: capabilities(), acceptFlow: vi.fn() }),
}));
vi.mock('./webauthn', async (importOriginal) => ({
  ...await importOriginal<typeof import('./webauthn')>(),
  webAuthnSupported: () => supported(),
}));
vi.mock('./fragment', async (importOriginal) => ({
  ...await importOriginal<typeof import('./fragment')>(),
  beginOidcHandoff: () => beginHandoff(),
}));

const caps = (overrides: Partial<AuthCapabilities> = {}): AuthCapabilities => ({
  passwordEnabled: true,
  oidcEnabled: false,
  bootstrapRequired: false,
  webAuthnRpId: 'localhost',
  oidcStartUrl: null,
  passkeyLoginEnabled: true,
  deviceLinkEnabled: true,
  ...overrides,
});

const renderLogin = () => render(
  <MemoryRouter initialEntries={['/login']}><LoginScreen /></MemoryRouter>,
);

describe('LoginScreen', () => {
  beforeEach(() => {
    capabilities.mockReturnValue(caps());
    supported.mockReturnValue(true);
    beginHandoff.mockReturnValue('handoff-correlation');
  });

  afterEach(cleanup);

  it('offers the passkey and other-device paths when the server and browser allow them', () => {
    renderLogin();
    expect(screen.getByRole('button', { name: 'Sign in with a passkey' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Sign in from another device' })).toBeTruthy();
  });

  it('hides the passkey button in a browser without WebAuthn', () => {
    supported.mockReturnValue(false);
    renderLogin();
    expect(screen.queryByRole('button', { name: 'Sign in with a passkey' })).toBeNull();
  });

  it('hides both when the server has them switched off', () => {
    capabilities.mockReturnValue(caps({ passkeyLoginEnabled: false, deviceLinkEnabled: false }));
    renderLogin();
    expect(screen.queryByRole('button', { name: 'Sign in with a passkey' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Sign in from another device' })).toBeNull();
  });

  it('offers the first administrator alongside single sign-on on a fresh server', () => {
    capabilities.mockReturnValue(caps({ bootstrapRequired: true, oidcEnabled: true }));
    renderLogin();
    expect(screen.getByRole('button', { name: /Create the first administrator/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /single sign-on/ })).toBeTruthy();
  });

  it('does not offer it once the server has an administrator', () => {
    renderLogin();
    expect(screen.queryByRole('button', { name: /Create the first administrator/ })).toBeNull();
  });

  it('fails closed when this tab cannot persist the OIDC handoff correlation', () => {
    capabilities.mockReturnValue(caps({
      passwordEnabled: false,
      oidcEnabled: true,
      passkeyLoginEnabled: false,
    }));
    beginHandoff.mockReturnValue(null);
    renderLogin();

    fireEvent.click(screen.getByRole('button', { name: /single sign-on/ }));

    expect(screen.getByRole('alert').textContent)
      .toContain('Single sign-on could not be completed');
  });

  it('still renders password sign-in when session storage is unavailable', () => {
    const remove = vi.spyOn(Storage.prototype, 'removeItem')
      .mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    try {
      renderLogin();
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeTruthy();
    } finally {
      remove.mockRestore();
    }
  });
});
