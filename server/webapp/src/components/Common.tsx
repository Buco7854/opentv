// Mirrors Common.kt.

import { ReactNode, useState } from 'react';
import { imgUrl } from '../api';
import { t } from '../i18n';
import { Icon, kindIconName } from './Icons';

// Kind icon shown when the logo is missing or fails to load.
export function ChannelLogo({ url, kind }: { url: string | null; kind: number }) {
  const [failed, setFailed] = useState(false);
  return (
    <div className="logo-box">
      {url && !failed
        ? <img loading="lazy" src={imgUrl(url)} alt="" onError={() => setFailed(true)} />
        : <Icon name={kindIconName(kind)} />}
    </div>
  );
}

export function EmptyState({ title, subtitle, children }: { title: string; subtitle: string; children?: ReactNode }) {
  return (
    <div className="empty">
      {children}
      <h3>{title}</h3>
      <p>{subtitle}</p>
    </div>
  );
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
