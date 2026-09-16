import { GroupCount, ListingPage } from '../api';

/**
 * In-memory, per-session cache of the last successful category/listing fetch for
 * Browse. `useAsync`'s default behaviour blanks the screen on every remount (see
 * hooks.ts), which is right for most screens but means returning to Browse -- a
 * back navigation, or simply reopening a category already seen this session --
 * always redraws a blank loading state before the identical data comes back. This
 * cache lets BrowseScreen seed that redraw with the last known-good page while a
 * fresh fetch still runs in the background.
 *
 * Cleared on sign-out/user switch via `clearCatalogCache` (see
 * `clearUserActivitySnapshots` in hooks.ts) so a different account never sees a
 * previous session's cached catalog.
 */
const groupsCache = new Map<string, GroupCount[]>();
const listingCache = new Map<string, ListingPage<unknown>>();

export function cachedGroups(key: string): GroupCount[] | null {
  return groupsCache.get(key) ?? null;
}

export function cacheGroups(key: string, groups: GroupCount[]): void {
  groupsCache.set(key, groups);
}

export function cachedListingPage<T>(key: string): ListingPage<T> | null {
  return (listingCache.get(key) as ListingPage<T> | undefined) ?? null;
}

export function cacheListingPage<T>(key: string, page: ListingPage<T>): void {
  listingCache.set(key, page as ListingPage<unknown>);
}

export function clearCatalogCache(): void {
  groupsCache.clear();
  listingCache.clear();
}
