import {
  useCallback, useEffect, useMemo, useState, useSyncExternalStore,
} from 'react';
import { api, Download, DownloadStatus } from './api';

/** Server page size (OPENTV_PAGE_SIZE), fetched once per session. */
let cachedPageSize: number | null = null;
let pageSizeRequest: Promise<number> | null = null;
let serverSettingsAllowed = false;

/** Server settings are administrator-only; ordinary users use the safe client default. */
export function setServerSettingsAllowed(allowed: boolean) {
  serverSettingsAllowed = allowed;
  cachedPageSize = null;
  pageSizeRequest = null;
}

function loadPageSize(): Promise<number> {
  if (cachedPageSize != null) return Promise.resolve(cachedPageSize);
  if (!serverSettingsAllowed) return Promise.resolve(50);
  if (!pageSizeRequest) {
    pageSizeRequest = api.settings()
      .then((settings) => {
        cachedPageSize = settings.pageSize > 0 ? settings.pageSize : 50;
        return cachedPageSize;
      })
      .catch(() => 50)
      .finally(() => { pageSizeRequest = null; });
  }
  return pageSizeRequest;
}

export function usePageSize(): number {
  const [size, setSize] = useState(cachedPageSize ?? 50);
  useEffect(() => {
    let active = true;
    loadPageSize().then((next) => { if (active) setSize(next); });
    return () => { active = false; };
  }, []);
  return size;
}

/** Client-side pagination; changing `resetKey` (group/query/tab) jumps back to page one. */
export function usePaged<T>(items: T[], resetKey: unknown): {
  pageItems: T[]; page: number; pages: number; setPage: (page: number) => void;
} {
  const pageSize = usePageSize();
  const [page, setPage] = useState(0);
  // State updates during render make concurrent rendering unpredictable.
  useEffect(() => setPage(0), [resetKey]);
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
  data: T | null; error: string | null; loading: boolean; reload: () => void;
} {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fn().then(
      (next) => {
        if (!cancelled) {
          setData(next);
          setLoading(false);
        }
      },
      (cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : String(cause));
          setLoading(false);
        }
      },
    );
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, loading, reload };
}

/** tvg ids with guide data, for canShowGuide(). */
export function useGuideIds(playlistId: number): { guideIds: Set<string>; reload: () => void } {
  const { data, reload } = useAsync(() => api.guideIds(playlistId), [playlistId]);
  const guideIds = useMemo(() => new Set(data ?? []), [data]);
  return { guideIds, reload };
}

const isActiveDownload = (d: Download) =>
  d.active && (d.status === DownloadStatus.QUEUED || d.status === DownloadStatus.RUNNING);

/**
 * One external store owns download polling for the entire app. Previously each
 * mounted consumer ran an independent polling loop (normally the dock plus the
 * current screen), producing duplicate requests and inconsistent snapshots.
 */
class DownloadPollingStore {
  private snapshot: Download[] = [];
  private readonly listeners = new Set<() => void>();
  private activeConsumers = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private request: Promise<void> | null = null;
  private generation = 0;

  readonly subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  readonly getSnapshot = () => this.snapshot;

  activate(): () => void {
    this.activeConsumers++;
    if (this.activeConsumers === 1) void this.refresh();
    return () => {
      this.activeConsumers = Math.max(0, this.activeConsumers - 1);
      if (this.activeConsumers === 0) clearTimeout(this.timer);
    };
  }

  readonly refresh = async (): Promise<void> => {
    clearTimeout(this.timer);
    if (this.request) return this.request;
    const generation = this.generation;
    this.request = api.downloads()
      .then((downloads) => {
        if (generation !== this.generation) return;
        this.snapshot = downloads;
        this.listeners.forEach((listener) => listener());
      })
      .catch(() => {
        // Preserve the last usable snapshot across transient network failures.
      })
      .finally(() => {
        this.request = null;
        if (generation !== this.generation && this.activeConsumers > 0) {
          void this.refresh();
          return;
        }
        if (this.activeConsumers > 0) {
          const delay = this.snapshot.some(isActiveDownload) ? 2000 : 15000;
          this.timer = setTimeout(() => void this.refresh(), delay);
        }
      });
    return this.request;
  };

  clear(): void {
    clearTimeout(this.timer);
    this.generation++;
    this.snapshot = [];
    this.listeners.forEach((listener) => listener());
    if (this.activeConsumers > 0 && !this.request) void this.refresh();
  }
}

const downloadStore = new DownloadPollingStore();

/** Drop user-owned snapshots immediately when the authenticated user changes. */
export const clearUserActivitySnapshots = () => downloadStore.clear();

/** Shared downloads snapshot. Pass enabled=false while a covered screen is inactive. */
export function useDownloads(enabled = true): {
  list: Download[]; byContentId: Map<string, Download>; refresh: () => void;
} {
  const list = useSyncExternalStore(downloadStore.subscribe, downloadStore.getSnapshot);
  useEffect(() => {
    if (!enabled) return;
    return downloadStore.activate();
  }, [enabled]);

  const byContentId = useMemo(() => new Map(
    list
      .filter((d) => d.status !== DownloadStatus.CANCELLED && d.status !== DownloadStatus.FAILED)
      .map((d) => [d.contentId, d]),
  ), [list]);
  return { list, byContentId, refresh: () => { void downloadStore.refresh(); } };
}

/** Stable content ids favorited by the current user in one playlist. */
export function useFavorites(playlistId: number) {
  const [contentIds, setContentIds] = useState<Set<string>>(new Set());
  useEffect(() => {
    api.favorites(playlistId)
      .then((f) => setContentIds(new Set(f.map((x) => x.contentId))))
      .catch(() => {});
  }, [playlistId]);
  const toggle = useCallback((contentId: string) => {
    setContentIds((old) => {
      const next = new Set(old);
      if (next.has(contentId)) {
        next.delete(contentId);
        api.removeFavorite(playlistId, contentId).catch(() => {});
      } else {
        next.add(contentId);
        api.addFavorite(playlistId, contentId).catch(() => {});
      }
      return next;
    });
  }, [playlistId]);
  return { favoriteContentIds: contentIds, toggleFavorite: toggle };
}

/** Stable content id -> watched fraction, from the current user's resume points. */
export function useWatchProgress(): Map<string, number> {
  const [map, setMap] = useState<Map<string, number>>(new Map());
  useEffect(() => {
    api.resumeAll().then((points) => {
      setMap(new Map(
        points
          .filter((p) => p.durationMs > 0 && p.positionMs >= 10_000)
          .map((p) => [p.contentId, Math.min(1, p.positionMs / p.durationMs)]),
      ));
    }).catch(() => {});
  }, []);
  return map;
}
