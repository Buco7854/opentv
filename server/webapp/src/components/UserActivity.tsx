import { ReactNode } from 'react';
import type { AccountAuthSession, AccountProgress } from '../auth/types';
import { getLocale } from '../i18n';
import { fmtDuration } from '../lib/format';
import { WatchProgressBar } from './WatchProgress';
import { activityText as c } from './userActivityCopy';
import './user-activity.css';

export const progressTitle = (row: AccountProgress) => row.title || c('unavailableTitle');
export const sessionTitle = (row: AccountAuthSession) => row.deviceName || clientLabel(row.clientKind);
const clientLabel = (kind: AccountAuthSession['clientKind']) => c(kind === 'BROWSER' ? 'browser' : kind === 'NATIVE' ? 'app' : 'linkedDevice');
const methodLabels = { PASSWORD: 'password', OIDC: 'oidc', TOTP: 'totp', WEBAUTHN: 'webauthn' } as const;
const methodLabel = (method: string) => c(methodLabels[method.toUpperCase() as keyof typeof methodLabels] ?? 'signIn');
const dateFormatter = () => new Intl.DateTimeFormat(getLocale(), { dateStyle: 'medium', timeStyle: 'short' }).format;

/** One progress presentation for account owners and administrators; callers own the action. */
export function ProgressList({ items, action, titleLevel = 3 }: {
  items: AccountProgress[];
  action: (item: AccountProgress) => ReactNode;
  titleLevel?: 3 | 4;
}) {
  const Title = titleLevel === 3 ? 'h3' : 'h4';
  const date = dateFormatter();
  return <ul className="activity-list">{items.map((row) => <li className="activity-row" key={row.contentId}>
    <div className="activity-row-copy">
      <Title className="activity-title">{progressTitle(row)}</Title>
      {!row.title && <p>{c('unavailableHelp')}</p>}
      <p>{row.durationMs > 0 ? c('progressPosition', { position: fmtDuration(row.positionMs / 1000), duration: fmtDuration(row.durationMs / 1000) }) : c('watched', { position: fmtDuration(row.positionMs / 1000) })}</p>
      <p>{c('updated', { date: date(row.updatedMs) })}</p>
      {row.durationMs > 0 && <WatchProgressBar fraction={Math.min(1, Math.max(0, row.positionMs / row.durationMs))} />}
    </div>
    <div className="activity-row-action">{action(row)}</div>
  </li>)}</ul>;
}

/** Session metadata and the current-device marker are identical in both management views. */
export function SessionList({ items, currentSessionId, action, titleLevel = 3 }: {
  items: AccountAuthSession[];
  currentSessionId?: string | null;
  action: (item: AccountAuthSession) => ReactNode;
  titleLevel?: 3 | 4;
}) {
  const Title = titleLevel === 3 ? 'h3' : 'h4';
  const date = dateFormatter();
  return <ul className="activity-list">{items.map((row) => <li className="activity-row" key={row.id}>
    <div className="activity-row-copy">
      <div className="activity-session-title"><Title className="activity-title">{sessionTitle(row)}</Title>
        {row.id === currentSessionId && <span className="pill">{c('currentSession')}</span>}
      </div>
      <p>{clientLabel(row.clientKind)} · {methodLabel(row.authMethod)}</p>
      <p>{c('lastActive', { date: date(row.lastSeenAtMs) })}</p>
      <p>{c('signedIn', { date: date(row.createdAtMs) })}</p>
      <p>{c('expires', { date: date(Math.min(row.idleExpiresAtMs, row.absoluteExpiresAtMs)) })}</p>
    </div>
    <div className="activity-row-action">{action(row)}</div>
  </li>)}</ul>;
}
