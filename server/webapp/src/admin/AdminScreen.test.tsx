import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/http';
import { AdminUser, adminApi } from './api';
import { AdminScreen } from './AdminScreen';
import { errorMessage as adminErrorMessage } from './format';

const capabilities = vi.fn();
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    capabilities: capabilities(),
    user: {
      id: 'admin-1',
      username: 'root',
      displayName: 'Root',
      role: 'ADMIN',
      authSessionId: 'session-1',
    },
  }),
}));

vi.mock('./api', () => ({
  adminApi: {
    users: vi.fn(),
    playlists: vi.fn(),
    playlistTemplate: vi.fn(),
    pendingOidc: vi.fn(),
    downloads: vi.fn(),
    createUser: vi.fn(),
    updateUser: vi.fn(),
    resetUser: vi.fn(),
  },
}));

const signedInAccount: AdminUser = {
  id: 'admin-1',
  username: 'root',
  displayName: 'Root',
  status: 'ACTIVE',
  manualRole: 'ADMIN',
  effectiveRole: 'ADMIN',
  authMethods: ['password', 'totp'],
  playlistIds: [],
  settableStatuses: ['ACTIVE', 'DISABLED'],
  createdAtMs: 1,
  lastLoginAtMs: 2,
};

const settled = async () => { await act(async () => {}); };

describe('AdminScreen', () => {
  beforeEach(() => {
    vi.mocked(adminApi.users).mockReset().mockResolvedValue([signedInAccount]);
    vi.mocked(adminApi.playlists).mockResolvedValue([]);
    vi.mocked(adminApi.playlistTemplate).mockResolvedValue({ playlistIds: [] });
    vi.mocked(adminApi.pendingOidc).mockResolvedValue([]);
    vi.mocked(adminApi.downloads).mockResolvedValue([]);
    vi.mocked(adminApi.updateUser).mockReset().mockResolvedValue(signedInAccount);
    capabilities.mockReturnValue({ passwordEnabled: true });
  });

  it('offers a retry instead of an endless spinner when a section fails', async () => {
    vi.mocked(adminApi.users)
      .mockRejectedValueOnce(new ApiError('Internal error', 500, 'internal_error'));
    const view = render(<AdminScreen />);
    await settled();

    expect(view.container.querySelector('.spinner')).toBeNull();
    expect(screen.getByText('The server could not complete this operation.')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await settled();

    expect(screen.getByText('Root')).toBeTruthy();
  });

  it('does not offer to demote, disable or delete your own account', async () => {
    // The server refuses all three for the acting administrator; another administrator is
    // the way out. Offering them and explaining the refusal afterwards is worse.
    render(<AdminScreen />);
    await settled();

    fireEvent.click(screen.getByText('Root'));
    fireEvent.click(screen.getByRole('button', { name: 'Manual role' }));
    expect(screen.queryByRole('option', { name: 'User' })).toBeNull();
    fireEvent.keyDown(document, { key: 'Escape' });

    fireEvent.click(screen.getByRole('button', { name: 'Status' }));
    expect(screen.queryByRole('option', { name: 'Disabled' })).toBeNull();
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('button', { name: 'Delete user' })).toBeNull();
  });

  it('explains disabled local provisioning and hides actions the server will refuse', async () => {
    capabilities.mockReturnValue({ passwordEnabled: false });
    render(<AdminScreen />);
    await settled();

    const disclaimer = 'Local account provisioning is off on this server. Accounts come from the identity provider and appear under Pending SSO for administrator approval; passwords, activation links and credential resets are unavailable.';
    expect(screen.queryByRole('button', { name: 'Create user' })).toBeNull();
    expect(screen.getByText(disclaimer)).toBeTruthy();

    fireEvent.click(screen.getByText('Root'));

    expect(screen.queryByRole('button', { name: 'Reset password and MFA' })).toBeNull();
    expect(screen.getByText(disclaimer)).toBeTruthy();
  });

  it('keeps local provisioning actions unchanged when password authentication is on', async () => {
    render(<AdminScreen />);
    await settled();

    expect(screen.getByRole('button', { name: 'Create user' })).toBeTruthy();
    expect(screen.queryByText(/Local account provisioning is off/)).toBeNull();

    fireEvent.click(screen.getByText('Root'));

    expect(screen.getByRole('button', { name: 'Reset password and MFA' })).toBeTruthy();
    expect(screen.queryByText(/Local account provisioning is off/)).toBeNull();
  });

  it('maps local provisioning and password capability failures to admin copy', () => {
    expect(adminErrorMessage(new ApiError(
      'Provisioning disabled', 409, 'local_account_provisioning_disabled',
    ))).toBe(
      'Local account provisioning is off on this server. Approve the pending identity from the identity provider instead.',
    );
    expect(adminErrorMessage(new ApiError(
      'Password disabled', 400, 'password_auth_disabled', 'password',
    ))).toBe(
      'Password sign-in is off on this server, so this request cannot set a password.',
    );
  });

  it('offers only the statuses the server says an administrator may set', async () => {
    // Invited is set by creating or resetting an account; Pending is a legacy value nothing
    // assigns. Offering either produced a request the server rejects.
    vi.mocked(adminApi.users).mockResolvedValue([
      signedInAccount,
      { ...signedInAccount, id: 'user-9', username: 'other', displayName: 'Other',
        manualRole: 'USER', effectiveRole: 'USER' },
    ]);
    render(<AdminScreen />);
    await settled();

    fireEvent.click(screen.getByText('Other'));
    fireEvent.click(screen.getByRole('button', { name: 'Status' }));

    expect(screen.getByRole('option', { name: 'Active' })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Disabled' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: 'Invited' })).toBeNull();
    expect(screen.queryByRole('option', { name: 'Pending' })).toBeNull();
  });

  it('still shows a lifecycle status the account currently sits in', async () => {
    vi.mocked(adminApi.users).mockResolvedValue([
      { ...signedInAccount, id: 'user-2', username: 'newcomer', displayName: 'Newcomer',
        status: 'INVITED', manualRole: 'USER', effectiveRole: 'USER' },
    ]);
    render(<AdminScreen />);
    await settled();

    fireEvent.click(screen.getByText('Newcomer'));
    fireEvent.click(screen.getByRole('button', { name: 'Status' }));

    // Present so the row is not misrepresented, alongside the two an admin may choose.
    expect(screen.getByRole('option', { name: 'Invited' })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Active' })).toBeTruthy();
  });

  it('creates an account with a password and asks for no activation link', async () => {
    vi.mocked(adminApi.createUser).mockResolvedValue({
      user: { ...signedInAccount, id: 'user-3', username: 'direct', displayName: 'Direct' },
      activationToken: null,
    });
    render(<AdminScreen />);
    await settled();

    fireEvent.click(screen.getByRole('button', { name: 'Create user' }));
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'direct' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'a long enough one' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await settled();

    expect(adminApi.createUser).toHaveBeenCalledWith(
      expect.objectContaining({ username: 'direct', password: 'a long enough one' }),
    );
    // Nothing to hand over, so no one-time token dialog.
    expect(screen.queryByText(/one-time/i)).toBeNull();
  });

  it('keeps the one-time setup link when refreshing the row fails', async () => {
    vi.mocked(adminApi.resetUser).mockResolvedValue({ setupToken: 'setup-token' });
    render(<AdminScreen />);
    await settled();

    fireEvent.click(screen.getByText('Root'));
    fireEvent.click(screen.getByRole('button', { name: 'Reset password and MFA' }));
    vi.mocked(adminApi.users).mockRejectedValue(new ApiError('offline', 0, 'network'));
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Reset password and MFA' }),
    );
    await settled();

    expect(screen.getByText(/#token=setup-token$/)).toBeTruthy();
  });

  it('marks the signed-in administrator and shows every playlist for admins', async () => {
    render(<AdminScreen />);
    await settled();

    expect(screen.getByText('You')).toBeTruthy();
    expect(screen.getByText('@root · All playlists')).toBeTruthy();
  });
});
