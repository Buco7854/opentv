// Thin "continue watching" bar. Mirrors WatchProgress.kt.

export function WatchProgressBar({ fraction, height = 4, mint, className = '' }: {
  fraction: number;
  height?: number;
  mint?: boolean;
  className?: string;
}) {
  const f = Math.min(1, Math.max(0, fraction));
  return (
    <div className={`progress-track ${className}`.trim()} style={{ height }}>
      <div className={`progress-fill${mint ? ' mint' : ''}`} style={{ width: `${Math.round(f * 100)}%` }} />
    </div>
  );
}
