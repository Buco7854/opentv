import {
  createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { browserApiHttp } from '../api/http';
import { Spinner } from '../components/Primitives';
import { clearUserActivitySnapshots, setServerSettingsAllowed } from '../hooks';
import { authApi } from './api';
import { AuthCapabilities, AuthFlow, CurrentUser } from './types';
import { authText as tx } from './copy';

type AuthPhase = 'loading' | 'authenticated' | 'unauthenticated' | 'error';

interface AuthContextValue {
  phase: AuthPhase;
  capabilities: AuthCapabilities | null;
  user: CurrentUser | null;
  error: string | null;
  acceptFlow: (flow: AuthFlow) => void;
  refresh: () => Promise<void>;
  logout: (all?: boolean) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [phase, setPhase] = useState<AuthPhase>('loading');
  const [capabilities, setCapabilities] = useState<AuthCapabilities | null>(null);
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [error, setError] = useState<string | null>(null);
  const csrf = useRef<string | null>(null);

  const clearSession = useCallback(() => {
    clearUserActivitySnapshots();
    setServerSettingsAllowed(false);
    csrf.current = null;
    setUser(null);
    setPhase('unauthenticated');
  }, []);

  const installUser = useCallback((next: CurrentUser) => {
    clearUserActivitySnapshots();
    setServerSettingsAllowed(next.role === 'ADMIN');
    csrf.current = next.csrfToken;
    setUser(next);
    setError(null);
    setPhase('authenticated');
  }, []);

  const refresh = useCallback(async () => {
    try {
      installUser(await authApi.me());
    } catch (requestError) {
      clearSession();
      if (!(requestError instanceof Error)) return;
      // An unauthenticated /auth/me is the normal signed-out state.
      if ('status' in requestError && requestError.status === 401) return;
      setError(requestError.message);
      setPhase('error');
    }
  }, [clearSession, installUser]);

  useEffect(() => {
    browserApiHttp.setCsrfTokenProvider(() => csrf.current);
    const unsubscribe = browserApiHttp.onUnauthorized(clearSession);
    const unsubscribeForbidden = browserApiHttp.onForbidden(() => { void refresh(); });
    let active = true;
    void (async () => {
      try {
        const nextCapabilities = await authApi.capabilities();
        if (!active) return;
        setCapabilities(nextCapabilities);
        await refresh();
      } catch (requestError) {
        if (!active) return;
        setError(requestError instanceof Error ? requestError.message : String(requestError));
        setPhase('error');
      }
    })();
    return () => {
      active = false;
      unsubscribe();
      unsubscribeForbidden();
      browserApiHttp.setCsrfTokenProvider(undefined);
    };
  }, [clearSession, refresh]);

  const acceptFlow = useCallback((flow: AuthFlow) => {
    if (flow.status !== 'AUTHENTICATED' || !flow.user) {
      throw new Error(tx('authIncomplete'));
    }
    installUser({
      ...flow.user,
      csrfToken: flow.csrfToken ?? flow.user.csrfToken,
    });
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
    logout,
  }), [acceptFlow, capabilities, error, logout, phase, refresh, user]);

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
      <div className="auth-state">
        <h1>OpenTV</h1>
        <p>{auth.error ?? tx('serviceUnavailable')}</p>
        <button className="btn tonal" onClick={() => void auth.refresh()}>{tx('retry')}</button>
      </div>
    );
  }
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
    const saved = sessionStorage.getItem('auth.returnTo');
    if (!saved) return;
    sessionStorage.removeItem('auth.returnTo');
    if (saved.startsWith('/')
        && !saved.startsWith('//')
        && !saved.includes('\\')
        && !/[\u0000-\u001f\u007f]/.test(saved)) {
      navigate(saved, { replace: true });
    }
  }, [navigate, phase]);
  return null;
}
