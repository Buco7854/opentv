import { cleanup, render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import {
  capturePendingDeviceLink,
  clearPendingDeviceLink,
  consumeOidcSessionToken,
  readPendingDeviceLink,
  useFragmentToken,
} from './fragment';

function TokenProbe() {
  return <span>{useFragmentToken('token') ?? 'missing'}</span>;
}

const renderToken = () => render(
  <BrowserRouter>
    <TokenProbe />
  </BrowserRouter>,
);

describe('fragment tokens', () => {
  afterEach(() => {
    cleanup();
    clearPendingDeviceLink();
    sessionStorage.clear();
    window.history.replaceState(null, '', '/');
  });

  it('does not replay the token captured on a previous visit', () => {
    window.history.replaceState(null, '', '/activate#token=first-secret');
    const first = renderToken();
    expect(screen.getByText('first-secret')).toBeTruthy();
    first.unmount();

    window.history.replaceState(null, '', '/activate#token=second-secret');
    renderToken();

    expect(screen.getByText('second-secret')).toBeTruthy();
    expect(window.location.hash).toBe('');
  });

  it('accepts one exact, recent OIDC handoff and rejects its replay', () => {
    const handoff = 'exact-correlation-value';
    sessionStorage.setItem(
      'auth.oidcPendingAt',
      JSON.stringify({ handoff, startedAt: Date.now() }),
    );
    window.history.replaceState(null, '', `/#session=opaque-session&handoff=${handoff}`);

    expect(consumeOidcSessionToken()).toBe('opaque-session');
    expect(window.location.hash).toBe('');

    window.history.replaceState(null, '', `/#session=opaque-session&handoff=${handoff}`);
    expect(consumeOidcSessionToken()).toBeNull();
    expect(window.location.hash).toBe('');
  });

  it('rejects partially matching and expired OIDC handoffs', () => {
    sessionStorage.setItem(
      'auth.oidcPendingAt',
      JSON.stringify({ handoff: 'expected-correlation', startedAt: Date.now() }),
    );
    window.history.replaceState(
      null,
      '',
      '/#session=opaque-session&handoff=expected-correlatio',
    );
    expect(consumeOidcSessionToken()).toBeNull();

    sessionStorage.setItem(
      'auth.oidcPendingAt',
      JSON.stringify({ handoff: 'expired', startedAt: Date.now() - 5 * 60_000 - 1 }),
    );
    window.history.replaceState(null, '', '/#session=opaque-session&handoff=expired');
    expect(consumeOidcSessionToken()).toBeNull();
  });

  it('keeps a same-device link tab-local across an authentication round trip', () => {
    window.history.replaceState(
      null,
      '',
      '/link#t=opaque-link-secret&mode=sign-in',
    );

    expect(capturePendingDeviceLink()).toEqual({
      linkToken: 'opaque-link-secret',
      browserSignIn: true,
      automaticApproval: true,
    });
    expect(window.location.pathname).toBe('/link');
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('');

    window.history.replaceState(null, '', '/');
    expect(readPendingDeviceLink()).toEqual({
      linkToken: 'opaque-link-secret',
      browserSignIn: true,
      automaticApproval: true,
    });
  });

  it('does not let an ordinary QR fragment opt itself into browser completion', () => {
    window.history.replaceState(null, '', '/link#t=ordinary-qr-secret');

    expect(capturePendingDeviceLink()).toEqual({
      linkToken: 'ordinary-qr-secret',
      browserSignIn: false,
      automaticApproval: false,
    });
  });

  it('requires explicit approval when the link opened over an existing browser session', () => {
    window.history.replaceState(
      null,
      '',
      '/link#t=already-signed-in-secret&mode=sign-in',
    );

    expect(capturePendingDeviceLink(false)).toEqual({
      linkToken: 'already-signed-in-secret',
      browserSignIn: true,
      automaticApproval: false,
    });
  });
});
