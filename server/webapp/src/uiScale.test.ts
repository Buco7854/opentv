import { describe, expect, it } from 'vitest';
import {
  applyUiScale, LARGE_SCREEN_PX, LARGEST_SCREEN_PX, uiScaleFor,
} from './uiScale';

describe('uiScale', () => {
  it('leaves a screen the design was drawn for alone', () => {
    expect(uiScaleFor(390)).toBe('default');
    expect(uiScaleFor(1024)).toBe('default');
    expect(uiScaleFor(LARGE_SCREEN_PX - 1)).toBe('default');
  });

  it('grows once a screen is large enough to be watched from a distance', () => {
    expect(uiScaleFor(LARGE_SCREEN_PX)).toBe('large');
    expect(uiScaleFor(1440)).toBe('large');
    expect(uiScaleFor(LARGEST_SCREEN_PX)).toBe('largest');
    expect(uiScaleFor(2560)).toBe('largest');
  });

  it('never shrinks as a screen grows', () => {
    const order = { default: 0, large: 1, largest: 2 };
    const widths = [320, 768, 1279, 1280, 1699, 1700, 3840];
    const steps = widths.map((w) => order[uiScaleFor(w)]);
    expect(steps).toEqual([...steps].sort((a, b) => a - b));
  });

  it('records the step where the stylesheet reads it', () => {
    const root = document.createElement('html');

    expect(applyUiScale(root, 1920)).toBe('largest');
    expect(root.dataset.uiScale).toBe('largest');

    // Re-running replaces rather than accumulating, so a later call cannot leave
    // two answers on the element.
    applyUiScale(root, 800);
    expect(root.dataset.uiScale).toBe('default');
  });
});
