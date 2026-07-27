import { act, fireEvent, render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ChannelKind, GroupCount, PlaylistDetail } from '../api';
import { BrowseScreen } from './BrowseScreen';

vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ user: { role: 'USER' } }) }));
vi.mock('../player/PlayerNavigation', () => ({
  usePlayer: () => ({ playChannel: vi.fn(), playCatchup: vi.fn() }),
}));

const liveGroups: GroupCount[] = [{ groupTitle: 'All channels', count: 3 }];
const movieGroups: GroupCount[] = [
  { groupTitle: 'Action', count: 2 },
  { groupTitle: 'Comedy', count: 1 },
];

const detail: PlaylistDetail = {
  playlist: {
    id: 1, name: 'Test playlist', mode: 'url', hasXtreamPanel: false,
    lastRefreshedMs: 0, channelCount: 6,
  },
  isXtreamNative: false,
  liveCount: 3,
  movieCount: 3,
  seriesCount: 0,
};

function Harness() {
  const navigate = useNavigate();
  const location = useLocation();
  return (
    <>
      <button onClick={() => navigate('/browse/1?t=1')}>movies tab</button>
      <span data-testid="query">{location.search}</span>
      <BrowseScreen />
    </>
  );
}

const renderBrowse = () => render(
  <MemoryRouter initialEntries={['/browse/1?t=0']}>
    <Routes>
      <Route path="/browse/:playlistId" element={<Harness />} />
    </Routes>
  </MemoryRouter>,
);

describe('BrowseScreen', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'playlistDetail').mockResolvedValue(detail);
    vi.spyOn(api, 'groups').mockImplementation(async (_id, kind) =>
      (kind === ChannelKind.LIVE ? liveGroups : movieGroups));
    vi.spyOn(api, 'channels').mockResolvedValue([]);
    vi.spyOn(api, 'seriesGroups').mockResolvedValue([]);
    vi.spyOn(api, 'xtreamSeries').mockResolvedValue([]);
    vi.spyOn(api, 'nowAiring').mockResolvedValue({});
    vi.spyOn(api, 'guideIds').mockResolvedValue([]);
    vi.spyOn(api, 'favorites').mockResolvedValue([]);
    vi.spyOn(api, 'downloads').mockResolvedValue([]);
  });

  it('does not descend into the previous section category after a tab switch', async () => {
    const view = renderBrowse();
    await act(async () => {});
    expect(view.getByTestId('query').textContent).toBe('?t=0&g=All+channels');

    fireEvent.click(view.getByText('movies tab'));
    await act(async () => {});

    expect(view.getByTestId('query').textContent).toBe('?t=1');
    expect(view.getByText('Action')).toBeTruthy();
    expect(view.queryByText('Empty category')).toBeNull();
    view.unmount();
  });

  it('renders a retry instead of a spinner when the catalog fails to load', async () => {
    vi.mocked(api.groups).mockRejectedValueOnce(new Error('Forbidden'));
    const view = renderBrowse();
    await act(async () => {});

    expect(view.getByText('Forbidden')).toBeTruthy();
    expect(view.container.querySelector('.spinner')).toBeNull();

    fireEvent.click(view.getByRole('button', { name: 'Retry' }));
    await act(async () => {});

    expect(view.queryByText('Forbidden')).toBeNull();
    expect(view.getByTestId('query').textContent).toBe('?t=0&g=All+channels');
    view.unmount();
  });
});
