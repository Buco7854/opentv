import { describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  ApiHttpClient,
  browserAccessToken,
  isTransportError,
  setBrowserAccessToken,
} from './http';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

describe('ApiHttpClient', () => {
  it('attaches the bearer token to every HTTP method and sends no ambient credentials', async () => {
    const calls: Array<[RequestInfo | URL, RequestInit | undefined]> = [];
    const fetch: typeof globalThis.fetch = async (input, init) => {
      calls.push([input, init]);
      return json({ ok: true });
    };
    const client = new ApiHttpClient(fetch, () => 'opaque-session');

    await client.json('/probe', { method: 'POST' });

    const init = calls[0]![1]!;
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer opaque-session');
    expect(new Headers(init.headers).has('X-CSRF-Token')).toBe(false);
    expect(init.credentials).toBe('omit');
  });

  it('places only a short-lived socket capability in a WebSocket URL', () => {
    const client = new ApiHttpClient();
    const url = new URL(client.socketUrl('/playback/lease-1/ws', 'socket-capability'));

    expect(url.searchParams.get('ws_token')).toBe('socket-capability');
    expect(url.searchParams.has('access_token')).toBe(false);
  });

  it('reports a dropped connection as a transport failure, not an authorization outcome', async () => {
    const client = new ApiHttpClient(() => Promise.reject(new TypeError('Failed to fetch')));
    const unauthorized = vi.fn();
    const forbidden = vi.fn();
    client.onUnauthorized(unauthorized);
    client.onForbidden(forbidden);

    const error = await client.json('/playlists').catch((cause: unknown) => cause);

    expect(isTransportError(error)).toBe(true);
    expect((error as ApiError).code).toBe('network');
    expect(unauthorized).not.toHaveBeenCalled();
    expect(forbidden).not.toHaveBeenCalled();
  });

  it('rejects an HTML answer instead of parsing it as JSON', async () => {
    const html = new Response('<!doctype html><div id="root"></div>', {
      status: 200,
      headers: { 'Content-Type': 'text/html' },
    });
    const client = new ApiHttpClient(() => Promise.resolve(html));

    const error = await client.json('/unknown').catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('unexpected_response');
  });

  it('carries the structured server error', async () => {
    const client = new ApiHttpClient(
      () => Promise.resolve(json({ code: 'invalid_credentials', message: 'nope' }, 401)),
    );
    const unauthorized = vi.fn();
    client.onUnauthorized(unauthorized);

    const error = await client.json('/auth/me').catch((cause: unknown) => cause) as ApiError;

    expect(error.status).toBe(401);
    expect(error.code).toBe('invalid_credentials');
    expect(error.message).toBe('nope');
    expect(unauthorized).toHaveBeenCalled();
  });

  it('notifies forbidden separately and suppresses auth broadcasts for public flows', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ code: 'forbidden', message: 'no access' }, 403))
      .mockResolvedValueOnce(json({ code: 'invalid_credentials', message: 'wrong' }, 401));
    const client = new ApiHttpClient(fetch, () => 'current-session');
    const unauthorized = vi.fn();
    const forbidden = vi.fn();
    client.onUnauthorized(unauthorized);
    client.onForbidden(forbidden);

    await client.json('/admin/users').catch(() => {});
    await client.json(
      '/auth/password',
      { method: 'POST' },
      { broadcastAuthFailure: false },
    ).catch(() => {});

    expect(forbidden).toHaveBeenCalledOnce();
    expect(unauthorized).not.toHaveBeenCalled();
  });

  it('does not let an old in-flight 401 invalidate a replacement bearer', async () => {
    let token: string | null = 'old-session';
    let answer!: (response: Response) => void;
    const fetch = vi.fn(() => new Promise<Response>((resolve) => { answer = resolve; }));
    const client = new ApiHttpClient(fetch, () => token);
    const unauthorized = vi.fn();
    client.onUnauthorized(unauthorized);

    const request = client.json('/playlists').catch((cause: unknown) => cause);
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    token = 'replacement-session';
    answer(json({ code: 'unauthenticated', message: 'old session ended' }, 401));
    await request;

    expect(unauthorized).not.toHaveBeenCalled();
  });

  it('keeps the current-tab bearer in memory when browser storage is unavailable', () => {
    const get = vi.spyOn(Storage.prototype, 'getItem')
      .mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    const set = vi.spyOn(Storage.prototype, 'setItem')
      .mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });

    setBrowserAccessToken('memory-session');

    expect(browserAccessToken()).toBe('memory-session');
    get.mockRestore();
    set.mockRestore();
    setBrowserAccessToken(null);
  });

  it('gives up on a request that outlives its budget', async () => {
    vi.useFakeTimers();
    try {
      const client = new ApiHttpClient((_input, init) => new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      }));
      const request = client
        .json('/playlists', undefined, { timeoutMs: 10 })
        .catch((cause: unknown) => cause) as Promise<ApiError>;

      await vi.advanceTimersByTimeAsync(10);
      const error = await request;

      expect(error.status).toBe(0);
      expect(error.code).toBe('timeout');
    } finally {
      vi.useRealTimers();
    }
  });

  it('reads an empty success as no content', async () => {
    const client = new ApiHttpClient(() => Promise.resolve(new Response(null, { status: 204 })));
    await expect(client.json('/favorites')).resolves.toBeNull();
  });
});
