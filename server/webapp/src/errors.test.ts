import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, TRANSPORT_STATUS } from './api/http';
import { GENERIC, errorMessage, isCancellation, reportError, reportSuccess } from './errors';

const toasts = () => Array.from(document.querySelectorAll('.toast'));
const only = () => {
  const [element, ...rest] = toasts();
  expect(rest).toHaveLength(0);
  return element!;
};

afterEach(() => { document.getElementById('toast-root')?.remove(); });

describe('errorMessage', () => {
  it('prefers its own copy for a known code over the server sentence', () => {
    const message = errorMessage(new ApiError('No', 401, 'unauthenticated'));
    expect(message).toContain('Sign in');
  });

  it('reads a transport failure as a connection problem', () => {
    expect(errorMessage(new ApiError('boom', TRANSPORT_STATUS, 'network')))
      .toBe('No connection to the server. Check your network and try again.');
  });

  it('keeps the server message for a code it has no copy for', () => {
    expect(errorMessage(new ApiError('ffmpeg is busy', 503, 'busy'))).toBe('ffmpeg is busy');
  });

  it('lets a screen sharpen a code and replace the last resort', () => {
    const overrides = {
      forbidden: () => 'Ask an administrator.',
      [GENERIC]: () => 'That favorite could not be saved.',
    };
    expect(errorMessage(new ApiError('', 403, 'forbidden'), overrides)).toBe('Ask an administrator.');
    expect(errorMessage({ nope: true }, overrides)).toBe('That favorite could not be saved.');
  });

  it('never surfaces a non-error as text', () => {
    expect(errorMessage(undefined)).toBe('Something went wrong. Try again.');
    expect(errorMessage({ status: 500 })).toBe('Something went wrong. Try again.');
  });

  it('treats a dismissed browser prompt and an abandoned request as cancellations', () => {
    expect(isCancellation(new DOMException('closed', 'NotAllowedError'))).toBe(true);
    expect(isCancellation(new ApiError('aborted', TRANSPORT_STATUS, 'aborted'))).toBe(true);
    expect(isCancellation(new ApiError('nope', 500, 'internal_error'))).toBe(false);
  });
});

describe('reportError', () => {
  it('raises an assertive error toast and returns what it said', () => {
    const message = reportError(new ApiError('ffmpeg is busy', 503, 'busy'));

    const toast = only();
    expect(message).toBe('ffmpeg is busy');
    expect(toast.textContent).toContain('ffmpeg is busy');
    expect(toast.className).toContain('error');
    expect(toast.getAttribute('role')).toBe('alert');
    // The tone is never carried by colour alone.
    expect(toast.querySelector('.toast-icon svg')).toBeTruthy();
  });

  it('says nothing when the user closed the prompt themselves', () => {
    reportError(new DOMException('closed', 'NotAllowedError'));
    expect(toasts()).toHaveLength(0);
  });

  it('marks a confirmation as such', () => {
    reportSuccess('Playlist added');

    const toast = only();
    expect(toast.className).toContain('success');
    expect(toast.getAttribute('role')).toBe('status');
  });
});
