// One user-owned favorites list across every granted playlist. Playlist is a
// filter; kind remains the list's grouping, matching the Android client.

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { api, canShowGuide, Channel, ChannelKind, hasCatchup, Programme } from '../api';
import { mediaTags } from '../components/Badges';
import { asyncFallback, EmptyState } from '../components/Common';
import { DownloadStateIcon } from '../components/DownloadStateIcon';
import { GuideSheet } from '../components/GuideSheet';
import { MediaListRow } from '../components/MediaListRow';
import {
  ConfirmDialog, IconBtn, Pager, ScreenHeader, Segmented, toast,
} from '../components/Primitives';
import { GENERIC, reportError } from '../errors';
import { useAsync, useDownloads, usePaged } from '../hooks';
import { t } from '../i18n';
import { useLibrary } from '../library';
import { usePlayer } from '../player/PlayerNavigation';

const ALL_PLAYLISTS = 'all' as const;
type PlaylistFilter = typeof ALL_PLAYLISTS | number;
type Fav = { playlistId: number; contentId: string };

/** Playlist stays in every interaction key even though content ids are globally stable. */
const favKey = (kind: 'live' | 'movie' | 'series', favorite: Fav) =>
  `${kind}:${favorite.playlistId}:${favorite.contentId}`;

const EMPTY_GUIDE_IDS = new Set<string>();

export function FavoritesScreen() {
  const navigate = useNavigate();
  const { playChannel, playCatchup } = usePlayer();
  const { playlists } = useLibrary();
  const favorites = useAsync(api.userFavoritesResolved, []);
  const { data: resolved, reload } = favorites;
  const downloads = useDownloads();
  const [filter, setFilter] = useState<PlaylistFilter>(ALL_PLAYLISTS);
  const [guideChannel, setGuideChannel] = useState<Channel | null>(null);
  const [guideIds, setGuideIds] = useState<Record<number, Set<string>>>({});
  const [nowAiring, setNowAiring] = useState<Record<number, Record<string, Programme>>>({});
  const [selectMode, setSelectMode] = useState(false);
  const [selected, setSelected] = useState<Map<string, Fav>>(new Map());
  const [pendingDelete, setPendingDelete] = useState(false);

  const playlistIds = useMemo(() => {
    const ids = new Set<number>();
    resolved?.live.forEach((item) => ids.add(item.playlistId));
    resolved?.movies.forEach((item) => ids.add(item.playlistId));
    resolved?.series.forEach((item) => ids.add(item.playlistId));
    return ids;
  }, [resolved]);
  const filterPlaylists = useMemo(() => {
    const known = (playlists ?? []).filter((playlist) => playlistIds.has(playlist.id));
    const knownIds = new Set(known.map((playlist) => playlist.id));
    return [
      ...known.map(({ id, name }) => ({ id, name })),
      ...[...playlistIds]
        .filter((id) => !knownIds.has(id))
        .map((id) => ({ id, name: String(id) })),
    ];
  }, [playlistIds, playlists]);

  useEffect(() => {
    if (filter !== ALL_PLAYLISTS && !playlistIds.has(filter)) setFilter(ALL_PLAYLISTS);
  }, [filter, playlistIds]);

  const visible = useMemo(() => ({
    live: (resolved?.live ?? []).filter((item) =>
      filter === ALL_PLAYLISTS || item.playlistId === filter),
    movies: (resolved?.movies ?? []).filter((item) =>
      filter === ALL_PLAYLISTS || item.playlistId === filter),
    series: (resolved?.series ?? []).filter((item) =>
      filter === ALL_PLAYLISTS || item.playlistId === filter),
  }), [filter, resolved]);

  // Unfavorite through the playlist that owns this row. Undo uses that same identity.
  const remove = async (favorite: Fav) => {
    const undo = async () => {
      try {
        await api.addFavorite(favorite.playlistId, favorite.contentId);
      } catch (cause) {
        reportError(cause, { [GENERIC]: () => t('favorites.saveFailed') });
      }
      reload();
    };
    try {
      await api.removeFavorite(favorite.playlistId, favorite.contentId);
    } catch (cause) {
      reportError(cause, { [GENERIC]: () => t('favorites.saveFailed') });
      reload();
      return;
    }
    reload();
    toast(t('favorites.removedOne'), {
      tone: 'success',
      action: { label: t('common.undo'), onClick: () => void undo() },
    });
  };

  const pagedLive = usePaged(visible.live, `live:${filter}`);
  const pagedMovies = usePaged(visible.movies, `movies:${filter}`);
  const pagedSeries = usePaged(visible.series, `series:${filter}`);
  const liveDecorationScopes = useMemo(() => {
    const scopes = new Map<number, Set<string>>();
    pagedLive.pageItems.forEach((item) => {
      if (item.tvgId == null) return;
      const ids = scopes.get(item.playlistId) ?? new Set<string>();
      ids.add(item.tvgId);
      scopes.set(item.playlistId, ids);
    });
    return [...scopes]
      .sort(([left], [right]) => left - right)
      .map(([playlistId, ids]): [number, string[]] => [playlistId, [...ids]]);
  }, [pagedLive.pageItems]);
  const liveDecorationKey = JSON.stringify(liveDecorationScopes);
  useEffect(() => {
    let cancelled = false;
    setGuideIds({});
    setNowAiring({});
    liveDecorationScopes.forEach(([playlistId, tvgIds]) => {
      api.guideIds(playlistId, tvgIds).then((ids) => {
        if (!cancelled) {
          setGuideIds((current) => ({ ...current, [playlistId]: new Set(ids) }));
        }
      }).catch(() => {});
      api.nowAiring(playlistId, tvgIds).then((airing) => {
        if (!cancelled) {
          setNowAiring((current) => ({ ...current, [playlistId]: airing }));
        }
      }).catch(() => {});
    });
    return () => { cancelled = true; };
  // The serialized scope is stable while an unchanged favorites page re-renders.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [liveDecorationKey]);
  const isEmpty = resolved && !resolved.live.length && !resolved.movies.length && !resolved.series.length;
  const now = Date.now();

  // Select-all follows the active playlist filter, as on Android.
  const allEntries = useMemo(() => {
    const map = new Map<string, Fav>();
    visible.live.forEach((item) => {
      const favorite = { playlistId: item.playlistId, contentId: item.contentId };
      map.set(favKey('live', favorite), favorite);
    });
    visible.movies.forEach((item) => {
      const favorite = { playlistId: item.playlistId, contentId: item.contentId };
      map.set(favKey('movie', favorite), favorite);
    });
    visible.series.forEach((item) => {
      const favorite = { playlistId: item.playlistId, contentId: item.contentId };
      map.set(favKey('series', favorite), favorite);
    });
    return map;
  }, [visible]);

  useEffect(() => {
    const available = new Set<string>();
    resolved?.live.forEach((item) => available.add(favKey('live', item)));
    resolved?.movies.forEach((item) => available.add(favKey('movie', item)));
    resolved?.series.forEach((item) => available.add(favKey('series', item)));
    setSelected((current) => new Map([...current].filter(([key]) => available.has(key))));
  }, [resolved]);

  const toggle = (selectionKey: string, favorite: Fav) => {
    setSelected((current) => {
      const next = new Map(current);
      if (next.has(selectionKey)) next.delete(selectionKey); else next.set(selectionKey, favorite);
      return next;
    });
  };
  const allSelected = allEntries.size > 0 && [...allEntries.keys()].every((key) => selected.has(key));
  const exitSelect = () => { setSelectMode(false); setSelected(new Map()); };
  const startSelect = (selectionKey: string, favorite: Fav) => {
    setSelectMode(true);
    setSelected(new Map([[selectionKey, favorite]]));
  };

  const deleteSelected = async () => {
    const entries = [...selected.values()];
    const results = await Promise.allSettled(
      entries.map((favorite) => api.removeFavorite(favorite.playlistId, favorite.contentId)),
    );
    setPendingDelete(false);
    exitSelect();
    reload();
    const removed = entries.filter((_, index) => results[index]?.status === 'fulfilled');
    const rejected = results.find((result) => result.status === 'rejected');
    if (rejected) reportError(rejected.reason, { [GENERIC]: () => t('favorites.saveFailed') });
    if (removed.length === 0) return;
    toast(t('favorites.removedN', { count: removed.length }), {
      tone: 'success',
      action: {
        label: t('common.undo'),
        onClick: () => {
          void Promise.allSettled(
            removed.map((favorite) => api.addFavorite(favorite.playlistId, favorite.contentId)),
          ).then((undone) => {
            const failure = undone.find((result) => result.status === 'rejected');
            if (failure) reportError(failure.reason, { [GENERIC]: () => t('favorites.saveFailed') });
            reload();
          });
        },
      },
    });
  };

  const rowClick = (selectionKey: string, favorite: Fav, open: () => void) =>
    () => (selectMode ? toggle(selectionKey, favorite) : open());

  const headerActions = !resolved || isEmpty ? undefined : selectMode ? (
    <>
      <IconBtn name="checkAll" label={allSelected ? t('favorites.selectNone') : t('favorites.selectAll')}
               className="muted" onClick={() => setSelected(allSelected ? new Map() : new Map(allEntries))} />
      <IconBtn name="del" label={t('favorites.removeSelected')} className="danger"
               disabled={selected.size === 0} onClick={() => setPendingDelete(true)} />
      <IconBtn name="close" label={t('common.cancel')} className="muted" onClick={exitSelect} />
    </>
  ) : (
    <IconBtn name="checklist" label={t('favorites.select')} className="muted"
             onClick={() => setSelectMode(true)} />
  );

  return (
    <>
      <ScreenHeader
        title={selectMode ? t('favorites.selectedN', { count: selected.size }) : t('favorites.title')}
        actions={headerActions}
      />
      {asyncFallback(favorites)}
      {isEmpty && <EmptyState title={t('favorites.emptyTitle')} subtitle={t('favorites.emptySub')} />}
      {resolved && !isEmpty && (
        <>
          {filterPlaylists.length > 1 && !selectMode && (
            <div className="search-wrap">
              <Segmented<PlaylistFilter>
                className="scroll"
                options={[
                  [ALL_PLAYLISTS, t('favorites.filterAll')],
                  ...filterPlaylists.map(({ id, name }) => [id, name] as [number, string]),
                ]}
                selected={filter}
                onSelect={setFilter}
              />
            </div>
          )}
          <div className="list">
            {visible.live.length > 0 &&
              <div className="section-header cursor-default">{t('nav.live')} · {visible.live.length}</div>}
            {pagedLive.pageItems.map((channel) => {
              const favorite = { playlistId: channel.playlistId, contentId: channel.contentId };
              const selectionKey = favKey('live', favorite);
              const airing = channel.tvgId
                ? nowAiring[channel.playlistId]?.[channel.tvgId]
                : undefined;
              return (
                <MediaListRow
                  key={selectionKey} title={channel.name} subtitle={channel.groupTitle}
                  logo={channel.logo} kind={channel.kind} tags={mediaTags(channel.name, 1)}
                  airing={airing?.title}
                  airingProgress={airing
                    ? Math.min(1, Math.max(0, (now - airing.startMs) / Math.max(1, airing.endMs - airing.startMs)))
                    : null}
                  isFavorite onToggleFavorite={() => remove(favorite)}
                  onGuide={canShowGuide(
                    channel,
                    guideIds[channel.playlistId] ?? EMPTY_GUIDE_IDS,
                  ) ? () => setGuideChannel(channel) : null}
                  guideHighlight={hasCatchup(channel)}
                  selectable={selectMode} selected={selected.has(selectionKey)}
                  onLongPress={selectMode ? undefined : () => startSelect(selectionKey, favorite)}
                  onClick={rowClick(selectionKey, favorite, () => playChannel(channel.contentId))}
                />
              );
            })}
            <Pager page={pagedLive.page} pages={pagedLive.pages} onPage={pagedLive.setPage} />

            {visible.movies.length > 0 &&
              <div className="section-header cursor-default">{t('nav.movies')} · {visible.movies.length}</div>}
            {pagedMovies.pageItems.map((channel) => {
              const favorite = { playlistId: channel.playlistId, contentId: channel.contentId };
              const selectionKey = favKey('movie', favorite);
              return (
                <MediaListRow
                  key={selectionKey} title={channel.name} subtitle={channel.groupTitle}
                  logo={channel.logo} kind={channel.kind} tags={mediaTags(channel.name, 1)}
                  isFavorite onToggleFavorite={() => remove(favorite)}
                  downloadSlot={
                    <DownloadStateIcon state={downloads.byContentId.get(channel.contentId)}
                                       onDownload={() => api.enqueueDownload(channel.contentId)}
                                       onChanged={downloads.refresh} />
                  }
                  selectable={selectMode} selected={selected.has(selectionKey)}
                  onLongPress={selectMode ? undefined : () => startSelect(selectionKey, favorite)}
                  onClick={rowClick(
                    selectionKey,
                    favorite,
                    () => navigate(`/movie/${channel.contentId}`),
                  )}
                />
              );
            })}
            <Pager page={pagedMovies.page} pages={pagedMovies.pages} onPage={pagedMovies.setPage} />

            {visible.series.length > 0 &&
              <div className="section-header cursor-default">{t('nav.series')} · {visible.series.length}</div>}
            {pagedSeries.pageItems.map((series) => {
              const favorite = { playlistId: series.playlistId, contentId: series.contentId };
              const selectionKey = favKey('series', favorite);
              return (
                <MediaListRow
                  key={selectionKey} title={series.seriesKey}
                  subtitle={series.groupTitle
                    + (series.count > 0 ? ` · ${t('browse.episodes', { count: series.count })}` : '')}
                  logo={series.logo} kind={ChannelKind.SERIES} chevron
                  isFavorite onToggleFavorite={() => remove(favorite)}
                  selectable={selectMode} selected={selected.has(selectionKey)}
                  onLongPress={selectMode ? undefined : () => startSelect(selectionKey, favorite)}
                  onClick={rowClick(selectionKey, favorite, () => series.xtreamSeriesId != null
                    ? navigate(`/xseries/${series.playlistId}/${encodeURIComponent(series.xtreamSeriesId)}`)
                    : navigate(`/series/${series.playlistId}/${encodeURIComponent(series.seriesKey)}`))}
                />
              );
            })}
            <Pager page={pagedSeries.page} pages={pagedSeries.pages} onPage={pagedSeries.setPage} />
          </div>
        </>
      )}

      {pendingDelete && (
        <ConfirmDialog
          title={t('favorites.deleteTitle')}
          message={t('favorites.deleteMessage', { count: selected.size })}
          confirmLabel={t('favorites.removeConfirm')}
          onConfirm={deleteSelected}
          onDismiss={() => setPendingDelete(false)}
        />
      )}

      {guideChannel && (
        <GuideSheet
          channel={guideChannel}
          onDismiss={() => setGuideChannel(null)}
          onPlayCatchup={(channelId, start, end) => playCatchup(channelId, start, end)}
        />
      )}
    </>
  );
}
