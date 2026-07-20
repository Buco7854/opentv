// Shared list-row for live/movie/series. Every affordance is optional.
// Mirrors MediaListRow.kt.

import { ReactNode, useRef } from 'react';
import { t } from '../i18n';
import { BadgeRow } from './Badges';
import { ChannelLogo, FavoriteIcon } from './Common';
import { Icon } from './Icons';
import { WatchProgressBar } from './WatchProgress';

export function MediaListRow({
  title, subtitle, logo, kind, tags = [], airing, airingProgress,
  isFavorite, onToggleFavorite, onGuide, guideHighlight, downloadSlot, chevron, onClick,
  selectable, selected, onLongPress,
}: {
  title: string;
  subtitle?: string | null;
  logo: string | null;
  kind: number;
  tags?: string[];
  airing?: string | null;
  airingProgress?: number | null;
  isFavorite?: boolean;
  onToggleFavorite?: () => void;
  onGuide?: (() => void) | null;
  guideHighlight?: boolean;
  downloadSlot?: ReactNode;
  chevron?: boolean;
  onClick: () => void;
  // Selection mode: click toggles the checkbox instead of opening.
  selectable?: boolean;
  selected?: boolean;
  // Touch-and-hold to enter multi-select.
  onLongPress?: () => void;
}) {
  // Press held ~500ms without scrolling fires onLongPress and swallows the click.
  const timer = useRef<number | null>(null);
  const origin = useRef<{ x: number; y: number } | null>(null);
  const fired = useRef(false);
  const clear = () => { if (timer.current != null) { clearTimeout(timer.current); timer.current = null; } };
  const onDown = (e: React.PointerEvent) => {
    if (!onLongPress) return;
    fired.current = false;
    origin.current = { x: e.clientX, y: e.clientY };
    timer.current = window.setTimeout(() => { fired.current = true; clear(); onLongPress(); }, 500);
  };
  const onMove = (e: React.PointerEvent) => {
    if (!origin.current || timer.current == null) return;
    if (Math.abs(e.clientX - origin.current.x) > 10 || Math.abs(e.clientY - origin.current.y) > 10) clear();
  };
  const handleClick = () => { if (fired.current) { fired.current = false; return; } onClick(); };
  return (
    <button className={`card${selectable && selected ? ' selected' : ''}`} onClick={handleClick}
            onPointerDown={onLongPress ? onDown : undefined}
            onPointerMove={onLongPress ? onMove : undefined}
            onPointerUp={onLongPress ? clear : undefined}
            onPointerLeave={onLongPress ? clear : undefined}
            onContextMenu={onLongPress ? (e) => e.preventDefault() : undefined}
            aria-pressed={selectable ? !!selected : undefined}>
      <div className="row">
        {selectable && (
          <span className={`select-check${selected ? ' on' : ''}`} aria-hidden>
            {selected && <Icon name="check" />}
          </span>
        )}
        <ChannelLogo url={logo} kind={kind} />
        <div className="body">
          <div className="title-line">
            <span className="title">{title}</span>
            <BadgeRow tags={tags} />
          </div>
          {airing
            ? <div className="sub airing">{airing}</div>
            : subtitle && <div className="sub">{subtitle}</div>}
          {airing && airingProgress != null && <WatchProgressBar fraction={airingProgress} height={3} mint />}
        </div>
        {!selectable && (
          <div className="actions">
            {onToggleFavorite && <FavoriteIcon isFavorite={!!isFavorite} onToggle={onToggleFavorite} />}
            {onGuide && (
              <button
                className="icon-btn muted"
                aria-label={guideHighlight ? t('guide.withCatchup') : t('guide.title')}
                title={guideHighlight ? t('guide.withCatchup') : t('guide.title')}
                onClick={(e) => { e.stopPropagation(); onGuide(); }}
              >
                <Icon name="calendar" />
              </button>
            )}
            {downloadSlot}
            {chevron && <Icon name="chevron" className="sm" />}
          </div>
        )}
      </div>
    </button>
  );
}
