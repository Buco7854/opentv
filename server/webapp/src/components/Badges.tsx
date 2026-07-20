// Quality tags mined from provider titles. Mirrors Badges.kt.

const TAG_REGEX = /\b(4K|UHD|2160p|1080p|FHD|720p|HEVC|H\.?26[45]|HDR(?:10)?|10bit|HD|SD)\b/gi;
const PREMIUM = new Set(['4K', 'UHD', 'HDR', 'HDR10', 'DOLBY']);

export function mediaTags(name: string, max = 2): string[] {
  const found = [...name.matchAll(TAG_REGEX)]
    .map((m) => m[1].toUpperCase().replace('.', ''))
    .map((t) => (t === '2160P' ? '4K' : t));
  return [...new Set(found)].slice(0, max);
}

export const QualityBadge = ({ tag }: { tag: string }) => (
  <span className={`badge${PREMIUM.has(tag) ? ' premium' : ''}`}>{tag}</span>
);

export const BadgeRow = ({ tags }: { tags: string[] }) => (
  <>{tags.map((t) => <QualityBadge key={t} tag={t} />)}</>
);
