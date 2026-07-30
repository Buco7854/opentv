import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { BrowserRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LinkLandingScreen } from './LinkLanding';

vi.mock('./AuthProvider', () => ({
  RequireAuth: ({ children }: { children: ReactNode }) => children,
}));
vi.mock('./LinkApproval', () => ({
  LinkApprovalScreen: ({
    pending,
  }: {
    pending: {
      linkToken: string;
      browserSignIn: boolean;
      automaticApproval: boolean;
    } | null;
  }) => (
    <span>
      {pending
        ? `${pending.linkToken}:${pending.browserSignIn}:${pending.automaticApproval}`
        : 'missing'}
    </span>
  ),
}));

describe('LinkLandingScreen', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState(null, '', '/');
  });

  it('captures and scrubs the same-device fragment before auth routing continues', () => {
    window.history.replaceState(
      null,
      '',
      '/link#t=browser-link-secret&mode=sign-in',
    );

    render(
      <BrowserRouter>
        <LinkLandingScreen />
      </BrowserRouter>,
    );

    expect(screen.getByText('browser-link-secret:true:true')).toBeTruthy();
    expect(window.location.pathname).toBe('/link');
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('');
  });
});
