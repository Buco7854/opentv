import {
  useCallback, useEffect, useRef, useState,
} from 'react';
import { useNavigate } from 'react-router';
import { EmptyState } from '../components/Common';
import { Icon } from '../components/Icons';
import { ScreenHeader, Spinner } from '../components/Primitives';
import { reportSuccess } from '../errors';
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
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<'approved' | 'denied' | null>(null);
  const returnedLinkToken = useRef<string | null>(null);

  useEffect(() => {
    if (!pending) {
      setBusy(false);
      return undefined;
    }
    let active = true;
    loadLink(pending)
      .then((loaded) => {
        if (!active) return;
        setRequest(loaded.request);
        if (loaded.approved) {
          clearPendingDeviceLink(pending.linkToken);
          setDone('approved');
          reportSuccess(tx('linkApproved'));
        }
      })
      .catch(() => { if (active) setError(tx('linkNotFound')); })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [pending]);

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
      if (approve) reportSuccess(tx('linkApproved'));
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
    <>
      <ScreenHeader
        title={tx('linkApproveTitle')}
        subtitle={<span className="subtitle">{tx('linkApproveSubtitle')}</span>}
        onBack={leave}
      />
      <div className="mx-auto flex max-w-[520px] flex-col gap-3 px-4 pb-6">
        <ErrorNotice message={error} />

        {done && (
          <EmptyState
            title={done === 'approved' ? tx('linkApproved') : tx('linkDeniedDone')}
            subtitle={browserSignInDone
              ? tx('browserSignInApprovedBody')
              : done === 'approved' ? tx('linkApprovedBody') : tx('linkDeniedDoneBody')}
            action={canReturnToApp
              ? <a className="btn" href={BROWSER_SIGN_IN_RETURN_URL}>{tx('openOpenTv')}</a>
              : <button className="btn" onClick={leave}>{tx('continue')}</button>}
          >
            <div className="empty-home-art">
              <Icon name={done === 'approved' ? 'check' : 'close'} />
            </div>
          </EmptyState>
        )}

        {!done && busy && !request && <Spinner />}

        {!done && !busy && !request && !error && (
          <EmptyState title={tx('linkScanFirstTitle')} subtitle={tx('linkScanFirstBody')}>
            <div className="empty-home-art"><Icon name="link" /></div>
          </EmptyState>
        )}

        {!done && request && (
          <>
            <section className="link-card">
              <div className="row">
                <span className="logo-box"><Icon name="liveTv" /></span>
                <div className="body">
                  <div className="title">{request.deviceName || tx('linkDeviceUnknown')}</div>
                  <div className="sub">
                    {[browser === request.deviceName ? null : browser, request.ip]
                      .filter(Boolean).join(' · ')}
                  </div>
                  <div className="sub">{tx('linkRequestedAt', { date: stamp(request.requestedAtMs) })}</div>
                </div>
              </div>
              <p className="link-question">{tx('linkQuestion', { account })}</p>
              <p className="auth-hint">{tx('linkApproveIntro')}</p>
              <div className="actions">
                <button className="btn" disabled={busy} onClick={() => void decide(true)}>
                  {busy ? tx('working') : tx('linkApprove')}
                </button>
                <button className="btn danger-text" disabled={busy} onClick={() => void decide(false)}>
                  {tx('linkDeny')}
                </button>
              </div>
            </section>
          </>
        )}
      </div>
    </>
  );
}
