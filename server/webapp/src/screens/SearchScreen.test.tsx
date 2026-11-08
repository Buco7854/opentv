import { act, fireEvent, render, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ChannelKind, SearchResults } from '../api';
import { SearchScreen } from './SearchScreen';

const playChannel = vi.fn();
vi.mock('../player/PlayerNavigation', () => ({ usePlayer: () => ({ playChannel, playCatchup: vi.fn() }) }));
const empty: SearchResults = { live: [], movies: [], series: [] };
const movie = { contentId: 'stable-film', id: 87, name: 'Moon', logo: null, groupTitle: 'Drama',
  tvgId: null, kind: ChannelKind.MOVIE, xtreamStreamId: null, catchupDays: 0, hasCatchup: false };
function Harness() {
  const location = useLocation();
  const navigate = useNavigate();
  return <><span data-testid="query">{location.search}</span><button onClick={() => navigate(-1)}>Go back</button>
    <Routes><Route path="/search/:playlistId" element={<SearchScreen />} />
      <Route path="*" element={<span>{location.pathname}</span>} /></Routes></>;
}
const renderSearch = (path = '/search/1') => render(<MemoryRouter initialEntries={[path]}><Harness /></MemoryRouter>);

describe('SearchScreen', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    playChannel.mockReset();
    vi.spyOn(api, 'favorites').mockResolvedValue([]);
    vi.spyOn(api, 'downloads').mockResolvedValue([]);
    vi.spyOn(api, 'guideIds').mockResolvedValue([]);
    vi.spyOn(api, 'search').mockResolvedValue(empty);
  });

  it('retains the search in the URL when returning from a title', async () => {
    vi.mocked(api.search).mockResolvedValue({ ...empty, movies: [movie] } as SearchResults);
    const view = renderSearch();
    fireEvent.change(view.getByRole('searchbox'), { target: { value: 'Moon' } });
    fireEvent.click(await view.findByRole('button', { name: 'Moon' }));
    expect(await view.findByText('/movie/stable-film')).toBeTruthy();
    fireEvent.click(view.getByRole('button', { name: 'Go back' }));
    const field = await view.findByRole('searchbox');
    expect((field as HTMLInputElement).value).toBe('Moon');
    expect(view.getByTestId('query').textContent).toBe('?q=Moon');
    expect(await view.findByRole('button', { name: 'Moon' })).toBeTruthy();
  });

  it('shows pending state, cancels obsolete searches, and does not show old results', async () => {
    let finishFirst!: (value: SearchResults) => void;
    vi.mocked(api.search).mockImplementationOnce(() => new Promise((resolve) => { finishFirst = resolve; }));
    const view = renderSearch('/search/1?q=Moon');
    expect(view.getByText('Searching…')).toBeTruthy();
    await waitFor(() => expect(api.search).toHaveBeenCalledWith(1, 'Moon', expect.any(AbortSignal)));
    const firstSignal = vi.mocked(api.search).mock.calls[0]![2]!;
    fireEvent.change(view.getByRole('searchbox'), { target: { value: 'Other' } });
    expect(firstSignal.aborted).toBe(true);
    await act(async () => { finishFirst({ ...empty, movies: [movie] } as SearchResults); });
    expect(view.queryByRole('button', { name: 'Moon' })).toBeNull();
    expect(await view.findByText('No results')).toBeTruthy();
    expect(view.getByText('Nothing matches "Other".')).toBeTruthy();
  });
});
