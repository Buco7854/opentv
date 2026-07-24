// Movie / M3U-series / episode detail pages. Mirrors DetailScreens.kt.

import { ReactNode, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, Channel, ChannelKind, imgUrl, Metadata } from '../api';
import { BadgeRow, mediaTags } from '../components/Badges';
import { CastMember, CastRow, castFromNames, decodeCast } from '../components/CastRow';
import { EmptyState, FavoriteIcon, Pill } from '../components/Common';
import { DownloadStateIcon } from '../components/DownloadStateIcon';
import { Icon, kindIconName } from '../components/Icons';
import { Pager, SelectField, Spinner, ScreenHeader } from '../components/Primitives';
import { WatchProgressBar } from '../components/WatchProgress';
import { useAsync, useDownloads, useFavorites, usePaged, useWatchProgress } from '../hooks';
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
  return chips.slice(0, 4);
}

export function Poster({ image, kind, cover }: { image: string | null; kind: number; cover?: boolean }) {
  const [failed, setFailed] = useState(false);
  return (
    <div className="poster">
      {image && !failed
        ? <img className={cover ? 'cover' : undefined} src={imgUrl(image)} alt="" onError={() => setFailed(true)} />
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
      <WatchProgressBar fraction={fraction} height={5} />
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
        state={downloads.byUrl.get(channel.url)}
        onDownload={() => api.enqueueDownload(channel.id)}
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
  const channelId = Number(useParams().channelId);
  const navigate = useNavigate();
  const { playChannel } = usePlayer();
  const { data: movie, error } = useAsync(() => api.channel(channelId), [channelId]);
  const { data: meta } = useAsync(
    async () => (movie ? api.vodInfo(channelId) : null),
    [channelId, movie != null],
  );
  const progress = useWatchProgress();
  const { favoriteKeys, toggleFavorite } = useFavorites(movie?.playlistId ?? 0);

  if (error) return <DetailShell onBack={() => navigate(-1)}><EmptyState title={t('detail.notFound')} subtitle={error} /></DetailShell>;
  if (!movie) return <DetailShell onBack={() => navigate(-1)}><Spinner /></DetailShell>;

  const fraction = progress.get(movie.url);
  return (
    <DetailShell
      onBack={() => navigate(-1)}
      favorite={
        <FavoriteIcon isFavorite={favoriteKeys.has(movie.url)}
                      onToggle={() => toggleFavorite(movie.url, movie.kind)} />
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
  const navigate = useNavigate();
  const progress = useWatchProgress();
  const downloads = useDownloads();
  const [season, setSeason] = useState<number | null>(null);
  const seasons = [...new Set(episodes.map((e) => e.season).filter((s): s is number => s != null))].sort((a, b) => a - b);
  const shown = season == null ? episodes : episodes.filter((e) => e.season === season);
  const paged = usePaged(shown, season);

  return (
    <>
      {seasons.length > 0 && (
        <SelectField
          label={t('detail.season')}
          options={[[-1, t('detail.allSeasons')], ...seasons.map((s): [number, string] => [s, t('detail.seasonN', { n: s })])]}
          selected={season ?? -1}
          onSelect={(value) => setSeason(value === -1 ? null : value)}
        />
      )}
      <div className="mt-3 flex flex-col gap-2">
        {paged.pageItems.map((ep) => (
          <EpisodeRow key={ep.id} episode={ep}
                      progress={progress.get(ep.url)}
                      downloads={downloads}
                      onOpen={() => navigate(`/episode/${ep.id}`)} />
        ))}
      </div>
      <Pager page={paged.page} pages={paged.pages} onPage={paged.setPage} />
    </>
  );
}

function EpisodeRow({ episode, progress, downloads, onOpen }: {
  episode: Channel;
  progress: number | undefined;
  downloads: ReturnType<typeof useDownloads>;
  onOpen: () => void;
}) {
  const [failed, setFailed] = useState(false);
  const metaLine = [
    episode.durationSecs != null ? fmtDuration(episode.durationSecs) : null,
    episode.airDate,
  ].filter(Boolean).join(' · ');
  return (
    <button className="card" onClick={onOpen}>
      <div className="episode-row">
        <div className="thumb">
          {episode.logo && !failed
            ? <img loading="lazy" src={imgUrl(episode.logo)} alt="" onError={() => setFailed(true)} />
            : <Icon name="videoLib" />}
          {progress != null && <WatchProgressBar fraction={progress} height={3} />}
        </div>
        <div className="body">
          {episodeTag(episode) && <div className="tag">{episodeTag(episode)}</div>}
          <div className="title">{episode.name}</div>
          {metaLine && <div className="sub">{metaLine}</div>}
        </div>
        <DownloadStateIcon state={downloads.byUrl.get(episode.url)}
                           onDownload={() => api.enqueueDownload(episode.id)}
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
  const { data: episodes } = useAsync(() => api.episodes(playlistId, key), [playlistId, key]);
  const { data: meta } = useAsync(() => api.meta('series', key), [key]);
  const { favoriteKeys, toggleFavorite } = useFavorites(playlistId);

  if (!episodes) return <DetailShell onBack={() => navigate(-1)}><Spinner /></DetailShell>;

  const seasons = new Set(episodes.map((e) => e.season).filter((s) => s != null));
  const poster = meta?.posterUrl ?? episodes.find((e) => e.logo)?.logo ?? null;
  return (
    <DetailShell
      onBack={() => navigate(-1)}
      favorite={<FavoriteIcon isFavorite={favoriteKeys.has(key)}
                              onToggle={() => toggleFavorite(key, ChannelKind.SERIES)} />}
    >
      <Poster image={poster} kind={ChannelKind.SERIES} />
      <h2>{key}</h2>
      <div className="pills">
        {episodes[0] && <Pill>{episodes[0].groupTitle}</Pill>}
        <Pill>{t('browse.episodes', { count: episodes.length })}</Pill>
        {seasons.size > 1 && <Pill>{t('detail.seasons', { count: seasons.size })}</Pill>}
        {meta?.rating != null && <Pill>{starRating(meta.rating)}</Pill>}
      </div>
      {meta?.infoLine && (
        <div className="pills" style={{ marginTop: 8 }}>
          {meta.infoLine.split(' · ').slice(0, 4).map((x) => <Pill key={x}>{x}</Pill>)}
        </div>
      )}
      <MetadataBlock meta={meta} />
      <div className="mt-4" />
      <EpisodeList episodes={episodes} />
    </DetailShell>
  );
}

export function EpisodeDetailScreen() {
  const channelId = Number(useParams().channelId);
  const navigate = useNavigate();
  const { playChannel } = usePlayer();
  const progress = useWatchProgress();

  const { data } = useAsync(async () => {
    const ep = await api.channel(channelId);
    // Xtream episodes key by series id; M3U episodes key by series title.
    let seriesTitle: string | null = null;
    let seriesCast: CastMember[] = [];
    if (ep.seriesKey?.startsWith('xs:')) {
      const detail = await api.xseries(ep.playlistId, Number(ep.seriesKey.slice(3))).catch(() => null);
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

  if (!data) return <DetailShell onBack={() => navigate(-1)}><Spinner /></DetailShell>;
  const { ep, seriesTitle, seriesCast, info } = data;

  const chips = [
    episodeTag(ep),
    ep.airDate ?? info?.year,
    ep.durationSecs != null ? fmtDuration(ep.durationSecs) : info?.infoLine,
    info?.rating != null ? starRating(info.rating) : null,
  ].filter((x): x is string => !!x);
  const plot = ep.description ?? info?.overview;
  const fraction = progress.get(ep.url);

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
