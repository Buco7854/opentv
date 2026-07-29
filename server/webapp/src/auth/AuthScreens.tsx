import {
  FormEvent, ReactNode, useCallback, useEffect, useRef, useState,
} from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { ApiError } from '../api/http';
import { Icon, IconName } from '../components/Icons';
import {
  ConfirmDialog, Dialog, IconBtn, ScreenHeader, Spinner, TextField,
} from '../components/Primitives';
import { reportSuccess } from '../errors';
import { getLocale } from '../i18n';
import { authApi } from './api';
import { AuthLayout } from './AuthLayout';
import { ChoiceRow, ErrorNotice, errorMessage, reportAuthError } from './AuthUi';
import { useAuth } from './AuthProvider';
import { authText as tx } from './copy';
import { beginOidcHandoff, useFragmentToken } from './fragment';
import { MfaStep, RecoveryCodesPanel, RecoveryCodesScreen, TotpSetup } from './TwoFactor';
import { AuthFlow, TotpStatus, WebAuthnCredential } from './types';
import {
  createCredential, getCredential, useCeremonySignal, webAuthnSupported,
} from './webauthn';
import './auth.css';

const safeReturnTo = (value: unknown): string => {
  if (typeof value !== 'string'
      || !value.startsWith('/')
      || value.startsWith('//')
      || value.includes('\\')
      || /[\u0000-\u001f\u007f]/.test(value)) return '/';
  return value;
};

const withoutFragment = (path: string) => path.split('#')[0];

function AuthFlowScreen({
  eyebrow, title, subtitle, footer, initialFlow, children, returnTo = '/',
}: {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  footer?: ReactNode;
  initialFlow: AuthFlow | null;
  children: (setFlow: (flow: AuthFlow) => void) => ReactNode;
  returnTo?: string;
}) {
  const { acceptFlow } = useAuth();
  const navigate = useNavigate();
  const [flow, setFlow] = useState(initialFlow);
  const finish = (completed: AuthFlow) => {
    setFlow(completed);
    if (completed.status === 'AUTHENTICATED' && completed.recoveryCodes.length === 0) {
      acceptFlow(completed);
      navigate(returnTo, { replace: true });
    }
  };
  const acceptRecoveryCodes = () => {
    if (!flow) return;
    acceptFlow(flow);
    navigate(returnTo, { replace: true });
  };

  if (flow?.status === 'AUTHENTICATED') {
    return flow.recoveryCodes.length > 0
      ? <RecoveryCodesScreen codes={flow.recoveryCodes} onDone={acceptRecoveryCodes} />
      : <Spinner />;
  }
  if (flow?.status === 'PENDING_APPROVAL') {
    return (
      <AuthLayout eyebrow={eyebrow} title={title} footer={footer}>
        <div className="auth-step">
          <ErrorNotice message={tx('pending')} />
        </div>
      </AuthLayout>
    );
  }
  if (flow) return <MfaStep flow={flow} onComplete={finish} onRestart={() => setFlow(null)} />;
  return (
    <AuthLayout eyebrow={eyebrow} title={title} subtitle={subtitle} footer={footer}>
      {children(finish)}
    </AuthLayout>
  );
}

export function LoginScreen() {
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const beginCeremony = useCeremonySignal();
  const [searchParams] = useSearchParams();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(
    searchParams.get('auth') === 'pending'
      ? tx('pending')
      : searchParams.get('auth') === 'oidc_error' ? tx('oidcError') : null,
  );
  const [busy, setBusy] = useState(false);
  const locationState = location.state as {
    returnTo?: unknown;
    reauthenticate?: boolean;
  } | null;
  const returnTo = safeReturnTo(locationState?.returnTo);
  const reauthenticate = locationState?.reauthenticate === true;

  useEffect(() => {
    if (auth.phase === 'authenticated' && !reauthenticate) navigate(returnTo, { replace: true });
  }, [auth.phase, navigate, reauthenticate, returnTo]);

  useEffect(() => {
    try { sessionStorage.removeItem('auth.returnTo'); } catch { /* storage unavailable */ }
  }, []);

  if (!auth.capabilities) return <Spinner />;

  const passwordEnabled = auth.capabilities.passwordEnabled;
  const oidcEnabled = auth.capabilities.oidcEnabled;
  const passkeyOffered = auth.capabilities.passkeyLoginEnabled;
  const passkeyEnabled = passkeyOffered && webAuthnSupported();
  const deviceLinkEnabled = auth.capabilities.deviceLinkEnabled;
  // A server with no administrator yet: offer that path here rather than commandeering
  // the screen, so signing in with single sign-on stays available.
  const bootstrapOffered = auth.capabilities.bootstrapRequired;

  const form = (setFlow: (flow: AuthFlow) => void) => {
    const submit = async (event: FormEvent) => {
      event.preventDefault();
      setBusy(true);
      setError(null);
      try {
        setFlow(await authApi.password(username, password));
      } catch (requestError) {
        setError(errorMessage(requestError));
      } finally {
        setBusy(false);
      }
    };
    const startOidc = () => {
      const handoff = beginOidcHandoff();
      if (!handoff) {
        setError(tx('oidcError'));
        return;
      }
      try {
        sessionStorage.setItem('auth.returnTo', withoutFragment(returnTo) ?? '/');
      } catch {
        setError(tx('oidcError'));
        return;
      }
      const startUrl = auth.capabilities?.oidcStartUrl ?? '/api/v1/auth/oidc/start';
      const separator = startUrl.includes('?') ? '&' : '?';
      window.location.assign(
        `${startUrl}${separator}handoff=${encodeURIComponent(handoff)}`,
      );
    };
    const passkey = async () => {
      const signal = beginCeremony();
      setBusy(true);
      setError(null);
      try {
        const options = await authApi.passkeyOptions();
        const credential = await getCredential(options, signal);
        setFlow(await authApi.completePasskeyLogin(options.serverChallenge, credential));
      } catch (requestError) {
        if (signal.aborted) return;
        setError(errorMessage(requestError));
      } finally {
        if (!signal.aborted) setBusy(false);
      }
    };
    return (
      <form className="auth-step" onSubmit={(event) => void submit(event)}>
        <ErrorNotice message={error} />
        {bootstrapOffered && (
          <>
            <ChoiceRow icon="person" title={tx('bootstrapCta')} subtitle={tx('bootstrapCtaSub')}
                       onClick={() => navigate('/setup')} />
            <div className="auth-sep">{tx('or')}</div>
          </>
        )}
        {passkeyEnabled && (
          <button className="btn" type="button" disabled={busy} onClick={() => void passkey()}>
            <Icon name="key" />{busy ? tx('working') : tx('usePasskey')}
          </button>
        )}
        {passkeyEnabled && (passwordEnabled || oidcEnabled) && <div className="auth-sep">{tx('or')}</div>}
        {passwordEnabled && (
          <>
            <TextField label={tx('username')} value={username} onChange={setUsername}
                       autoComplete="username" />
            <TextField label={tx('password')} value={password} onChange={setPassword}
                       type="password" autoComplete="current-password" />
            <button className={`btn${passkeyEnabled ? ' tonal' : ''}`}
                    disabled={busy || !username || !password}>
              {busy ? tx('working') : tx('signIn')}
            </button>
          </>
        )}
        {passwordEnabled && oidcEnabled && <div className="auth-sep">{tx('or')}</div>}
        {oidcEnabled && (
          <button className="btn tonal" type="button" onClick={startOidc}>
            <Icon name="link" />{tx('sso')}
          </button>
        )}
        {passkeyOffered && !passkeyEnabled && <p className="auth-hint">{tx('passkeyUnavailable')}</p>}
        {!passwordEnabled && !oidcEnabled && !passkeyOffered && <p className="auth-hint">{tx('noMethods')}</p>}
      </form>
    );
  };

  return (
    <AuthFlowScreen
      title={tx('loginTitle')}
      subtitle={tx('loginSubtitle')}
      returnTo={returnTo}
      initialFlow={null}
      footer={
        <>
          {deviceLinkEnabled && (
            <Link className="link" to="/login/device">{tx('useAnotherDevice')}</Link>
          )}
          <span className="auth-hint">{tx('haveToken')}</span>
          <Link className="link" to="/activate">{tx('activateAccount')}</Link>
        </>
      }
    >
      {form}
    </AuthFlowScreen>
  );
}

export function SetupScreen() {
  const auth = useAuth();
  const [token, setToken] = useState('');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!auth.capabilities) return <Spinner />;
  // Reachable directly and from the sign-in screen, so it has to say why it is closed
  // rather than bouncing silently.
  if (!auth.capabilities.bootstrapRequired) {
    return (
      <AuthLayout eyebrow={tx('setupEyebrow')} title={tx('setupTitle')}
                  footer={<Link className="link" to="/login">{tx('backToLogin')}</Link>}>
        <div className="auth-step">
          <ErrorNotice message={tx('setupDone')} />
        </div>
      </AuthLayout>
    );
  }

  const form = (setFlow: (flow: AuthFlow) => void) => {
    const submit = async (event: FormEvent) => {
      event.preventDefault();
      if (Array.from(password).length < 12) {
        setError(tx('passwordHint'));
        return;
      }
      setBusy(true);
      setError(null);
      try {
        const flow = await authApi.bootstrap(token, username, password, displayName);
        reportSuccess(tx('bootstrapDone'));
        setFlow(flow);
      } catch (requestError) {
        setError(errorMessage(requestError));
      } finally {
        setBusy(false);
      }
    };
    return (
      <form className="auth-step" onSubmit={(event) => void submit(event)}>
        <ErrorNotice message={error} />
        <TextField label={tx('setupToken')} value={token} onChange={setToken} autoFocus />
        <TextField label={tx('username')} value={username} onChange={setUsername} autoComplete="username" />
        <TextField label={tx('displayName')} value={displayName} onChange={setDisplayName} />
        <TextField label={tx('choosePassword')} value={password} onChange={setPassword}
                   type="password" autoComplete="new-password" />
        <p className="auth-hint">{tx('passwordHint')}</p>
        <button className="btn" disabled={busy || !token || !username || !password}>
          {busy ? tx('working') : tx('createAdmin')}
        </button>
      </form>
    );
  };

  return (
    <AuthFlowScreen
      eyebrow={tx('setupEyebrow')}
      title={tx('setupTitle')}
      subtitle={tx('setupSubtitle')}
      initialFlow={null}
      footer={<Link className="link" to="/login">{tx('backToLogin')}</Link>}
    >
      {form}
    </AuthFlowScreen>
  );
}

export function ActivateScreen() {
  const linkedToken = useFragmentToken('token');
  const [token, setToken] = useState(linkedToken ?? '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const form = (setFlow: (flow: AuthFlow) => void) => {
    const submit = async (event: FormEvent) => {
      event.preventDefault();
      if (Array.from(password).length < 12) {
        setError(tx('passwordHint'));
        return;
      }
      setBusy(true);
      setError(null);
      try {
        setFlow(await authApi.activate(token, password));
      } catch (requestError) {
        setError(errorMessage(requestError));
      } finally {
        setBusy(false);
      }
    };
    return (
      <form className="auth-step" onSubmit={(event) => void submit(event)}>
        <ErrorNotice message={error} />
        <TextField label={tx('activationToken')} value={token} onChange={setToken} autoFocus={!token} />
        <TextField label={tx('choosePassword')} value={password} onChange={setPassword}
                   type="password" autoComplete="new-password" autoFocus={Boolean(token)} />
        <p className="auth-hint">{tx('passwordHint')}</p>
        <button className="btn" disabled={busy || !token || !password}>
          {busy ? tx('working') : tx('continue')}
        </button>
      </form>
    );
  };

  return (
    <AuthFlowScreen
      eyebrow={tx('activateEyebrow')}
      title={tx('activateTitle')}
      subtitle={tx('activateSubtitle')}
      initialFlow={null}
      footer={<Link className="link" to="/login">{tx('backToLogin')}</Link>}
    >
      {form}
    </AuthFlowScreen>
  );
}

function SecurityCard({ icon, title, subtitle, children }: {
  icon: IconName;
  title: string;
  subtitle: string;
  children: ReactNode;
}) {
  return (
    <section className="security-card">
      <div className="head">
        <span className="logo-box"><Icon name={icon} /></span>
        <div className="body">
          <div className="title">{title}</div>
          <div className="sub">{subtitle}</div>
        </div>
      </div>
      {children}
    </section>
  );
}

function PasskeyList({ credentials, onRemove }: {
  credentials: WebAuthnCredential[] | null;
  onRemove: (credential: WebAuthnCredential) => void;
}) {
  if (credentials === null) return <Spinner />;
  if (credentials.length === 0) return <p className="auth-hint">{tx('noPasskeys')}</p>;
  const stamp = (ms: number) => new Date(ms).toLocaleDateString(getLocale(), { dateStyle: 'medium' });
  return (
    <div className="key-list">
      {credentials.map((credential) => (
        <div className="row" key={credential.id}>
          <span className="logo-box"><Icon name="key" /></span>
          <div className="body">
            <div className="title">{credential.label}</div>
            <div className="sub">
              {[
                tx('passkeyAdded', { date: stamp(credential.createdAtMs) }),
                credential.lastUsedAtMs
                  ? tx('passkeyLastUsed', { date: stamp(credential.lastUsedAtMs) })
                  : tx('passkeyNeverUsed'),
                credential.backedUp ? tx('passkeySynced') : null,
              ].filter(Boolean).join(' · ')}
            </div>
          </div>
          <div className="actions">
            <IconBtn name="del" className="danger" label={tx('passkeyRemove')}
                     onClick={() => onRemove(credential)} />
          </div>
        </div>
      ))}
    </div>
  );
}

export function SecurityScreen() {
  const auth = useAuth();
  const navigate = useNavigate();
  const beginCeremony = useCeremonySignal();
  const [password, setPassword] = useState('');
  const [keyLabel, setKeyLabel] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [passkeys, setPasskeys] = useState<WebAuthnCredential[] | null>(null);
  const [removing, setRemoving] = useState<WebAuthnCredential | null>(null);
  const [totp, setTotp] = useState<TotpStatus | null>(null);
  const [addingTotp, setAddingTotp] = useState(false);
  const [removingTotp, setRemovingTotp] = useState(false);
  const [signingOutEverywhere, setSigningOutEverywhere] = useState(false);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const factorRequest = useRef(0);

  const reportSecurityError = (requestError: unknown) => {
    if (requestError instanceof ApiError && requestError.status === 403) {
      navigate('/login', {
        replace: true,
        state: { returnTo: '/security', reauthenticate: true },
      });
      return;
    }
    setError(errorMessage(requestError));
  };

  const reportFactorError = (requestError: unknown) => {
    if (requestError instanceof ApiError && requestError.code === 'last_factor') {
      setError(tx('passkeyLastFactor'));
      return;
    }
    reportSecurityError(requestError);
  };

  const adopt = (flow: AuthFlow, success: string) => {
    if (flow.status !== 'AUTHENTICATED') throw new Error(tx('recentAuthRequired'));
    auth.acceptFlow(flow);
    if (flow.recoveryCodes.length > 0) setRecoveryCodes(flow.recoveryCodes);
    reportSuccess(success);
  };

  const changePassword = async (event: FormEvent) => {
    event.preventDefault();
    if (Array.from(password).length < 12) {
      setError(tx('passwordHint'));
      return;
    }
    setBusy('password');
    setError(null);
    try {
      adopt(await authApi.changePassword(password), tx('passwordChanged'));
      setPassword('');
    } catch (requestError) {
      reportSecurityError(requestError);
    } finally {
      setBusy(null);
    }
  };

  // Both factor lists in one attempt: a server that is down fails them together and must
  // say so once, not twice, and not by quietly rendering "no passkeys".
  const loadFactors = useCallback(() => {
    const request = ++factorRequest.current;
    void Promise.all([
      authApi.webAuthnCredentials(),
      authApi.totpStatus(),
    ]).then(([nextPasskeys, nextTotp]) => {
      if (request !== factorRequest.current) return;
      setPasskeys(nextPasskeys);
      setTotp(nextTotp);
    }).catch((requestError) => {
      if (request !== factorRequest.current) return;
      setPasskeys((current) => current ?? []);
      setTotp((current) => current ?? { enrolled: false, confirmedAtMs: null });
      reportAuthError(requestError);
    });
  }, []);

  useEffect(() => {
    loadFactors();
    return () => { factorRequest.current++; };
  }, [loadFactors]);

  const addKey = async () => {
    const signal = beginCeremony();
    setBusy('key');
    setError(null);
    try {
      const options = await authApi.additionalRegistrationOptions();
      const credential = await createCredential(options, signal);
      adopt(
        await authApi.completeAdditionalRegistration(options.serverChallenge, credential, keyLabel),
        tx('securityKeyAdded'),
      );
      loadFactors();
    } catch (requestError) {
      if (signal.aborted) return;
      reportSecurityError(requestError);
    } finally {
      if (!signal.aborted) setBusy(null);
    }
  };

  const removeKey = async (credential: WebAuthnCredential) => {
    setBusy('key');
    setError(null);
    try {
      adopt(await authApi.deleteWebAuthnCredential(credential.id), tx('passkeyRemoved'));
      setPasskeys((current) => current?.filter((item) => item.id !== credential.id) ?? current);
    } catch (requestError) {
      reportFactorError(requestError);
    } finally {
      setBusy(null);
    }
  };

  const removeTotp = async () => {
    setBusy('totp');
    setError(null);
    try {
      adopt(await authApi.deleteTotp(), tx('authenticatorRemoved'));
      setTotp({ enrolled: false, confirmedAtMs: null });
    } catch (requestError) {
      reportFactorError(requestError);
    } finally {
      setBusy(null);
    }
  };

  const regenerate = async () => {
    setBusy('recovery');
    setError(null);
    try {
      adopt(await authApi.regenerateRecoveryCodes(), tx('recoveryRegenerated'));
    } catch (requestError) {
      reportSecurityError(requestError);
    } finally {
      setBusy(null);
    }
  };

  const logout = async (all: boolean) => {
    setBusy('logout');
    await auth.logout(all);
    navigate('/login', { replace: true });
  };

  // What this account can do, not how this session happened to sign in: somebody with a
  // password who signed in through SSO still owns that password.
  const passwordAccount = auth.user?.hasPassword === true;
  // The server reports whether the address this page was opened on can be a WebAuthn
  // relying party at all; offering a key form it would refuse is a dead end.
  const keysUsable = webAuthnSupported() && auth.capabilities?.passkeyLoginEnabled !== false;
  const working = busy !== null;

  return (
    <>
      <ScreenHeader
        title={tx('securityTitle')}
        subtitle={<span className="subtitle">{tx('securitySubtitle')}</span>}
        onBack={() => navigate(-1)}
      />
      <div className="mx-auto flex max-w-[620px] flex-col gap-3 px-4 pb-6">
        <ErrorNotice message={error} />

        {auth.capabilities?.passwordEnabled && passwordAccount && (
          <form className="security-card" onSubmit={(event) => void changePassword(event)}>
            <div className="head">
              <span className="logo-box"><Icon name="lock" /></span>
              <div className="body">
                <div className="title">{tx('passwordCard')}</div>
                <div className="sub">{tx('passwordCardBody')}</div>
              </div>
            </div>
            <TextField label={tx('newPassword')} value={password} onChange={setPassword}
                       type="password" autoComplete="new-password" />
            <p className="auth-hint">{tx('passwordHint')}</p>
            <div className="actions">
              <button className="btn" disabled={working || !password}>
                {busy === 'password' ? tx('working') : tx('changePassword')}
              </button>
            </div>
          </form>
        )}

        {/* An authenticator is only ever asked for during password sign-in, and recovery
            codes only recover that step - so without a password neither can be reached.
            Passkeys are different: they are also a primary sign-in method, so they stay. */}
        {passwordAccount && (
        <SecurityCard icon="phone" title={tx('authenticatorCard')} subtitle={tx('authenticatorCardBody')}>
          {totp?.enrolled ? (
            <>
              <p className="auth-hint">
                {totp.confirmedAtMs
                  ? tx('authenticatorReady', {
                      date: new Date(totp.confirmedAtMs).toLocaleDateString(getLocale(), { dateStyle: 'medium' }),
                    })
                  : tx('authenticatorCardBody')}
              </p>
              <div className="actions">
                <button className="btn danger-text" disabled={working}
                        onClick={() => setRemovingTotp(true)}>
                  {busy === 'totp' ? tx('working') : tx('authenticatorRemove')}
                </button>
              </div>
            </>
          ) : (
            <div className="actions">
              <button className="btn" disabled={working || totp === null}
                      onClick={() => setAddingTotp(true)}>
                {tx('authenticatorAdd')}
              </button>
            </div>
          )}
        </SecurityCard>
        )}

        <SecurityCard icon="key" title={tx('passkeysCard')} subtitle={tx('passkeysCardBody')}>
          <PasskeyList credentials={passkeys} onRemove={setRemoving} />
          <div className="divider" />
          {keysUsable ? (
            <>
              <TextField label={tx('securityKeyLabel')} value={keyLabel} onChange={setKeyLabel} />
              <p className="auth-hint">{tx('securityKeyReplacesRecovery')}</p>
              <div className="actions">
                <button className="btn" disabled={working || !keyLabel.trim()} onClick={() => void addKey()}>
                  {busy === 'key' ? tx('working') : tx('addSecurityKey')}
                </button>
              </div>
            </>
          ) : (
            // Two different dead ends: this browser cannot, or this address cannot.
            <p className="auth-hint">
              {webAuthnSupported() ? tx('passkeyAddressUnsupported') : tx('securityKeyUnavailable')}
            </p>
          )}
        </SecurityCard>

        {/* Recovery codes recover the password MFA step, so they follow the authenticator. */}
        {passwordAccount && (
        <SecurityCard icon="lifebuoy" title={tx('recoveryCard')} subtitle={tx('recoveryCardBody')}>
          <div className="actions">
            <button className="btn tonal" disabled={working} onClick={() => void regenerate()}>
              {busy === 'recovery' ? tx('working') : tx('regenerate')}
            </button>
          </div>
        </SecurityCard>
        )}
        {!passwordAccount && <p className="auth-hint">{tx('noPasswordAccount')}</p>}

        <SecurityCard icon="logout" title={tx('sessionsCard')} subtitle={tx('sessionsCardBody')}>
          <div className="actions">
            <button className="btn tonal" disabled={working} onClick={() => void logout(false)}>
              {tx('currentLogout')}
            </button>
            <button className="btn danger-text" disabled={working}
                    onClick={() => setSigningOutEverywhere(true)}>
              {tx('allLogout')}
            </button>
          </div>
        </SecurityCard>
      </div>

      {addingTotp && (
        <Dialog title={tx('authenticatorCard')} onDismiss={() => setAddingTotp(false)}>
          <p className="hint">{tx('totpBody')}</p>
          <TotpSetup
            start={authApi.startTotpAdd}
            complete={async (challenge, code) => {
              adopt(await authApi.completeTotpAdd(challenge, code), tx('authenticatorAdded'));
              setAddingTotp(false);
              loadFactors();
            }}
            onError={(requestError) => {
              if (requestError instanceof ApiError && requestError.status === 403) {
                setAddingTotp(false);
                reportSecurityError(requestError);
                return true;
              }
              return false;
            }}
          />
        </Dialog>
      )}

      {removingTotp && (
        <ConfirmDialog
          title={tx('authenticatorRemoveTitle')}
          message={tx('authenticatorRemoveBody')}
          confirmLabel={tx('authenticatorRemove')}
          onDismiss={() => setRemovingTotp(false)}
          onConfirm={() => void removeTotp()}
        />
      )}

      {removing && (
        <ConfirmDialog
          title={tx('passkeyRemoveTitle')}
          message={tx('passkeyRemoveBody')}
          confirmLabel={tx('passkeyRemove')}
          onDismiss={() => setRemoving(null)}
          onConfirm={() => void removeKey(removing)}
        />
      )}

      {signingOutEverywhere && (
        <ConfirmDialog
          title={tx('allLogoutTitle')}
          message={tx('allLogoutBody')}
          confirmLabel={tx('allLogout')}
          onDismiss={() => setSigningOutEverywhere(false)}
          onConfirm={() => void logout(true)}
        />
      )}

      {recoveryCodes.length > 0 && (
        <Dialog title={tx('recoveryTitle')} dismissible={false} onDismiss={() => setRecoveryCodes([])}>
          <p className="auth-hint">{tx('recoveryReplaced')}</p>
          <RecoveryCodesPanel codes={recoveryCodes} onDone={() => setRecoveryCodes([])} />
        </Dialog>
      )}
    </>
  );
}

export { ApiError };
