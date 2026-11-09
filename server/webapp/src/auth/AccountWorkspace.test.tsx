import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/http';
import { forgetWatchProgress } from '../watchProgress';
import { authApi } from './api';
import { AccountWorkspace } from './AccountWorkspace';
import { AccountAuthSession, AccountProgress } from './types';

const logout = vi.fn();
vi.mock('./AuthProvider', () => ({ useAuth: () => ({ user: { id: 'ordinary-user', role: 'USER', displayName: 'Alex', username: 'alex', authSessionId: 'current' }, logout }) }));
vi.mock('./AuthScreens', () => ({ SecurityScreen: () => <p>Security controls</p> }));
vi.mock('../watchProgress', () => ({ forgetWatchProgress: vi.fn() }));
vi.mock('./api', () => ({ authApi: { progress: vi.fn(), deleteProgress: vi.fn(), clearProgress: vi.fn(), sessions: vi.fn(), revokeSession: vi.fn(), revokeOtherSessions: vi.fn() } }));

const progress: AccountProgress[] = [
  { contentId: 'private-id', title: 'Evening cinema', positionMs: 600_000, durationMs: 3_600_000, updatedMs: 1_700_000_000_000 },
  { contentId: 'orphan-secret-id', title: null, positionMs: 1_200_000, durationMs: 7_200_000, updatedMs: 1_700_000_000_000 },
];
const current: AccountAuthSession = { id: 'current', authMethod: 'PASSWORD', clientKind: 'BROWSER', deviceName: null, createdAtMs: 1_700_000_000_000, lastSeenAtMs: 1_700_000_001_000, idleExpiresAtMs: 1_800_000_000_000, absoluteExpiresAtMs: 1_900_000_000_000 };
const other: AccountAuthSession = { ...current, id: 'other-secret-id', clientKind: 'LINKED_DEVICE', authMethod: 'OIDC', deviceName: 'Living room television' };
const settled = async () => { await act(async () => {}); };
function NavigationProbe() { const location = useLocation(); const navigate = useNavigate(); return <><span data-testid="account-route">{location.pathname}</span><button onClick={() => navigate(-1)}>Browser back</button></>; }
const openAccount = (path = '/security/progress') => render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/security/*" element={<AccountWorkspace />} /><Route path="/login" element={<p>Login page</p>} /></Routes><NavigationProbe /></MemoryRouter>);

describe('Your account', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(authApi.progress).mockReset().mockResolvedValue(progress);
    vi.mocked(authApi.sessions).mockReset().mockResolvedValue([other, current]);
    vi.mocked(authApi.deleteProgress).mockReset().mockResolvedValue(null);
    vi.mocked(authApi.clearProgress).mockReset().mockResolvedValue(null);
    vi.mocked(authApi.revokeSession).mockReset().mockResolvedValue(null);
    vi.mocked(authApi.revokeOtherSessions).mockReset().mockResolvedValue(null);
    logout.mockReset().mockResolvedValue(undefined);
  });

  it('gives an ordinary user visible, deep-linked account sections and loads only the chosen section', async () => {
    openAccount('/security/sessions'); await settled();
    expect(screen.getByRole('heading', { name: 'Your account' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Sessions' }).getAttribute('aria-current')).toBe('page');
    expect(authApi.sessions).toHaveBeenCalledOnce();
    expect(authApi.progress).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('link', { name: 'Watch progress' })); await settled();
    expect(screen.getByTestId('account-route').textContent).toBe('/security/progress');
    expect(authApi.progress).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole('button', { name: 'Browser back' })); await settled();
    expect(screen.getByRole('link', { name: 'Sessions' }).getAttribute('aria-current')).toBe('page');
  });

  it('shows titles, durations and missing-title context without exposing content identities', async () => {
    const view = openAccount(); await settled();
    expect(screen.getByRole('heading', { name: 'Evening cinema' })).toBeTruthy();
    expect(screen.getByText('10 min of 1h 00min')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Unavailable title' })).toBeTruthy();
    expect(view.container.textContent).not.toContain('private-id');
    expect(view.container.textContent).not.toContain('orphan-secret-id');
  });

  it('confirms one progress reset, retains it on failure, then updates history and cached playback after retry', async () => {
    vi.mocked(authApi.deleteProgress).mockRejectedValueOnce(new ApiError('offline', 0, 'network'));
    openAccount(); await settled();
    fireEvent.click(screen.getByRole('button', { name: 'Reset progress for Evening cinema' }));
    expect(authApi.deleteProgress).not.toHaveBeenCalled();
    const dialog = screen.getByRole('dialog', { name: 'Reset progress for “Evening cinema”?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reset progress' })); await settled();
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(within(dialog).getByRole('alert')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Evening cinema' })).toBeTruthy();
    expect(forgetWatchProgress).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reset progress' })); await settled();
    expect(authApi.deleteProgress).toHaveBeenLastCalledWith('private-id');
    expect(forgetWatchProgress).toHaveBeenCalledWith('private-id');
    expect(screen.queryByRole('heading', { name: 'Evening cinema' })).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByText('Watch progress reset.').getAttribute('role')).toBe('status');
    expect(document.querySelector('.toast.success')).toBeNull();
  });

  it('resets the complete account history even with a title filter active', async () => {
    openAccount(); await settled();
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Evening' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reset all watch progress' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reset all watch progress' })); await settled();
    expect(authApi.clearProgress).toHaveBeenCalledOnce();
    expect(forgetWatchProgress).toHaveBeenCalledWith();
    expect(screen.getByText('No saved watch progress')).toBeTruthy();
  });

  it('keeps account navigation usable while a history failure offers a local retry', async () => {
    vi.mocked(authApi.progress).mockRejectedValueOnce(new ApiError('unavailable', 500, 'internal_error'));
    openAccount(); await settled();
    expect(screen.queryByRole('status', { name: 'Working…' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' })); await settled();
    expect(screen.getByRole('heading', { name: 'Evening cinema' })).toBeTruthy();
  });

  it('names devices, marks the current session, and revokes another session only after confirmation', async () => {
    const view = openAccount('/security/sessions'); await settled();
    expect(screen.getByText('This session')).toBeTruthy();
    expect(view.container.querySelector('.activity-row')?.textContent).toContain('This session');
    expect(view.container.textContent).not.toContain('other-secret-id');
    fireEvent.click(screen.getByRole('button', { name: 'Sign out Living room television' }));
    expect(authApi.revokeSession).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Sign out device' })); await settled();
    expect(authApi.revokeSession).toHaveBeenCalledWith('other-secret-id');
    expect(screen.queryByRole('heading', { name: 'Living room television' })).toBeNull();
    expect(screen.getByText('This session')).toBeTruthy();
    expect(logout).not.toHaveBeenCalled();
  });

  it('uses atomic revoke-others and preserves this session', async () => {
    openAccount('/security/sessions'); await settled();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out other devices' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Sign out other devices' })); await settled();
    expect(authApi.revokeOtherSessions).toHaveBeenCalledOnce();
    expect(authApi.revokeSession).not.toHaveBeenCalled();
    expect(screen.getByText('This session')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Sign out other devices' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Other devices signed out.').getAttribute('role')).toBe('status');
    fireEvent.click(screen.getByRole('button', { name: 'Sign out everywhere' }));
    expect(screen.queryByText('Other devices signed out.')).toBeNull();
  });

  it('keeps failed all-device sign-out retryable before navigating away', async () => {
    logout.mockRejectedValueOnce(new ApiError('offline', 0, 'network'));
    openAccount('/security/sessions'); await settled();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out everywhere' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Sign out everywhere' })); await settled();
    expect(within(screen.getByRole('dialog')).getByRole('alert')).toBeTruthy();
    expect(screen.getByTestId('account-route').textContent).toBe('/security/sessions');
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Sign out everywhere' }));
    await waitFor(() => expect(screen.getByText('Login page')).toBeTruthy());
    expect(logout).toHaveBeenLastCalledWith(true);
  });
});
