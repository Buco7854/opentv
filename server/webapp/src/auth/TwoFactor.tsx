import { FormEvent, ReactNode, useEffect, useState } from 'react';
import { toDataURL } from 'qrcode';
import { Icon } from '../components/Icons';
import { IconBtn, Spinner, TextField, toast } from '../components/Primitives';
import { reportSuccess } from '../errors';
import { authApi } from './api';
import { AuthLayout } from './AuthLayout';
import { ChoiceRow, ErrorNotice, errorMessage, OtpField } from './AuthUi';
import { authText as tx } from './copy';
import { AuthFlow, TotpEnrollment } from './types';
import {
  createCredential, getCredential, useCeremonySignal, webAuthnSupported,
} from './webauthn';

type AuthMethod = 'totp' | 'webauthn' | 'recovery';

const ENROLLMENT_METHODS: AuthMethod[] = ['totp', 'webauthn'];

const backLink = (onBack: (() => void) | null): ReactNode => onBack
  ? <button type="button" className="link" onClick={onBack}>{tx('back')}</button>
  : undefined;

function useChallengeExpiry(expiresAtMs: number | null): boolean {
  const [expired, setExpired] = useState(false);
  useEffect(() => {
    if (expiresAtMs === null) return undefined;
    const remaining = expiresAtMs - Date.now();
    setExpired(remaining <= 0);
    if (remaining <= 0) return undefined;
    const timer = setTimeout(() => setExpired(true), remaining);
    return () => clearTimeout(timer);
  }, [expiresAtMs]);
  return expired;
}

const copyToClipboard = async (value: string) => {
  try {
    await navigator.clipboard.writeText(value);
    reportSuccess(tx('copied'));
  } catch {
    // A clipboard the browser refuses is not an app failure; the codes stay on screen.
    toast(tx('copyFailed'), { tone: 'error' });
  }
};

export function RecoveryCodesPanel({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const text = codes.join('\n');
  const download = () => {
    try {
      const url = URL.createObjectURL(new Blob([`${text}\n`], { type: 'text/plain' }));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'opentv-recovery-codes.txt';
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch {
      toast(tx('downloadFailed'), { tone: 'error' });
    }
  };
  return (
    <div className="auth-step">
      <p className="auth-hint">{tx('recoveryBody')}</p>
      <ol className="auth-codes">
        {codes.map((code, index) => (
          <li key={code}>
            <span className="n">{index + 1}</span>
            <code>{code}</code>
          </li>
        ))}
      </ol>
      <div className="auth-row">
        <button type="button" className="btn tonal" onClick={() => void copyToClipboard(text)}>
          <Icon name="copy" />{tx('copy')}
        </button>
        <button type="button" className="btn tonal" onClick={download}>
          <Icon name="download" />{tx('download')}
        </button>
      </div>
      <button type="button" className="btn" onClick={onDone}>{tx('savedCodes')}</button>
    </div>
  );
}

export function RecoveryCodesScreen({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  return (
    <AuthLayout title={tx('recoveryTitle')}>
      <RecoveryCodesPanel codes={codes} onDone={onDone} />
    </AuthLayout>
  );
}

/** The QR/secret/code ceremony, shared by first enrolment and Account security. */
export function TotpSetup({ start, complete, onError }: {
  start: () => Promise<TotpEnrollment>;
  complete: (challenge: string, code: string) => Promise<void>;
  onError?: (error: unknown) => boolean;
}) {
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(true);
  const [attempt, setAttempt] = useState(0);

  const report = (requestError: unknown) => {
    if (!onError?.(requestError)) setError(errorMessage(requestError));
  };

  useEffect(() => {
    let active = true;
    setBusy(true);
    setError(null);
    void start().then((result) => {
      if (!active) return;
      setEnrollment(result);
      return toDataURL(result.uri, {
        width: 424,
        margin: 0,
        color: { dark: '#000000', light: '#ffffff' },
      });
    }).then((dataUrl) => {
      if (active && dataUrl) setQr(dataUrl);
    }).catch((requestError) => {
      if (active) report(requestError);
    }).finally(() => {
      if (active) setBusy(false);
    });
    return () => { active = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!enrollment || code.length !== 6) return;
    setBusy(true);
    setError(null);
    try {
      await complete(enrollment.challenge, code);
    } catch (requestError) {
      report(requestError);
    } finally {
      setBusy(false);
    }
  };

  if (!enrollment) {
    if (busy) return <Spinner />;
    return (
      <div className="auth-step">
        <ErrorNotice message={error} />
        <button type="button" className="btn tonal" onClick={() => setAttempt((count) => count + 1)}>
          {tx('retry')}
        </button>
      </div>
    );
  }
  return (
    <form className="auth-step" onSubmit={(event) => void submit(event)}>
      {qr && <img className="auth-qr" src={qr} alt="" />}
      <div className="auth-secret">
        <div className="body">
          <div className="k">{tx('secret')}</div>
          <code>{enrollment.secret}</code>
        </div>
        <IconBtn
          name="copy"
          className="muted"
          label={tx('copySecret')}
          onClick={() => void copyToClipboard(enrollment.secret)}
        />
      </div>
      <OtpField label={tx('totpCode')} value={code} onChange={setCode} autoFocus />
      <ErrorNotice message={error} />
      <button className="btn" disabled={busy || code.length !== 6}>
        {busy ? tx('working') : tx('verify')}
      </button>
    </form>
  );
}

function TotpEnrollmentStep({ challenge, onComplete, footer }: {
  challenge: string;
  onComplete: (flow: AuthFlow) => void;
  footer: ReactNode;
}) {
  return (
    <AuthLayout
      eyebrow={tx('enrollEyebrow')}
      title={tx('totpTitle')}
      subtitle={tx('totpBody')}
      footer={footer}
    >
      <TotpSetup
        start={() => authApi.startTotpEnrollment(challenge)}
        complete={async (enrollmentChallenge, code) => {
          onComplete(await authApi.completeTotpEnrollment(enrollmentChallenge, code));
        }}
      />
    </AuthLayout>
  );
}

export function MfaStep({ flow, onComplete, onRestart }: {
  flow: AuthFlow;
  onComplete: (next: AuthFlow) => void;
  onRestart: () => void;
}) {
  const enrollment = flow.status === 'ENROLLMENT_REQUIRED';
  const keysUsable = webAuthnSupported();
  const candidates = enrollment ? ENROLLMENT_METHODS : flow.methods as AuthMethod[];
  const offered = candidates.filter((candidate) => candidate !== 'webauthn' || keysUsable);
  const only = enrollment && offered.length === 1 ? offered[0] ?? null : null;
  const [method, setMethod] = useState<AuthMethod | null>(only);
  const [code, setCode] = useState('');
  const [label, setLabel] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const beginCeremony = useCeremonySignal();
  const expired = useChallengeExpiry(flow.expiresAtMs);
  const challenge = flow.challenge;
  const eyebrow = enrollment ? tx('enrollEyebrow') : tx('mfaEyebrow');
  const back = only ? null : () => { setMethod(null); setCode(''); setError(null); };
  const restartLink = (
    <button type="button" className="link" onClick={onRestart}>{tx('startAgain')}</button>
  );
  const footer = <>{backLink(back)}{restartLink}</>;

  if (!challenge) {
    return (
      <AuthLayout eyebrow={eyebrow} title={tx('mfaTitle')} footer={restartLink}>
        <ErrorNotice message={tx('challengeMissing')} />
      </AuthLayout>
    );
  }

  if (expired) {
    return (
      <AuthLayout
        eyebrow={eyebrow}
        title={tx('challengeExpiredTitle')}
        subtitle={tx('challengeExpiredBody')}
      >
        <div className="auth-step">
          <button type="button" className="btn" onClick={onRestart}>{tx('startAgain')}</button>
        </div>
      </AuthLayout>
    );
  }

  if (method === 'totp' && enrollment) {
    return <TotpEnrollmentStep challenge={challenge} onComplete={onComplete} footer={footer} />;
  }

  const securityKey = async () => {
    const signal = beginCeremony();
    setBusy(true);
    setError(null);
    try {
      if (enrollment) {
        const options = await authApi.registrationOptions(challenge);
        const credential = await createCredential(options, signal);
        onComplete(await authApi.completeRegistration(options.serverChallenge, credential, label));
      } else {
        const options = await authApi.authenticationOptions(challenge);
        const credential = await getCredential(options, signal);
        onComplete(await authApi.completeAuthentication(options.serverChallenge, credential));
      }
    } catch (requestError) {
      if (signal.aborted) return;
      setError(errorMessage(requestError));
    } finally {
      if (!signal.aborted) setBusy(false);
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
      <AuthLayout
        eyebrow={eyebrow}
        title={enrollment ? tx('enrollTitle') : tx('mfaTitle')}
        subtitle={enrollment ? tx('enrollBody') : tx('mfaBody')}
        footer={restartLink}
      >
        <div className="auth-choices">
          {offered.includes('totp') && (
            <ChoiceRow icon="phone" title={tx('useAuthenticator')} subtitle={tx('authenticatorSub')}
                       onClick={() => setMethod('totp')} />
          )}
          {offered.includes('webauthn') && (
            <ChoiceRow icon="key" title={tx('useSecurityKey')} subtitle={tx('securityKeySub')}
                       onClick={() => setMethod('webauthn')} />
          )}
          {offered.includes('recovery') && (
            <ChoiceRow icon="lifebuoy" title={tx('useRecovery')} subtitle={tx('recoverySub')}
                       onClick={() => setMethod('recovery')} />
          )}
        </div>
        {candidates.includes('webauthn') && !keysUsable && (
          <p className="auth-hint">{tx('securityKeyUnavailable')}</p>
        )}
      </AuthLayout>
    );
  }

  if (method === 'webauthn') {
    return (
      <AuthLayout
        eyebrow={eyebrow}
        title={tx('securityKeyStepTitle')}
        subtitle={enrollment ? tx('securityKeyRegisterBody') : tx('securityKeyAuthBody')}
        footer={footer}
      >
        <div className="auth-step">
          {enrollment && <TextField label={tx('securityKeyLabel')} value={label} onChange={setLabel} />}
          <ErrorNotice message={error} />
          <button type="button" className="btn" disabled={busy || (enrollment && !label.trim())}
                  onClick={() => void securityKey()}>
            {busy ? tx('working') : enrollment ? tx('registerSecurityKey') : tx('useSecurityKey')}
          </button>
        </div>
      </AuthLayout>
    );
  }

  const totp = method === 'totp';
  return (
    <AuthLayout
      eyebrow={eyebrow}
      title={totp ? tx('totpCodeTitle') : tx('recoveryStepTitle')}
      subtitle={totp ? tx('totpCodeBody') : tx('recoveryStepBody')}
      footer={footer}
    >
      <form className="auth-step" onSubmit={(event) => void submitCode(event)}>
        {totp
          ? <OtpField label={tx('totpCode')} value={code} onChange={setCode} autoFocus />
          : <TextField label={tx('recoveryCode')} value={code} onChange={setCode}
                       autoFocus autoComplete="one-time-code" />}
        <ErrorNotice message={error} />
        <button className="btn" disabled={busy || (totp ? code.length !== 6 : !code)}>
          {busy ? tx('working') : tx('verify')}
        </button>
      </form>
    </AuthLayout>
  );
}
