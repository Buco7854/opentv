// Movie / M3U-series / episode detail pages. Mirrors DetailScreens.kt.

import { ReactNode, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import {
  api, Channel, ChannelKind, EpisodeListItem, EpisodePage, imgUrl, Metadata,
} from '../api';
import { BadgeRow, mediaTags } from '../components/Badges';
import { CastMember, CastRow, castFromNames, decodeCast } from '../components/CastRow';
import { asyncFallback, EmptyState, FavoriteIcon, Pill } from '../components/Common';
import { DownloadStateIcon } from '../components/DownloadStateIcon';
import { Icon, kindIconName } from '../components/Icons';
import { Pager, SelectField, Spinner, ScreenHeader } from '../components/Primitives';
import { WatchProgressBar } from '../components/WatchProgress';
import {
  useAsync, useDownloads, useFavorites, usePaged, useServerPaged, useWatchProgress,
} from '../hooks';
import { t } from '../i18n';
import { episodeTag, fmtDuration, starRating } from '../lib/format';
import { usePlayer } from '../player/PlayerNavigation';

const YEAR_TAG = /\b(19|20)\d{2}\b/;

/** Facts from the playlist entry plus any enrichment. */
function metaChips(channel: Channel, meta: Metadata | null): string[] {
  const chips: string[] = [channel.groupTitle];
  const year = meta?.year ?? channel.name.match(YEAR_TAG)?.[0];
  if (year) chips.push(year);
  if (meta?.rating != null) chips.push(starRating(meta.rating));
  (meta?.infoLine ?? '').split(' · ').filter(Boolean).slice(0, 2).forEach((x) => chips.push(x));
  return [...new Set(chips)].slice(0, 4);
}

export function Poster({ image, kind, cover }: { image: string | null; kind: number; cover?: boolean }) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const failed = failedSrc !== null && failedSrc === image;
  return (
    <div className="poster">
      {image && !failed
        ? <img className={cover ? 'cover' : undefined} src={imgUrl(image)} data-src={image} alt="" onError={(e) => setFailedSrc(e.currentTarget.getAttribute("data-src"))} />
        : <Icon name={kindIconName(kind)} />}
    </div>
  );
}

function MetadataBlock({ meta }: { meta: Metadata | null }) {
  if (!meta) return null;
  const cast = decodeCast(meta.castJson);
  return (
    <>
      {meta.overview && <p className="overview">{meta.overview}</p>}
      {cast.length > 0 ? (
        <>
          <div className="section-h">{t('detail.cast')}</div>
          <CastRow members={cast} />
          {meta.castNames && !meta.castNames.startsWith('Cast:') &&
            <p className="watch-label">{meta.castNames}</p>}
        </>
      ) : meta.castNames && <p className="watch-label">{meta.castNames}</p>}
    </>
  );
}

function WatchedBlock({ fraction }: { fraction: number | undefined }) {
  if (fraction == null) return null;
  return (
    <div className="mt-6">
      <WatchProgressBar fraction={fraction} className="h-[5px]" />
      <p className="watch-label">{t('detail.watched', { percent: Math.round(fraction * 100) })}</p>
    </div>
  );
}

function PlayButton({ resumed, onClick }: { resumed: boolean; onClick: () => void }) {
  return (
    <button className="btn" onClick={onClick}>
      <Icon name="play" />
      {resumed ? t('common.resume') : t('common.play')}
    </button>
  );
}

function DownloadButton({ channel }: { channel: Channel }) {
  const downloads = useDownloads();
  return (
    <div className="dl-slot">
      <DownloadStateIcon
        state={downloads.byContentId.get(channel.contentId)}
        onDownload={() => api.enqueueDownload(channel.contentId)}
        onChanged={downloads.refresh}
      />
    </div>
  );
}

function DetailShell({ favorite, onBack, children }: {
  favorite?: ReactNode;
  onBack: () => void;
  children: ReactNode;
}) {
  return (
    <>
      <ScreenHeader title="" onBack={onBack} actions={favorite} />
      <div className="detail">{children}</div>
    </>
  );
}

export function MovieDetailScreen() {
  const channelId = useParams().channelId ?? '';
  const navigate = useNavigate();
  const { playChannel } = usePlayer();
  const { data: movie, error } = useAsync(() => api.channel(channelId), [channelId]);
  const { data: meta } = useAsync(
    async () => (movie ? api.vodInfo(channelId) : null),
    [channelId, movie != null],
  );
  const progress = useWatchProgress();
  const { favoriteContentIds, toggleFavorite } = useFavorites(movie?.playlistId ?? null);

  if (error) return <DetailShell onBack={() => navigate(-1)}><EmptyState title={t('detail.notFound')} subtitle={error} /></DetailShell>;
  if (!movie) return <DetailShell onBack={() => navigate(-1)}><Spinner /></DetailShell>;

  const fraction = progress.get(movie.contentId);
  return (
    <DetailShell
      onBack={() => navigate(-1)}
      favorite={
        <FavoriteIcon isFavorite={favoriteContentIds.has(movie.contentId)}
                      onToggle={() => toggleFavorite(movie.contentId)} />
      }
    >
      <Poster image={meta?.posterUrl ?? movie.logo} kind={ChannelKind.MOVIE} />
      <h2>{movie.name}</h2>
      <div className="pills">
        {metaChips(movie, meta).map((c) => <Pill key={c}>{c}</Pill>)}
        <BadgeRow tags={mediaTags(movie.name)} />
      </div>
      <MetadataBlock meta={meta} />
      <WatchedBlock fraction={fraction} />
      <div className="action-row">
        <PlayButton resumed={fraction != null}
                    onClick={() => playChannel(movie.id)} />
        <DownloadButton channel={movie} />
      </div>
    </DetailShell>
  );
}

/** Season picker + episode rows, shared by M3U and Xtream series pages. */
export function EpisodeList({ episodes }: { episodes: Channel[] }) {
  const [season, setSeason] = useState<number | null>(null);
  const seasons = [...new Set(episodes.map((e) => e.season).filter((s): s is number => s != null))].sort((a, b) => a - b);
  const shown = season == null ? episodes : episodes.filter((e) => e.season === season);
  const paged = usePaged(shown, season);

  return (
    <>
      <SeasonPicker seasons={seasons} season={season} onSeason={setSeason} />
      <EpisodeRows episodes={paged.pageItems} />
      <Pager {...paged} onPage={paged.setPage} />
    </>
  );
}

function SeasonPicker({ seasons, season, onSeason }: {
  seasons: number[];
  season: number | null;
  onSeason: (season: number | null) => void;
}) {
  if (seasons.length === 0) return null;
  return (
    <SelectField
      label={t('detail.season')}
      options={[[-1, t('detail.allSeasons')], ...seasons.map((s): [number, string] => [s, t('detail.seasonN', { n: s })])]}
      selected={season ?? -1}
      onSelect={(value) => onSeason(value === -1 ? null : value)}
    />
  );
}

function EpisodeRows({ episodes }: { episodes: Array<Channel | EpisodeListItem> }) {
  const navigate = useNavigate();
  const progress = useWatchProgress();
  const downloads = useDownloads();
  return (
    <div className="mt-3 flex flex-col gap-2">
      {episodes.map((ep) => (
        <EpisodeRow key={ep.id} episode={ep}
                    progress={progress.get(ep.contentId)}
                    downloads={downloads}
                    onOpen={() => navigate(`/episode/${ep.contentId}`)} />
      ))}
    </div>
  );
}

function EpisodeRow({ episode, progress, downloads, onOpen }: {
  episode: Channel | EpisodeListItem;
  progress: number | undefined;
  downloads: ReturnType<typeof useDownloads>;
  onOpen: () => void;
}) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const failed = failedSrc !== null && failedSrc === episode.logo;
  const metaLine = [
    episode.durationSecs != null ? fmtDuration(episode.durationSecs) : null,
    episode.airDate,
  ].filter(Boolean).join(' · ');
  return (
    <button className="card" onClick={onOpen}>
      <div className="episode-row">
        <div className="thumb">
          {episode.logo && !failed
            ? <img loading="lazy" src={imgUrl(episode.logo)} data-src={episode.logo} alt="" onError={(e) => setFailedSrc(e.currentTarget.getAttribute("data-src"))} />
            : <Icon name="videoLib" />}
          {progress != null && <WatchProgressBar fraction={progress} />}
        </div>
        <div className="body">
          {episodeTag(episode) && <div className="tag">{episodeTag(episode)}</div>}
          <div className="title">{episode.name}</div>
          {metaLine && <div className="sub">{metaLine}</div>}
        </div>
        <DownloadStateIcon state={downloads.byContentId.get(episode.contentId)}
                           onDownload={() => api.enqueueDownload(episode.contentId)}
                           onChanged={downloads.refresh} />
      </div>
    </button>
  );
}

export function SeriesDetailScreen() {
  const { playlistId: pid, seriesKey } = useParams();
  const playlistId = Number(pid);
  const key = seriesKey!;
  const navigate = useNavigate();
  const [season, setSeason] = useState<number | null>(null);
  const episodes = useServerPaged(
    (offset, limit) => api.episodes(playlistId, key, offset, limit, season ?? undefined),
    `${playlistId}:${key}:${season}`,
  );
  const episodePage = episodes.data as EpisodePage | null;
  const { data: meta } = useAsync(() => api.meta('series', key), [key]);
  const { favoriteContentIds, toggleFavorite } = useFavorites(playlistId);

  const pending = asyncFallback({
    ...episodes,
    data: episodes.loading ? null : episodes.pageItems,
  });
  if (pending) return <DetailShell onBack={() => navigate(-1)}>{pending}</DetailShell>;
  if (episodes.total === 0 && season == null) {
    return (
      <DetailShell onBack={() => navigate(-1)}>
        <EmptyState title={t('browse.noEpisodesTitle')} subtitle={t('browse.noEpisodesSub', { series: key })} />
      </DetailShell>
    );
  }

  const seasons = episodePage?.seasons ?? [];
  const poster = meta?.posterUrl ?? episodes.pageItems.find((e) => e.logo)?.logo ?? null;
  const seriesContentId = episodePage?.seriesContentId ?? null;
  return (
    <DetailShell
      onBack={() => navigate(-1)}
      favorite={seriesContentId
        ? <FavoriteIcon isFavorite={favoriteContentIds.has(seriesContentId)}
                        onToggle={() => toggleFavorite(seriesContentId)} />
        : undefined}
    >
      <Poster image={poster} kind={ChannelKind.SERIES} />
      <h2>{key}</h2>
      <div className="pills">
        {episodePage?.groupTitle && <Pill>{episodePage.groupTitle}</Pill>}
        <Pill>{t('browse.episodes', { count: episodes.total })}</Pill>
        {seasons.length > 1 && <Pill>{t('detail.seasons', { count: seasons.length })}</Pill>}
        {meta?.rating != null && <Pill>{starRating(meta.rating)}</Pill>}
      </div>
      {meta?.infoLine && (
        <div className="pills mt-2">
          {[...new Set(meta.infoLine.split(' · '))].slice(0, 4).map((x) => <Pill key={x}>{x}</Pill>)}
        </div>
      )}
      <MetadataBlock meta={meta} />
      <div className="mt-4" />
      <SeasonPicker seasons={seasons} season={season} onSeason={setSeason} />
      <EpisodeRows episodes={episodes.pageItems} />
      <Pager {...episodes} onPage={episodes.setPage} />
    </DetailShell>
  );
}

export function EpisodeDetailScreen() {
  const channelId = useParams().channelId ?? '';
  const navigate = useNavigate();
  const { playChannel } = usePlayer();
  const progress = useWatchProgress();

  const request = useAsync(async () => {
    const ep = await api.channel(channelId);
    // Xtream episodes key by series id; M3U episodes key by series title.
    let seriesTitle: string | null = null;
    let seriesCast: CastMember[] = [];
    if (ep.seriesKey?.startsWith('xs:')) {
      const detail = await api.xseries(ep.playlistId, ep.seriesKey.slice(3)).catch(() => null);
      seriesTitle = detail?.series.name ?? null;
      seriesCast = castFromNames(detail?.series.castNames ?? null);
    } else {
      seriesTitle = ep.seriesKey;
      if (seriesTitle) {
        const meta = await api.meta('series', seriesTitle).catch(() => null);
        seriesCast = decodeCast(meta?.castJson ?? null);
      }
    }
    let info: Metadata | null = null;
    if (ep.description == null && ep.season != null && ep.episode != null && seriesTitle) {
      info = await api.metaEpisode(seriesTitle, ep.season, ep.episode).catch(() => null);
      if (info && !info.title && !info.overview && !info.posterUrl) info = null;
    }
    return { ep, seriesTitle, seriesCast, info };
  }, [channelId]);

  const pending = asyncFallback(request);
  if (pending) return <DetailShell onBack={() => navigate(-1)}>{pending}</DetailShell>;
  const { ep, seriesTitle, seriesCast, info } = request.data!;

  const chips = [...new Set([
    episodeTag(ep),
    ep.airDate ?? info?.year,
    ep.durationSecs != null ? fmtDuration(ep.durationSecs) : info?.infoLine,
    info?.rating != null ? starRating(info.rating) : null,
  ].filter((x): x is string => !!x))];
  const plot = ep.description ?? info?.overview;
  const fraction = progress.get(ep.contentId);

  return (
    <DetailShell onBack={() => navigate(-1)}>
      <Poster image={info?.posterUrl ?? ep.logo} kind={ChannelKind.SERIES} cover />
      {seriesTitle && <div className="series-label">{seriesTitle}</div>}
      <h2>{info?.title ?? ep.name}</h2>
      <div className="pills">{chips.map((c) => <Pill key={c}>{c}</Pill>)}</div>
      {plot && <p className="overview">{plot}</p>}
      {seriesCast.length > 0 && <><div className="section-h">{t('detail.cast')}</div><CastRow members={seriesCast} /></>}
      <WatchedBlock fraction={fraction} />
      <div className="action-row">
        <PlayButton resumed={fraction != null}
                    onClick={() => playChannel(ep.id)} />
        <DownloadButton channel={ep} />
      </div>
    </DetailShell>
  );
}
