// Browse the active playlist. The dock picks the section via ?t=; this screen
// handles the category level, filtering, list/grid toggle, now-airing lines,
// guide sheet and group-kind correction for M3U playlists.

import { ReactNode, useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import {
  api, canShowGuide, Channel, ChannelKind, ChannelListItem, GroupCount, hasCatchup,
  Programme,
} from '../api';
import { useAuth } from '../auth/AuthProvider';
import { mediaTags } from '../components/Badges';
import { asyncFallback, EmptyState } from '../components/Common';
import { DownloadStateIcon } from '../components/DownloadStateIcon';
import { GuideSheet } from '../components/GuideSheet';
import { Icon } from '../components/Icons';
import { MediaListRow } from '../components/MediaListRow';
import {
  Dialog, IconBtn, Pager, SearchField, ScreenHeader,
} from '../components/Primitives';
import { PosterGrid } from '../components/PosterGrid';
import { reportError, reportErrorAs, reportSuccess } from '../errors';
import {
  useAsync, useDownloads, useFavorites, useGuideIds, usePaged, useServerPaged,
} from '../hooks';
import { t } from '../i18n';
import { starRating } from '../lib/format';
import { prefs } from '../preferences';
import { usePlayer } from '../player/PlayerNavigation';

export function BrowseScreen() {
  const playlistId = Number(useParams().playlistId);
  const navigate = useNavigate();
  const [search, setSearch] = useSearchParams();
  const tab = Number(search.get('t') ?? ChannelKind.LIVE);
  const group = search.get('g');
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';

  const { playChannel, playCatchup } = usePlayer();
  const { data: detail, reload: reloadDetail } = useAsync(() => api.playlistDetail(playlistId), [playlistId]);
  const { favoriteContentIds, toggleFavorite } = useFavorites(playlistId);
  const downloads = useDownloads();
  const [filter, setFilter] = useState('');
  const [grid, setGrid] = useState(prefs.gridBrowse);
  const [guideChannel, setGuideChannel] = useState<ChannelListItem | null>(null);
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
    if (!isAdmin) return;
    api.refreshPlaylist(playlistId, false).then(reloadGuideIds, (cause: unknown) =>
      reportErrorAs((message) => t('browse.refreshFailed', { message }), cause));
  }, [isAdmin, playlistId, reloadGuideIds]);

  const groupsRequest = useAsync(
    async () => ({ tab, items: await api.groups(playlistId, tab) }),
    [playlistId, tab],
  );
  const groups = groupsRequest.data?.tab === tab ? groupsRequest.data.items : null;

  // Single group: skip the category level.
  const singleGroup = groups?.length === 1;
  useEffect(() => {
    const single = singleGroup ? groups?.[0] : undefined;
    if (group == null && single) setTabGroup(tab, single.groupTitle, true);
  }, [group, singleGroup, groups, tab, setTabGroup]);

  const listingFilter = filter.trim();
  const listingKey = `${playlistId}:${tab}:${group}:${listingFilter.toLowerCase()}`;
  const pagedChannels = useServerPaged(
    async (offset, limit) => {
      if (group == null || tab === ChannelKind.SERIES) {
        return { items: [], total: 0, offset, limit };
      }
      return api.channels(playlistId, tab, group, offset, limit, listingFilter);
    },
    `channels:${listingKey}`,
  );
  const pagedSeries = useServerPaged(
    async (offset, limit) => {
      if (group == null || tab !== ChannelKind.SERIES || isXtreamNative) {
        return { items: [], total: 0, offset, limit };
      }
      return api.seriesGroups(playlistId, group, offset, limit, listingFilter);
    },
    `series:${listingKey}:${isXtreamNative}`,
  );
  const pagedXtream = useServerPaged(
    async (offset, limit) => {
      if (group == null || tab !== ChannelKind.SERIES || !isXtreamNative) {
        return { items: [], total: 0, offset, limit };
      }
      return api.xtreamSeries(playlistId, group, offset, limit, listingFilter);
    },
    `xtream:${listingKey}:${isXtreamNative}`,
  );

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

  const pagedGroups = usePaged((groups ?? []).filter((g) => matches(g.groupTitle)), listingKey);

  const groupsPending = asyncFallback({ ...groupsRequest, data: groups });
  const channelsPending = asyncFallback({
    ...pagedChannels,
    data: pagedChannels.loading ? null : pagedChannels.pageItems,
  });
  const seriesPending = asyncFallback({
    ...pagedSeries,
    data: pagedSeries.loading ? null : pagedSeries.pageItems,
  });
  const xtreamPending = asyncFallback({
    ...pagedXtream,
    data: pagedXtream.loading ? null : pagedXtream.pageItems,
  });

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
        subtitle={detail?.playlist.hasXtreamPanel && isAdmin
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
        groupsPending ?? (
          <>
            <GroupList
              groups={pagedGroups.pageItems}
              onCorrect={isXtreamNative || !isAdmin ? null : setCorrectingGroup}
              onSelect={(g) => setTabGroup(tab, g)}
            />
            <Pager {...pagedGroups} onPage={pagedGroups.setPage} />
          </>
        )
      ) : tab === ChannelKind.SERIES && isXtreamNative ? (
        <CategoryItems pending={xtreamPending} count={pagedXtream.pageItems.length}>
          {grid ? (
            <PosterGrid
              kind={ChannelKind.SERIES}
              items={pagedXtream.pageItems.map((s) => ({
                id: String(s.seriesId), image: s.cover, title: s.name, subtitle: s.genre,
              }))}
              onClick={(id) => navigate(`/xseries/${playlistId}/${encodeURIComponent(id)}`)}
            />
          ) : (
            <div className="list">
              {pagedXtream.pageItems.map((s) => (
                <MediaListRow
                  key={s.seriesId}
                  title={s.name}
                  subtitle={[s.genre, s.rating != null ? starRating(s.rating) : null].filter(Boolean).join(' · ') || null}
                  logo={s.cover} kind={ChannelKind.SERIES} chevron
                  isFavorite={favoriteContentIds.has(s.contentId)}
                  onToggleFavorite={() => toggleFavorite(s.contentId)}
                  onClick={() => navigate(`/xseries/${playlistId}/${encodeURIComponent(s.seriesId)}`)}
                />
              ))}
            </div>
          )}
          <Pager {...pagedXtream} onPage={pagedXtream.setPage} />
        </CategoryItems>
      ) : tab === ChannelKind.SERIES ? (
        <CategoryItems pending={seriesPending} count={pagedSeries.pageItems.length}>
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
                  isFavorite={favoriteContentIds.has(s.contentId)}
                  onToggleFavorite={() => toggleFavorite(s.contentId)}
                  onClick={() => navigate(`/series/${playlistId}/${encodeURIComponent(s.seriesKey)}`)}
                />
              ))}
            </div>
          )}
          <Pager {...pagedSeries} onPage={pagedSeries.setPage} />
        </CategoryItems>
      ) : tab === ChannelKind.MOVIE && grid ? (
        <CategoryItems pending={channelsPending} count={pagedChannels.pageItems.length}>
          <PosterGrid
            kind={ChannelKind.MOVIE}
            items={pagedChannels.pageItems.map((c) => ({
              id: c.contentId, image: c.logo, title: c.name, tag: mediaTags(c.name, 1)[0],
            }))}
            onClick={(id) => navigate(`/movie/${id}`)}
          />
          <Pager {...pagedChannels} onPage={pagedChannels.setPage} />
        </CategoryItems>
      ) : (
        <CategoryItems pending={channelsPending} count={pagedChannels.pageItems.length}>
          <ChannelList
            channels={pagedChannels.pageItems}
            nowAiring={tab === ChannelKind.LIVE ? nowAiring : {}}
            favoriteContentIds={favoriteContentIds}
            onToggleFavorite={(c) => toggleFavorite(c.contentId)}
            onGuide={tab === ChannelKind.LIVE ? setGuideChannel : null}
            guideIds={guideIds}
            downloads={tab === ChannelKind.MOVIE ? downloads : null}
            onOpen={(c) => {
              if (tab === ChannelKind.MOVIE) navigate(`/movie/${c.contentId}`);
              else playChannel(c.contentId);
            }}
          />
          <Pager {...pagedChannels} onPage={pagedChannels.setPage} />
        </CategoryItems>
      )}

      {guideChannel && (
        <GuideSheet
          channel={guideChannel as Channel}
          onDismiss={() => setGuideChannel(null)}
          onPlayCatchup={(cid, s, e) => playCatchup(cid, s, e)}
        />
      )}
      {isAdmin && correctingGroup && (
        <GroupKindDialog
          groupTitle={correctingGroup}
          onDismiss={() => setCorrectingGroup(null)}
          onSelect={async (kind) => {
            setCorrectingGroup(null);
            try {
              await api.setGroupKind(playlistId, correctingGroup, kind);
              reportSuccess(kind == null ? t('browse.categoryAuto') : t('browse.categoryUpdated'));
              setTabGroup(tab, null, true);
              reloadDetail();
              groupsRequest.reload();
            } catch (e) { reportError(e); }
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
  const earlier = info.stale ? ` · ${t('account.earlierData')}` : '';
  return (
    <button type="button" className={`subtitle conn${warn ? ' warn' : ''}`}
            onClick={() => navigate(`/account/${playlistId}`)}>
      <Icon name="person" className="sm" />
      {t('browse.connections', { active: info.activeConnections, max: info.maxConnections })}{earlier}
    </button>
  );
}

function CategoryItems({ pending, count, children }: {
  pending: ReactNode | null;
  count: number;
  children: ReactNode;
}) {
  if (pending) return pending;
  if (count === 0) {
    return <EmptyState title={t('browse.emptyCategoryTitle')} subtitle={t('browse.emptyCategorySub')} />;
  }
  return children;
}

function GroupList({ groups, onCorrect, onSelect }: {
  groups: GroupCount[];
  onCorrect: ((group: string) => void) | null;
  onSelect: (group: string) => void;
}) {
  if (!groups.length) {
    return <EmptyState title={t('browse.emptyTitle')} subtitle={t('browse.emptySub')} />;
  }
  return (
    <div className="list">
      {groups.map((g) => (
        <div key={g.groupTitle} className="card interactive-card">
          <div className="group-row">
            <button
              type="button"
              className="group-open"
              aria-label={g.groupTitle}
              onClick={() => onSelect(g.groupTitle)}
            >
              <Icon name="folder" className="folder" />
              <span className="name">{g.groupTitle}</span>
              <span className="count">{g.count}</span>
              {!onCorrect && <Icon name="chevron" className="sm" />}
            </button>
            {onCorrect
              ? <IconBtn name="more" label={t('browse.correctCategory')} className="muted" onClick={() => onCorrect(g.groupTitle)} />
              : null}
          </div>
        </div>
      ))}
    </div>
  );
}

function ChannelList({ channels, nowAiring, favoriteContentIds, onToggleFavorite, onGuide, guideIds, downloads, onOpen }: {
  channels: ChannelListItem[];
  nowAiring: Record<string, Programme>;
  favoriteContentIds: Set<string>;
  onToggleFavorite: (c: ChannelListItem) => void;
  onGuide: ((c: ChannelListItem) => void) | null;
  guideIds: Set<string>;
  downloads: ReturnType<typeof useDownloads> | null;
  onOpen: (c: ChannelListItem) => void;
}) {
  const now = Date.now();
  return (
    <div className="list">
      {channels.map((c) => {
        const airing = c.tvgId ? nowAiring[c.tvgId] : undefined;
        return (
          <MediaListRow
            key={c.id}
            title={c.name}
            logo={c.logo} kind={c.kind} tags={mediaTags(c.name, 1)}
            airing={airing?.title}
            airingProgress={airing
              ? Math.min(1, Math.max(0, (now - airing.startMs) / Math.max(1, airing.endMs - airing.startMs)))
              : null}
            isFavorite={favoriteContentIds.has(c.contentId)}
            onToggleFavorite={() => onToggleFavorite(c)}
            onGuide={onGuide && canShowGuide(c, guideIds) ? () => onGuide(c) : null}
            guideHighlight={hasCatchup(c)}
            downloadSlot={downloads && (
              <DownloadStateIcon
                state={downloads.byContentId.get(c.contentId)}
                onDownload={() => api.enqueueDownload(c.contentId)}
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
