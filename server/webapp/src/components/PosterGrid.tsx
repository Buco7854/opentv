// Adaptive portrait-card grid. Mirrors PosterGrid.kt.

import { useState } from 'react';
import { imgUrl } from '../api';
import { QualityBadge } from './Badges';
import { Icon, kindIconName } from './Icons';

export interface PosterItem {
  id: string;
  image: string | null;
  title: string;
  subtitle?: string | null;
  tag?: string;
}

export function PosterCard({ item, kind, onClick }: { item: PosterItem; kind: number; onClick: () => void }) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const failed = failedSrc !== null && failedSrc === item.image;
  return (
    <button className="card poster-card" onClick={onClick}>
      <div className="img">
        {item.image && !failed
          ? <img loading="lazy" decoding="async" src={imgUrl(item.image)} data-src={item.image} alt="" onError={(e) => setFailedSrc(e.currentTarget.getAttribute("data-src"))} />
          : <Icon name={kindIconName(kind)} />}
        {item.tag && <QualityBadge tag={item.tag} />}
      </div>
      <div className="meta">
        <div className="title">{item.title}</div>
        {item.subtitle && <div className="sub">{item.subtitle}</div>}
      </div>
    </button>
  );
}

export function PosterGrid({ items, kind, onClick }: {
  items: PosterItem[];
  kind: number;
  onClick: (id: string) => void;
}) {
  return (
    <div className="poster-grid">
      {items.map((item) => (
        <PosterCard key={item.id} item={item} kind={kind} onClick={() => onClick(item.id)} />
      ))}
    </div>
  );
}
