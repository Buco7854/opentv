// Mirrors CastRow.kt.

import { imgUrl } from '../api';

export interface CastMember { n: string; p: string | null }

export function decodeCast(json: string | null): CastMember[] {
  if (!json) return [];
  try {
    return ((JSON.parse(json) as CastMember[]) || []).filter((m) => m && m.n);
  } catch {
    return [];
  }
}

export const castFromNames = (names: string | null): CastMember[] =>
  (names ?? '').split(',').map((s) => s.trim()).filter(Boolean).map((n) => ({ n, p: null }));

export function CastRow({ members }: { members: CastMember[] }) {
  if (!members.length) return null;
  return (
    <div className="cast-row">
      {members.map((m) => (
        <div key={m.n} className="cast-member">
          <div className="avatar">
            {m.p
              ? <img loading="lazy" src={imgUrl(m.p)} alt={m.n} />
              : m.n.split(/\s+/).map((w) => w[0]?.toUpperCase()).filter(Boolean).slice(0, 2).join('')}
          </div>
          <div className="name">{m.n}</div>
        </div>
      ))}
    </div>
  );
}
