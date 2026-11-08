// A stable starting point: recent viewing first, followed by the user's libraries.
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, ChannelKind, Playlist } from '../api';
import { ApiError } from '../api/http';
import { asyncFallback, ChannelLogo, EmptyState } from '../components/Common';
import { Icon } from '../components/Icons';
import { ScreenHeader, Spinner } from '../components/Primitives';
import { PlaylistDialog } from '../components/PlaylistDialog';
import { t } from '../i18n';
import { useAsync } from '../hooks';
import { useLibrary } from '../library';
import { fmtDuration } from '../lib/format';
import { prefs } from '../preferences';
import { useAuth } from '../auth/AuthProvider';
import './library-screens.css';

export function HomeScreen() {
  const navigate = useNavigate();
  const { playlists, loading, error, reload, rememberPlaylist } = useLibrary();
  const [adding, setAdding] = useState(false);
  const { user } = useAuth();

  if (playlists === null && loading) return <Spinner />;
  if (playlists === null) {
    return <EmptyState title={t('playlists.loadFailedTitle')}
      subtitle={t('playlists.loadFailedSub', { message: error ?? '' })}
      action={<button className="btn tonal" onClick={() => void reload()}>
        <Icon name="refresh" />{t('common.retry')}</button>} />;
  }

  return (
    <>
      <ScreenHeader title={t('home.library')} />
      {playlists.length > 0 ? <LibraryHome playlists={playlists} onAdd={user?.role === 'ADMIN' ? () => setAdding(true) : undefined} /> : (
        <EmptyState title={t('home.welcome')}
          subtitle={user?.role === 'ADMIN' ? t('home.welcomeSub') : t('playlists.assignmentRequired')}
          action={user?.role === 'ADMIN' ? <button className="btn" onClick={() => setAdding(true)}>
            {t('playlists.add')}</button> : undefined}>
          <div className="empty-home-art"><Icon name="playlist" /></div>
        </EmptyState>
      )}
      {adding && user?.role === 'ADMIN' && <PlaylistDialog editing={null} onDismiss={() => setAdding(false)}
        onDone={(saved) => {
          setAdding(false);
          prefs.activePlaylist = saved.id;
          rememberPlaylist(saved);
          void reload();
          navigate(`/browse/${saved.id}`);
        }} />}
    </>
  );
}

function LibraryHome({ playlists, onAdd }: { playlists: Playlist[]; onAdd?: () => void }) {
  // Content reads resolve the local catalog only. Opening Home never refreshes a provider.
  const recent = useAsync(async () => {
    const points = (await api.resumeAll())
      .filter((point) => point.positionMs > 0 &&
        (point.durationMs <= 0 || point.positionMs / point.durationMs < 0.95))
      .sort((left, right) => right.updatedMs - left.updatedMs)
      .slice(0, 6);
    const rows = await Promise.allSettled(points.map(async (point) => ({
      point, channel: await api.channel(point.contentId),
    })));
    // A removed title is expected after a catalog update; a failed request is not
    // an empty library. Keep a recoverable error rather than hiding a network outage.
    const failure = rows.find((row) => row.status === 'rejected' &&
      !(row.reason instanceof ApiError && [404, 410].includes(row.reason.status)));
    if (failure?.status === 'rejected') throw failure.reason;
    return rows.flatMap((row) => row.status === 'fulfilled' ? [row.value] : []);
  }, [playlists.map(({ id }) => id).join(':')]);
  const active = playlists.find(({ id }) => id === prefs.activePlaylist) ?? playlists[0]!;
  const ordered = [active, ...playlists.filter(({ id }) => id !== active.id)];

  return (
    <div className="library-home">
      <section className="library-section" aria-labelledby="continue-title">
        <div className="library-section-heading"><h2 id="continue-title">{t('home.continue')}</h2></div>
        {asyncFallback(recent) ?? (recent.data?.length ? (
          <div className="continue-grid">{recent.data.map(({ channel, point }) => (
            <Link className="continue-card" key={channel.contentId}
              to={`/${channel.kind === ChannelKind.SERIES ? 'episode' : 'movie'}/${channel.contentId}`}>
              <ChannelLogo url={channel.logo} kind={channel.kind} />
              <div className="continue-copy"><strong>{channel.name}</strong>
                <span>{channel.seriesKey ?? channel.groupTitle}</span>
                <span>{point.durationMs > 0
                  ? t('home.remaining', { time: fmtDuration(Math.max(0, point.durationMs - point.positionMs) / 1000) })
                  : t('common.resume')}</span>
                {point.durationMs > 0 && <progress max={point.durationMs} value={point.positionMs}
                  aria-label={t('home.progress', { title: channel.name })} />}
              </div><Icon name="play" />
            </Link>
          ))}</div>
        ) : <p className="library-inline-empty">{t('home.continueEmpty')}</p>)}
      </section>

      <section className="library-section" aria-labelledby="libraries-title">
        <div className="library-section-heading"><h2 id="libraries-title">{t('home.sources')}</h2>
          <Link to="/favorites" className="library-text-link"><Icon name="favoriteBorder" />{t('favorites.title')}</Link>
        </div>
        <div className="library-source-list">{ordered.map((playlist) => (
          <div className="library-source" key={playlist.id}>
            <Link to={`/browse/${playlist.id}?t=0`} className="library-source-open"
              onClick={() => { prefs.activePlaylist = playlist.id; }}>
              <Icon name="playlist" />
              <div><h3>{playlist.name}</h3><p>{t('home.catalogCount', { count: playlist.channelCount })}</p></div>
              <Icon name="chevron" />
            </Link>
            <Link to={`/search/${playlist.id}`} aria-label={t('home.searchSource', { name: playlist.name })}
              className="icon-btn muted library-source-search"><Icon name="search" /></Link>
          </div>
        ))}</div>
        {onAdd && <div className="library-source-actions">
          <button type="button" className="btn tonal library-add" onClick={onAdd}>{t('playlists.add')}</button>
        </div>}
      </section>
    </div>
  );
}
