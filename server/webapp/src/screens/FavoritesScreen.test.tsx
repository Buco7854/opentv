import { fireEvent, render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { FavoritesScreen } from './FavoritesScreen';

vi.mock('../player/PlayerNavigation', () => ({
  usePlayer: () => ({ playChannel: vi.fn(), playCatchup: vi.fn() }),
}));

function Location() {
  return <span data-testid="location">{useLocation().pathname}</span>;
}

describe('FavoritesScreen', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'favoritesResolved').mockResolvedValue({
      live: [],
      movies: [],
      series: [{
        contentId: 'series-content',
        seriesKey: 'The Show',
        count: 0,
        logo: null,
        groupTitle: 'Drama',
        xtreamSeriesId: '91',
      }],
    });
    vi.spyOn(api, 'guideIds').mockResolvedValue([]);
    vi.spyOn(api, 'nowAiring').mockResolvedValue({});
    vi.spyOn(api, 'downloads').mockResolvedValue([]);
  });

  it('opens an Xtream favorite by its series discriminator', async () => {
    const view = render(
      <MemoryRouter initialEntries={['/favorites/7']}>
        <Routes>
          <Route path="/favorites/:playlistId" element={<FavoritesScreen />} />
          <Route path="*" element={<Location />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await view.findByRole('button', { name: 'The Show' }));

    expect(view.getByTestId('location').textContent).toBe('/xseries/7/91');
  });
});
