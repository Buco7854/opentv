import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';

const metadata = {
  cacheKey: '', title: null, year: null, overview: null, rating: null,
  castNames: null, castJson: null, posterUrl: null, infoLine: null,
  sourceId: null, fetchedAtMs: 0,
};

describe('staged movie metadata', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('addresses both the immediate panel response and the enriched response explicitly', async () => {
    const fetch = vi.fn(async (_input: RequestInfo | URL) => new Response(JSON.stringify(metadata), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetch);

    await api.vodInfo('film/content', false);
    await api.vodInfo('film/content', true);

    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/content/film%2Fcontent/vod-info?enrich=false',
      '/api/v1/content/film%2Fcontent/vod-info?enrich=true',
    ]);
  });
});
