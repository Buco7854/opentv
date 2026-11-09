import {
  useCallback, useEffect, useRef, useState,
} from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '../api/http';
import { EmptyState } from '../components/Common';
import { Icon } from '../components/Icons';
import { Spinner } from '../components/Primitives';
import { AuthLayout } from './AuthLayout';
import { getLocale } from '../i18n';
import { deviceLabel } from '../lib/format';
import { authApi } from './api';
import { ErrorNotice, errorMessage } from './AuthUi';
import { useAuth } from './AuthProvider';
import {
  attemptBrowserSignInReturn,
  BROWSER_SIGN_IN_RETURN_URL,
  supportsBrowserSignInReturn,
} from './browserSignInReturn';
import { authText as tx } from './copy';
import { clearPendingDeviceLink, PendingDeviceLink } from './fragment';
import { DeviceLinkRequest } from './types';
import './auth.css';

const stamp = (ms: number) =>
  new Date(ms).toLocaleString(getLocale(), { dateStyle: 'medium', timeStyle: 'short' });

interface LoadedLink {
  request: DeviceLinkRequest;
  approved: boolean;
}

// StrictMode replays the effect. Coalesce the authenticated lookup + automatic approval so
// one browser landing cannot race itself into a successful approval followed by an error.
const automaticLoads = new Map<string, Promise<LoadedLink>>();

function loadLink(pending: PendingDeviceLink): Promise<LoadedLink> {
  if (!pending.automaticApproval) {
    return authApi.linkLookup({ linkToken: pending.linkToken })
      .then((request) => ({ request, approved: false }));
  }
  const current = automaticLoads.get(pending.linkToken);
  if (current) return current;
  const started = authApi.linkLookup({ linkToken: pending.linkToken })
    .then(async (request) => {
      // Both sides must agree. The fragment is merely browser routing input; the
      // authenticated lookup reports the mode bound into the server challenge.
      if (!request.browserSignIn) return { request, approved: false };
      await authApi.linkApprove({ linkToken: pending.linkToken });
      return { request, approved: true };
    })
    .finally(() => automaticLoads.delete(pending.linkToken));
  automaticLoads.set(pending.linkToken, started);
  return started;
}

export function LinkApprovalScreen({ pending }: { pending: PendingDeviceLink | null }) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [request, setRequest] = useState<DeviceLinkRequest | null>(null);
  const [busy, setBusy] = useState(true);
  const [lookupAttempt, setLookupAttempt] = useState(0);
  const [retryLookup, setRetryLookup] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<'approved' | 'denied' | null>(null);
  const returnedLinkToken = useRef<string | null>(null);

  useEffect(() => {
    if (!pending) {
      setBusy(false);
      return undefined;
    }
    let active = true;
    setBusy(true);
    setError(null);
    setRetryLookup(false);
    loadLink(pending)
      .then((loaded) => {
        if (!active) return;
        setRequest(loaded.request);
        if (loaded.approved) {
          clearPendingDeviceLink(pending.linkToken);
          setDone('approved');
        }
      })
      .catch((failure: unknown) => {
        if (!active) return;
        const expired = failure instanceof ApiError &&
          ([404, 410].includes(failure.status) || failure.code === 'challenge_invalid');
        setError(expired ? tx('linkNotFound') : errorMessage(failure));
        setRetryLookup(!expired);
      })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [pending, lookupAttempt]);

  useEffect(() => {
    // The fragment's mode is untrusted routing input. Only the mode recovered from
    // the authenticated server lookup can trigger the fixed, signal-only app URL.
    if (done !== 'approved' || !request?.browserSignIn || !pending
        || returnedLinkToken.current === pending.linkToken) return;
    returnedLinkToken.current = pending.linkToken;
    attemptBrowserSignInReturn();
  }, [done, pending, request]);

  const decide = useCallback(async (approve: boolean) => {
    if (!pending) return;
    setBusy(true);
    setError(null);
    try {
      await (approve
        ? authApi.linkApprove({ linkToken: pending.linkToken })
        : authApi.linkDeny({ linkToken: pending.linkToken }));
      clearPendingDeviceLink(pending.linkToken);
      setDone(approve ? 'approved' : 'denied');
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setBusy(false);
    }
  }, [pending]);

  const leave = () => {
    if (pending) clearPendingDeviceLink(pending.linkToken);
    navigate('/');
  };

  const browser = request?.userAgent ? deviceLabel(request.userAgent) : null;
  const account = user?.displayName || user?.username || '';
  const browserSignInDone = done === 'approved' && request?.browserSignIn === true;
  const canReturnToApp = browserSignInDone && supportsBrowserSignInReturn();

  return (
    <AuthLayout flow
      title={done ? tx(done === 'approved' ? 'linkApproved' : 'linkDeniedDone') : tx('linkApproveTitle')}
      subtitle={!done ? tx('linkApproveSubtitle') : undefined}
      footer={!done && <button className="link" onClick={leave}>{tx('back')}</button>}>
      <div className="auth-step link-approval-content">
        <ErrorNotice message={error} />
        {!busy && retryLookup && !request && <button className="btn tonal"
          onClick={() => setLookupAttempt((attempt) => attempt + 1)}>{tx('retry')}</button>}

        {done && <section className="link-result" role="status">
          <Icon name={done === 'approved' ? 'check' : 'close'} />
          <p>{browserSignInDone ? tx('browserSignInApprovedBody')
            : tx(done === 'approved' ? 'linkApprovedBody' : 'linkDeniedDoneBody')}</p>
          {canReturnToApp
            ? <a className="btn" href={BROWSER_SIGN_IN_RETURN_URL}>{tx('openOpenTv')}</a>
            : <button className="btn" onClick={leave}>{tx('continue')}</button>}
        </section>}

        {!done && busy && !request && <Spinner />}

        {!done && !busy && !request && !error && (
          <EmptyState title={tx('linkScanFirstTitle')} subtitle={tx('linkScanFirstBody')}>
            <div className="empty-home-art"><Icon name="link" /></div>
          </EmptyState>
        )}

        {!done && request && <section className="link-card" aria-label={tx('linkApproveTitle')}>
          <div className="link-device">
            <Icon name="liveTv" />
            <div className="body">
              <h2>{request.deviceName || tx('linkDeviceUnknown')}</h2>
              <p>{[browser === request.deviceName ? null : browser, request.ip].filter(Boolean).join(' · ')}</p>
              <p>{tx('linkRequestedAt', { date: stamp(request.requestedAtMs) })}</p>
            </div>
          </div>
          <p className="link-question">{tx('linkQuestion', { account })}</p>
          <p className="auth-hint">{tx('linkApproveIntro')}</p>
          <div className="link-approval-actions">
            <button className="btn" disabled={busy} onClick={() => void decide(true)}>
              {busy ? tx('working') : tx('linkApprove')}
            </button>
            <button className="btn tonal" disabled={busy} onClick={() => void decide(false)}>{tx('linkDeny')}</button>
          </div>
        </section>}
      </div>
    </AuthLayout>
  );
}
