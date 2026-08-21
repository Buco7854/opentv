const STORAGE_KEY = 'opentv.playback-client-instance';

const newId = () => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // This is correlation, not authentication. Entropy only prevents two tabs from accidentally
  // replacing each other's lease on old browsers without randomUUID().
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
};

/** Stable across reloads of this tab, but separate from the account's bearer. */
export function playbackClientInstanceId(storage?: Storage) {
  try {
    const target = storage ?? globalThis.sessionStorage;
    const stored = target.getItem(STORAGE_KEY);
    if (stored) return stored;
    const created = newId();
    target.setItem(STORAGE_KEY, created);
    return created;
  } catch {
    // sessionStorage can be disabled. The lease still works; only reload replacement becomes
    // best-effort through pagehide until storage is available again.
    return newId();
  }
}
