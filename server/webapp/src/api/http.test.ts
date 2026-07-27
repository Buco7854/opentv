import { describe, expect, it, vi } from 'vitest';
import { ApiError, ApiHttpClient, isTransportError } from './http';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

describe('ApiHttpClient', () => {
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

  it('gives up on a request that outlives its budget', async () => {
    const client = new ApiHttpClient((_input, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));

    const error = await client
      .json('/playlists', undefined, { timeoutMs: 10 })
      .catch((cause: unknown) => cause) as ApiError;

    expect(error.status).toBe(0);
    expect(error.code).toBe('timeout');
  });

  it('reads an empty success as no content', async () => {
    const client = new ApiHttpClient(() => Promise.resolve(new Response(null, { status: 204 })));
    await expect(client.json('/favorites')).resolves.toBeNull();
  });
});
