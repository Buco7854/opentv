import { ContentRef } from '../api';
import { createContext, ReactNode, useContext, useMemo } from 'react';
import { useNavigate } from 'react-router';

/** Route-based player navigation. Source tokens never enter browser history. */
export interface PlayerNav {
  playChannel: (ref: ContentRef) => void;
  playCatchup: (channelId: number, startMs: number, endMs: number) => void;
  playDownload: (downloadId: string) => void;
}

const PlayerNavigationContext = createContext<PlayerNav | null>(null);

export function usePlayer(): PlayerNav {
  const player = useContext(PlayerNavigationContext);
  if (!player) throw new Error('usePlayer must be used inside PlayerNavigationProvider');
  return player;
}

/**
 * Lightweight app-shell provider. Playback engines belong to the lazy watch
 * feature and are not imported by this module.
 */
export function PlayerNavigationProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const value = useMemo<PlayerNav>(() => ({
    playChannel: (ref) => navigate(`/watch/${ref}`),
    playCatchup: (id, startMs, endMs) => navigate(`/watch/catchup/${id}/${startMs}/${endMs}`),
    playDownload: (id) => navigate(`/watch/download/${id}`),
  }), [navigate]);

  return (
    <PlayerNavigationContext.Provider value={value}>
      {children}
    </PlayerNavigationContext.Provider>
  );
}
