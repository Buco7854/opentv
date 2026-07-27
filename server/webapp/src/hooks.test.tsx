import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { useAsync, useFavorites } from './hooks';

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
