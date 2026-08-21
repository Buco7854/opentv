import { describe, expect, it } from 'vitest';
import { playbackClientInstanceId } from './playbackInstance';

describe('playback client instance', () => {
  it('survives a reload through tab-scoped storage', () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => { values.set(key, value); },
    } as Storage;

    const beforeReload = playbackClientInstanceId(storage);
    const afterReload = playbackClientInstanceId(storage);

    expect(afterReload).toBe(beforeReload);
    expect(beforeReload.length).toBeGreaterThanOrEqual(16);
  });
});

