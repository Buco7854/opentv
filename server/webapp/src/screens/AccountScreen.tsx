// Xtream account: connections hero + detail rows, always fetched live from the
// provider (open and refresh both force a fresh request).

import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { api, AccountInfo } from '../api';
import { asyncFallback, EmptyState, Pill } from '../components/Common';
import { IconBtn, ScreenHeader } from '../components/Primitives';
import { GENERIC, errorMessage } from '../errors';
import { useAsync } from '../hooks';
import { getLocale, t } from '../i18n';
import { WatchProgressBar } from '../components/WatchProgress';

export function AccountScreen() {
  const playlistId = Number(useParams().playlistId);
  const navigate = useNavigate();
  const playlist = useAsync(() => api.playlistDetail(playlistId), [playlistId]);
  const detail = playlist.data;
  const [info, setInfo] = useState<AccountInfo | null>(null);
  const [updatedAtMs, setUpdatedAtMs] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isXtream = detail?.playlist.hasXtreamPanel ?? false;

  const load = useCallback(async (force: boolean, stillWanted: () => boolean = () => true) => {
    setBusy(true);
    setError(null);
    try {
      const next = await api.account(playlistId, force);
      if (!stillWanted()) return;
      setInfo(next);
      setUpdatedAtMs(Date.now());
    } catch (cause) {
      if (!stillWanted()) return;
      setError(errorMessage(cause, { [GENERIC]: () => t('account.unreachable') }));
    } finally {
      if (stillWanted()) setBusy(false);
    }
  }, [playlistId]);

  useEffect(() => {
    if (!isXtream) {
      setBusy(false);
      return;
    }
    let current = true;
    void load(true, () => current);
    return () => { current = false; };
  }, [isXtream, load]);

  return (
    <>
      <ScreenHeader
        title={t('account.title')}
        subtitle={detail?.playlist.name}
        onBack={() => navigate(-1)}
        actions={
          busy
            ? <div className="grid size-11 flex-none place-items-center"><div className="spinner m-0 size-[22px]" /></div>
            : <IconBtn name="refresh" label={t('account.refresh')} disabled={!isXtream} onClick={() => load(true)} />
        }
      />

      {asyncFallback(playlist)}
      {detail !== null && !isXtream && (
        <EmptyState title={t('account.noApiTitle')} subtitle={t('account.noApiSub')} />
      )}
      {isXtream && (
        <div className="mx-5 pb-8">
          {info === null ? (
            <p className={`mt-10 type-body-medium ${error ? 'text-error' : 'text-on-surface-variant'}`}>
              {error ?? t('account.loading')}
            </p>
          ) : (
            <>
              <ConnectionsCard account={info} />
              <div className="mt-3.5" />
              <DetailsCard account={info} />
              <div className="mt-3.5" />
              {error && <p className="mb-2 type-body-small text-error">{error}</p>}
              {updatedAtMs != null && (
                <p className="type-body-small text-on-surface-variant">
                  {t('account.updatedAt', { time: new Date(updatedAtMs).toLocaleTimeString(getLocale()) })}
                </p>
              )}
            </>
          )}
        </div>
      )}
    </>
  );
}

function ConnectionsCard({ account }: { account: AccountInfo }) {
  const atLimit = account.maxConnections >= 1 && account.activeConnections >= account.maxConnections;
  const fraction = account.maxConnections > 0
    ? Math.min(1, account.activeConnections / account.maxConnections) : 0;
  return (
    <div className="card r20 account-hero">
      <div className={`big${atLimit ? ' warn' : ''}`}>{account.activeConnections} / {account.maxConnections}</div>
      <div className="cap">{t('account.activeMax')}</div>
      <WatchProgressBar fraction={fraction} over={atLimit} />
    </div>
  );
}

function DetailsCard({ account }: { account: AccountInfo }) {
  const fmtDate = (ms: number) => new Date(ms).toLocaleDateString(getLocale(), { dateStyle: 'medium' });
  const rows: React.ReactNode[] = [];

  rows.push(
    <div className="kv-row" key="status">
      <div className="k">{t('account.status')}</div>
      <div className={`v${/^active$/i.test(account.status) ? ' ok' : ' err'}`}>{account.status}</div>
      {account.isTrial && <Pill>{t('account.trial')}</Pill>}
    </div>,
  );
  if (account.username != null) {
    rows.push(
      <div className="kv-row" key="user"><div className="k">{t('account.username')}</div><div className="v">{account.username}</div></div>,
    );
  }
  if (account.expiresAtMs != null) {
    const daysLeft = Math.floor((account.expiresAtMs - Date.now()) / 86_400_000);
    rows.push(
      <div className="kv-row" key="expires">
        <div className="k">{t('account.expires')}</div>
        <div className={`v${daysLeft < 7 ? ' err' : ''}`}>
          {fmtDate(account.expiresAtMs)}{daysLeft >= 0 ? ` · ${t('account.inDays', { count: daysLeft })}` : ` · ${t('account.expired')}`}
        </div>
      </div>,
    );
  }
  if (account.createdAtMs != null) {
    rows.push(
      <div className="kv-row" key="since">
        <div className="k">{t('account.memberSince')}</div><div className="v">{fmtDate(account.createdAtMs)}</div>
      </div>,
    );
  }

  return (
    <div className="card r20 px-4.5 py-2">
      {rows.map((row, i) => (
        <div key={i}>
          {i > 0 && <div className="h-px bg-surface-container-highest" />}
          {row}
        </div>
      ))}
    </div>
  );
}
