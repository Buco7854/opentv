// Keyboard shortcuts and the OS media keys / lock-screen controls.

import { RefObject, useEffect } from 'react';
import { prefs } from '../preferences';

const TARGETS_HANDLING_THEIR_OWN_KEYS =
  'input, textarea, select, [contenteditable="true"], [role="dialog"], [role="listbox"]';

export function usePlayerShortcuts(opts: {
  title: string;
  live: boolean;
  /** A sheet is layered over the player, so Escape dismisses that rather than closing. */
  menu: string | null;
  guideOpen: boolean;
  watchTogetherOpen: boolean;
  videoRef: RefObject<HTMLVideoElement | null>;
  onClose: () => void;
  poke: () => void;
  togglePlay: () => void;
  toggleMute: () => void;
  seekBy: (delta: number) => void;
  changeVolume: (level: number) => void;
  toggleFullscreen: () => void;
}) {
  const {
    title, live, menu, guideOpen, watchTogetherOpen, videoRef,
    onClose, poke, togglePlay, toggleMute, seekBy, changeVolume, toggleFullscreen,
  } = opts;

  useEffect(() => {
    if (!('mediaSession' in navigator)) return;
    const session = navigator.mediaSession;
    session.metadata = new MediaMetadata({ title });
    session.setActionHandler('play', () => videoRef.current?.play().catch(() => {}));
    session.setActionHandler('pause', () => videoRef.current?.pause());
    session.setActionHandler('seekbackward', live ? null : () => seekBy(-prefs.seekSeconds));
    session.setActionHandler('seekforward', live ? null : () => seekBy(prefs.seekSeconds));
    return () => {
      session.metadata = null;
      session.setActionHandler('play', null);
      session.setActionHandler('pause', null);
      session.setActionHandler('seekbackward', null);
      session.setActionHandler('seekforward', null);
    };
  }, [title, live, seekBy, videoRef]);

  const layered = !!menu || guideOpen || watchTogetherOpen;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // A focused slider already consumes arrows/space.
      if ((e.target as HTMLElement).closest?.(TARGETS_HANDLING_THEIR_OWN_KEYS)) return;
      // Player closes on Escape only when no sheet is layered over it.
      if (e.key === 'Escape') {
        if (!layered && !document.fullscreenElement) onClose();
      }
      else if (e.key === ' ') { e.preventDefault(); togglePlay(); poke(); }
      else if (e.key === 'ArrowLeft' && !live) { seekBy(-prefs.seekSeconds); poke(); }
      else if (e.key === 'ArrowRight' && !live) { seekBy(prefs.seekSeconds); poke(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); changeVolume((videoRef.current?.volume ?? 1) + 0.05); poke(); }
      else if (e.key === 'ArrowDown') { e.preventDefault(); changeVolume((videoRef.current?.volume ?? 1) - 0.05); poke(); }
      else if (e.key === 'm') { toggleMute(); poke(); }
      else if (e.key === 'f') toggleFullscreen();
    };
    document.addEventListener('keydown', onKey);
    poke();
    return () => document.removeEventListener('keydown', onKey);
  }, [
    live, layered, onClose, poke, seekBy, togglePlay, toggleMute, changeVolume,
    toggleFullscreen, videoRef,
  ]);
}
