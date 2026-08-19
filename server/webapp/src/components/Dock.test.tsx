import { act, fireEvent, render, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  api, PlaylistDeleteEffect, PlaylistEditField, PlaylistOperation,
} from '../api';
import { languageSetting, t } from '../i18n';
import { Dock, playlistDeleteWarning, playlistRefreshNotice } from './Dock';

const library = vi.hoisted(() => ({
  reload: vi.fn().mockResolvedValue(undefined),
  rememberPlaylist: vi.fn(),
  forgetPlaylist: vi.fn(),
  setPlaylistPanelOpen: vi.fn(),
}));

vi.mock('../library', () => ({
  useLibrary: () => ({
    playlists: [{
      id: 7,
      name: 'Provider',
      mode: 'url',
      hasXtreamPanel: false,
      lastRefreshedMs: 0,
      channelCount: 12,
    }],
    loading: false,
    error: null,
    playlistPanelOpen: true,
    ...library,
  }),
}));
vi.mock('../hooks', () => ({
  useDownloads: () => ({ list: [], byContentId: new Map(), refresh: vi.fn() }),
}));
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    user: { role: 'ADMIN', displayName: 'Admin', username: 'admin' },
    logout: vi.fn().mockResolvedValue(undefined),
  }),
}));

function LocationMarker() {
  const location = useLocation();
  return <span data-testid="path">{location.pathname}</span>;
}

const renderDock = () => render(
  <MemoryRouter initialEntries={['/browse/7']}>
    <Dock />
    <Routes><Route path="*" element={<LocationMarker />} /></Routes>
  </MemoryRouter>,
);

async function openAction(view: ReturnType<typeof render>, label: string) {
  fireEvent.click(document.querySelector('button[aria-label="Playlist actions"]')!);
  fireEvent.click(await view.findByRole('menuitem', { name: label }));
}

describe('Dock playlist operations', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    library.reload.mockClear();
    library.rememberPlaylist.mockClear();
    library.forgetPlaylist.mockClear();
    document.getElementById('toast-root')?.remove();
    languageSetting.set('en');
  });

  afterEach(() => {
    languageSetting.set('auto');
    document.getElementById('toast-root')?.remove();
  });

  it('keeps playlist actions inside the selected playlist card and groups global actions', () => {
    const view = renderDock();
    const actions = view.getByLabelText(t('playlists.actions'));

    expect(actions.parentElement?.classList.contains('panel-row-group')).toBe(true);
    expect(actions.parentElement?.classList.contains('selected')).toBe(true);
    expect(view.getByText(t('nav.library'))).toBeTruthy();
    expect(view.getByText(t('nav.server'))).toBeTruthy();
    expect(view.getByText(t('nav.account'))).toBeTruthy();
  });

  it('loads the server-owned edit form before opening the dialog', async () => {
    vi.spyOn(api, 'playlistCapabilities').mockResolvedValue({
      operations: [{
        operation: PlaylistOperation.EDIT,
        execution: 'IN_APP',
        browserPath: null,
      }],
    });
    const edit = vi.spyOn(api, 'playlistEdit').mockResolvedValue({
      id: 7,
      name: 'Provider',
      mode: 'file',
      fields: [PlaylistEditField.NAME, PlaylistEditField.CONTENT],
      storedFields: [PlaylistEditField.CONTENT],
    });
    const view = renderDock();

    await openAction(view, t('playlists.edit.action'));

    expect(edit).toHaveBeenCalledWith(7);
    expect(await view.findByRole('dialog')).toBeTruthy();
    expect(view.queryByLabelText(t('playlists.url'))).toBeNull();
    expect(document.querySelector('input[type="file"]')).toBeTruthy();
  });

  it('loads deletion facts and renders them with client-localized wording', async () => {
    vi.spyOn(api, 'playlistCapabilities').mockResolvedValue({
      operations: [{
        operation: PlaylistOperation.DELETE,
        execution: 'IN_APP',
        browserPath: null,
      }],
    });
    const info = vi.spyOn(api, 'playlistDeleteInfo').mockResolvedValue({
      id: 7,
      name: 'Authoritative name',
      warning: 'Legacy English warning',
      effects: Object.values(PlaylistDeleteEffect),
    });
    const view = renderDock();

    await openAction(view, t('playlists.delete.action'));

    expect(info).toHaveBeenCalledWith(7);
    expect(await view.findByText(/Authoritative name/)).toBeTruthy();
    expect(view.queryByText('Legacy English warning')).toBeNull();
  });

  it('reports a successful catalog refresh with a failed guide as a partial failure', async () => {
    vi.spyOn(api, 'playlistCapabilities').mockResolvedValue({
      operations: [{
        operation: PlaylistOperation.REFRESH,
        execution: 'IN_APP',
        browserPath: null,
      }],
    });
    vi.spyOn(api, 'refreshPlaylist').mockResolvedValue({
      playlist: {
        id: 7,
        name: 'Provider',
        mode: 'url',
        hasXtreamPanel: false,
        lastRefreshedMs: 123,
        channelCount: 12,
      },
      catalogChanged: true,
      epgStatus: 'FAILED',
    });
    const view = renderDock();

    await openAction(view, t('playlists.refresh'));

    await waitFor(() => {
      expect(document.querySelector('.toast.error .toast-text')?.textContent)
        .toBe(t('playlists.refreshedGuideFailed'));
    });
    expect(document.body.textContent).not.toContain(t('playlists.refreshed'));
  });

  it('navigates browser-owned operations without calling their in-app endpoint', async () => {
    vi.spyOn(api, 'playlistCapabilities').mockResolvedValue({
      operations: [{
        operation: PlaylistOperation.DELETE,
        execution: 'BROWSER',
        browserPath: '/admin/playlists/7',
      }],
    });
    const info = vi.spyOn(api, 'playlistDeleteInfo');
    const view = renderDock();

    await openAction(view, t('playlists.delete.action'));
    await act(async () => {});

    expect(view.getByTestId('path').textContent).toBe('/admin/playlists/7');
    expect(info).not.toHaveBeenCalled();
  });

  it('localizes known deletion facts and distinguishes every EPG outcome', () => {
    languageSetting.set('fr');
    expect(playlistDeleteWarning({
      id: 7,
      name: 'Ma playlist',
      warning: 'English fallback',
      effects: Object.values(PlaylistDeleteEffect),
    })).toContain('seront supprimées');
    expect(playlistRefreshNotice({
      playlist: {} as never,
      catalogChanged: true,
      epgStatus: 'SUCCEEDED',
    }).message).toBe('Playlist et guide actualisés');
    expect(playlistRefreshNotice({
      playlist: {} as never,
      catalogChanged: true,
      epgStatus: 'NOT_CONFIGURED',
    }).message).toContain("aucun guide n'est configuré");
    expect(playlistRefreshNotice({
      playlist: {} as never,
      catalogChanged: true,
      epgStatus: 'FAILED',
    })).toEqual({
      message: "Playlist actualisée, mais l'actualisation du guide a échoué",
      tone: 'error',
    });
  });
});
