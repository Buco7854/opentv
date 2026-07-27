import { useCallback, useEffect, useRef, useState } from 'react';
import { toDataURL } from 'qrcode';
import { Link, useNavigate } from 'react-router';
import { ApiError } from '../api/http';
import { Spinner } from '../components/Primitives';
import { deviceLabel } from '../lib/format';
import { authApi } from './api';
import { AuthLayout } from './AuthLayout';
import { GENERIC } from '../errors';
import { ErrorNotice, errorMessage } from './AuthUi';
import { useAuth } from './AuthProvider';
import { authText as tx } from './copy';
import { DeviceLinkPreview, DeviceLinkStart, DeviceLinkState } from './types';
import './auth.css';

const initials = (name: string) =>
  name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => Array.from(part)[0]).join('');

const RATE_LIMIT_MARGIN_MS = 250;

export function useDeviceLink() {
  const { acceptFlow } = useAuth();
  const navigate = useNavigate();
  const [request, setRequest] = useState<DeviceLinkStart | null>(null);
  const [state, setState] = useState<DeviceLinkState>('PENDING');
  const [preview, setPreview] = useState<DeviceLinkPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const requested = useRef(-1);

  const restart = useCallback(() => {
    setRequest(null);
    setState('PENDING');
    setPreview(null);
    setError(null);
    setAttempt((current) => current + 1);
  }, []);

  useEffect(() => {
    if (requested.current === attempt) return;
    requested.current = attempt;
    setRequest(null);
    void authApi.linkStart(deviceLabel(navigator.userAgent))
      .then(setRequest)
      .catch((cause: unknown) => setError(
        errorMessage(cause, { [GENERIC]: () => tx('linkStartFailed') }),
      ));
  }, [attempt]);

  useEffect(() => {
    if (!request) return undefined;
    let active = true;
    const poll = async () => {
      let delay = request.intervalMs;
      try {
        const status = await authApi.linkPoll(request.pollToken);
        if (!active) return;
        delay = status.intervalMs || delay;
        setPreview(status.preview);
        if (status.status === 'APPROVED') {
          if (!status.flow) {
            setError(tx('linkIncomplete'));
            return;
          }
          acceptFlow(status.flow);
          navigate('/', { replace: true });
          return;
        }
        setState(status.status);
        if (status.status !== 'PENDING' && status.status !== 'SCANNED') return;
      } catch (requestError) {
        if (!active) return;
        // A throttled poll is the server asking us to wait, not a failed link.
        if (requestError instanceof ApiError && requestError.status === 429) delay *= 2;
        else {
          setError(errorMessage(requestError));
          return;
        }
      }
      timer.current = setTimeout(() => void poll(), delay + RATE_LIMIT_MARGIN_MS);
    };
    timer.current = setTimeout(() => void poll(), request.intervalMs + RATE_LIMIT_MARGIN_MS);
    return () => { active = false; clearTimeout(timer.current); };
  }, [acceptFlow, navigate, request]);

  return { request, state, preview, error, restart };
}

export function DeviceLinkScreen() {
  const { request, state, preview, error, restart } = useDeviceLink();
  const [qr, setQr] = useState<string | null>(null);
  // Scanning is the only way in on this screen, so a QR that cannot be drawn is a dead
  // end and has to say so rather than spin forever.
  const [qrError, setQrError] = useState<string | null>(null);

  useEffect(() => {
    setQr(null);
    setQrError(null);
    if (!request) return;
    void toDataURL(request.verificationUriComplete, {
      width: 480,
      margin: 0,
      color: { dark: '#000000', light: '#ffffff' },
    })
      .then(setQr)
      .catch((cause: unknown) => setQrError(
        errorMessage(cause, { [GENERIC]: () => tx('linkQrFailed') }),
      ));
  }, [request]);

  const footer = <Link className="link" to="/login">{tx('backToLogin')}</Link>;
  const shownError = error ?? qrError;

  if (shownError) {
    return (
      <AuthLayout eyebrow={tx('linkEyebrow')} title={tx('linkTitle')} footer={footer}>
        <div className="auth-step">
          <ErrorNotice message={shownError} />
          <button type="button" className="btn" onClick={restart}>{tx('linkNewCode')}</button>
        </div>
      </AuthLayout>
    );
  }

  if (state === 'EXPIRED' || state === 'DENIED') {
    const denied = state === 'DENIED';
    return (
      <AuthLayout
        eyebrow={tx('linkEyebrow')}
        title={denied ? tx('linkDeniedTitle') : tx('linkExpiredTitle')}
        subtitle={denied ? tx('linkDeniedBody') : tx('linkExpiredBody')}
        footer={footer}
      >
        <div className="auth-step">
          <button type="button" className="btn" onClick={restart}>{tx('linkNewCode')}</button>
        </div>
      </AuthLayout>
    );
  }

  if (state === 'SCANNED' && preview) {
    return (
      <AuthLayout
        eyebrow={tx('linkEyebrow')}
        title={tx('linkScannedTitle')}
        subtitle={tx('linkScannedBody')}
        footer={footer}
      >
        <div className="auth-step">
          <div className="link-account">
            <span className="link-avatar">{initials(preview.displayName || preview.username)}</span>
            <div className="body">
              <div className="name">{preview.displayName || preview.username}</div>
              <div className="sub">@{preview.username}</div>
            </div>
          </div>
          <div className="link-status">
            <span className="dot" />
            {tx('linkConfirmOnPhone')}
          </div>
        </div>
      </AuthLayout>
    );
  }

  if (!request) {
    return (
      <AuthLayout eyebrow={tx('linkEyebrow')} title={tx('linkTitle')} footer={footer}>
        <Spinner />
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      eyebrow={tx('linkEyebrow')}
      title={tx('linkTitle')}
      subtitle={tx('linkBody')}
      footer={footer}
    >
      <div className="auth-step">
        {qr
          ? <img className="auth-qr size-[240px]" src={qr} alt="" />
          : <Spinner />}
        <div className="link-status">
          <span className="dot" />
          {tx('linkWaiting')}
        </div>
      </div>
    </AuthLayout>
  );
}
