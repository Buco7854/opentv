import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';

const captured = new Map<string, string | null>();
const OIDC_PENDING_KEY = 'auth.oidcPendingAt';
const OIDC_HANDOFF_MAX_AGE_MS = 5 * 60_000;

export function fragmentToken(name: string): string | null {
  const known = captured.get(name);
  if (known !== undefined) return known;
  const found = new URLSearchParams(window.location.hash.slice(1)).get(name);
  captured.set(name, found);
  return found;
}

export function consumeFragmentToken(name: string): string | null {
  const token = fragmentToken(name);
  captured.delete(name);
  const params = new URLSearchParams(window.location.hash.slice(1));
  if (!params.has(name)) return token;
  params.delete(name);
  const hash = params.toString();
  window.history.replaceState(
    window.history.state,
    '',
    `${window.location.pathname}${window.location.search}${hash ? `#${hash}` : ''}`,
  );
  return token;
}

/** Marks that this tab, rather than another site, initiated an OIDC redirect. */
export function beginOidcHandoff() {
  try {
    const bytes = new Uint8Array(24);
    window.crypto.getRandomValues(bytes);
    const handoff = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
    sessionStorage.setItem(
      OIDC_PENDING_KEY,
      JSON.stringify({ handoff, startedAt: Date.now() }),
    );
    return handoff;
  } catch {
    // A storage-disabled browser cannot safely accept a bearer from a redirect.
    return null;
  }
}

/**
 * Accepts a session fragment only after this tab initiated a recent OIDC flow.
 *
 * A raw `/#session=...` link is otherwise a login-CSRF primitive: someone can
 * put their own bearer in a victim's browser and make the victim work inside
 * the attacker's account.
 */
export function consumeOidcSessionToken(): string | null {
  const token = consumeFragmentToken('session');
  const returnedHandoff = consumeFragmentToken('handoff');
  let pending: { handoff?: unknown; startedAt?: unknown } | null = null;
  try {
    const raw = sessionStorage.getItem(OIDC_PENDING_KEY);
    sessionStorage.removeItem(OIDC_PENDING_KEY);
    if (raw !== null) pending = JSON.parse(raw);
  } catch {
    return null;
  }
  const handoff = typeof pending?.handoff === 'string' ? pending.handoff : null;
  const startedAt = typeof pending?.startedAt === 'number' ? pending.startedAt : Number.NaN;
  if (!token || !returnedHandoff || returnedHandoff !== handoff || !Number.isFinite(startedAt)) {
    return null;
  }
  const age = Date.now() - startedAt;
  return age >= 0 && age <= OIDC_HANDOFF_MAX_AGE_MS ? token : null;
}

export function useFragmentToken(name: string): string | null {
  const navigate = useNavigate();
  const { pathname, search, hash } = useLocation();
  const [token, setToken] = useState(() => fragmentToken(name));
  const previousHash = useRef(hash);
  useEffect(() => {
    // BrowserRouter can keep this component mounted when another fragment is opened on the
    // same path. Capture that new secret before removing it, but retain the first value across
    // the rerender caused by clearing the address bar.
    if (hash && hash !== previousHash.current) setToken(fragmentToken(name));
    previousHash.current = hash;
    // Drop the module-level render cache as well as the visible fragment. Otherwise a later
    // activation/device-link visit in this long-lived tab replays the first secret it saw.
    consumeFragmentToken(name);
    if (hash) navigate(`${pathname}${search}`, { replace: true });
  }, [hash, name, navigate, pathname, search]);
  return token;
}
