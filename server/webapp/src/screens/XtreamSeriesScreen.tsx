// Series page for native Xtream playlists. Mirrors XtreamSeriesScreen.kt.

import { useNavigate, useParams } from 'react-router';
import { api, ChannelKind } from '../api';
import { castFromNames, CastRow, decodeCast } from '../components/CastRow';
import { asyncFallback, FavoriteIcon, Pill } from '../components/Common';
import { ScreenHeader } from '../components/Primitives';
import { useAsync, useFavorites } from '../hooks';
import { t } from '../i18n';
import { starRating } from '../lib/format';
import { EpisodeList, Poster } from './DetailScreens';

export function XtreamSeriesScreen() {
  const { playlistId: pid, seriesId: sid } = useParams();
  const playlistId = Number(pid);
  const seriesId = sid ?? '';
  const navigate = useNavigate();
  const request = useAsync(async () => {
    const detail = await api.xseries(playlistId, seriesId);
    const metadata = await api.meta('series', detail.series.name).catch(() => null);
    return { ...detail, metadata };
  }, [playlistId, seriesId]);
  const { favoriteContentIds, toggleFavorite } = useFavorites(playlistId);

  const pending = asyncFallback(request);
  if (pending) {
    return (
      <>
        <ScreenHeader title="" onBack={() => navigate(-1)} />
        {pending}
      </>
    );
  }

  const { series, episodes, error, metadata } = request.data!;
  const enrichedCast = decodeCast(metadata?.castJson ?? null);
  const cast = enrichedCast.length > 0 ? enrichedCast : castFromNames(series.castNames);

  return (
    <>
      <ScreenHeader
        title="" onBack={() => navigate(-1)}
        actions={<FavoriteIcon isFavorite={favoriteContentIds.has(series.contentId)}
                               onToggle={() => toggleFavorite(series.contentId)} />}
      />
      <div className="detail">
        <Poster image={series.cover} kind={ChannelKind.SERIES} />
        <h2>{series.name}</h2>
        <div className="pills">
          {series.categoryName && <Pill>{series.categoryName}</Pill>}
          {series.rating != null && <Pill>{starRating(series.rating)}</Pill>}
          {episodes.length > 0 && <Pill>{t('browse.episodes', { count: episodes.length })}</Pill>}
        </div>
        {series.genre && <p className="mt-2 type-body-small text-on-surface-variant">{series.genre}</p>}
        {series.plot && <p className="overview">{series.plot}</p>}
        {cast.length > 0 && (
          <>
            <div className="section-h">{t('detail.cast')}</div>
            <CastRow members={cast} />
          </>
        )}
        <div className="mt-4" />
        {episodes.length === 0 && error != null
          ? <p className="type-body-medium text-error">{t('detail.episodesFailed', { message: error })}</p>
          : <EpisodeList episodes={episodes} />}
      </div>
    </>
  );
}
