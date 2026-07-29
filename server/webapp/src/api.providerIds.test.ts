import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';

const json = (body: string) => new Response(body, {
  status: 200,
  headers: { 'Content-Type': 'application/json' },
});

describe('provider id wire contract', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('preserves an id above the JavaScript safe integer and encodes it in routes', async () => {
    const providerId = '9007199254740993';
    const fetch = vi.fn(async () => json(
      `{"series":{"contentId":"series-1","playlistId":7,"seriesId":"${providerId}",`
      + '"name":"Precise","categoryName":"Drama","cover":null,"plot":null,'
      + '"castNames":null,"genre":null,"rating":null,"episodesFetchedAtMs":0},'
      + '"episodes":[],"error":null}',
    ));
    vi.stubGlobal('fetch', fetch);

    const detail = await api.xseries(7, providerId);

    expect(detail.series.seriesId).toBe(providerId);
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/playlists/7/xseries/9007199254740993',
      expect.any(Object),
    );
  });

  it('treats an opaque provider id as one encoded path segment', async () => {
    const fetch = vi.fn(async () => json(
      '{"series":{"contentId":"series-1","playlistId":7,"seriesId":"opaque",'
      + '"name":"Precise","categoryName":"Drama","cover":null,"plot":null,'
      + '"castNames":null,"genre":null,"rating":null,"episodesFetchedAtMs":0},'
      + '"episodes":[],"error":null}',
    ));
    vi.stubGlobal('fetch', fetch);

    await api.xseries(7, 'series/?# é');

    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/playlists/7/xseries/series%2F%3F%23%20%C3%A9',
      expect.any(Object),
    );
  });
});
