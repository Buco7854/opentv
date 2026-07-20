// Player as a real route: each screen resolves content by id and renders the
// shared PlayerSurface, keeping tokens and provider URLs out of the address bar.

import { useNavigate, useParams } from 'react-router-dom';
import { api, ChannelKind, downloadFileUrl } from '../api';
import { useAsync } from '../hooks';
import { PlayerSurface, PlayRequest } from '../player/PlayerProvider';

// Player's own surface while resolving, so it reads as the player loading.
function WatchLoading() {
  return <div className="player-frame"><div className="player-spinner" aria-hidden /></div>;
}

// Close returns back, falling back to home for deep-links.
function useClose() {
  const navigate = useNavigate();
  return () => (window.history.length > 1 ? navigate(-1) : navigate('/'));
}

function Stage({ request }: { request: PlayRequest }) {
  const navigate = useNavigate();
  const close = useClose();
  return (
    <PlayerSurface
      request={request}
      onClose={close}
      onPlayCatchup={(id, startMs, endMs) => navigate(`/watch/catchup/${id}/${startMs}/${endMs}`)}
    />
  );
}

export function WatchChannelScreen() {
  const channelId = Number(useParams().channelId);
  const { data: channel } = useAsync(() => api.channel(channelId), [channelId]);
  if (!channel) return <WatchLoading />;
  const request: PlayRequest = {
    url: channel.url,
    title: channel.name,
    channelId: channel.id,
    live: channel.kind === ChannelKind.LIVE,
    tvgId: channel.tvgId,
    hasGuide: channel.tvgId != null || channel.xtreamStreamId != null,
  };
  return <Stage request={request} />;
}

export function WatchCatchupScreen() {
  const p = useParams();
  const channelId = Number(p.channelId);
  const startMs = Number(p.startMs);
  const endMs = Number(p.endMs);
  const { data } = useAsync(async () => {
    const [channel, cu, guide] = await Promise.all([
      api.channel(channelId),
      api.catchupUrl(channelId, startMs, endMs),
      api.guide(channelId).catch(() => []),
    ]);
    return { channel, url: cu.url, title: guide.find((g) => g.startMs === startMs)?.title };
  }, [channelId, startMs, endMs]);
  if (!data) return <WatchLoading />;
  const request: PlayRequest = {
    url: data.url ?? '',
    title: data.title ? `${data.channel.name} · ${data.title}` : data.channel.name,
    channelId,
    live: false,
    catchup: true,
  };
  return <Stage request={request} />;
}

export function WatchDownloadScreen() {
  const downloadId = Number(useParams().downloadId);
  const { data: item } = useAsync(
    async () => (await api.downloads()).find((d) => d.id === downloadId) ?? null,
    [downloadId],
  );
  if (item === null) return <WatchLoading />;
  const request: PlayRequest = {
    url: downloadFileUrl(downloadId),
    title: item.title,
    direct: true,
  };
  return <Stage request={request} />;
}
