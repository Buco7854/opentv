import { useEffect, useState } from 'react';
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
import { authText as tx } from './copy';
import { useFragmentToken } from './fragment';
import { DeviceLinkRequest } from './types';
import './auth.css';

const stamp = (ms: number) =>
  new Date(ms).toLocaleString(getLocale(), { dateStyle: 'medium', timeStyle: 'short' });

export function LinkApprovalScreen() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const linkToken = useFragmentToken('t');
  const [request, setRequest] = useState<DeviceLinkRequest | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<'approved' | 'denied' | null>(null);

  useEffect(() => {
    if (!linkToken) {
      setBusy(false);
      return undefined;
    }
    let active = true;
    authApi.linkLookup({ linkToken })
      .then((found) => { if (active) setRequest(found); })
      .catch(() => { if (active) setError(tx('linkNotFound')); })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [linkToken]);

  const decide = async (approve: boolean) => {
    if (!linkToken) return;
    setBusy(true);
    setError(null);
    try {
      await (approve
        ? authApi.linkApprove({ linkToken })
        : authApi.linkDeny({ linkToken }));
      setDone(approve ? 'approved' : 'denied');
      if (approve) reportSuccess(tx('linkApproved'));
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setBusy(false);
    }
  };

  const browser = request?.userAgent ? deviceLabel(request.userAgent) : null;
  const account = user?.displayName || user?.username || '';

  return (
    <>
      <ScreenHeader
        title={tx('linkApproveTitle')}
        subtitle={<span className="subtitle">{tx('linkApproveSubtitle')}</span>}
        onBack={() => navigate('/')}
      />
      <div className="mx-auto flex max-w-[520px] flex-col gap-3 px-4 pb-6">
        <ErrorNotice message={error} />

        {done && (
          <EmptyState
            title={done === 'approved' ? tx('linkApproved') : tx('linkDeniedDone')}
            subtitle={done === 'approved' ? tx('linkApprovedBody') : tx('linkDeniedDoneBody')}
            action={<button className="btn" onClick={() => navigate('/')}>{tx('continue')}</button>}
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
