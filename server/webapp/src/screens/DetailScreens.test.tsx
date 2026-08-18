import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, Channel, ChannelKind } from '../api';
import { t } from '../i18n';
import { EpisodeList } from './DetailScreens';

const episode = {
  id: 7,
  contentId: 'episode-7',
  playlistId: 1,
  name: 'Episode seven',
  groupTitle: 'Series',
  kind: ChannelKind.SERIES,
  logo: null,
  tvgId: null,
  xtreamStreamId: null,
  seriesKey: 'Series',
  season: 1,
  episode: 7,
  position: 0,
  catchupDays: 0,
  hasCatchup: false,
  durationSecs: 1200,
  airDate: null,
  description: null,
} satisfies Channel;

describe('episode rows', () => {
  beforeEach(() => {
    vi.spyOn(api, 'downloads').mockResolvedValue([]);
    vi.spyOn(api, 'resumeAll').mockResolvedValue([]);
  });

  it('keeps the download action outside the episode navigation control', () => {
    render(<MemoryRouter><EpisodeList episodes={[episode]} /></MemoryRouter>);

    const download = screen.getByRole('button', { name: t('downloads.download') });
    expect(download.parentElement?.closest('button')).toBeNull();
  });
});
