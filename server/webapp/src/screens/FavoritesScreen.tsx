// Sectioned favorites resolved against the current channel tables. Mirrors
// FavoritesScreen.kt.

import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, canShowGuide, Channel, ChannelKind, hasCatchup, Programme } from '../api';
import { mediaTags } from '../components/Badges';
import { EmptyState } from '../components/Common';
import { DownloadStateIcon } from '../components/DownloadStateIcon';
import { GuideSheet } from '../components/GuideSheet';
import { MediaListRow } from '../components/MediaListRow';
import { ConfirmDialog, IconBtn, Pager, Spinner, ScreenHeader, snackbar } from '../components/Primitives';
import { useAsync, useDownloads, useGuideIds, usePaged } from '../hooks';
import { t } from '../i18n';
import { usePlayer } from '../player/PlayerNavigation';

/** Stable selection key for any favorite. */
const favKey = (kind: 'live' | 'movie' | 'series', id: string) => `${kind}:${id}`;

export function FavoritesScreen() {
  const playlistId = Number(useParams().playlistId);
  const navigate = useNavigate();
  const { playChannel, playCatchup } = usePlayer();
  const { data: resolved, reload } = useAsync(() => api.favoritesResolved(playlistId), [playlistId]);
  const { guideIds } = useGuideIds(playlistId);
  const downloads = useDownloads();
  const [guideChannel, setGuideChannel] = useState<Channel | null>(null);
  const [nowAiring, setNowAiring] = useState<Record<string, Programme>>({});
  type Fav = { key: string; kind: number };
  const [selectMode, setSelectMode] = useState(false);
  const [selected, setSelected] = useState<Map<string, Fav>>(new Map());
  const [pendingDelete, setPendingDelete] = useState(false);

  useEffect(() => {
    api.nowAiring(playlistId).then(setNowAiring).catch(() => {});
  }, [playlistId]);
  // Switching playlists clears the selection.
  useEffect(() => { setSelectMode(false); setSelected(new Map()); }, [playlistId]);

  // Unfavorite with an Undo toast that re-adds it.
  const remove = async (key: string, kind: number) => {
    await api.removeFavorite(playlistId, key).catch(() => {});
    reload();
    snackbar(t('favorites.removedOne'), {
      label: t('common.undo'),
      onClick: async () => { await api.addFavorite(playlistId, key, kind).catch(() => {}); reload(); },
    });
  };

  const pagedLive = usePaged(resolved?.live ?? [], `live:${playlistId}`);
  const pagedMovies = usePaged(resolved?.movies ?? [], `movies:${playlistId}`);
  const pagedSeries = usePaged(resolved?.series ?? [], `series:${playlistId}`);

  const isEmpty = resolved && !resolved.live.length && !resolved.movies.length && !resolved.series.length;
  const now = Date.now();

  // All favorites keyed by selection key, for select-all.
  const allEntries = useMemo(() => {
    const map = new Map<string, Fav>();
    resolved?.live.forEach((c) => map.set(favKey('live', String(c.id)), { key: c.url, kind: c.kind }));
    resolved?.movies.forEach((c) => map.set(favKey('movie', String(c.id)), { key: c.url, kind: c.kind }));
    resolved?.series.forEach((s) => {
      const key = s.xtreamSeriesId != null ? `x:${s.xtreamSeriesId}` : s.seriesKey;
      map.set(favKey('series', key), { key, kind: ChannelKind.SERIES });
    });
    return map;
  }, [resolved]);

  const toggle = (selKey: string, fav: Fav) => {
    setSelected((prev) => {
      const next = new Map(prev);
      if (next.has(selKey)) next.delete(selKey); else next.set(selKey, fav);
      return next;
    });
  };
  const allSelected = allEntries.size > 0 && selected.size === allEntries.size;
  const exitSelect = () => { setSelectMode(false); setSelected(new Map()); };
  // Long-press enters select mode with that item already selected.
  const startSelect = (selKey: string, fav: Fav) => { setSelectMode(true); setSelected(new Map([[selKey, fav]])); };

  const deleteSelected = async () => {
    // Dedup by favorite key (one key can map from multiple rows).
    const favs = [...new Map([...selected.values()].map((f) => [f.key, f])).values()];
    await Promise.all(favs.map((f) => api.removeFavorite(playlistId, f.key).catch(() => {})));
    exitSelect();
    reload();
    snackbar(t('favorites.removedN', { count: favs.length }), {
      label: t('common.undo'),
      onClick: async () => {
        await Promise.all(favs.map((f) => api.addFavorite(playlistId, f.key, f.kind).catch(() => {})));
        reload();
      },
    });
  };

  // In select mode a row click toggles selection, else runs the action.
  const rowClick = (selKey: string, fav: Fav, open: () => void) =>
    () => (selectMode ? toggle(selKey, fav) : open());

  const headerActions = !resolved || isEmpty ? undefined : selectMode ? (
    <>
      <IconBtn name="checkAll" label={allSelected ? t('favorites.selectNone') : t('favorites.selectAll')}
               className="muted" onClick={() => setSelected(allSelected ? new Map() : new Map(allEntries))} />
      <IconBtn name="del" label={t('favorites.removeSelected')} className="danger"
               disabled={selected.size === 0} onClick={() => setPendingDelete(true)} />
      <IconBtn name="close" label={t('common.cancel')} className="muted" onClick={exitSelect} />
    </>
  ) : (
    <IconBtn name="checklist" label={t('favorites.select')} className="muted" onClick={() => setSelectMode(true)} />
  );

  return (
    <>
      <ScreenHeader
        title={selectMode ? t('favorites.selectedN', { count: selected.size }) : t('favorites.title')}
        actions={headerActions}
      />
      {resolved === null && <Spinner />}
      {isEmpty && (
        <EmptyState title={t('favorites.emptyTitle')} subtitle={t('favorites.emptySub')} />
      )}
      {resolved && !isEmpty && (
        <div className="list">
          {resolved.live.length > 0 &&
            <div className="section-header" style={{ cursor: 'default' }}>{t('nav.live')} · {resolved.live.length}</div>}
          {pagedLive.pageItems.map((c) => {
            const airing = c.tvgId ? nowAiring[c.tvgId] : undefined;
            const sk = favKey('live', String(c.id));
            return (
              <MediaListRow
                key={c.id} title={c.name} subtitle={c.groupTitle} logo={c.logo} kind={c.kind}
                tags={mediaTags(c.name, 1)}
                airing={airing?.title}
                airingProgress={airing
                  ? Math.min(1, Math.max(0, (now - airing.startMs) / Math.max(1, airing.endMs - airing.startMs)))
                  : null}
                isFavorite onToggleFavorite={() => remove(c.url, c.kind)}
                onGuide={canShowGuide(c, guideIds) ? () => setGuideChannel(c) : null}
                guideHighlight={hasCatchup(c)}
                selectable={selectMode} selected={selected.has(sk)}
                onLongPress={selectMode ? undefined : () => startSelect(sk, { key: c.url, kind: c.kind })}
                onClick={rowClick(sk, { key: c.url, kind: c.kind }, () => playChannel(c.id))}
              />
            );
          })}
          <Pager page={pagedLive.page} pages={pagedLive.pages} onPage={pagedLive.setPage} />

          {resolved.movies.length > 0 &&
            <div className="section-header" style={{ cursor: 'default' }}>{t('nav.movies')} · {resolved.movies.length}</div>}
          {pagedMovies.pageItems.map((c) => {
            const sk = favKey('movie', String(c.id));
            return (
              <MediaListRow
                key={c.id} title={c.name} subtitle={c.groupTitle} logo={c.logo} kind={c.kind}
                tags={mediaTags(c.name, 1)}
                isFavorite onToggleFavorite={() => remove(c.url, c.kind)}
                downloadSlot={
                  <DownloadStateIcon state={downloads.byUrl.get(c.url)}
                                     onDownload={() => api.enqueueDownload(c.id)} onChanged={downloads.refresh} />
                }
                selectable={selectMode} selected={selected.has(sk)}
                onLongPress={selectMode ? undefined : () => startSelect(sk, { key: c.url, kind: c.kind })}
                onClick={rowClick(sk, { key: c.url, kind: c.kind }, () => navigate(`/movie/${c.id}`))}
              />
            );
          })}
          <Pager page={pagedMovies.page} pages={pagedMovies.pages} onPage={pagedMovies.setPage} />

          {resolved.series.length > 0 &&
            <div className="section-header" style={{ cursor: 'default' }}>{t('nav.series')} · {resolved.series.length}</div>}
          {pagedSeries.pageItems.map((s) => {
            const key = s.xtreamSeriesId != null ? `x:${s.xtreamSeriesId}` : s.seriesKey;
            const sk = favKey('series', key);
            return (
              <MediaListRow
                key={key} title={s.seriesKey}
                subtitle={s.groupTitle + (s.count > 0 ? ` · ${t('browse.episodes', { count: s.count })}` : '')}
                logo={s.logo} kind={ChannelKind.SERIES} chevron
                isFavorite onToggleFavorite={() => remove(key, ChannelKind.SERIES)}
                selectable={selectMode} selected={selected.has(sk)}
                onLongPress={selectMode ? undefined : () => startSelect(sk, { key, kind: ChannelKind.SERIES })}
                onClick={rowClick(sk, { key, kind: ChannelKind.SERIES }, () => s.xtreamSeriesId != null
                  ? navigate(`/xseries/${playlistId}/${s.xtreamSeriesId}`)
                  : navigate(`/series/${playlistId}/${encodeURIComponent(s.seriesKey)}`))}
              />
            );
          })}
          <Pager page={pagedSeries.page} pages={pagedSeries.pages} onPage={pagedSeries.setPage} />
        </div>
      )}

      {pendingDelete && (
        <ConfirmDialog
          title={t('favorites.deleteTitle')}
          message={t('favorites.deleteMessage', { count: new Set([...selected.values()].map((f) => f.key)).size })}
          confirmLabel={t('favorites.removeConfirm')}
          onConfirm={deleteSelected}
          onDismiss={() => setPendingDelete(false)}
        />
      )}

      {guideChannel && (
        <GuideSheet
          channel={guideChannel}
          onDismiss={() => setGuideChannel(null)}
          onPlayCatchup={(cid, s, e) => playCatchup(cid, s, e)}
        />
      )}
    </>
  );
}
