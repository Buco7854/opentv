/** Replace only the lease-scoped media grant while preserving the opaque URL. */
export function replaceMediaGrant(url: string | null, grant: string): string | null {
  if (!url) return null;
  const next = new URL(url, window.location.origin);
  next.searchParams.set('g', grant);
  return `${next.pathname}${next.search}${next.hash}`;
}

/** Authorization changes must not change the identity of a mounted media source. */
export function mediaSourceIdentity(url: string | null): string {
  if (!url) return '';
  const next = new URL(url, window.location.origin);
  next.searchParams.delete('g');
  return `${next.pathname}${next.search}${next.hash}`;
}

export interface MediaPositionSnapshot {
  position: number;
  paused: boolean;
}

export function captureMediaPosition(video: HTMLMediaElement): MediaPositionSnapshot {
  return {
    position: Number.isFinite(video.currentTime) ? video.currentTime : 0,
    paused: video.paused,
  };
}

export function restoreMediaPosition(
  video: HTMLMediaElement,
  snapshot: MediaPositionSnapshot,
  live: boolean | undefined,
): void {
  if (!live && snapshot.position > 0) video.currentTime = snapshot.position;
  if (snapshot.paused) video.pause();
  else void video.play().catch(() => {});
}

export const isTerminalPlaybackStatus = (
  status: number | undefined,
): status is 401 | 403 | 410 => status === 401 || status === 403 || status === 410;
