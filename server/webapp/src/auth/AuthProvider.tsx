import {
  createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  ApiError,
  browserAccessToken,
  browserApiHttp,
  isTransportError,
  setBrowserAccessToken,
} from '../api/http';
import { Spinner } from '../components/Primitives';
import { clearUserActivitySnapshots, setServerSettingsAllowed } from '../hooks';
import { authApi } from './api';
import { AuthLayout } from './AuthLayout';
import { errorMessage } from './AuthUi';
import { AuthCapabilities, AuthFlow, CurrentUser } from './types';
import { authText as tx } from './copy';
import { consumeOidcSessionToken } from './fragment';

type AuthPhase = 'loading' | 'authenticated' | 'unauthenticated' | 'error';

const AUTHORITY_REFRESH_INTERVAL_MS = 5000;
const OFFLINE_RETRY_INTERVAL_MS = 5000;

interface AuthContextValue {
  phase: AuthPhase;
  capabilities: AuthCapabilities | null;
  user: CurrentUser | null;
  error: string | null;
  acceptFlow: (flow: AuthFlow) => void;
  refresh: () => Promise<void>;
  retry: () => Promise<void>;
  logout: (all?: boolean) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [phase, setPhase] = useState<AuthPhase>(() => {
    const returnedToken = consumeOidcSessionToken();
    if (returnedToken) setBrowserAccessToken(returnedToken);
    return 'loading';
  });
  const [capabilities, setCapabilities] = useState<AuthCapabilities | null>(null);
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [error, setError] = useState<string | null>(null);
  const session = useRef<CurrentUser | null>(null);
  const offlineRetry = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const refreshGeneration = useRef(0);

  const clearSession = useCallback(() => {
    refreshGeneration.current++;
    clearTimeout(offlineRetry.current);
    clearUserActivitySnapshots();
    setServerSettingsAllowed(false);
    setBrowserAccessToken(null);
    session.current = null;
    setUser(null);
    setError(null);
    setPhase('unauthenticated');
  }, []);

  const installUser = useCallback((next: CurrentUser) => {
    if (session.current?.id !== next.id) clearUserActivitySnapshots();
    setServerSettingsAllowed(next.role === 'ADMIN');
    session.current = next;
    setUser(next);
    setError(null);
    setPhase('authenticated');
  }, []);

  const refresh: () => Promise<void> = useCallback(async () => {
    const generation = ++refreshGeneration.current;
    const attemptedToken = browserAccessToken();
    clearTimeout(offlineRetry.current);
    try {
      const next = await authApi.me();
      if (generation !== refreshGeneration.current) return;
      if (browserAccessToken() !== attemptedToken) {
        void refresh();
        return;
      }
      installUser(next);
    } catch (requestError) {
      if (generation !== refreshGeneration.current) return;
      if (browserAccessToken() !== attemptedToken) {
        void refresh();
        return;
      }
      if (isTransportError(requestError) && session.current) {
        offlineRetry.current = setTimeout(() => void refresh(), OFFLINE_RETRY_INTERVAL_MS);
        return;
      }
      // An unauthenticated /auth/me is the normal signed-out state.
      if (requestError instanceof ApiError && requestError.status === 401) {
        clearSession();
        return;
      }
      setError(errorMessage(requestError));
      setPhase('error');
    }
  }, [clearSession, installUser]);

  const inFlight = useRef<Promise<void> | null>(null);
  const refreshedAtMs = useRef(0);
  const refreshAuthority = useCallback(() => {
    if (inFlight.current) return;
    if (Date.now() - refreshedAtMs.current < AUTHORITY_REFRESH_INTERVAL_MS) return;
    inFlight.current = refresh().finally(() => {
      refreshedAtMs.current = Date.now();
      inFlight.current = null;
    });
  }, [refresh]);

  // Capabilities decide which screen is reachable at all, so a failure here is
  // fatal to routing and must stay retryable rather than being folded into the
  // session refresh.
  const initialize = useCallback(async (isActive: () => boolean = () => true) => {
    try {
      const nextCapabilities = await authApi.capabilities();
      if (!isActive()) return;
      setCapabilities(nextCapabilities);
      await refresh();
    } catch (requestError) {
      if (!isActive()) return;
      setError(errorMessage(requestError));
      setPhase('error');
    }
  }, [refresh]);

  const retry = useCallback(async () => {
    setError(null);
    setPhase('loading');
    await initialize();
  }, [initialize]);

  useEffect(() => {
    const unsubscribe = browserApiHttp.onUnauthorized(clearSession);
    const unsubscribeForbidden = browserApiHttp.onForbidden(refreshAuthority);
    const onStorage = (event: StorageEvent) => {
      // localStorage.clear() is reported with a null key and removes the bearer too.
      if (event.key !== ACCESS_TOKEN_STORAGE_KEY && event.key !== null) return;
      if (event.key === null || event.newValue == null) {
        clearSession();
        return;
      }
      // Do not render one account's protected state while requests already carry another
      // account's bearer. The replacement user is installed only after /auth/me confirms it.
      refreshGeneration.current++;
      clearTimeout(offlineRetry.current);
      clearUserActivitySnapshots();
      setServerSettingsAllowed(false);
      session.current = null;
      setUser(null);
      setError(null);
      setPhase('loading');
      void refresh();
    };
    let active = true;
    window.addEventListener('storage', onStorage);
    void initialize(() => active);
    return () => {
      active = false;
      refreshGeneration.current++;
      clearTimeout(offlineRetry.current);
      window.removeEventListener('storage', onStorage);
      unsubscribe();
      unsubscribeForbidden();
    };
  }, [clearSession, initialize, refreshAuthority]);

  const acceptFlow = useCallback((flow: AuthFlow) => {
    if (flow.status !== 'AUTHENTICATED' || !flow.user) {
      throw new Error(tx('authIncomplete'));
    }
    if (!flow.sessionToken) throw new Error(tx('authIncomplete'));
    refreshGeneration.current++;
    clearTimeout(offlineRetry.current);
    setBrowserAccessToken(flow.sessionToken);
    installUser(flow.user);
  }, [installUser]);

  const logout = useCallback(async (all = false) => {
    try {
      await authApi.logout(all);
    } finally {
      clearSession();
    }
  }, [clearSession]);

  const value = useMemo<AuthContextValue>(() => ({
    phase,
    capabilities,
    user,
    error,
    acceptFlow,
    refresh,
    retry,
    logout,
  }), [acceptFlow, capabilities, error, logout, phase, refresh, retry, user]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const location = useLocation();
  if (auth.phase === 'loading') return <Spinner />;
  if (auth.phase === 'error') {
    return (
      <AuthLayout title={tx('serviceUnavailableTitle')}>
        <div className="auth-step">
          <p>{auth.error ?? tx('serviceUnavailable')}</p>
          <button className="btn tonal" onClick={() => void auth.retry()}>{tx('retry')}</button>
        </div>
      </AuthLayout>
    );
  }
  // A server with no administrator yet still lands on the sign-in screen: it offers the
  // first-administrator path alongside single sign-on, which may be how this server is
  // meant to be entered. Forcing /setup hid that choice.
  if (auth.phase !== 'authenticated') {
    const returnTo = `${location.pathname}${location.search}${location.hash}`;
    const pending = new URLSearchParams(location.search).get('auth') === 'pending';
    return <Navigate to={pending ? '/login?auth=pending' : '/login'} replace state={{ returnTo }} />;
  }
  return children;
}

export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  if (user?.role !== 'ADMIN') return <Navigate to="/" replace />;
  return children;
}

/** Restores the protected route saved immediately before an OIDC redirect. */
export function AuthReturnHandler() {
  const { phase } = useAuth();
  const navigate = useNavigate();
  useEffect(() => {
    if (phase !== 'authenticated') return;
    let saved: string | null;
    try {
      saved = sessionStorage.getItem('auth.returnTo');
      if (saved) sessionStorage.removeItem('auth.returnTo');
    } catch {
      // Password/passkey/device-link sessions do not depend on OIDC return storage.
      return;
    }
    if (!saved) return;
    if (saved.startsWith('/')
        && !saved.startsWith('//')
        && !saved.includes('\\')
        && !/[\u0000-\u001f\u007f]/.test(saved)) {
      navigate(saved, { replace: true });
    }
  }, [navigate, phase]);
  return null;
}
