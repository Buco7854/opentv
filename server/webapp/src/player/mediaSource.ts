import { PlaybackLease } from '../api';
import { StreamKind, streamKind } from './playbackPolicy';
import { replaceMediaGrant } from './mediaGrant';

export type Transport = 'proxy' | 'shared-hls' | 'relay' | 'transcode' | 'remux' | 'download';

export interface MediaSource {
  transport: Transport;
  url: string;
  kind: StreamKind;
}

export interface TransportContext {
  lease: PlaybackLease;
  remuxPlaylistUrl?: string | null;
  downloadId?: string | null;
}

function leaseUrl(context: TransportContext, transport: Transport): string | null {
  const { lease } = context;
  switch (transport) {
    case 'proxy':
      return lease.streamUrl;
    case 'shared-hls':
      return lease.sharedHlsUrl;
    case 'relay':
      return lease.relayUrl;
    case 'transcode':
      return lease.transcodeUrl;
    case 'remux':
      return context.remuxPlaylistUrl ?? null;
    case 'download':
      return lease.downloadFileUrl;
  }
}

export function resolveSource(
  context: TransportContext,
  transport: Transport,
): MediaSource | null {
  const url = leaseUrl(context, transport);
  if (!url) return null;
  const authorized = transport === 'download'
    ? url
    : replaceMediaGrant(url, context.lease.mediaGrant) ?? url;
  return { transport, url: authorized, kind: sourceKind(authorized) };
}

export function playbackSource(context: TransportContext): MediaSource | null {
  return resolveSource(context, 'remux')
    ?? resolveSource(context, 'proxy')
    ?? resolveSource(context, 'download');
}

export function sourceKind(url: string | null): StreamKind {
  if (!url) return 'direct';
  const parsed = new URL(url, window.location.origin);
  return streamKind(parsed.searchParams.get('u') ?? url);
}

export function hlsVariantOf(url: string): string {
  const next = new URL(url, window.location.origin);
  next.searchParams.set('hls', '1');
  return `${next.pathname}${next.search}${next.hash}`;
}
