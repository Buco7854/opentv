import { Dispatch, SetStateAction, useCallback, useEffect, useState } from 'react';
import { Icon } from '../components/Icons';
import { Dialog, toast } from '../components/Primitives';
import { reportSuccess } from '../errors';
import { AdminPlaylist } from './api';
import { adminText as c } from './copy';
import { errorMessage, playlistModeLabel } from './format';

export interface AdminSection<T> {
  data: T | null;
  error: string | null;
  set: Dispatch<SetStateAction<T | null>>;
  reload: () => void;
}

export function useAdminSection<T>(load: () => Promise<T>): AdminSection<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    setError(null);
    load().then(
      (next) => { if (active) setData(next); },
      (cause: unknown) => { if (active) setError(errorMessage(cause)); },
    );
    return () => { active = false; };
  }, [load, attempt]);
  const reload = useCallback(() => setAttempt((current) => current + 1), []);
  return { data, error, set: setData, reload };
}

export function PlaylistPicker({ playlists, selected, onChange, disabled }: {
  playlists: AdminPlaylist[];
  selected: number[];
  onChange: (ids: number[]) => void;
  disabled?: boolean;
}) {
  if (playlists.length === 0) return <p className="admin-hint">{c('noPlaylists')}</p>;
  return (
    <div className="admin-picks">
      {playlists.map((playlist) => {
        const on = selected.includes(playlist.id);
        return (
          <button
            type="button"
            key={playlist.id}
            aria-pressed={on}
            disabled={disabled}
            className={`panel-row${on ? ' selected' : ''}`}
            onClick={() => onChange(on
              ? selected.filter((id) => id !== playlist.id)
              : [...selected, playlist.id])}
          >
            <span className={`select-check${on ? ' on' : ''}`} aria-hidden>
              {on && <Icon name="check" />}
            </span>
            <div className="body">
              <div className="name">{playlist.name}</div>
              <div className="sub">
                {c('playlistItems', {
                  count: playlist.channelCount,
                  mode: playlistModeLabel(playlist.mode),
                })}
              </div>
            </div>
          </button>
        );
      })}
    </div>
  );
}

export function OneTimeTokenDialog({ title, explanation, token, path, onDismiss }: {
  title: string;
  explanation: string;
  token: string;
  path: string;
  onDismiss: () => void;
}) {
  const link = `${window.location.origin}${path}#token=${encodeURIComponent(token)}`;
  const copy = async (value: string, label: string) => {
    try {
      await navigator.clipboard.writeText(value);
      reportSuccess(c('copied', { label }));
    } catch {
      // A clipboard the browser refuses is not an app failure; the token is on screen.
      toast(c('copyFailed'), { tone: 'error' });
    }
  };
  return (
    <Dialog
      title={title}
      onDismiss={onDismiss}
      buttons={<button className="btn text" onClick={onDismiss}>{c('savedToken')}</button>}
    >
      <p className="hint">{explanation}</p>
      <code className="admin-token">{link}</code>
      <div className="flex flex-wrap gap-2">
        <button className="btn tonal w-auto" onClick={() => void copy(link, c('tokenLink'))}>
          <Icon name="copy" />{c('copyLink')}
        </button>
        <button className="btn tonal w-auto" onClick={() => void copy(token, c('token'))}>
          <Icon name="copy" />{c('copyToken')}
        </button>
      </div>
    </Dialog>
  );
}
