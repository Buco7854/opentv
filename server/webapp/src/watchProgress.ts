import { api, ResumePoint } from './api';

const MIN_RESUME_MS = 10_000;
const END_GUARD_MS = 15_000;

/** Shared user-owned progress, including positions not yet visible through GET /resume. */
class WatchProgressStore {
  private snapshot = new Map<string, number>();
  private readonly listeners = new Set<() => void>();
  private readonly changedAt = new Map<string, number>();
  private readonly pending = new Map<string, number>();
  private revision = 0;
  private generation = 0;
  private request: Promise<void> | null = null;

  readonly subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  readonly getSnapshot = () => this.snapshot;

  readonly refresh = (): Promise<void> => {
    if (this.request) return this.request;
    const generation = this.generation;
    const startedAt = this.revision;
    const pendingAtStart = new Set(this.pending.keys());
    const request = api.resumeAll()
      .then((points) => {
        if (generation !== this.generation) return;
        const next = progressMap(points);
        // A detail route can mount while the player's final PUT /resume is still in flight.
        // Never let the GET that raced it overwrite the position the player already observed.
        for (const [contentId, changedAt] of this.changedAt) {
          if (changedAt <= startedAt && !pendingAtStart.has(contentId)) continue;
          const local = this.snapshot.get(contentId);
          if (local == null) next.delete(contentId);
          else next.set(contentId, local);
        }
        this.publish(next);
      })
      .finally(() => {
        if (this.request === request) this.request = null;
      });
    this.request = request;
    return request;
  };

  update(contentId: string, positionMs: number, durationMs: number): number {
    const next = new Map(this.snapshot);
    // Mirror UserActivityService.saveResume exactly. Publishing is optimistic, so
    // the returning detail route must already show the state the successful PUT
    // will persist, including clearing a title watched to its final 15 seconds.
    if (durationMs > 0 && positionMs >= MIN_RESUME_MS && positionMs <= durationMs - END_GUARD_MS) {
      next.set(contentId, Math.min(1, positionMs / durationMs));
    } else {
      next.delete(contentId);
    }
    const revision = ++this.revision;
    this.changedAt.set(contentId, revision);
    this.pending.set(contentId, revision);
    this.publish(next);
    return revision;
  }

  confirm(contentId: string, revision: number) {
    // A slower earlier PUT must not mark a newer local position as synchronized.
    if (this.pending.get(contentId) === revision) this.pending.delete(contentId);
  }

  clear() {
    this.generation++;
    // Revisions stay monotonic across accounts. A PUT started by the previous account can
    // finish after the replacement account publishes the same content id; reusing revision 1
    // would let that old completion mark the replacement position as synchronized.
    this.changedAt.clear();
    this.pending.clear();
    // The old account's GET cannot be cancelled here, but it must not prevent the replacement
    // account from loading. Its generation check keeps its eventual answer from publishing.
    this.request = null;
    this.publish(new Map());
  }

  private publish(next: Map<string, number>) {
    if (sameProgress(this.snapshot, next)) return;
    this.snapshot = next;
    this.listeners.forEach((listener) => listener());
  }
}

function progressMap(points: ResumePoint[]): Map<string, number> {
  return new Map(
    points
      .filter((point) => point.durationMs > 0 && point.positionMs >= MIN_RESUME_MS)
      .map((point) => [point.contentId, Math.min(1, point.positionMs / point.durationMs)]),
  );
}

function sameProgress(a: Map<string, number>, b: Map<string, number>): boolean {
  if (a.size !== b.size) return false;
  for (const [key, value] of a) if (b.get(key) !== value) return false;
  return true;
}

export const watchProgressStore = new WatchProgressStore();

/** Publish before the PUT completes so returning to details never races the server write. */
export function publishWatchProgress(
  contentId: string,
  positionMs: number,
  durationMs: number,
): number {
  return watchProgressStore.update(contentId, positionMs, durationMs);
}

/** Mark exactly the position accepted by the server as synchronized. */
export function confirmWatchProgress(contentId: string, revision: number) {
  watchProgressStore.confirm(contentId, revision);
}
