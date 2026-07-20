// Browse the active playlist. The dock picks the section via ?t=; this screen
// handles the category level, filtering, list/grid toggle, now-airing lines,
// guide sheet and group-kind correction for M3U playlists.

import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  api, canShowGuide, Channel, ChannelKind, GroupCount, hasCatchup, prefs,
  Programme, SeriesGroup, XtreamSeries,
} from '../api';
import { mediaTags } from '../components/Badges';
import { EmptyState } from '../components/Common';
import { DownloadStateIcon } from '../components/DownloadStateIcon';
import { GuideSheet } from '../components/GuideSheet';
import { Icon } from '../components/Icons';
import { MediaListRow } from '../components/MediaListRow';
import {
  Dialog, IconBtn, Pager, SearchField, snackbar, Spinner, ScreenHeader,
} from '../components/Primitives';
import { PosterGrid } from '../components/PosterGrid';
import { useAsync, useDownloads, useFavorites, useGuideIds, usePaged } from '../hooks';
import { t } from '../i18n';
import { starRating } from '../lib/format';
import { usePlayer } from '../player/PlayerProvider';

export function BrowseScreen() {
  const playlistId = Number(useParams().playlistId);
  const navigate = useNavigate();
  const [search, setSearch] = useSearchParams();
  const tab = Number(search.get('t') ?? ChannelKind.LIVE);
  const group = search.get('g');

  const { playChannel, playCatchup } = usePlayer();
  const { data: detail } = useAsync(() => api.playlistDetail(playlistId), [playlistId]);
  const { favoriteKeys, toggleFavorite } = useFavorites(playlistId);
  const downloads = useDownloads();
  const [filter, setFilter] = useState('');
  const [grid, setGrid] = useState(prefs.gridBrowse);
  const [guideChannel, setGuideChannel] = useState<Channel | null>(null);
  const [correctingGroup, setCorrectingGroup] = useState<string | null>(null);
  const [nowAiring, setNowAiring] = useState<Record<string, Programme>>({});

  const isXtreamNative = detail?.isXtreamNative ?? false;
  const { guideIds, reload: reloadGuideIds } = useGuideIds(playlistId);

  const setTabGroup = useCallback((tabIndex: number, g: string | null, replace = false) => {
    const q = new URLSearchParams();
    q.set('t', String(tabIndex));
    if (g != null) q.set('g', g);
    setSearch(q, { replace });
    setFilter('');
  }, [setSearch]);

  // Background refresh (throttled server-side).
  useEffect(() => {
    api.refreshPlaylist(playlistId, false).then(reloadGuideIds, (e: Error) =>
      snackbar(t('browse.refreshFailed', { message: e.message })));
  }, [playlistId, reloadGuideIds]);

  const { data: groups } = useAsync(async () => {
    if (tab === ChannelKind.SERIES && isXtreamNative) {
      return (await api.groups(playlistId, ChannelKind.SERIES));
    }
    return api.groups(playlistId, tab);
  }, [playlistId, tab, isXtreamNative]);

  // Single group: skip the category level.
  const singleGroup = groups?.length === 1;
  useEffect(() => {
    if (group == null && singleGroup) setTabGroup(tab, groups![0].groupTitle, true);
  }, [group, singleGroup, groups, tab, setTabGroup]);

  const { data: channels } = useAsync(async () => {
    if (group == null || tab === ChannelKind.SERIES) return [] as Channel[];
    return api.channels(playlistId, tab, group);
  }, [playlistId, tab, group]);

  const { data: seriesGroups } = useAsync(async () => {
    if (group == null || tab !== ChannelKind.SERIES || isXtreamNative) return [] as SeriesGroup[];
    return api.seriesGroups(playlistId, group);
  }, [playlistId, tab, group, isXtreamNative]);

  const { data: xtreamSeries } = useAsync(async () => {
    if (group == null || tab !== ChannelKind.SERIES || !isXtreamNative) return [] as XtreamSeries[];
    return api.xtreamSeries(playlistId, group);
  }, [playlistId, tab, group, isXtreamNative]);

  // Keep "now airing" rows fresh during long sessions.
  useEffect(() => {
    if (tab !== ChannelKind.LIVE) return;
    let cancelled = false;
    const load = () => api.nowAiring(playlistId).then((d) => { if (!cancelled) setNowAiring(d); }).catch(() => {});
    load();
    const timer = setInterval(load, 60_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [playlistId, tab]);

  const matches = useCallback(
    (s: string) => !filter.trim() || s.toLowerCase().includes(filter.trim().toLowerCase()),
    [filter],
  );

  const pageKey = `${playlistId}:${tab}:${group}:${filter.trim().toLowerCase()}`;
  const pagedGroups = usePaged((groups ?? []).filter((g) => matches(g.groupTitle)), pageKey);
  const pagedChannels = usePaged((channels ?? []).filter((c) => matches(c.name)), pageKey);
  const pagedSeries = usePaged((seriesGroups ?? []).filter((s) => matches(s.seriesKey)), pageKey);
  const pagedXtream = usePaged((xtreamSeries ?? []).filter((s) => matches(s.name)), pageKey);

  const counts = detail
    ? { [ChannelKind.LIVE]: detail.liveCount, [ChannelKind.MOVIE]: detail.movieCount, [ChannelKind.SERIES]: detail.seriesCount }
    : { 0: 0, 1: 0, 2: 0 };
  const sectionNames = { [ChannelKind.LIVE]: t('nav.live'), [ChannelKind.MOVIE]: t('nav.movies'), [ChannelKind.SERIES]: t('nav.series') };

  const atRoot = group == null || singleGroup;
  const title = (singleGroup ? null : group) ?? detail?.playlist.name ?? t('browse.title');

  return (
    <>
      <ScreenHeader
        title={title}
        onBack={atRoot ? undefined : () => setTabGroup(tab, null)}
        subtitle={detail?.playlist.hasXtreamPanel
          ? <ConnectionLine playlistId={playlistId} />
          : `${sectionNames[tab as 0 | 1 | 2] ?? ''} · ${counts[tab as 0 | 1 | 2] ?? 0}`}
        actions={
          group != null && tab !== ChannelKind.LIVE ? (
            <IconBtn name={grid ? 'listView' : 'grid'}
                     label={grid ? t('browse.listView') : t('browse.gridView')} className="muted"
                     onClick={() => { prefs.gridBrowse = !grid; setGrid(!grid); }} />
          ) : undefined
        }
      />

      <SearchField
        placeholder={group == null ? t('browse.filterCategories') : t('browse.filterCategory')}
        value={filter}
        onChange={setFilter}
      />

      {group == null ? (
        <>
          <GroupList
            groups={pagedGroups.pageItems}
            loaded={groups != null}
            // Only M3U group guessing needs correcting; Xtream categories are authoritative.
            onCorrect={isXtreamNative ? null : setCorrectingGroup}
            onSelect={(g) => setTabGroup(tab, g)}
          />
          <Pager {...pagedGroups} onPage={pagedGroups.setPage} />
        </>
      ) : tab === ChannelKind.SERIES && isXtreamNative ? (
        <>
          {grid ? (
            <PosterGrid
              kind={ChannelKind.SERIES}
              items={pagedXtream.pageItems.map((s) => ({
                id: String(s.seriesId), image: s.cover, title: s.name, subtitle: s.genre,
              }))}
              onClick={(id) => navigate(`/xseries/${playlistId}/${id}`)}
            />
          ) : (
            <div className="list">
              {pagedXtream.pageItems.map((s) => (
                <MediaListRow
                  key={s.seriesId}
                  title={s.name}
                  subtitle={[s.genre, s.rating != null ? starRating(s.rating) : null].filter(Boolean).join(' · ') || null}
                  logo={s.cover} kind={ChannelKind.SERIES} chevron
                  isFavorite={favoriteKeys.has(`x:${s.seriesId}`)}
                  onToggleFavorite={() => toggleFavorite(`x:${s.seriesId}`, ChannelKind.SERIES)}
                  onClick={() => navigate(`/xseries/${playlistId}/${s.seriesId}`)}
                />
              ))}
            </div>
          )}
          <Pager {...pagedXtream} onPage={pagedXtream.setPage} />
        </>
      ) : tab === ChannelKind.SERIES ? (
        <>
          {grid ? (
            <PosterGrid
              kind={ChannelKind.SERIES}
              items={pagedSeries.pageItems.map((s) => ({
                id: s.seriesKey, image: s.logo, title: s.seriesKey,
                subtitle: t('browse.episodes', { count: s.count }),
              }))}
              onClick={(key) => navigate(`/series/${playlistId}/${encodeURIComponent(key)}`)}
            />
          ) : (
            <div className="list">
              {pagedSeries.pageItems.map((s) => (
                <MediaListRow
                  key={s.seriesKey}
                  title={s.seriesKey} subtitle={t('browse.episodes', { count: s.count })}
                  logo={s.logo} kind={ChannelKind.SERIES} chevron
                  isFavorite={favoriteKeys.has(s.seriesKey)}
                  onToggleFavorite={() => toggleFavorite(s.seriesKey, ChannelKind.SERIES)}
                  onClick={() => navigate(`/series/${playlistId}/${encodeURIComponent(s.seriesKey)}`)}
                />
              ))}
            </div>
          )}
          <Pager {...pagedSeries} onPage={pagedSeries.setPage} />
        </>
      ) : tab === ChannelKind.MOVIE && grid ? (
        <>
          <PosterGrid
            kind={ChannelKind.MOVIE}
            items={pagedChannels.pageItems.map((c) => ({
              id: String(c.id), image: c.logo, title: c.name, tag: mediaTags(c.name, 1)[0],
            }))}
            onClick={(id) => navigate(`/movie/${id}`)}
          />
          <Pager {...pagedChannels} onPage={pagedChannels.setPage} />
        </>
      ) : (
        <>
          <ChannelList
            channels={pagedChannels.pageItems}
            loaded={channels != null}
            nowAiring={tab === ChannelKind.LIVE ? nowAiring : {}}
            favoriteKeys={favoriteKeys}
            onToggleFavorite={(c) => toggleFavorite(c.url, c.kind)}
            onGuide={tab === ChannelKind.LIVE ? setGuideChannel : null}
            guideIds={guideIds}
            downloads={tab === ChannelKind.MOVIE ? downloads : null}
            onOpen={(c) => {
              if (tab === ChannelKind.MOVIE) navigate(`/movie/${c.id}`);
              else playChannel(c.id);
            }}
          />
          <Pager {...pagedChannels} onPage={pagedChannels.setPage} />
        </>
      )}

      {guideChannel && (
        <GuideSheet
          channel={guideChannel}
          onDismiss={() => setGuideChannel(null)}
          onPlayCatchup={(cid, s, e) => playCatchup(cid, s, e)}
        />
      )}
      {correctingGroup && (
        <GroupKindDialog
          groupTitle={correctingGroup}
          onDismiss={() => setCorrectingGroup(null)}
          onSelect={async (kind) => {
            setCorrectingGroup(null);
            try {
              await api.setGroupKind(playlistId, correctingGroup, kind);
              snackbar(kind == null ? t('browse.categoryAuto') : t('browse.categoryUpdated'));
              setTabGroup(tab, null, true);
            } catch (e) { snackbar((e as Error).message); }
          }}
        />
      )}
    </>
  );
}

function ConnectionLine({ playlistId }: { playlistId: number }) {
  const navigate = useNavigate();
  const { data: info } = useAsync(() => api.account(playlistId, false), [playlistId]);
  if (!info) return null;
  const warn = info.maxConnections >= 1 && info.activeConnections >= info.maxConnections;
  return (
    <div className={`subtitle conn${warn ? ' warn' : ''}`} onClick={() => navigate(`/account/${playlistId}`)}>
      <Icon name="person" className="sm" />
      {t('browse.connections', { active: info.activeConnections, max: info.maxConnections })}
    </div>
  );
}

function GroupList({ groups, loaded, onCorrect, onSelect }: {
  groups: GroupCount[];
  loaded: boolean;
  onCorrect: ((group: string) => void) | null;
  onSelect: (group: string) => void;
}) {
  if (!loaded) return <Spinner />;
  if (!groups.length) {
    return <EmptyState title={t('browse.emptyTitle')} subtitle={t('browse.emptySub')} />;
  }
  return (
    <div className="list">
      {groups.map((g) => (
        <button key={g.groupTitle} className="card" onClick={() => onSelect(g.groupTitle)}>
          <div className="group-row">
            <Icon name="folder" className="folder" />
            <span className="name">{g.groupTitle}</span>
            <span className="count">{g.count}</span>
            {onCorrect
              ? <IconBtn name="more" label={t('browse.correctCategory')} className="muted" onClick={() => onCorrect(g.groupTitle)} />
              : <Icon name="chevron" className="sm" />}
          </div>
        </button>
      ))}
    </div>
  );
}

function ChannelList({ channels, loaded, nowAiring, favoriteKeys, onToggleFavorite, onGuide, guideIds, downloads, onOpen }: {
  channels: Channel[];
  loaded: boolean;
  nowAiring: Record<string, Programme>;
  favoriteKeys: Set<string>;
  onToggleFavorite: (c: Channel) => void;
  onGuide: ((c: Channel) => void) | null;
  guideIds: Set<string>;
  downloads: ReturnType<typeof useDownloads> | null;
  onOpen: (c: Channel) => void;
}) {
  if (!loaded) return <Spinner />;
  if (!channels.length) return <EmptyState title={t('browse.emptyCategoryTitle')} subtitle={t('browse.emptyCategorySub')} />;
  const now = Date.now();
  return (
    <div className="list">
      {channels.map((c) => {
        const airing = c.tvgId ? nowAiring[c.tvgId] : undefined;
        const epTag = c.season != null && c.episode != null
          ? `S${String(c.season).padStart(2, '0')}E${String(c.episode).padStart(2, '0')} · ` : '';
        return (
          <MediaListRow
            key={c.id}
            title={epTag + c.name}
            logo={c.logo} kind={c.kind} tags={mediaTags(c.name, 1)}
            airing={airing?.title}
            airingProgress={airing
              ? Math.min(1, Math.max(0, (now - airing.startMs) / Math.max(1, airing.endMs - airing.startMs)))
              : null}
            isFavorite={favoriteKeys.has(c.url)}
            onToggleFavorite={() => onToggleFavorite(c)}
            onGuide={onGuide && canShowGuide(c, guideIds) ? () => onGuide(c) : null}
            guideHighlight={hasCatchup(c)}
            downloadSlot={downloads && (
              <DownloadStateIcon
                state={downloads.byUrl.get(c.url)}
                onDownload={() => api.enqueueDownload(c.id)}
                onChanged={downloads.refresh}
              />
            )}
            onClick={() => onOpen(c)}
          />
        );
      })}
    </div>
  );
}

/** Correction dialog for misclassified M3U groups. */
function GroupKindDialog({ groupTitle, onDismiss, onSelect }: {
  groupTitle: string;
  onDismiss: () => void;
  onSelect: (kind: number | null) => void;
}) {
  return (
    <Dialog
      title={groupTitle}
      onDismiss={onDismiss}
      buttons={<button className="btn text" onClick={onDismiss}>{t('common.cancel')}</button>}
    >
      <p className="hint">{t('browse.correctHint')}</p>
      <div className="flex flex-col items-start">
        <button className="btn text" onClick={() => onSelect(ChannelKind.LIVE)}>{t('browse.correctLive')}</button>
        <button className="btn text" onClick={() => onSelect(ChannelKind.MOVIE)}>{t('browse.correctMovies')}</button>
        <button className="btn text" onClick={() => onSelect(ChannelKind.SERIES)}>{t('browse.correctSeries')}</button>
        <button className="btn text" onClick={() => onSelect(null)}>{t('browse.correctAuto')}</button>
      </div>
    </Dialog>
  );
}
