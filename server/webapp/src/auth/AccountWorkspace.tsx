import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { Dialog, ScreenHeader, SearchField } from '../components/Primitives';
import { Icon } from '../components/Icons';
import { WatchProgressBar } from '../components/WatchProgress';
import { WorkspaceNavigation } from '../components/WorkspaceNavigation';
import { errorMessage } from '../errors';
import { getLocale } from '../i18n';
import { fmtDuration } from '../lib/format';
import { forgetWatchProgress } from '../watchProgress';
import { useAuth } from './AuthProvider';
import { SecurityScreen } from './AuthScreens';
import { accountText as c } from './accountCopy';
import { authApi } from './api';
import { AccountAuthSession, AccountProgress } from './types';
import './account.css';

function useAccountCollection<T>(load: () => Promise<T[]>) {
  const [data, setData] = useState<T[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const generation = useRef(0);
  const reload = useCallback(() => {
    const request = ++generation.current;
    setLoading(true); setError(null);
    load().then((rows) => {
      if (request === generation.current) { setData(rows); setLoading(false); }
    }, (cause: unknown) => {
      if (request === generation.current) { setError(errorMessage(cause)); setLoading(false); }
    });
  }, [load]);
  useEffect(() => { reload(); return () => { generation.current++; }; }, [reload]);
  const update = (change: (rows: T[]) => T[]) => {
    generation.current++;
    setData((rows) => change(rows ?? [])); setError(null); setLoading(false);
  };
  return { data, error, loading, reload, update };
}

function CollectionNotice({ state }: { state: { data: unknown; error: string | null; loading: boolean; reload: () => void } }) {
  if (state.error) return <div className="account-notice" role="alert"><p>{state.error}</p><button className="btn text" onClick={state.reload}>{c('retry')}</button></div>;
  if (state.data === null && state.loading) return <div className="account-loading" role="status" aria-label={c('working')}><span /><span /><span /></div>;
  return null;
}

function AccountConfirmation({ title, message, label, busy, error, onDismiss, onConfirm }: {
  title: string; message: string; label: string; busy: boolean; error: string | null;
  onDismiss: () => void; onConfirm: () => void;
}) {
  return <Dialog className="account-confirm" title={title} onDismiss={onDismiss} dismissible={!busy} buttons={<>
    <button className="btn text" disabled={busy} onClick={onDismiss}>{c('cancel')}</button>
    <button className="btn danger-text" disabled={busy} onClick={onConfirm}>{busy ? c('working') : label}</button>
  </>}>
    <p className="account-description">{message}</p>
    {error && <p className="account-operation-error" role="alert">{error}</p>}
  </Dialog>;
}

const date = (value: number) => new Intl.DateTimeFormat(getLocale(), { dateStyle: 'medium', timeStyle: 'short' }).format(value);
const progressTitle = (row: AccountProgress) => row.title || c('unavailableTitle');
const sessionTitle = (row: AccountAuthSession) => row.deviceName || clientLabel(row.clientKind);
const clientLabel = (kind: AccountAuthSession['clientKind']) => c(kind === 'BROWSER' ? 'browser' : kind === 'NATIVE' ? 'app' : 'linkedDevice');
const methodLabels = { PASSWORD: 'password', OIDC: 'oidc', TOTP: 'totp', WEBAUTHN: 'webauthn' } as const;
const methodLabel = (method: string) => c(methodLabels[method.toUpperCase() as keyof typeof methodLabels] ?? 'signIn');

function useSectionFocus() {
  const heading = useRef<HTMLHeadingElement>(null);
  const afterChange = () => { requestAnimationFrame(() => heading.current?.focus({ preventScroll: true })); };
  return { heading, afterChange };
}

export function AccountProgressSection() {
  const resource = useAccountCollection(authApi.progress);
  const [query, setQuery] = useState('');
  const [confirm, setConfirm] = useState<AccountProgress | 'all' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const { heading, afterChange } = useSectionFocus();
  const rows = resource.data ?? [];
  const needle = query.trim().toLocaleLowerCase(getLocale());
  const visible = rows.filter((row) => progressTitle(row).toLocaleLowerCase(getLocale()).includes(needle));
  const ask = (target: AccountProgress | 'all') => { setNotice(null); setError(null); setConfirm(target); };
  const reset = async () => {
    if (confirm === null || busy) return;
    setBusy(true); setError(null);
    try {
      if (confirm === 'all') {
        await authApi.clearProgress(); forgetWatchProgress(); resource.update(() => []);
      } else {
        await authApi.deleteProgress(confirm.contentId); forgetWatchProgress(confirm.contentId);
        resource.update((current) => current.filter((row) => row.contentId !== confirm.contentId));
      }
      setNotice(c(confirm === 'all' ? 'allProgressReset' : 'progressReset'));
      setConfirm(null); afterChange();
    } catch (cause) { setError(errorMessage(cause)); } finally { setBusy(false); }
  };
  return <section className="account-section">
    <header className="account-section-header"><div><h2 ref={heading} tabIndex={-1}>{c('progress')}</h2><p>{c('progressHelp')}</p></div>
      <button className="btn text" disabled={busy || resource.loading} onClick={resource.reload}><Icon name="refresh" />{c('refresh')}</button>
    </header>
    {notice && <p className="account-status" role="status">{notice}</p>}
    <CollectionNotice state={resource} />
    {resource.data !== null && <>
      {rows.length > 0 && <><SearchField placeholder={c('searchProgress')} value={query} onChange={setQuery} /><p className="account-count" role="status">{c('progressCount', { count: visible.length })}</p></>}
      {rows.length === 0 ? <div className="account-empty"><h3>{c('noProgress')}</h3><p>{c('noProgressHelp')}</p></div>
        : visible.length === 0 ? <div className="account-empty"><h3>{c('noMatches')}</h3><button className="btn text" onClick={() => setQuery('')}>{c('clearSearch')}</button></div>
          : <ul className="account-list">{visible.map((row) => <li className="account-row" key={row.contentId}>
            <div className="account-row-copy"><h3>{progressTitle(row)}</h3>
              {!row.title && <p>{c('unavailableHelp')}</p>}
              <p>{row.durationMs > 0 ? c('progressPosition', { position: fmtDuration(row.positionMs / 1000), duration: fmtDuration(row.durationMs / 1000) }) : c('watched', { position: fmtDuration(row.positionMs / 1000) })}</p>
              <p>{c('updated', { date: date(row.updatedMs) })}</p>
              {row.durationMs > 0 && <WatchProgressBar fraction={Math.min(1, Math.max(0, row.positionMs / row.durationMs))} />}
            </div>
            <button className="btn text account-row-action" aria-label={c('resetNamed', { title: progressTitle(row) })} disabled={busy} onClick={() => ask(row)}>{c('resetProgress')}</button>
          </li>)}</ul>}
      {rows.length > 0 && <div className="account-bulk-actions"><button className="btn text danger-text" disabled={busy} onClick={() => ask('all')}>{c('clearProgress')}</button></div>}
    </>}
    {confirm !== null && <AccountConfirmation title={confirm === 'all' ? c('clearProgressTitle') : c('resetTitle', { title: progressTitle(confirm) })}
      message={c(confirm === 'all' ? 'clearProgressBody' : 'resetBody')} label={c(confirm === 'all' ? 'clearProgress' : 'resetProgress')}
      busy={busy} error={error} onDismiss={() => setConfirm(null)} onConfirm={() => void reset()} />}
  </section>;
}

export function AccountSessionsSection() {
  const auth = useAuth();
  const navigate = useNavigate();
  const resource = useAccountCollection(authApi.sessions);
  const [confirm, setConfirm] = useState<AccountAuthSession | 'others' | 'all' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const { heading, afterChange } = useSectionFocus();
  const currentId = auth.user?.authSessionId;
  const rows = [...(resource.data ?? [])].sort((left, right) => Number(right.id === currentId) - Number(left.id === currentId) || right.lastSeenAtMs - left.lastSeenAtMs);
  const others = rows.filter((row) => row.id !== currentId);
  const ask = (target: AccountAuthSession | 'others' | 'all') => { setNotice(null); setError(null); setConfirm(target); };
  const signOut = async () => {
    if (confirm === null || busy) return;
    setBusy(true); setError(null);
    try {
      if (confirm === 'all' || (typeof confirm !== 'string' && confirm.id === currentId)) {
        await auth.logout(confirm === 'all'); navigate('/login', { replace: true });
      } else if (confirm === 'others') {
        await authApi.revokeOtherSessions(); resource.update((current) => current.filter((row) => row.id === currentId));
        setNotice(c('otherSessionsEnded'));
      } else {
        await authApi.revokeSession(confirm.id); resource.update((current) => current.filter((row) => row.id !== confirm.id));
        setNotice(c('sessionEnded'));
      }
      setConfirm(null); afterChange();
    } catch (cause) { setError(errorMessage(cause)); } finally { setBusy(false); }
  };
  const current = confirm !== null && typeof confirm !== 'string' && confirm.id === currentId;
  const title = confirm === 'all' ? c('signOutAllTitle') : confirm === 'others' ? c('signOutOthersTitle') : current ? c('signOutCurrentTitle') : confirm ? c('signOutTitle', { device: sessionTitle(confirm as AccountAuthSession) }) : '';
  return <section className="account-section">
    <header className="account-section-header"><div><h2 ref={heading} tabIndex={-1}>{c('sessions')}</h2><p>{c('sessionsHelp')}</p></div>
      <button className="btn text" disabled={busy || resource.loading} onClick={resource.reload}><Icon name="refresh" />{c('refresh')}</button>
    </header>
    {notice && <p className="account-status" role="status">{notice}</p>}
    <CollectionNotice state={resource} />
    {resource.data !== null && (rows.length === 0 ? <div className="account-empty"><h3>{c('noSessions')}</h3><p>{c('noSessionsHelp')}</p></div>
      : <ul className="account-list">{rows.map((row) => <li className="account-row" key={row.id}>
        <div className="account-row-copy"><div className="account-session-title"><h3>{sessionTitle(row)}</h3>{row.id === currentId && <span className="pill">{c('currentSession')}</span>}</div>
          <p>{clientLabel(row.clientKind)} · {methodLabel(row.authMethod)}</p>
          <p>{c('lastActive', { date: date(row.lastSeenAtMs) })}</p>
          <p>{c('signedIn', { date: date(row.createdAtMs) })}</p>
          <p>{c('expires', { date: date(Math.min(row.idleExpiresAtMs, row.absoluteExpiresAtMs)) })}</p>
        </div>
        <button className="btn text account-row-action" disabled={busy} aria-label={row.id === currentId ? c('signOut') : c('signOutNamed', { device: sessionTitle(row) })} onClick={() => ask(row)}>{c(row.id === currentId ? 'signOut' : 'signOutDevice')}</button>
      </li>)}</ul>)}
    {resource.data !== null && rows.length > 0 && <div className="account-bulk-actions">
      <button className="btn tonal" disabled={busy || others.length === 0} onClick={() => ask('others')}>{c('signOutOthers')}</button>
      <button className="btn text danger-text" disabled={busy} onClick={() => ask('all')}>{c('signOutAll')}</button>
    </div>}
    {confirm !== null && <AccountConfirmation title={title} message={c(confirm === 'all' ? 'signOutAllBody' : confirm === 'others' ? 'signOutOthersBody' : current ? 'signOutCurrentBody' : 'signOutBody')}
      label={c(confirm === 'all' ? 'signOutAll' : confirm === 'others' ? 'signOutOthers' : current ? 'signOut' : 'signOutDevice')}
      busy={busy} error={error} onDismiss={() => setConfirm(null)} onConfirm={() => void signOut()} />}
  </section>;
}

export function AccountWorkspace() {
  const { user } = useAuth();
  const { pathname } = useLocation();
  const section = pathname === '/security/progress' ? 'progress' : pathname === '/security/sessions' ? 'sessions' : 'security';
  return <div className="account-workspace">
    <ScreenHeader compact title={c('title')} subtitle={<span className="subtitle">{user?.displayName || user?.username}{user?.displayName && user.username && ` · @${user.username}`}</span>} />
    <WorkspaceNavigation label={c('navigation')} active={section} className="account-navigation" destinations={[
      { id: 'security', label: c('security'), to: '/security', icon: 'lock' },
      { id: 'progress', label: c('progress'), to: '/security/progress', icon: 'play' },
      { id: 'sessions', label: c('sessions'), to: '/security/sessions', icon: 'phone' },
    ]} />
    {section === 'security' ? <SecurityScreen embedded /> : section === 'progress' ? <AccountProgressSection /> : <AccountSessionsSection />}
  </div>;
}
