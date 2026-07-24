import { FormEvent, ReactNode, useEffect, useState } from 'react';
import { toDataURL } from 'qrcode';
import {
  Link, useLocation, useNavigate, useSearchParams,
} from 'react-router-dom';
import { ApiError } from '../api/http';
import { snackbar, Spinner, TextField } from '../components/Primitives';
import { authApi } from './api';
import { useAuth } from './AuthProvider';
import { authText as tx } from './copy';
import { AuthFlow, TotpEnrollment } from './types';
import { createCredential, getCredential } from './webauthn';
import './auth.css';

type AuthMethod = 'totp' | 'webauthn' | 'recovery';

const errorMessage = (error: unknown) => {
  if (error instanceof ApiError) {
    if (error.code === 'challenge_invalid') return tx('challengeInvalid');
    if (error.code === 'auth_rate_limited') return tx('rateLimited');
  }
  return error instanceof Error ? error.message : tx('genericError');
};

const safeReturnTo = (value: unknown): string => {
  if (typeof value !== 'string'
      || !value.startsWith('/')
      || value.startsWith('//')
      || value.includes('\\')
      || /[\u0000-\u001f\u007f]/.test(value)) return '/';
  return value;
};

function AuthLayout({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="auth-brand">OpenTV</div>
        <h1>{title}</h1>
        {children}
      </section>
    </main>
  );
}

function ErrorNotice({ message }: { message: string | null }) {
  return message ? <p className="auth-error" role="alert">{message}</p> : null;
}

function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const text = codes.join('\n');
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      snackbar(tx('copied'));
    } catch {
      snackbar(tx('copyFailed'));
    }
  };
  const download = () => {
    try {
      const url = URL.createObjectURL(new Blob([`${text}\n`], { type: 'text/plain' }));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'opentv-recovery-codes.txt';
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch {
      snackbar(tx('downloadFailed'));
    }
  };
  return (
    <div className="auth-step">
      <h2>{tx('recoveryTitle')}</h2>
      <p>{tx('recoveryBody')}</p>
      <pre className="auth-recovery">{text}</pre>
      <div className="auth-button-row">
        <button type="button" className="btn tonal" onClick={() => void copy()}>{tx('copy')}</button>
        <button type="button" className="btn tonal" onClick={download}>{tx('download')}</button>
      </div>
      <button type="button" className="btn" onClick={onDone}>{tx('savedCodes')}</button>
    </div>
  );
}

function TotpEnrollmentStep({
  challenge, onComplete,
}: {
  challenge: string;
  onComplete: (flow: AuthFlow) => void;
}) {
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(true);

  useEffect(() => {
    let active = true;
    void authApi.startTotpEnrollment(challenge).then((result) => {
      if (!active) return;
      setEnrollment(result);
      return toDataURL(result.uri, {
        width: 224,
        margin: 1,
        color: { dark: '#111111', light: '#ffffff' },
      });
    }).then((dataUrl) => {
      if (active && dataUrl) setQr(dataUrl);
    }).catch((requestError) => {
      if (active) setError(errorMessage(requestError));
    }).finally(() => {
      if (active) setBusy(false);
    });
    return () => { active = false; };
  }, [challenge]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!enrollment || code.length !== 6) return;
    setBusy(true);
    setError(null);
    try {
      onComplete(await authApi.completeTotpEnrollment(enrollment.challenge, code));
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setBusy(false);
    }
  };

  if (busy && !enrollment) return <Spinner />;
  return (
    <form className="auth-step" onSubmit={(event) => void submit(event)}>
      <h2>{tx('totpTitle')}</h2>
      <p>{tx('totpBody')}</p>
      {qr && <img className="auth-qr" src={qr} alt="" />}
      {enrollment && (
        <div className="auth-secret">
          <span>{tx('secret')}</span>
          <code>{enrollment.secret}</code>
        </div>
      )}
      <TextField
        label={tx('totpCode')}
        value={code}
        onChange={(value) => setCode(value.replace(/\D/g, '').slice(0, 6))}
        autoComplete="one-time-code"
      />
      <ErrorNotice message={error} />
      <button className="btn" disabled={busy || code.length !== 6}>{tx('verify')}</button>
    </form>
  );
}

function MfaStep({
  flow, onComplete,
}: {
  flow: AuthFlow;
  onComplete: (next: AuthFlow) => void;
}) {
  const enrollment = flow.status === 'ENROLLMENT_REQUIRED';
  const available = flow.methods as AuthMethod[];
  const [method, setMethod] = useState<AuthMethod | null>(
    enrollment && available.length === 1 ? available[0] : null,
  );
  const [code, setCode] = useState('');
  const [label, setLabel] = useState(tx('securityKeyDefault'));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const challenge = flow.challenge;

  if (!challenge) return <ErrorNotice message={tx('challengeMissing')} />;
  if (method === 'totp' && enrollment) {
    return <TotpEnrollmentStep challenge={challenge} onComplete={onComplete} />;
  }

  const securityKey = async () => {
    setBusy(true);
    setError(null);
    try {
      if (enrollment) {
        const options = await authApi.registrationOptions(challenge);
        const credential = await createCredential(options);
        onComplete(await authApi.completeRegistration(options.serverChallenge, credential, label));
      } else {
        const options = await authApi.authenticationOptions(challenge);
        const credential = await getCredential(options);
        onComplete(await authApi.completeAuthentication(options.serverChallenge, credential));
      }
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setBusy(false);
    }
  };

  const submitCode = async (event: FormEvent) => {
    event.preventDefault();
    if (!method) return;
    setBusy(true);
    setError(null);
    try {
      onComplete(method === 'totp'
        ? await authApi.completeTotp(challenge, code)
        : await authApi.completeRecovery(challenge, code));
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setBusy(false);
    }
  };

  if (!method) {
    return (
      <div className="auth-step">
        <h2>{tx('mfaTitle')}</h2>
        <p>{tx('mfaBody')}</p>
        {available.includes('totp') && (
          <button className="btn tonal" onClick={() => setMethod('totp')}>{tx('useAuthenticator')}</button>
        )}
        {available.includes('webauthn') && (
          <button className="btn tonal" onClick={() => setMethod('webauthn')}>{tx('useSecurityKey')}</button>
        )}
        {available.includes('recovery') && !enrollment && (
          <button className="btn tonal" onClick={() => setMethod('recovery')}>{tx('useRecovery')}</button>
        )}
      </div>
    );
  }

  if (method === 'webauthn') {
    return (
      <div className="auth-step">
        <h2>{tx('useSecurityKey')}</h2>
        {enrollment && (
          <TextField label={tx('securityKeyLabel')} value={label} onChange={setLabel} />
        )}
        <ErrorNotice message={error} />
        <button className="btn" disabled={busy} onClick={() => void securityKey()}>
          {busy ? '…' : tx('continue')}
        </button>
      </div>
    );
  }

  return (
    <form className="auth-step" onSubmit={(event) => void submitCode(event)}>
      <h2>{method === 'totp' ? tx('useAuthenticator') : tx('useRecovery')}</h2>
      <TextField
        label={method === 'totp' ? tx('totpCode') : tx('recoveryCode')}
        value={code}
        onChange={(value) => setCode(method === 'totp' ? value.replace(/\D/g, '').slice(0, 6) : value)}
        autoComplete="one-time-code"
      />
      <ErrorNotice message={error} />
      <button className="btn" disabled={busy || !code}>{tx('verify')}</button>
    </form>
  );
}

function AuthFlowScreen({
  title, initialFlow, children, returnTo = '/',
}: {
  title: string;
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
  return (
    <AuthLayout title={title}>
      {!flow && children(finish)}
      {flow && flow.status !== 'AUTHENTICATED' && <MfaStep flow={flow} onComplete={finish} />}
      {flow?.status === 'AUTHENTICATED' && flow.recoveryCodes.length > 0 && (
        <RecoveryCodes codes={flow.recoveryCodes} onDone={acceptRecoveryCodes} />
      )}
    </AuthLayout>
  );
}

export function LoginScreen() {
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
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

  if (!auth.capabilities) return <Spinner />;
  if (auth.capabilities.bootstrapRequired) {
    return (
      <AuthLayout title={tx('loginTitle')}>
        <Link className="btn" to="/setup">{tx('setupTitle')}</Link>
      </AuthLayout>
    );
  }

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
      sessionStorage.setItem('auth.returnTo', returnTo);
      window.location.assign(auth.capabilities?.oidcStartUrl ?? '/api/v1/auth/oidc/start');
    };
    return (
      <form className="auth-step" onSubmit={(event) => void submit(event)}>
        {auth.capabilities?.passwordEnabled && (
          <>
            <TextField label={tx('username')} value={username} onChange={setUsername}
                       autoFocus autoComplete="username" />
            <TextField label={tx('password')} value={password} onChange={setPassword}
                       type="password" autoComplete="current-password" />
            <button className="btn" disabled={busy || !username || !password}>{tx('signIn')}</button>
          </>
        )}
        {auth.capabilities?.oidcEnabled && (
          <button className="btn tonal" type="button" onClick={startOidc}>{tx('sso')}</button>
        )}
        {!auth.capabilities?.passwordEnabled && !auth.capabilities?.oidcEnabled && <p>{tx('noMethods')}</p>}
        <ErrorNotice message={error} />
      </form>
    );
  };

  return <AuthFlowScreen title={tx('loginTitle')} initialFlow={null} returnTo={returnTo}>{form}</AuthFlowScreen>;
}

export function SetupScreen() {
  const [token, setToken] = useState('');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
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
        setFlow(await authApi.bootstrap(token, username, password, displayName));
      } catch (requestError) {
        setError(errorMessage(requestError));
      } finally {
        setBusy(false);
      }
    };
    return (
      <form className="auth-step" onSubmit={(event) => void submit(event)}>
        <TextField label={tx('setupToken')} value={token} onChange={setToken} autoFocus />
        <TextField label={tx('username')} value={username} onChange={setUsername} autoComplete="username" />
        <TextField label={tx('displayName')} value={displayName} onChange={setDisplayName} />
        <TextField label={tx('choosePassword')} value={password} onChange={setPassword}
                   type="password" autoComplete="new-password" />
        <p className="auth-hint">{tx('passwordHint')}</p>
        <ErrorNotice message={error} />
        <button className="btn" disabled={busy || !token || !username || !password}>{tx('createAdmin')}</button>
        <Link to="/login">{tx('backToLogin')}</Link>
      </form>
    );
  };
  return <AuthFlowScreen title={tx('setupTitle')} initialFlow={null}>{form}</AuthFlowScreen>;
}

export function ActivateScreen() {
  const [searchParams] = useSearchParams();
  const initialToken = searchParams.get('token') ?? '';
  const [token, setToken] = useState(initialToken);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (initialToken) window.history.replaceState(window.history.state, '', '/activate');
  }, [initialToken]);

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
        <TextField label={tx('activationToken')} value={token} onChange={setToken} autoFocus={!token} />
        <TextField label={tx('choosePassword')} value={password} onChange={setPassword}
                   type="password" autoComplete="new-password" autoFocus={Boolean(token)} />
        <p className="auth-hint">{tx('passwordHint')}</p>
        <ErrorNotice message={error} />
        <button className="btn" disabled={busy || !token || !password}>{tx('continue')}</button>
        <Link to="/login">{tx('backToLogin')}</Link>
      </form>
    );
  };
  return <AuthFlowScreen title={tx('activateTitle')} initialFlow={null}>{form}</AuthFlowScreen>;
}

export function SecurityScreen() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [password, setPassword] = useState('');
  const [keyLabel, setKeyLabel] = useState(tx('securityKeyDefault'));
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);

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

  const adopt = (flow: AuthFlow, success: string) => {
    if (flow.status !== 'AUTHENTICATED') throw new Error(tx('recentAuthRequired'));
    auth.acceptFlow(flow);
    if (flow.recoveryCodes.length > 0) setRecoveryCodes(flow.recoveryCodes);
    snackbar(success);
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

  const addKey = async () => {
    setBusy('key');
    setError(null);
    try {
      const options = await authApi.additionalRegistrationOptions();
      const credential = await createCredential(options);
      adopt(
        await authApi.completeAdditionalRegistration(options.serverChallenge, credential, keyLabel),
        tx('securityKeyAdded'),
      );
    } catch (requestError) {
      reportSecurityError(requestError);
    } finally {
      setBusy(null);
    }
  };

  const regenerate = async () => {
    setBusy('recovery');
    setError(null);
    try {
      adopt(await authApi.regenerateRecoveryCodes(), tx('recoveryTitle'));
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

  if (recoveryCodes.length > 0) {
    return (
      <AuthLayout title={tx('securityTitle')}>
        <RecoveryCodes codes={recoveryCodes} onDone={() => setRecoveryCodes([])} />
      </AuthLayout>
    );
  }

  return (
    <div className="auth-security">
      <header>
        <h1>{tx('securityTitle')}</h1>
        <p>{tx('securitySubtitle')}</p>
      </header>
      <ErrorNotice message={error} />
      {auth.capabilities?.passwordEnabled && auth.user?.authMethod === 'PASSWORD' && (
        <form className="settings-card" onSubmit={(event) => void changePassword(event)}>
          <h2>{tx('changePassword')}</h2>
          <TextField label={tx('newPassword')} value={password} onChange={setPassword}
                     type="password" autoComplete="new-password" />
          <p className="auth-hint">{tx('passwordHint')}</p>
          <button className="btn" disabled={busy !== null || !password}>{tx('changePassword')}</button>
        </form>
      )}
      {auth.user?.authMethod === 'PASSWORD' && (
        <section className="settings-card">
          <h2>{tx('addSecurityKey')}</h2>
          <TextField label={tx('securityKeyLabel')} value={keyLabel} onChange={setKeyLabel} />
          <button className="btn" disabled={busy !== null || !keyLabel} onClick={() => void addKey()}>
            {tx('addSecurityKey')}
          </button>
          <div className="divider" />
          <button className="btn tonal" disabled={busy !== null} onClick={() => void regenerate()}>
            {tx('regenerate')}
          </button>
        </section>
      )}
      <section className="settings-card">
        <button className="btn tonal" disabled={busy !== null} onClick={() => void logout(false)}>
          {tx('currentLogout')}
        </button>
        <button className="btn danger-text" disabled={busy !== null} onClick={() => void logout(true)}>
          {tx('allLogout')}
        </button>
      </section>
    </div>
  );
}

// Kept exported so route-level error handling can distinguish expected API failures.
export { ApiError };
