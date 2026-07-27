import {
  useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore,
} from 'react';
import { api, Download, DownloadStatus, ListingPage } from './api';
import { GENERIC, errorMessage, reportError } from './errors';
import { t } from './i18n';

const DEFAULT_PAGE_SIZE = 50;

/** Server page size (OPENTV_PAGE_SIZE), fetched once per session. */
class PageSizeStore {
  private size = DEFAULT_PAGE_SIZE;
  private generation = 0;
  private readonly listeners = new Set<() => void>();

  readonly subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  readonly getSnapshot = () => this.size;

  setAllowed(allowed: boolean) {
    this.generation++;
    this.publish(DEFAULT_PAGE_SIZE);
    if (!allowed) return;
    const generation = this.generation;
    api.settings()
      .then(({ pageSize }) => {
        if (generation !== this.generation) return;
        this.publish(pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE);
      })
      // Nobody asked for this and the default page size is a working answer. The three
      // silent catches in this file are all of that shape: a background read whose only
      // consequence is a less decorated list, on a screen that reports its own failure.
      .catch(() => {});
  }

  private publish(size: number) {
    if (size === this.size) return;
    this.size = size;
    this.listeners.forEach((listener) => listener());
  }
}

const pageSizeStore = new PageSizeStore();

export function setServerSettingsAllowed(allowed: boolean) {
  pageSizeStore.setAllowed(allowed);
}

export function usePageSize(): number {
  return useSyncExternalStore(pageSizeStore.subscribe, pageSizeStore.getSnapshot);
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

/**
 * Server-backed pagination. The reset key names the listing (category/filter/season), while
 * the current page determines the LIMIT/OFFSET passed to its loader.
 */
export function useServerPaged<T>(
  loader: (offset: number, limit: number) => Promise<ListingPage<T>>,
  resetKey: unknown,
): {
  pageItems: T[];
  data: ListingPage<T> | null;
  total: number;
  page: number;
  pages: number;
  setPage: (page: number) => void;
  error: string | null;
  loading: boolean;
  reload: () => void;
} {
  const pageSize = usePageSize();
  const [position, setPosition] = useState({ key: resetKey, page: 0 });
  const page = Object.is(position.key, resetKey) ? position.page : 0;
  useEffect(() => {
    setPosition((current) => (
      Object.is(current.key, resetKey) ? current : { key: resetKey, page: 0 }
    ));
  }, [resetKey]);
  const request = useAsync(
    () => loader(page * pageSize, pageSize),
    [resetKey, page, pageSize],
  );
  const total = request.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const current = Math.min(page, pages - 1);
  useEffect(() => {
    if (current !== page) {
      setPosition({ key: resetKey, page: current });
    }
  }, [current, page, resetKey]);
  const turnPage = useCallback((next: number) => {
    setPosition({ key: resetKey, page: next });
    window.scrollTo({ top: 0 });
  }, [resetKey]);
  return {
    pageItems: request.data?.items ?? [],
    data: request.data,
    total,
    page: current,
    pages,
    setPage: turnPage,
    error: request.error,
    loading: request.loading,
    reload: request.reload,
  };
}

/** Load-once helper with a reload trigger. */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]): {
  data: T | null; error: string | null; loading: boolean; reload: () => void;
} {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const latest = useRef(fn);
  latest.current = fn;
  useEffect(() => {
    let cancelled = false;
    // Route parameters identify the result. Never render a previous route's
    // data while its replacement is loading (especially for playback routes).
    setData(null);
    setLoading(true);
    setError(null);
    latest.current().then(
      (next) => {
        if (!cancelled) {
          setData(next);
          setLoading(false);
        }
      },
      (cause: unknown) => {
        if (!cancelled) {
          // Same words a toast would use: LoadFailed is the inline surface for this one.
          setError(errorMessage(cause));
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

interface DownloadSnapshot { items: Download[]; loaded: boolean; error: string | null }

const same = (a: Download[], b: Download[]) =>
  a.length === b.length && a.every((item, index) => {
    const other = b[index];
    return other !== undefined
      && item.id === other.id
      && item.status === other.status
      && item.active === other.active
      && item.suspended === other.suspended
      && item.downloadedBytes === other.downloadedBytes
      && item.totalBytes === other.totalBytes;
  });

/**
 * One external store owns download polling for the entire app. Previously each
 * mounted consumer ran an independent polling loop (normally the dock plus the
 * current screen), producing duplicate requests and inconsistent snapshots.
 */
class DownloadPollingStore {
  private snapshot: DownloadSnapshot = { items: [], loaded: false, error: null };
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
        this.publish({ items: downloads, loaded: true, error: null });
      })
      .catch((cause: unknown) => {
        // Preserve the last usable snapshot across transient network failures.
        if (generation !== this.generation) return;
        this.publish({ ...this.snapshot, error: errorMessage(cause) });
      })
      .finally(() => {
        this.request = null;
        if (generation !== this.generation && this.activeConsumers > 0) {
          void this.refresh();
          return;
        }
        if (this.activeConsumers > 0) {
          const delay = this.snapshot.items.some(isActiveDownload) ? 2000 : 15000;
          this.timer = setTimeout(() => void this.refresh(), delay);
        }
      });
    return this.request;
  };

  clear(): void {
    clearTimeout(this.timer);
    this.generation++;
    this.snapshot = { items: [], loaded: false, error: null };
    this.listeners.forEach((listener) => listener());
    if (this.activeConsumers > 0 && !this.request) void this.refresh();
  }

  private publish(next: DownloadSnapshot) {
    const current = this.snapshot;
    if (current.loaded === next.loaded
      && current.error === next.error
      && same(current.items, next.items)) return;
    this.snapshot = next;
    this.listeners.forEach((listener) => listener());
  }
}

const downloadStore = new DownloadPollingStore();

/** Drop user-owned snapshots immediately when the authenticated user changes. */
export const clearUserActivitySnapshots = () => downloadStore.clear();

/** Shared downloads snapshot. Pass enabled=false while a covered screen is inactive. */
export function useDownloads(enabled = true): {
  list: Download[];
  byContentId: Map<string, Download>;
  loaded: boolean;
  error: string | null;
  refresh: () => void;
} {
  const { items, loaded, error } = useSyncExternalStore(
    downloadStore.subscribe, downloadStore.getSnapshot,
  );
  useEffect(() => {
    if (!enabled) return;
    return downloadStore.activate();
  }, [enabled]);

  const byContentId = useMemo(() => new Map(
    items
      .filter((d) => d.status !== DownloadStatus.CANCELLED && d.status !== DownloadStatus.FAILED)
      .map((d) => [d.contentId, d]),
  ), [items]);
  return {
    list: items, byContentId, loaded, error, refresh: () => { void downloadStore.refresh(); },
  };
}

/** Stable content ids favorited by the current user in one playlist. */
export function useFavorites(playlistId: number | null) {
  const [contentIds, setContentIds] = useState<Set<string>>(new Set());
  useEffect(() => {
    let active = true;
    setContentIds(new Set());
    if (playlistId == null) return () => { active = false; };
    api.favorites(playlistId)
      .then((f) => {
        if (active) setContentIds(new Set(f.map((x) => x.contentId)));
      })
      .catch(() => {});
    return () => { active = false; };
  }, [playlistId]);
  const contentIdsRef = useRef(contentIds);
  contentIdsRef.current = contentIds;
  const toggle = useCallback((contentId: string) => {
    if (playlistId == null) return;
    const adding = !contentIdsRef.current.has(contentId);
    setContentIds((old) => {
      const next = new Set(old);
      if (adding) next.add(contentId); else next.delete(contentId);
      return next;
    });
    const request = adding
      ? api.addFavorite(playlistId, contentId)
      : api.removeFavorite(playlistId, contentId);
    request.catch((cause: unknown) => {
      setContentIds((old) => {
        const next = new Set(old);
        if (adding) next.delete(contentId); else next.add(contentId);
        return next;
      });
      reportError(cause, { [GENERIC]: () => t('favorites.saveFailed') });
    });
  }, [playlistId]);
  return { favoriteContentIds: contentIds, toggleFavorite: toggle };
}

/** Stable content id -> watched fraction, from the current user's resume points. */
export function useWatchProgress(): Map<string, number> {
  const [map, setMap] = useState<Map<string, number>>(new Map());
  useEffect(() => {
    let active = true;
    const load = () => {
      api.resumeAll().then((points) => {
        if (!active) return;
        setMap(new Map(
          points
            .filter((p) => p.durationMs > 0 && p.positionMs >= 10_000)
            .map((p) => [p.contentId, Math.min(1, p.positionMs / p.durationMs)]),
        ));
      }).catch(() => {});
    };
    const onVisible = () => { if (document.visibilityState === 'visible') load(); };
    load();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active = false;
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);
  return map;
}
