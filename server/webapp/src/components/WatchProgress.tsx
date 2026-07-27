// Thin "continue watching" bar. Mirrors WatchProgress.kt.

import { useCssVars } from '../lib/cssVars';

export function WatchProgressBar({ fraction, mint, over, className = '' }: {
  fraction: number;
  mint?: boolean;
  over?: boolean;
  className?: string;
}) {
  const fill = useCssVars({ '--fill': Math.min(1, Math.max(0, fraction)) });
  return (
    <div className={`progress-track ${className}`.trim()}>
      <div ref={fill} className={`progress-fill${mint ? ' mint' : ''}${over ? ' over' : ''}`} />
    </div>
  );
}
