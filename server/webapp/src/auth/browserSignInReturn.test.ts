import { describe, expect, it, vi } from 'vitest';
import {
  attemptBrowserSignInReturn,
  BROWSER_SIGN_IN_RETURN_URL,
  supportsBrowserSignInReturn,
} from './browserSignInReturn';

describe('browser sign-in return', () => {
  it('uses one signal-only URL with no secret-bearing components', () => {
    const target = new URL(BROWSER_SIGN_IN_RETURN_URL);

    expect(BROWSER_SIGN_IN_RETURN_URL).toBe('opentv://sign-in');
    expect(target.protocol).toBe('opentv:');
    expect(target.host).toBe('sign-in');
    expect(target.username).toBe('');
    expect(target.password).toBe('');
    expect(target.pathname).toBe('');
    expect(target.search).toBe('');
    expect(target.hash).toBe('');
  });

  it('navigates an Android browser to the fixed URL', () => {
    const navigate = vi.fn();

    expect(attemptBrowserSignInReturn(
      'Mozilla/5.0 (Linux; Android 16) Chrome/140 Mobile',
      navigate,
    )).toBe(true);
    expect(navigate).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith('opentv://sign-in');
  });

  it('leaves an unsupported desktop browser on the completed web page', () => {
    const navigate = vi.fn();

    expect(supportsBrowserSignInReturn('Mozilla/5.0 (X11; Linux x86_64) Chrome/140'))
      .toBe(false);
    expect(attemptBrowserSignInReturn(
      'Mozilla/5.0 (X11; Linux x86_64) Chrome/140',
      navigate,
    )).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('treats a rejected scheme navigation as a harmless fallback', () => {
    const rejectNavigation = vi.fn(() => {
      throw new Error('No application handles this scheme');
    });

    expect(attemptBrowserSignInReturn('Android', rejectNavigation)).toBe(false);
    expect(rejectNavigation).toHaveBeenCalledWith(BROWSER_SIGN_IN_RETURN_URL);
  });
});
