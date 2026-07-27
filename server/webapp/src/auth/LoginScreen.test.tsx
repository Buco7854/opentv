import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginScreen } from './AuthScreens';
import { AuthCapabilities } from './types';

const capabilities = vi.fn();
const supported = vi.fn();

vi.mock('./AuthProvider', () => ({
  useAuth: () => ({ phase: 'unauthenticated', capabilities: capabilities(), acceptFlow: vi.fn() }),
}));
vi.mock('./webauthn', async (importOriginal) => ({
  ...await importOriginal<typeof import('./webauthn')>(),
  webAuthnSupported: () => supported(),
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
});
