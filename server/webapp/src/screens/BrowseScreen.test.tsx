import { act, fireEvent, render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AccountInfo, api, ChannelKind, GroupCount, PlaylistDetail, PlaylistOperation,
} from '../api';
import { t } from '../i18n';
import { BrowseScreen } from './BrowseScreen';

vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ user: { role: 'ADMIN' } }) }));
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
      <span data-testid="path">{location.pathname}</span>
      <span data-testid="query">{location.search}</span>
      <BrowseScreen />
    </>
  );
}

const renderBrowse = () => render(
  <MemoryRouter initialEntries={['/browse/1?t=0']}>
    <Routes>
      <Route path="/browse/:playlistId" element={<Harness />} />
      <Route path="*" element={<LocationMarker />} />
    </Routes>
  </MemoryRouter>,
);

function LocationMarker() {
  const location = useLocation();
  return <span data-testid="path">{location.pathname}</span>;
}

describe('BrowseScreen', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'playlistDetail').mockResolvedValue(detail);
    vi.spyOn(api, 'playlistCapabilities').mockResolvedValue({ operations: [] });
    vi.spyOn(api, 'groups').mockImplementation(async (_id, kind) =>
      (kind === ChannelKind.LIVE ? liveGroups : movieGroups));
    vi.spyOn(api, 'channels').mockResolvedValue([]);
    vi.spyOn(api, 'seriesGroups').mockResolvedValue([]);
    vi.spyOn(api, 'xtreamSeries').mockResolvedValue([]);
    vi.spyOn(api, 'nowAiring').mockResolvedValue({});
    vi.spyOn(api, 'guideIds').mockResolvedValue([]);
    vi.spyOn(api, 'refreshPlaylist').mockResolvedValue({
      playlist: detail.playlist,
      catalogChanged: false,
      epgStatus: 'NOT_CONFIGURED',
    });
    vi.spyOn(api, 'favorites').mockResolvedValue([]);
    vi.spyOn(api, 'downloads').mockResolvedValue([]);
  });

  it('does not descend into the previous section category after a tab switch', async () => {
    vi.mocked(api.channels).mockResolvedValue({
      items: [{
        contentId: 'live-1',
        id: 1,
        name: 'News',
        logo: null,
        tvgId: 'news',
        kind: ChannelKind.LIVE,
        xtreamStreamId: null,
        catchupDays: 0,
        hasCatchup: false,
      }],
      total: 1,
      offset: 0,
      limit: 50,
    } as never);
    vi.mocked(api.nowAiring).mockResolvedValue({
      news: {
        id: 9,
        playlistId: 1,
        tvgId: 'news',
        title: 'Live bulletin',
        description: null,
        startMs: Date.now() - 1_000,
        endMs: Date.now() + 1_000,
      },
    });
    const view = renderBrowse();
    expect(await view.findByText('Live bulletin')).toBeTruthy();
    expect(api.nowAiring).toHaveBeenCalledTimes(1);
    expect(api.nowAiring).toHaveBeenCalledWith(1, ['news']);
    expect(api.guideIds).toHaveBeenCalledTimes(1);
    expect(api.guideIds).toHaveBeenCalledWith(1, ['news']);
    expect(view.getByTestId('query').textContent).toBe('?t=0&g=All+channels');

    fireEvent.click(view.getByText('movies tab'));
    await act(async () => {});

    expect(view.getByTestId('query').textContent).toBe('?t=1');
    expect(view.getByText('Action')).toBeTruthy();
    expect(view.container.querySelector('button button')).toBeNull();
    expect(view.queryByText('Empty category')).toBeNull();
    view.unmount();
  });

  it('keeps a loaded catalog when scoped decorations fail', async () => {
    vi.mocked(api.channels).mockResolvedValue({
      items: [{
        contentId: 'live-1', id: 1, name: 'News', logo: null, tvgId: 'news',
        kind: ChannelKind.LIVE, xtreamStreamId: null, catchupDays: 0, hasCatchup: false,
      }],
      total: 1,
      offset: 0,
      limit: 50,
    } as never);
    vi.mocked(api.nowAiring).mockRejectedValue(new Error('decoration unavailable'));
    vi.mocked(api.guideIds).mockRejectedValue(new Error('decoration unavailable'));

    const view = renderBrowse();

    expect(await view.findByText('News')).toBeTruthy();
    expect(view.queryByText('decoration unavailable')).toBeNull();
    expect(view.container.querySelector('.spinner')).toBeNull();
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

  it('offers category correction when the server capability permits it', async () => {
    vi.mocked(api.playlistCapabilities).mockResolvedValue({
      operations: [{
        operation: PlaylistOperation.CORRECT_CATEGORY_TYPE,
        execution: 'IN_APP',
        browserPath: null,
      }],
    });
    const setGroupKind = vi.spyOn(api, 'setGroupKind').mockResolvedValue(null);
    const view = renderBrowse();

    fireEvent.click(view.getByText('movies tab'));
    await view.findByText('Action');
    fireEvent.click(view.getAllByRole('button', { name: t('browse.correctCategory') })[0]!);
    fireEvent.click(await view.findByRole('button', { name: t('browse.correctSeries') }));
    await act(async () => {});

    expect(setGroupKind).toHaveBeenCalledWith(1, 'Action', ChannelKind.SERIES);
    view.unmount();
  });

  it('labels stale connection figures as earlier data', async () => {
    vi.mocked(api.playlistCapabilities).mockResolvedValue({
      operations: [{
        operation: PlaylistOperation.VIEW_PROVIDER_ACCOUNT,
        execution: 'IN_APP',
        browserPath: null,
      }],
    });
    vi.mocked(api.playlistDetail).mockResolvedValue({
      ...detail,
      playlist: {
        ...detail.playlist,
        mode: 'xtream',
        hasXtreamPanel: true,
      },
    });
    const account: AccountInfo = {
      activeConnections: 1,
      maxConnections: 2,
      status: 'Active',
      expiresAtMs: null,
      isTrial: false,
      createdAtMs: null,
      timezone: null,
      fetchedAtMs: 100,
      stale: true,
    };
    vi.spyOn(api, 'account').mockResolvedValue(account);

    const view = renderBrowse();

    const figures = t('browse.connections', { active: 1, max: 2 });
    expect(await view.findByText(`${figures} · ${t('account.earlierData')}`)).toBeTruthy();
    view.unmount();
  });

  it('follows browser capabilities instead of invoking their in-app operations', async () => {
    vi.mocked(api.playlistCapabilities).mockResolvedValue({
      operations: [
        {
          operation: PlaylistOperation.REFRESH,
          execution: 'BROWSER',
          browserPath: '/manage/provider',
        },
        {
          operation: PlaylistOperation.VIEW_PROVIDER_ACCOUNT,
          execution: 'BROWSER',
          browserPath: '/manage/provider',
        },
      ],
    });
    vi.mocked(api.playlistDetail).mockResolvedValue({
      ...detail,
      playlist: { ...detail.playlist, hasXtreamPanel: true },
    });
    const account = vi.spyOn(api, 'account');
    const view = renderBrowse();

    fireEvent.click(await view.findByRole('button', { name: t('playlists.account') }));

    expect((await view.findByTestId('path')).textContent).toBe('/manage/provider');
    expect(api.refreshPlaylist).not.toHaveBeenCalled();
    expect(account).not.toHaveBeenCalled();
    view.unmount();
  });

  it('routes browser-owned category correction without changing the category in-app', async () => {
    vi.mocked(api.playlistCapabilities).mockResolvedValue({
      operations: [{
        operation: PlaylistOperation.CORRECT_CATEGORY_TYPE,
        execution: 'BROWSER',
        browserPath: '/manage/categories',
      }],
    });
    const setGroupKind = vi.spyOn(api, 'setGroupKind').mockResolvedValue(null);
    const view = renderBrowse();

    fireEvent.click(view.getByText('movies tab'));
    await view.findByText('Action');
    fireEvent.click(view.getAllByRole('button', { name: t('browse.correctCategory') })[0]!);

    expect((await view.findByTestId('path')).textContent).toBe('/manage/categories');
    expect(setGroupKind).not.toHaveBeenCalled();
    view.unmount();
  });
});
