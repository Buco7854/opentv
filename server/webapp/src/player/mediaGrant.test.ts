import { describe, expect, it, vi } from 'vitest';
import {
  captureMediaPosition,
  isTerminalPlaybackStatus,
  mediaSourceIdentity,
  replaceMediaGrant,
  restoreMediaPosition,
} from './mediaGrant';

describe('media grant URLs', () => {
  it('rotates only the grant parameter', () => {
    expect(replaceMediaGrant('/api/v1/remux/list.m3u8?sid=lease&g=old&audio=2#track', 'new token'))
      .toBe('/api/v1/remux/list.m3u8?sid=lease&g=new+token&audio=2#track');
  });

  it('keeps the mounted source identity stable across grant rotation', () => {
    const oldUrl = '/api/v1/remux/list.m3u8?sid=lease&g=old&audio=2';
    const newUrl = replaceMediaGrant(oldUrl, 'new');
    expect(mediaSourceIdentity(newUrl)).toBe(mediaSourceIdentity(oldUrl));
  });

  it('restores VOD position and paused state after an authorization-only reload', () => {
    const video = document.createElement('video');
    Object.defineProperty(video, 'paused', { configurable: true, value: true });
    video.currentTime = 123.5;
    const snapshot = captureMediaPosition(video);
    video.currentTime = 0;
    const pause = vi.spyOn(video, 'pause').mockImplementation(() => {});

    restoreMediaPosition(video, snapshot, false);

    expect(video.currentTime).toBe(123.5);
    expect(pause).toHaveBeenCalledOnce();
  });

  it('classifies authentication, entitlement, and revocation failures as terminal', () => {
    expect([401, 403, 410].every(isTerminalPlaybackStatus)).toBe(true);
    expect(isTerminalPlaybackStatus(429)).toBe(false);
  });

  it('does not treat 404 as terminal: it is the answer for a file that needs no remux', () => {
    expect(isTerminalPlaybackStatus(404)).toBe(false);
    expect(isTerminalPlaybackStatus(410)).toBe(true);
    expect(isTerminalPlaybackStatus(500)).toBe(false);
    expect(isTerminalPlaybackStatus(undefined)).toBe(false);
  });
});
