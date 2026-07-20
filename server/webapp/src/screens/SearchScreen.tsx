// Debounced search across live/movies/series with collapsible sections.
// Mirrors SearchScreen.kt.

import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, canShowGuide, Channel, ChannelKind, hasCatchup, SearchResults } from '../api';
import { mediaTags } from '../components/Badges';
import { EmptyState } from '../components/Common';
import { DownloadStateIcon } from '../components/DownloadStateIcon';
import { GuideSheet } from '../components/GuideSheet';
import { Icon } from '../components/Icons';
import { MediaListRow } from '../components/MediaListRow';
import { Pager, SearchField, ScreenHeader } from '../components/Primitives';
import { useDownloads, useFavorites, useGuideIds, usePaged } from '../hooks';
import { t } from '../i18n';
import { usePlayer } from '../player/PlayerProvider';

export function SearchScreen() {
  const playlistId = Number(useParams().playlistId);
  const navigate = useNavigate();
  const { playChannel, playCatchup } = usePlayer();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResults | null>(null);
  const [expanded, setExpanded] = useState({ live: true, movies: true, series: true });
  const [guideChannel, setGuideChannel] = useState<Channel | null>(null);
  const { favoriteKeys, toggleFavorite } = useFavorites(playlistId);
  const downloads = useDownloads();
  const { guideIds } = useGuideIds(playlistId);

  // Debounced to limit server hits while typing.
  useEffect(() => {
    if (query.trim().length < 2) { setResults(null); return; }
    const timer = setTimeout(() => {
      api.search(playlistId, query.trim()).then(setResults).catch(() => setResults({ live: [], movies: [], series: [] }));
    }, 250);
    return () => clearTimeout(timer);
  }, [playlistId, query]);

  const pageKey = `${playlistId}:${query.trim().toLowerCase()}`;
  const pagedLive = usePaged(results?.live ?? [], `l:${pageKey}`);
  const pagedMovies = usePaged(results?.movies ?? [], `m:${pageKey}`);
  const pagedSeries = usePaged(results?.series ?? [], `s:${pageKey}`);

  const section = (key: keyof typeof expanded, label: string, count: number) => (
    <div className="section-header" onClick={() => setExpanded({ ...expanded, [key]: !expanded[key] })}>
      {label} · {count}
      <Icon name={expanded[key] ? 'expandLess' : 'expandMore'} />
    </div>
  );

  const isEmpty = results && !results.live.length && !results.movies.length && !results.series.length;

  return (
    <>
      <ScreenHeader title={t('search.title')} />
      <SearchField placeholder={t('search.placeholder')} value={query} onChange={setQuery} autoFocus />

      {query.trim().length < 2 && (
        <EmptyState title={t('search.everythingTitle')} subtitle={t('search.everythingSub')} />
      )}
      {isEmpty && <EmptyState title={t('search.noResults')} subtitle={t('search.noResultsSub', { query: query.trim() })} />}

      {results && !isEmpty && (
        <div className="list">
          {results.live.length > 0 && section('live', t('nav.live'), results.live.length)}
          {expanded.live && pagedLive.pageItems.map((c) => (
            <MediaListRow
              key={c.id} title={c.name} subtitle={c.groupTitle}
              logo={c.logo} kind={c.kind} tags={mediaTags(c.name, 1)}
              isFavorite={favoriteKeys.has(c.url)} onToggleFavorite={() => toggleFavorite(c.url, c.kind)}
              onGuide={canShowGuide(c, guideIds) ? () => setGuideChannel(c) : null}
              guideHighlight={hasCatchup(c)}
              onClick={() => playChannel(c.id)}
            />
          ))}
          {expanded.live && <Pager page={pagedLive.page} pages={pagedLive.pages} onPage={pagedLive.setPage} />}

          {results.movies.length > 0 && section('movies', t('nav.movies'), results.movies.length)}
          {expanded.movies && pagedMovies.pageItems.map((c) => (
            <MediaListRow
              key={c.id} title={c.name} subtitle={c.groupTitle}
              logo={c.logo} kind={c.kind} tags={mediaTags(c.name, 1)}
              isFavorite={favoriteKeys.has(c.url)} onToggleFavorite={() => toggleFavorite(c.url, c.kind)}
              downloadSlot={
                <DownloadStateIcon state={downloads.byUrl.get(c.url)}
                                   onDownload={() => api.enqueueDownload(c.id)} onChanged={downloads.refresh} />
              }
              onClick={() => navigate(`/movie/${c.id}`)}
            />
          ))}
          {expanded.movies && <Pager page={pagedMovies.page} pages={pagedMovies.pages} onPage={pagedMovies.setPage} />}

          {results.series.length > 0 && section('series', t('nav.series'), results.series.length)}
          {expanded.series && pagedSeries.pageItems.map((s) => {
            const favKey = s.xtreamSeriesId != null ? `x:${s.xtreamSeriesId}` : s.seriesKey;
            return (
              <MediaListRow
                key={favKey}
                title={s.seriesKey}
                subtitle={s.groupTitle + (s.count > 0 ? ` · ${t('search.matchingEpisodes', { count: s.count })}` : '')}
                logo={s.logo} kind={ChannelKind.SERIES} chevron
                isFavorite={favoriteKeys.has(favKey)}
                onToggleFavorite={() => toggleFavorite(favKey, ChannelKind.SERIES)}
                onClick={() => s.xtreamSeriesId != null
                  ? navigate(`/xseries/${playlistId}/${s.xtreamSeriesId}`)
                  : navigate(`/series/${playlistId}/${encodeURIComponent(s.seriesKey)}`)}
              />
            );
          })}
          {expanded.series && <Pager page={pagedSeries.page} pages={pagedSeries.pages} onPage={pagedSeries.setPage} />}
        </div>
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
