// Debounced search across live/movies/series with collapsible sections.
// Mirrors SearchScreen.kt.

import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { api, canShowGuide, Channel, ChannelKind, hasCatchup, SearchResults } from '../api';
import { mediaTags } from '../components/Badges';
import { EmptyState, LoadFailed } from '../components/Common';
import { DownloadStateIcon } from '../components/DownloadStateIcon';
import { GuideSheet } from '../components/GuideSheet';
import { Icon } from '../components/Icons';
import { MediaListRow } from '../components/MediaListRow';
import { Pager, SearchField, ScreenHeader } from '../components/Primitives';
import { errorMessage } from '../errors';
import { useDownloads, useFavorites, useGuideIds, usePaged } from '../hooks';
import { t } from '../i18n';
import { usePlayer } from '../player/PlayerNavigation';

export function SearchScreen() {
  const playlistId = Number(useParams().playlistId);
  const navigate = useNavigate();
  const { playChannel, playCatchup } = usePlayer();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResults | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [expanded, setExpanded] = useState({ live: true, movies: true, series: true });
  const [guideChannel, setGuideChannel] = useState<Channel | null>(null);
  const { favoriteContentIds, toggleFavorite } = useFavorites(playlistId);
  const downloads = useDownloads();
  const { guideIds } = useGuideIds(playlistId);

  useEffect(() => {
    setError(null);
    if (query.trim().length < 2) { setResults(null); return; }
    let current = true;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      api.search(playlistId, query.trim(), controller.signal).then(
        (next) => { if (current) setResults(next); },
        (cause: unknown) => {
          if (!current) return;
          setResults(null);
          setError(errorMessage(cause));
        },
      );
    }, 150);
    return () => {
      current = false;
      clearTimeout(timer);
      controller.abort();
    };
  }, [playlistId, query, attempt]);

  const pageKey = `${playlistId}:${query.trim().toLowerCase()}`;
  const pagedLive = usePaged(results?.live ?? [], `l:${pageKey}`);
  const pagedMovies = usePaged(results?.movies ?? [], `m:${pageKey}`);
  const pagedSeries = usePaged(results?.series ?? [], `s:${pageKey}`);

  const section = (key: keyof typeof expanded, label: string, count: number) => (
    <button
      type="button" className="section-header w-full" aria-expanded={expanded[key]}
      onClick={() => setExpanded({ ...expanded, [key]: !expanded[key] })}
    >
      {label} · {count}
      <Icon name={expanded[key] ? 'expandLess' : 'expandMore'} />
    </button>
  );

  const isEmpty = results && !results.live.length && !results.movies.length && !results.series.length;

  return (
    <>
      <ScreenHeader title={t('search.title')} />
      <SearchField placeholder={t('search.placeholder')} value={query} onChange={setQuery} autoFocus />

      {query.trim().length < 2 && (
        <EmptyState title={t('search.everythingTitle')} subtitle={t('search.everythingSub')} />
      )}
      {error != null && <LoadFailed message={error} onRetry={() => setAttempt((n) => n + 1)} />}
      {isEmpty && <EmptyState title={t('search.noResults')} subtitle={t('search.noResultsSub', { query: query.trim() })} />}

      {results && !isEmpty && (
        <div className="list">
          {results.live.length > 0 && section('live', t('nav.live'), results.live.length)}
          {expanded.live && pagedLive.pageItems.map((c) => (
            <MediaListRow
              key={c.id} title={c.name} subtitle={c.groupTitle}
              logo={c.logo} kind={c.kind} tags={mediaTags(c.name, 1)}
              isFavorite={favoriteContentIds.has(c.contentId)}
              onToggleFavorite={() => toggleFavorite(c.contentId)}
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
              isFavorite={favoriteContentIds.has(c.contentId)}
              onToggleFavorite={() => toggleFavorite(c.contentId)}
              downloadSlot={
                <DownloadStateIcon state={downloads.byContentId.get(c.contentId)}
                                   onDownload={() => api.enqueueDownload(c.contentId)} onChanged={downloads.refresh} />
              }
              onClick={() => navigate(`/movie/${c.contentId}`)}
            />
          ))}
          {expanded.movies && <Pager page={pagedMovies.page} pages={pagedMovies.pages} onPage={pagedMovies.setPage} />}

          {results.series.length > 0 && section('series', t('nav.series'), results.series.length)}
          {expanded.series && pagedSeries.pageItems.map((s) => {
            const routeKey = s.xtreamSeriesId != null ? `x:${s.xtreamSeriesId}` : s.seriesKey;
            return (
              <MediaListRow
                key={routeKey}
                title={s.seriesKey}
                subtitle={s.groupTitle + (s.count > 0 ? ` · ${t('search.matchingEpisodes', { count: s.count })}` : '')}
                logo={s.logo} kind={ChannelKind.SERIES} chevron
                isFavorite={favoriteContentIds.has(s.contentId)}
                onToggleFavorite={() => toggleFavorite(s.contentId)}
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
