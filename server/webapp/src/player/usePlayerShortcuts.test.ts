import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePlayerShortcuts } from './usePlayerShortcuts';

function mountShortcuts(overrides: { watchTogetherOpen?: boolean } = {}) {
  const handlers = {
    onClose: vi.fn(),
    poke: vi.fn(),
    togglePlay: vi.fn(),
    toggleMute: vi.fn(),
    seekBy: vi.fn(),
    changeVolume: vi.fn(),
    toggleFullscreen: vi.fn(),
  };
  const opts = {
    title: 'Movie',
    live: false,
    menu: null,
    guideOpen: false,
    watchTogetherOpen: false,
    videoRef: { current: document.createElement('video') },
    ...handlers,
    ...overrides,
  };
  const view = renderHook(() => usePlayerShortcuts(opts));
  return { ...view, ...handlers };
}

const press = (key: string, target: EventTarget = document) => {
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
};

describe('player shortcuts', () => {
  afterEach(() => { document.body.innerHTML = ''; });

  it('leaves Escape to the watch-together sheet layered over the player', () => {
    const { onClose } = mountShortcuts({ watchTogetherOpen: true });
    press('Escape');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes the player on Escape when nothing is layered over it', () => {
    const { onClose } = mountShortcuts();
    press('Escape');
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('keeps playback keys off a control inside a sheet', () => {
    const sheet = document.createElement('div');
    sheet.setAttribute('role', 'dialog');
    const option = document.createElement('button');
    sheet.appendChild(option);
    document.body.appendChild(sheet);
    const { seekBy, toggleMute } = mountShortcuts();

    press('ArrowRight', option);
    press('m', option);

    expect(seekBy).not.toHaveBeenCalled();
    expect(toggleMute).not.toHaveBeenCalled();
  });

  it('toggles fullscreen with f rather than only entering it', () => {
    const { toggleFullscreen } = mountShortcuts();
    press('f');
    expect(toggleFullscreen).toHaveBeenCalledOnce();
  });
});
