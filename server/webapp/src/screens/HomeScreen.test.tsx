import { render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, Channel, ChannelKind } from '../api';
import { ApiError } from '../api/http';
import { LibraryProvider } from '../library';
import { HomeScreen } from './HomeScreen';

vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ user: { role: 'USER' } }) }));

const playlist = { id: 1, name: 'Evening library', mode: 'url' as const,
  hasXtreamPanel: false, lastRefreshedMs: 0, channelCount: 100 };
const channel = (contentId: string): Channel => ({
  contentId, playlistId: 1, id: 1, name: `Film ${contentId}`, logo: null, groupTitle: 'Drama',
  tvgId: null, kind: ChannelKind.MOVIE, seriesKey: null, season: null, episode: null,
  position: 0, xtreamStreamId: null, catchupDays: 0, hasCatchup: false, description: null,
  durationSecs: 3600, airDate: null,
});
const renderHome = () => render(<MemoryRouter><LibraryProvider><HomeScreen /></LibraryProvider></MemoryRouter>);

describe('HomeScreen library', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'playlists').mockResolvedValue([playlist]);
    vi.spyOn(api, 'resumeAll').mockResolvedValue([]);
    vi.spyOn(api, 'channel').mockImplementation(async (contentId) => channel(String(contentId)));
    vi.spyOn(api, 'startRefreshJob').mockResolvedValue({ id: 'unused', status: 'SUCCEEDED', result: null });
  });

  it('offers browsing and search for each library without triggering provider refresh', async () => {
    const view = renderHome();
    expect(await view.findByRole('heading', { name: 'Evening library' })).toBeTruthy();
    expect(view.getByRole('link', { name: /^Evening library/ }).getAttribute('href')).toBe('/browse/1?t=0');
    expect(view.getByRole('link', { name: 'Search Evening library' }).getAttribute('href')).toBe('/search/1');
    expect(api.startRefreshJob).not.toHaveBeenCalled();
    expect(api.channel).not.toHaveBeenCalled();
  });

  it('caps recent lookups, skips completed films, and retains titles after a catalog removal', async () => {
    vi.mocked(api.resumeAll).mockResolvedValue([
      { contentId: 'done', positionMs: 990, durationMs: 1000, updatedMs: 100 },
      ...Array.from({ length: 8 }, (_, n) => ({
        contentId: String(n), positionMs: 300, durationMs: 1000, updatedMs: 10 - n,
      })),
    ]);
    vi.mocked(api.channel).mockImplementation(async (id) => {
      if (id === '0') throw new ApiError('Catalog entry removed', 404, 'not_found');
      return channel(String(id));
    });
    const view = renderHome();
    expect(await view.findByRole('link', { name: /Film 1/ })).toBeTruthy();
    expect(api.channel).toHaveBeenCalledTimes(6);
    expect(api.channel).not.toHaveBeenCalledWith('done');
    expect(api.channel).not.toHaveBeenCalledWith('6');
    expect(view.getAllByRole('progressbar')).toHaveLength(5);
    expect(view.getByRole('heading', { name: 'Evening library' })).toBeTruthy();
  });

  it('offers retry when recent title lookups fail instead of claiming nothing is unfinished', async () => {
    vi.mocked(api.resumeAll).mockResolvedValue([
      { contentId: 'recent', positionMs: 300, durationMs: 1000, updatedMs: 10 },
    ]);
    vi.mocked(api.channel).mockRejectedValueOnce(new Error('Title lookup unavailable'));
    const view = renderHome();
    expect(await view.findByText('Title lookup unavailable')).toBeTruthy();
    expect(view.getByRole('button', { name: 'Retry' })).toBeTruthy();
    expect(view.getByRole('heading', { name: 'Evening library' })).toBeTruthy();
  });

  it('keeps browsing available and offers retry when recent viewing fails', async () => {
    vi.mocked(api.resumeAll).mockRejectedValueOnce(new Error('Recent viewing unavailable'));
    const view = renderHome();
    await waitFor(() => expect(view.getByText('Recent viewing unavailable')).toBeTruthy());
    expect(view.getByRole('button', { name: 'Retry' })).toBeTruthy();
    expect(view.getByRole('link', { name: /^Evening library/ })).toBeTruthy();
  });
});
