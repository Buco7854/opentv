import { useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { ConfirmDialog, ScreenHeader, SearchField } from '../components/Primitives';
import { EmptyState, ResourceFeedback } from '../components/Common';
import { ProgressList, SessionList, progressTitle, sessionTitle } from '../components/UserActivity';
import { useResource } from '../hooks';
import { Icon } from '../components/Icons';
import { WorkspaceNavigation } from '../components/WorkspaceNavigation';
import { errorMessage } from '../errors';
import { getLocale } from '../i18n';
import { forgetWatchProgress } from '../watchProgress';
import { useAuth } from './AuthProvider';
import { SecurityScreen } from './AuthScreens';
import { activityText as c } from '../components/userActivityCopy';
import { authApi } from './api';
import { AccountAuthSession, AccountProgress } from './types';
import './account.css';

function useSectionFocus() {
  const heading = useRef<HTMLHeadingElement>(null);
  const afterChange = () => { requestAnimationFrame(() => heading.current?.focus({ preventScroll: true })); };
  return { heading, afterChange };
}

export function AccountProgressSection() {
  const resource = useResource(authApi.progress);
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
        await authApi.clearProgress(); forgetWatchProgress(); resource.set([]);
      } else {
        await authApi.deleteProgress(confirm.contentId); forgetWatchProgress(confirm.contentId);
        resource.set((current) => current?.filter((row) => row.contentId !== confirm.contentId) ?? []);
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
    <ResourceFeedback state={resource} />
    {resource.data !== null && <>
      {rows.length > 0 && <><SearchField placeholder={c('searchProgress')} value={query} onChange={setQuery} /><p className="account-count" role="status">{c('progressCount', { count: visible.length })}</p></>}
      {rows.length === 0 ? <EmptyState title={c('noProgress')} subtitle={c('noProgressHelp')} />
        : visible.length === 0 ? <EmptyState title={c('noMatches')}
          action={<button className="btn text" onClick={() => setQuery('')}>{c('clearSearch')}</button>} />
          : <ProgressList items={visible} action={(row) =>
            <button className="btn text" aria-label={c('resetNamed', { title: progressTitle(row) })} disabled={busy} onClick={() => ask(row)}>{c('resetProgress')}</button>
          } />}
      {rows.length > 0 && <div className="account-bulk-actions"><button className="btn text danger-text" disabled={busy} onClick={() => ask('all')}>{c('clearProgress')}</button></div>}
    </>}
    {confirm !== null && <ConfirmDialog closeOnConfirm={false} title={confirm === 'all' ? c('clearProgressTitle') : c('resetTitle', { title: progressTitle(confirm) })}
      message={c(confirm === 'all' ? 'clearProgressBody' : 'resetBody')} confirmLabel={c(confirm === 'all' ? 'clearProgress' : 'resetProgress')}
      busy={busy} error={error} onDismiss={() => setConfirm(null)} onConfirm={() => void reset()} />}
  </section>;
}

export function AccountSessionsSection() {
  const auth = useAuth();
  const navigate = useNavigate();
  const resource = useResource(authApi.sessions);
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
        await authApi.revokeOtherSessions(); resource.set((current) => current?.filter((row) => row.id === currentId) ?? []);
        setNotice(c('otherSessionsEnded'));
      } else {
        await authApi.revokeSession(confirm.id); resource.set((current) => current?.filter((row) => row.id !== confirm.id) ?? []);
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
    <ResourceFeedback state={resource} />
    {resource.data !== null && (rows.length === 0
      ? <EmptyState title={c('noSessions')} subtitle={c('noSessionsHelp')} />
      : <SessionList items={rows} currentSessionId={currentId} action={(row) =>
        <button className="btn text" disabled={busy} aria-label={row.id === currentId ? c('signOut') : c('signOutNamed', { device: sessionTitle(row) })} onClick={() => ask(row)}>{c(row.id === currentId ? 'signOut' : 'signOutDevice')}</button>
      } />)}
    {resource.data !== null && rows.length > 0 && <div className="account-bulk-actions">
      <button className="btn tonal" disabled={busy || others.length === 0} onClick={() => ask('others')}>{c('signOutOthers')}</button>
      <button className="btn text danger-text" disabled={busy} onClick={() => ask('all')}>{c('signOutAll')}</button>
    </div>}
    {confirm !== null && <ConfirmDialog closeOnConfirm={false} title={title} message={c(confirm === 'all' ? 'signOutAllBody' : confirm === 'others' ? 'signOutOthersBody' : current ? 'signOutCurrentBody' : 'signOutBody')}
      confirmLabel={c(confirm === 'all' ? 'signOutAll' : confirm === 'others' ? 'signOutOthers' : current ? 'signOut' : 'signOutDevice')}
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
