import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from './api';
import { LinkApprovalScreen } from './LinkApproval';
import { PendingDeviceLink } from './fragment';
import { CurrentUser, DeviceLinkRequest } from './types';
import { attemptBrowserSignInReturn } from './browserSignInReturn';

const currentUser: CurrentUser = {
  id: 'user-1',
  username: 'alex',
  displayName: 'Alex Moreau',
  role: 'USER',
  authMethod: 'PASSWORD',
  hasPassword: true,
  authSessionId: 'session-1',
  clientKind: 'BROWSER',
  playlistIds: [],
};

vi.mock('./AuthProvider', () => ({
  useAuth: () => ({ user: currentUser }),
}));
vi.mock('./api', () => ({
  authApi: {
    linkLookup: vi.fn(),
    linkApprove: vi.fn(),
    linkDeny: vi.fn(),
  },
}));
vi.mock('./browserSignInReturn', () => ({
  attemptBrowserSignInReturn: vi.fn(),
  BROWSER_SIGN_IN_RETURN_URL: 'opentv://sign-in',
  supportsBrowserSignInReturn: vi.fn(() => false),
}));

const lookup = (browserSignIn: boolean): DeviceLinkRequest => ({
  deviceName: 'Android phone',
  userAgent: 'OpenTV Android',
  ip: '192.0.2.2',
  requestedAtMs: Date.now(),
  expiresAtMs: Date.now() + 300_000,
  browserSignIn,
});

const pending = (browserSignIn: boolean): PendingDeviceLink => ({
  linkToken: browserSignIn ? 'same-device-secret' : 'qr-secret',
  browserSignIn,
  automaticApproval: browserSignIn,
});

const renderApproval = (intent: PendingDeviceLink) => render(
  <MemoryRouter initialEntries={['/link']}>
    <LinkApprovalScreen pending={intent} />
  </MemoryRouter>,
);

describe('LinkApprovalScreen', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.mocked(authApi.linkLookup).mockReset();
    vi.mocked(authApi.linkApprove).mockReset().mockResolvedValue(null);
    vi.mocked(authApi.linkDeny).mockReset().mockResolvedValue(null);
    vi.mocked(attemptBrowserSignInReturn).mockReset();
  });

  afterEach(cleanup);

  it('claims and approves a server-bound same-device request after normal authentication', async () => {
    vi.mocked(authApi.linkLookup).mockResolvedValue(lookup(true));

    renderApproval(pending(true));

    expect(await screen.findByText('Device signed in')).toBeTruthy();
    expect(screen.getByText('Sign-in is complete. You can return to the OpenTV app now.'))
      .toBeTruthy();
    expect(authApi.linkLookup).toHaveBeenCalledWith({ linkToken: 'same-device-secret' });
    expect(authApi.linkApprove).toHaveBeenCalledWith({ linkToken: 'same-device-secret' });
    await waitFor(() => expect(attemptBrowserSignInReturn).toHaveBeenCalledOnce());
    expect(attemptBrowserSignInReturn).toHaveBeenCalledWith();
  });

  it('keeps an ordinary QR request on the explicit approval screen', async () => {
    vi.mocked(authApi.linkLookup).mockResolvedValue(lookup(false));

    renderApproval(pending(false));

    expect(await screen.findByRole('button', { name: 'Approve' })).toBeTruthy();
    expect(authApi.linkApprove).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(authApi.linkApprove)
      .toHaveBeenCalledWith({ linkToken: 'qr-secret' }));
    expect(attemptBrowserSignInReturn).not.toHaveBeenCalled();
  });

  it('requires the server-bound mode even when the fragment asks for automatic completion', async () => {
    vi.mocked(authApi.linkLookup).mockResolvedValue(lookup(false));

    renderApproval(pending(true));

    expect(await screen.findByRole('button', { name: 'Approve' })).toBeTruthy();
    expect(authApi.linkApprove).not.toHaveBeenCalled();
    expect(attemptBrowserSignInReturn).not.toHaveBeenCalled();
  });

  it('requires a click when the same-device link opened over an existing browser session', async () => {
    vi.mocked(authApi.linkLookup).mockResolvedValue(lookup(true));

    renderApproval({
      linkToken: 'signed-in-secret',
      browserSignIn: true,
      automaticApproval: false,
    });

    expect(await screen.findByRole('button', { name: 'Approve' })).toBeTruthy();
    expect(authApi.linkApprove).not.toHaveBeenCalled();
    expect(attemptBrowserSignInReturn).not.toHaveBeenCalled();
  });

  it('returns after explicit approval when the server says it is a browser sign-in', async () => {
    vi.mocked(authApi.linkLookup).mockResolvedValue(lookup(true));

    renderApproval({
      linkToken: 'signed-in-secret',
      browserSignIn: true,
      automaticApproval: false,
    });

    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(authApi.linkApprove)
      .toHaveBeenCalledWith({ linkToken: 'signed-in-secret' }));
    await waitFor(() => expect(attemptBrowserSignInReturn).toHaveBeenCalledOnce());
    expect(attemptBrowserSignInReturn).toHaveBeenCalledWith();
  });
});
