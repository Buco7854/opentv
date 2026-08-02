import { fireEvent, render, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, Channel, ChannelKind, UserFavoritesResolved } from '../api';
import { LibraryProvider } from '../library';
import { FavoritesScreen } from './FavoritesScreen';

vi.mock('../player/PlayerNavigation', () => ({
  usePlayer: () => ({ playChannel: vi.fn(), playCatchup: vi.fn() }),
}));

const channel = (
  contentId: string,
  playlistId: number,
  name: string,
  kind: number,
): Channel => ({
  contentId,
  playlistId,
  id: playlistId * 100 + kind,
  name,
  logo: null,
  groupTitle: kind === ChannelKind.LIVE ? 'News' : 'Drama',
  tvgId: kind === ChannelKind.LIVE ? `guide-${contentId}` : null,
  kind,
  seriesKey: null,
  season: null,
  episode: null,
  position: 0,
  xtreamStreamId: null,
  catchupDays: 0,
  hasCatchup: false,
  description: null,
  durationSecs: null,
  airDate: null,
});

const response: UserFavoritesResolved = {
  live: [
    channel('live-one', 1, 'Playlist One Live', ChannelKind.LIVE),
    channel('live-two', 2, 'Playlist Two Live', ChannelKind.LIVE),
  ],
  movies: [channel('movie-two', 2, 'Playlist Two Movie', ChannelKind.MOVIE)],
  series: [{
    contentId: 'series-two',
    playlistId: 2,
    seriesKey: 'The Show',
    count: 0,
    logo: null,
    groupTitle: 'Drama',
    xtreamSeriesId: '91',
  }],
};

function Location() {
  return <span data-testid="location">{useLocation().pathname}</span>;
}

function renderScreen() {
  return render(
    <MemoryRouter initialEntries={['/favorites']}>
      <LibraryProvider>
        <Routes>
          <Route path="/favorites" element={<FavoritesScreen />} />
          <Route path="*" element={<Location />} />
        </Routes>
      </LibraryProvider>
    </MemoryRouter>,
  );
}

describe('FavoritesScreen', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'playlists').mockResolvedValue([
      { id: 1, name: 'Playlist One', mode: 'url', hasXtreamPanel: false, lastRefreshedMs: 0, channelCount: 1 },
      { id: 2, name: 'Playlist Two', mode: 'xtream', hasXtreamPanel: true, lastRefreshedMs: 0, channelCount: 3 },
    ]);
    vi.spyOn(api, 'userFavoritesResolved').mockResolvedValue(response);
    vi.spyOn(api, 'guideIds').mockResolvedValue([]);
    vi.spyOn(api, 'nowAiring').mockResolvedValue({});
    vi.spyOn(api, 'downloads').mockResolvedValue([]);
    vi.spyOn(api, 'removeFavorite').mockResolvedValue(null);
    vi.spyOn(api, 'addFavorite').mockResolvedValue(null);
  });

  it('defaults to all playlists, narrows with chips, and groups the flat list by kind', async () => {
    const view = renderScreen();

    const all = await view.findByRole('button', { name: 'All' });
    expect(all.getAttribute('aria-pressed')).toBe('true');
    expect(view.getByText('Live · 2')).toBeTruthy();
    expect(view.getByText('Movies · 1')).toBeTruthy();
    expect(view.getByText('Series · 1')).toBeTruthy();
    expect(view.getByRole('button', { name: 'Playlist One Live' })).toBeTruthy();
    expect(view.getByRole('button', { name: 'Playlist Two Live' })).toBeTruthy();
    await waitFor(() => {
      expect(api.guideIds).toHaveBeenCalledWith(1, ['guide-live-one']);
      expect(api.guideIds).toHaveBeenCalledWith(2, ['guide-live-two']);
      expect(api.nowAiring).toHaveBeenCalledWith(1, ['guide-live-one']);
      expect(api.nowAiring).toHaveBeenCalledWith(2, ['guide-live-two']);
    });

    fireEvent.click(view.getByRole('button', { name: 'Playlist One' }));

    expect(view.getByRole('button', { name: 'Playlist One Live' })).toBeTruthy();
    expect(view.queryByRole('button', { name: 'Playlist Two Live' })).toBeNull();
    expect(view.queryByText('Movies · 1')).toBeNull();
    expect(view.queryByText('Series · 1')).toBeNull();
  });

  it('removes the playlist-qualified row when two playlists contain the same title', async () => {
    vi.mocked(api.userFavoritesResolved).mockResolvedValue({
      live: [
        channel('duplicate-one', 1, 'Same Title', ChannelKind.LIVE),
        channel('duplicate-two', 2, 'Same Title', ChannelKind.LIVE),
      ],
      movies: [],
      series: [],
    });
    const view = renderScreen();
    fireEvent.click(await view.findByRole('button', { name: 'Playlist Two' }));

    fireEvent.click(view.getByRole('button', { name: 'Remove from favorites' }));

    await waitFor(() => {
      expect(api.removeFavorite).toHaveBeenCalledWith(2, 'duplicate-two');
    });
    expect(api.removeFavorite).not.toHaveBeenCalledWith(1, 'duplicate-one');
  });

  it('opens an Xtream favorite through the playlist that owns it', async () => {
    const view = renderScreen();

    fireEvent.click(await view.findByRole('button', { name: 'The Show' }));

    expect(view.getByTestId('location').textContent).toBe('/xseries/2/91');
  });
});
