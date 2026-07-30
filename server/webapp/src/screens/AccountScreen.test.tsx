import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountInfo, api, PlaylistDetail } from '../api';
import { getLocale, t } from '../i18n';
import { AccountScreen } from './AccountScreen';

const detail: PlaylistDetail = {
  playlist: {
    id: 1,
    name: 'Test playlist',
    mode: 'xtream',
    hasXtreamPanel: true,
    lastRefreshedMs: 0,
    channelCount: 0,
  },
  isXtreamNative: true,
  liveCount: 0,
  movieCount: 0,
  seriesCount: 0,
};

const fetchedAtMs = Date.UTC(2026, 0, 2, 10, 30);
const staleInfo: AccountInfo = {
  activeConnections: 1,
  maxConnections: 2,
  status: 'Active',
  expiresAtMs: null,
  isTrial: false,
  createdAtMs: null,
  timezone: null,
  fetchedAtMs,
  stale: true,
};

describe('AccountScreen', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'playlistDetail').mockResolvedValue(detail);
    vi.spyOn(api, 'account').mockResolvedValue(staleInfo);
  });

  it('labels cached data as stale and shows when it was actually fetched', async () => {
    const view = render(
      <MemoryRouter initialEntries={['/account/1']}>
        <Routes>
          <Route path="/account/:playlistId" element={<AccountScreen />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await view.findByText(t('account.stale'))).toBeTruthy();
    const fetchedTime = new Date(fetchedAtMs).toLocaleTimeString(getLocale());
    expect(view.getByText(t('account.updatedAt', { time: fetchedTime }))).toBeTruthy();
  });
});
