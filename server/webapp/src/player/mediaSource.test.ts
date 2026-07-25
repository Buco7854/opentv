import { describe, expect, it } from 'vitest';
import { PlaybackLease } from '../api';
import { hlsVariantOf, playbackSource, resolveSource, sourceKind } from './mediaSource';

const lease = (overrides: Partial<PlaybackLease> = {}): PlaybackLease => ({
  id: 'lease-1',
  contentId: 'content-1',
  playlistId: 1,
  mediaGrant: 'grant-2',
  mediaGrantExpiresAtMs: 0,
  streamUrl: '/api/v1/stream?u=l.token&sid=lease-1&g=grant-1',
  relayUrl: '/api/v1/relay?u=l.token&sid=lease-1&g=grant-1',
  transcodeUrl: '/api/v1/transcode?u=l.token&sid=lease-1&g=grant-1',
  remuxStartUrl: '/api/v1/remux/start?u=l.token&sid=lease-1&g=grant-1',
  ...overrides,
});

describe('media source resolution', () => {
  it('applies the current grant to every proxied transport', () => {
    const context = { lease: lease() };
    (['proxy', 'relay', 'transcode'] as const).forEach((transport) => {
      const source = resolveSource(context, transport);
      expect(source?.url).toContain('g=grant-2');
      expect(source?.url).not.toContain('g=grant-1');
    });
  });

  it('resolves afresh so a rotated grant is never replayed', () => {
    const before = resolveSource({ lease: lease({ mediaGrant: 'old' }) }, 'proxy');
    const after = resolveSource({ lease: lease({ mediaGrant: 'new' }) }, 'proxy');
    expect(before?.url).toContain('g=old');
    expect(after?.url).toContain('g=new');
  });

  it('prefers the remux, then the proxy, then a downloaded file', () => {
    expect(playbackSource({
      lease: lease(),
      remuxPlaylistUrl: '/api/v1/remux/r1/main.m3u8?sid=lease-1&g=grant-1',
    })?.transport).toBe('remux');

    expect(playbackSource({ lease: lease() })?.transport).toBe('proxy');

    const download = playbackSource({
      lease: lease({ streamUrl: null, relayUrl: null, transcodeUrl: null }),
      downloadId: 'download-9',
    });
    expect(download?.transport).toBe('download');
    expect(download?.url).toContain('/downloads/download-9/file');
  });

  it('has no source when a lease offers nothing playable', () => {
    expect(playbackSource({
      lease: lease({ streamUrl: null, relayUrl: null, transcodeUrl: null }),
    })).toBeNull();
  });

  it('reads the engine kind from the opaque token, not the proxy path', () => {
    expect(sourceKind('/api/v1/stream?u=h.token')).toBe('hls');
    expect(sourceKind('/api/v1/stream?u=l.token')).toBe('livets');
    expect(sourceKind('/api/v1/stream?u=t.token')).toBe('ts');
    expect(sourceKind('/api/v1/downloads/x/file')).toBe('direct');
    expect(sourceKind(null)).toBe('direct');
  });

  it('asks for the hls variant without disturbing the rest of the query', () => {
    expect(hlsVariantOf('/api/v1/stream?u=l.token&sid=lease-1&g=grant-2'))
      .toBe('/api/v1/stream?u=l.token&sid=lease-1&g=grant-2&hls=1');
  });
});
