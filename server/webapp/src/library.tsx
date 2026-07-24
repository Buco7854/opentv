import {
  createContext, ReactNode, useCallback, useContext, useEffect, useRef, useState,
} from 'react';
import { api, Playlist } from './api';

interface LibraryContextValue {
  playlists: Playlist[] | null;
  loading: boolean;
  error: string | null;
  reload: () => Promise<void>;
  rememberPlaylist: (playlist: Playlist) => void;
  forgetPlaylist: (playlistId: number) => void;
  playlistPanelOpen: boolean;
  setPlaylistPanelOpen: (open: boolean) => void;
}

const LibraryContext = createContext<LibraryContextValue | null>(null);

/**
 * Owns the playlist catalog used by the application shell.
 *
 * Screens still load their feature data independently, while this small
 * catalog is shared so navigation and route guards cannot disagree about
 * whether a playlist exists.
 */
export function LibraryProvider({ children }: { children: ReactNode }) {
  const [playlists, setPlaylists] = useState<Playlist[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [playlistPanelOpen, setPlaylistPanelOpen] = useState(false);
  const requestSequence = useRef(0);

  const reload = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(null);
    try {
      const next = await api.playlists();
      if (requestSequence.current === sequence) setPlaylists(next);
    } catch (cause) {
      if (requestSequence.current === sequence) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    } finally {
      if (requestSequence.current === sequence) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
    return () => { requestSequence.current++; };
  }, [reload]);

  const rememberPlaylist = useCallback((playlist: Playlist) => {
    setError(null);
    setPlaylists((current) => {
      if (!current) return [playlist];
      const index = current.findIndex((item) => item.id === playlist.id);
      if (index < 0) return [...current, playlist];
      return current.map((item, itemIndex) => itemIndex === index ? playlist : item);
    });
  }, []);

  const forgetPlaylist = useCallback((playlistId: number) => {
    setPlaylists((current) => current?.filter((item) => item.id !== playlistId) ?? current);
  }, []);

  return (
    <LibraryContext.Provider value={{
      playlists,
      loading,
      error,
      reload,
      rememberPlaylist,
      forgetPlaylist,
      playlistPanelOpen,
      setPlaylistPanelOpen,
    }}>
      {children}
    </LibraryContext.Provider>
  );
}

export function useLibrary(): LibraryContextValue {
  const context = useContext(LibraryContext);
  if (!context) throw new Error('useLibrary must be used inside LibraryProvider');
  return context;
}
