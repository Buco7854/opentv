// Series page for native Xtream playlists. Mirrors XtreamSeriesScreen.kt.

import { useNavigate, useParams } from 'react-router-dom';
import { api, ChannelKind } from '../api';
import { castFromNames, CastRow } from '../components/CastRow';
import { FavoriteIcon, Pill } from '../components/Common';
import { Spinner, ScreenHeader } from '../components/Primitives';
import { useAsync, useFavorites } from '../hooks';
import { t } from '../i18n';
import { starRating } from '../lib/format';
import { EpisodeList, Poster } from './DetailScreens';

export function XtreamSeriesScreen() {
  const { playlistId: pid, seriesId: sid } = useParams();
  const playlistId = Number(pid);
  const seriesId = Number(sid);
  const navigate = useNavigate();
  const { data: detail } = useAsync(() => api.xseries(playlistId, seriesId), [playlistId, seriesId]);
  const { favoriteKeys, toggleFavorite } = useFavorites(playlistId);
  const favKey = `x:${seriesId}`;

  if (!detail) {
    return (
      <>
        <ScreenHeader title="" onBack={() => navigate(-1)} />
        <Spinner />
      </>
    );
  }

  const { series, episodes, error } = detail;
  const cast = castFromNames(series.castNames);

  return (
    <>
      <ScreenHeader
        title="" onBack={() => navigate(-1)}
        actions={<FavoriteIcon isFavorite={favoriteKeys.has(favKey)}
                               onToggle={() => toggleFavorite(favKey, ChannelKind.SERIES)} />}
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
