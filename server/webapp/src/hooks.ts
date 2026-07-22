import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, Download, DownloadStatus } from './api';

/** Server page size (OPENTV_PAGE_SIZE), fetched once per session. */
let cachedPageSize: number | null = null;
export function usePageSize(): number {
  const [size, setSize] = useState(cachedPageSize ?? 50);
  useEffect(() => {
    if (cachedPageSize != null) return;
    api.settings().then((s) => {
      cachedPageSize = s.pageSize > 0 ? s.pageSize : 50;
      setSize(cachedPageSize);
    }).catch(() => {});
  }, []);
  return size;
}

/** Client-side pagination; changing `resetKey` (group/query/tab) jumps back to page one. */
export function usePaged<T>(items: T[], resetKey: unknown): {
  pageItems: T[]; page: number; pages: number; setPage: (page: number) => void;
} {
  const pageSize = usePageSize();
  const [page, setPage] = useState(0);
  const lastKey = useRef(resetKey);
  if (lastKey.current !== resetKey) {
    lastKey.current = resetKey;
    setPage(0);
  }
  const pages = Math.max(1, Math.ceil(items.length / pageSize));
  const current = Math.min(page, pages - 1);
  const pageItems = useMemo(
    () => (pages > 1 ? items.slice(current * pageSize, (current + 1) * pageSize) : items),
    [items, current, pages, pageSize],
  );
  const turnPage = useCallback((next: number) => {
    setPage(next);
    window.scrollTo({ top: 0 });
  }, []);
  return { pageItems, page: current, pages, setPage: turnPage };
}

/** Load-once helper with a reload trigger. */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]): {
  data: T | null; error: string | null; reload: () => void;
} {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    fn().then(
      (d) => { if (!cancelled) { setData(d); setError(null); } },
      (e: Error) => { if (!cancelled) setError(e.message); },
    );
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, reload };
}

/** tvg ids with guide data, for canShowGuide(). */
export function useGuideIds(playlistId: number): { guideIds: Set<string>; reload: () => void } {
  const { data, reload } = useAsync(() => api.guideIds(playlistId), [playlistId]);
  const guideIds = useMemo(() => new Set(data ?? []), [data]);
  return { guideIds, reload };
}

const isActiveDownload = (d: Download) =>
  d.status === DownloadStatus.QUEUED || d.status === DownloadStatus.RUNNING;

/** Polls /api/downloads (fast while transfers run, slow otherwise); byUrl excludes cancelled/failed.
 *  Pass enabled=false to stop polling (e.g. the dock while the fullscreen player is up). */
export function useDownloads(enabled = true): {
  list: Download[]; byUrl: Map<string, Download>; refresh: () => void;
} {
  const [list, setList] = useState<Download[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout>>();

  const tick = useCallback(async () => {
    clearTimeout(timer.current);
    let next = 15000;
    try {
      const downloads = await api.downloads();
      setList(downloads);
      if (downloads.some(isActiveDownload)) next = 2000;
    } catch { /* keep last state */ }
    timer.current = setTimeout(tick, next);
  }, []);

  useEffect(() => {
    if (!enabled) return;
    tick();
    return () => clearTimeout(timer.current);
  }, [tick, enabled]);

  const byUrl = new Map(
    list
      .filter((d) => d.status !== DownloadStatus.CANCELLED && d.status !== DownloadStatus.FAILED)
      .map((d) => [d.url, d]),
  );
  return { list, byUrl, refresh: tick };
}

/** Favorite keys for one playlist with optimistic toggling. */
export function useFavorites(playlistId: number) {
  const [keys, setKeys] = useState<Set<string>>(new Set());
  useEffect(() => {
    api.favorites(playlistId)
      .then((f) => setKeys(new Set(f.map((x) => x.key))))
      .catch(() => {});
  }, [playlistId]);
  const toggle = useCallback((key: string, kind: number) => {
    setKeys((old) => {
      const next = new Set(old);
      if (next.has(key)) {
        next.delete(key);
        api.removeFavorite(playlistId, key).catch(() => {});
      } else {
        next.add(key);
        api.addFavorite(playlistId, key, kind).catch(() => {});
      }
      return next;
    });
  }, [playlistId]);
  return { favoriteKeys: keys, toggleFavorite: toggle };
}

/** url -> watched fraction, from shared resume points. */
export function useWatchProgress(): Map<string, number> {
  const [map, setMap] = useState<Map<string, number>>(new Map());
  useEffect(() => {
    api.resumeAll().then((points) => {
      setMap(new Map(
        points
          .filter((p) => p.durationMs > 0 && p.positionMs >= 10_000)
          .map((p) => [p.url, Math.min(1, p.positionMs / p.durationMs)]),
      ));
    }).catch(() => {});
  }, []);
  return map;
}
