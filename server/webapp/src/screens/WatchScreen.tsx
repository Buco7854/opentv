// Player as a real route: each screen resolves content by id and renders the
// shared PlayerSurface, keeping tokens and provider URLs out of the address bar.

import { ReactNode, useCallback } from 'react';
import { useNavigate, useParams } from 'react-router';
import { api, ChannelKind } from '../api';
import { t } from '../i18n';
import { useAsync } from '../hooks';
import { PlaybackErrorBoundary, PlayerSurface, PlayRequest } from '../player/PlayerProvider';

// Player's own surface while resolving, so it reads as the player loading.
function WatchLoading() {
  return <div className="player-frame"><div className="player-spinner" aria-hidden /></div>;
}

// Close returns back, falling back to home for deep-links.
function useClose() {
  const navigate = useNavigate();
  return useCallback(
    () => (window.history.length > 1 ? navigate(-1) : navigate('/')),
    [navigate],
  );
}

/** A stale deep link must say so and offer a way out, not spin forever. */
function WatchUnavailable({ title, subtitle }: { title: string; subtitle: string }) {
  const close = useClose();
  return (
    <div className="player-frame">
      <div className="player-error">
        <h3>{title}</h3>
        <p>{subtitle}</p>
        <button className="btn tonal" style={{ width: 'auto' }} onClick={close}>{t('common.close')}</button>
      </div>
    </div>
  );
}

/** Renders [children] once the route's content resolves; loading and failure are handled here. */
function Resolved<T>({ state, title, subtitle, children }: {
  state: { data: T | null; loading: boolean; error: string | null };
  title: string;
  subtitle: string;
  children: (value: T) => ReactNode;
}) {
  if (state.loading) return <WatchLoading />;
  if (state.data == null) return <WatchUnavailable title={title} subtitle={state.error ?? subtitle} />;
  return <>{children(state.data)}</>;
}

function Stage({ request }: { request: PlayRequest }) {
  const navigate = useNavigate();
  const close = useClose();
  return (
    <PlaybackErrorBoundary>
      <PlayerSurface
        request={request}
        onClose={close}
        onPlayCatchup={(id, startMs, endMs) => navigate(`/watch/catchup/${id}/${startMs}/${endMs}`)}
      />
    </PlaybackErrorBoundary>
  );
}

export function WatchChannelScreen() {
  const channelId = Number(useParams().channelId);
  const state = useAsync(() => api.channel(channelId), [channelId]);
  return (
    <Resolved state={state} title={t('player.unavailableTitle')} subtitle={t('player.unavailableSub')}>
      {(channel) => (
        <Stage request={{
          contentId: channel.contentId,
          title: channel.name,
          channelId: channel.id,
          live: channel.kind === ChannelKind.LIVE,
          tvgId: channel.tvgId,
          hasGuide: channel.tvgId != null || channel.xtreamStreamId != null,
          kind: channel.kind === ChannelKind.LIVE ? 'live'
            : channel.kind === ChannelKind.SERIES ? 'series' : 'movie',
          logo: channel.logo,
        }} />
      )}
    </Resolved>
  );
}

export function WatchCatchupScreen() {
  const p = useParams();
  const channelId = Number(p.channelId);
  const startMs = Number(p.startMs);
  const endMs = Number(p.endMs);
  const state = useAsync(async () => {
    const [channel, guide] = await Promise.all([
      api.channel(channelId),
      api.guide(channelId).catch(() => []),
    ]);
    return { channel, title: guide.find((g) => g.startMs === startMs)?.title };
  }, [channelId, startMs, endMs]);
  return (
    <Resolved state={state} title={t('player.unavailableTitle')} subtitle={t('player.unavailableSub')}>
      {({ channel, title }) => (
        <Stage request={{
          contentId: channel.contentId,
          title: title ? `${channel.name} · ${title}` : channel.name,
          channelId,
          live: false,
          mode: 'catchup',
          catchupStartMs: startMs,
          catchupDurationMs: endMs - startMs,
          kind: 'catchup',
          logo: channel.logo,
        }} />
      )}
    </Resolved>
  );
}

export function WatchDownloadScreen() {
  const downloadId = useParams().downloadId ?? '';
  const state = useAsync(
    async () => (await api.downloads()).find((d) => d.id === downloadId) ?? null,
    [downloadId],
  );
  return (
    <Resolved
      state={state}
      title={t('downloads.unavailableTitle')}
      subtitle={t('downloads.unavailableSub')}
    >
      {(item) => (
        <Stage request={{
          contentId: item.contentId,
          title: item.title,
          mode: 'download',
          downloadId: item.id,
          kind: 'download',
        }} />
      )}
    </Resolved>
  );
}
