import { act, fireEvent, render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, PlaylistEditField } from '../api';
import { t } from '../i18n';
import { PlaylistDialog } from './PlaylistDialog';

describe('PlaylistDialog edit forms', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('renders and submits the fields supplied by the server, including file replacement', async () => {
    const update = vi.spyOn(api, 'updatePlaylist').mockResolvedValue({
      id: 7,
      name: 'Imported',
      mode: 'file',
      hasXtreamPanel: false,
      lastRefreshedMs: 123,
      channelCount: 4,
    });
    const onDone = vi.fn();
    const view = render(
      <PlaylistDialog
        editing={{
          id: 7,
          name: 'Imported',
          // The field vocabulary, not this legacy classifier, owns the rendered form.
          mode: 'url',
          fields: [PlaylistEditField.NAME, PlaylistEditField.CONTENT],
          storedFields: [PlaylistEditField.CONTENT],
        }}
        onDismiss={vi.fn()}
        onDone={onDone}
      />,
    );

    expect(view.queryByLabelText(t('playlists.url'))).toBeNull();
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    const replacement = new File(['#EXTM3U\n#EXTINF:-1,News\nhttps://example.test/news'], 'new.m3u');
    Object.defineProperty(replacement, 'text', {
      value: vi.fn().mockResolvedValue('#EXTM3U\n#EXTINF:-1,News\nhttps://example.test/news'),
    });
    fireEvent.change(input, { target: { files: [replacement] } });
    fireEvent.click(view.getByRole('button', { name: t('common.save') }));
    await act(async () => {});

    expect(update).toHaveBeenCalledWith(7, {
      name: 'Imported',
      content: '#EXTM3U\n#EXTINF:-1,News\nhttps://example.test/news',
    });
    expect(onDone).toHaveBeenCalledOnce();
  });
});
