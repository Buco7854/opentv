// Mirrors Common.kt.

import { ReactNode, useState } from 'react';
import { imgUrl } from '../api';
import { t } from '../i18n';
import { Icon, kindIconName } from './Icons';
import { Spinner } from './Primitives';

// Kind icon shown when the logo is missing or fails to load.
export function ChannelLogo({ url, kind }: { url: string | null; kind: number }) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const failed = failedSrc !== null && failedSrc === url;
  return (
    <div className="logo-box">
      {url && !failed
        ? <img loading="lazy" src={imgUrl(url)} data-src={url} alt="" onError={(e) => setFailedSrc(e.currentTarget.getAttribute("data-src"))} />
        : <Icon name={kindIconName(kind)} />}
    </div>
  );
}

export function EmptyState({ title, subtitle, children, action }: {
  title: string;
  subtitle: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      {children}
      <h3 className="type-title-large">{title}</h3>
      <p>{subtitle}</p>
      {action}
    </div>
  );
}

export function LoadFailed({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <EmptyState
      title={t('common.loadFailed')}
      subtitle={message}
      action={onRetry && <button className="btn" onClick={onRetry}>{t('common.retry')}</button>}
    >
      <Icon name="alert" />
    </EmptyState>
  );
}

export function asyncFallback(state: {
  data: unknown;
  error: string | null;
  reload?: () => void;
}): ReactNode | null {
  if (state.error !== null) return <LoadFailed message={state.error} onRetry={state.reload} />;
  if (state.data == null) return <Spinner />;
  return null;
}

export function FavoriteIcon({ isFavorite, onToggle }: { isFavorite: boolean; onToggle: () => void }) {
  return (
    <button
      className={`icon-btn ${isFavorite ? 'coral' : 'muted'}`}
      aria-label={isFavorite ? t('favorites.removeAria') : t('favorites.addAria')}
      onClick={(e) => { e.stopPropagation(); onToggle(); }}
    >
      <Icon name={isFavorite ? 'favorite' : 'favoriteBorder'} />
    </button>
  );
}

export const Pill = ({ children }: { children: ReactNode }) => <span className="pill">{children}</span>;
