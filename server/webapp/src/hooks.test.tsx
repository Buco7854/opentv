import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { clearUserActivitySnapshots, useAsync, useFavorites, useWatchProgress } from './hooks';
import {
  confirmWatchProgress, publishWatchProgress, watchProgressStore,
} from './watchProgress';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

describe('useAsync', () => {
  it('clears stale route data when its dependencies change', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const { result, rerender } = renderHook(
      ({ id }) => useAsync(() => id === 1 ? first.promise : second.promise, [id]),
      { initialProps: { id: 1 } },
    );

    await act(async () => first.resolve('first'));
    expect(result.current.data).toBe('first');

    rerender({ id: 2 });
    expect(result.current.data).toBeNull();
    expect(result.current.loading).toBe(true);

    await act(async () => {
      second.resolve('second');
    });
    expect(result.current.data).toBe('second');
  });

  it('ignores a result from a superseded generation', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const { result, rerender } = renderHook(
      ({ id }) => useAsync(() => id === 1 ? first.promise : second.promise, [id]),
      { initialProps: { id: 1 } },
    );

    rerender({ id: 2 });
    await act(async () => second.resolve('second'));
    expect(result.current.data).toBe('second');

    await act(async () => first.resolve('first'));
    expect(result.current.data).toBe('second');
  });

  it('does not request favorites until a playlist is resolved', () => {
    const favorites = vi.spyOn(api, 'favorites');
    renderHook(() => useFavorites(null));
    expect(favorites).not.toHaveBeenCalled();
    favorites.mockRestore();
  });
});

describe('useWatchProgress', () => {
  it('keeps an unsaved player position when the returning detail route reads stale progress', async () => {
    clearUserActivitySnapshots();
    const resumes = deferred<Awaited<ReturnType<typeof api.resumeAll>>>();
    vi.spyOn(api, 'resumeAll').mockReturnValue(resumes.promise);

    act(() => publishWatchProgress('movie', 600_000, 3_600_000));
    const first = renderHook(() => useWatchProgress());
    expect(first.result.current.get('movie')).toBeCloseTo(1 / 6);

    await act(async () => resumes.resolve([{
      contentId: 'movie', positionMs: 10_000, durationMs: 3_600_000, updatedMs: 1,
    }]));
    expect(first.result.current.get('movie')).toBeCloseTo(1 / 6);

    first.unmount();
    const second = renderHook(() => useWatchProgress());
    expect(second.result.current.get('movie')).toBeCloseTo(1 / 6);
    second.unmount();
    vi.restoreAllMocks();
    clearUserActivitySnapshots();
  });

  it('drops cached progress when the authenticated user changes', () => {
    publishWatchProgress('private-movie', 600_000, 3_600_000);
    clearUserActivitySnapshots();
    const view = renderHook(() => useWatchProgress());
    expect(view.result.current.has('private-movie')).toBe(false);
    view.unmount();
  });

  it('accepts later server progress after the matching save is confirmed', async () => {
    clearUserActivitySnapshots();
    const revision = publishWatchProgress('movie', 600_000, 3_600_000);
    confirmWatchProgress('movie', revision);
    vi.spyOn(api, 'resumeAll').mockResolvedValue([{
      contentId: 'movie', positionMs: 900_000, durationMs: 3_600_000, updatedMs: 2,
    }]);

    await act(() => watchProgressStore.refresh());

    expect(watchProgressStore.getSnapshot().get('movie')).toBe(0.25);
    vi.restoreAllMocks();
    clearUserActivitySnapshots();
  });

  it('clears optimistic progress inside the same end guard as the server', () => {
    clearUserActivitySnapshots();
    publishWatchProgress('movie', 60_000, 120_000);
    publishWatchProgress('movie', 106_000, 120_000);

    expect(watchProgressStore.getSnapshot().has('movie')).toBe(false);
    clearUserActivitySnapshots();
  });

  it('does not let the old account request block or overwrite the replacement account', async () => {
    clearUserActivitySnapshots();
    const oldAccount = deferred<Awaited<ReturnType<typeof api.resumeAll>>>();
    const newAccount = deferred<Awaited<ReturnType<typeof api.resumeAll>>>();
    vi.spyOn(api, 'resumeAll')
      .mockReturnValueOnce(oldAccount.promise)
      .mockReturnValueOnce(newAccount.promise);
    const oldView = renderHook(() => useWatchProgress());

    clearUserActivitySnapshots();
    oldView.unmount();
    const newView = renderHook(() => useWatchProgress());
    expect(api.resumeAll).toHaveBeenCalledTimes(2);

    await act(async () => newAccount.resolve([{
      contentId: 'new-account', positionMs: 60_000, durationMs: 120_000, updatedMs: 2,
    }]));
    expect(newView.result.current.get('new-account')).toBe(0.5);

    await act(async () => oldAccount.resolve([{
      contentId: 'old-account', positionMs: 60_000, durationMs: 120_000, updatedMs: 1,
    }]));
    expect(newView.result.current.has('old-account')).toBe(false);
    expect(newView.result.current.get('new-account')).toBe(0.5);
    newView.unmount();
    vi.restoreAllMocks();
    clearUserActivitySnapshots();
  });

  it('does not let an old account save confirm the replacement account position', async () => {
    clearUserActivitySnapshots();
    const oldRevision = publishWatchProgress('shared-movie', 60_000, 120_000);
    clearUserActivitySnapshots();
    publishWatchProgress('shared-movie', 90_000, 120_000);
    confirmWatchProgress('shared-movie', oldRevision);
    vi.spyOn(api, 'resumeAll').mockResolvedValue([{
      contentId: 'shared-movie', positionMs: 10_000, durationMs: 120_000, updatedMs: 1,
    }]);

    await act(() => watchProgressStore.refresh());

    expect(watchProgressStore.getSnapshot().get('shared-movie')).toBe(0.75);
    vi.restoreAllMocks();
    clearUserActivitySnapshots();
  });
});
