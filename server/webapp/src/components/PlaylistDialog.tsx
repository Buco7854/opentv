// Add/edit playlist dialog with Xtream detection for get.php M3U links.

import { useEffect, useState } from 'react';
import { api, Playlist, PlaylistUpsertRequest } from '../api';
import { t } from '../i18n';
import { Dialog, Segmented, snackbar, TextField } from './Primitives';

type Mode = 'xtream' | 'url' | 'file';

export function PlaylistDialog({ editing, onDismiss, onDone }: {
  editing: Playlist | null;
  onDismiss: () => void;
  onDone: (saved: Playlist) => void;
}) {
  const isEdit = editing != null;
  const [mode, setMode] = useState<Mode>(editing ? editing.mode : 'xtream');
  const [name, setName] = useState(editing?.name ?? '');
  const [server, setServer] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [url, setUrl] = useState('');
  const [epg, setEpg] = useState('');
  const [file, setFile] = useState<File | null>(null);

  // Listings carry no secrets; fetch credentials on demand to pre-fill.
  useEffect(() => {
    if (!editing) return;
    let live = true;
    api.playlistCredentials(editing.id).then((c) => {
      if (!live) return;
      setServer(c.xtreamBase ?? '');
      setUsername(c.xtreamUser ?? '');
      setPassword(c.xtreamPass ?? '');
      setUrl(c.url ?? '');
      setEpg(c.epgUrl ?? '');
    }).catch(() => {});
    return () => { live = false; };
  }, [editing]);
  const [busy, setBusy] = useState(false);
  const [suggestion, setSuggestion] = useState<{ base: string; user: string; pass: string } | null>(null);

  async function submit(req: PlaylistUpsertRequest) {
    setBusy(true);
    try {
      const saved = isEdit ? await api.updatePlaylist(editing.id, req) : await api.addPlaylist(req);
      snackbar(isEdit ? t('playlists.updated') : t('playlists.added'));
      onDone(saved);
    } catch (e) {
      setBusy(false);
      snackbar((e as Error).message);
    }
  }

  async function onConfirm() {
    if (mode === 'xtream') {
      if (!server.trim() || !username.trim() || !password) return;
      await submit({ mode, name, server, username, password });
    } else if (mode === 'url') {
      const trimmed = url.trim();
      if (!trimmed) return;
      // A get.php URL carries an Xtream login: offer the richer mode.
      const detected = !isEdit && /get\.php\?/.test(trimmed) && /username=/.test(trimmed)
        ? (() => {
            try {
              const u = new URL(trimmed);
              return {
                base: `${u.protocol}//${u.host}`,
                user: u.searchParams.get('username') ?? '',
                pass: u.searchParams.get('password') ?? '',
              };
            } catch { return null; }
          })()
        : null;
      if (detected) setSuggestion(detected);
      else await submit({ mode, name, url: trimmed, epgUrl: epg.trim() });
    } else {
      if (isEdit && !file) await submit({ mode, name });
      else if (file) await submit({ mode, name, content: await file.text() });
      else snackbar(t('playlists.pickFileFirst'));
    }
  }

  return (
    <>
      <Dialog
        title={isEdit ? t('playlists.edit') : t('playlists.add')}
        onDismiss={onDismiss}
        buttons={
          <>
            <button className="btn text" onClick={onDismiss}>{t('common.cancel')}</button>
            <button className="btn text" disabled={busy} onClick={onConfirm}>
              {busy ? t('common.working') : isEdit ? t('common.save') : t('common.add')}
            </button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          {!isEdit && (
            <Segmented<Mode>
              options={[['xtream', t('playlists.modeXtream')], ['url', t('playlists.modeUrl')], ['file', t('playlists.modeFile')]]}
              selected={mode}
              onSelect={setMode}
            />
          )}
          <TextField label={t('playlists.name')} value={name} onChange={setName} />
          {mode === 'xtream' && (
            <>
              <TextField label={t('playlists.server')} value={server} onChange={setServer} />
              <TextField label={t('playlists.username')} value={username} onChange={setUsername} autoComplete="username" />
              <TextField label={t('playlists.password')} type="password" value={password} onChange={setPassword} autoComplete="current-password" />
              {!isEdit && (
                <p className="hint">{t('playlists.xtreamHint')}</p>
              )}
            </>
          )}
          {mode === 'url' && (
            <>
              <TextField label={t('playlists.url')} value={url} onChange={setUrl} />
              <TextField label={t('playlists.epgUrl')} value={epg} onChange={setEpg} />
            </>
          )}
          {mode === 'file' && (
            <>
              <p className="hint">
                {isEdit ? t('playlists.fileHintEdit') : t('playlists.fileHintAdd')}
              </p>
              <input
                type="file" accept=".m3u,.m3u8,audio/x-mpegurl"
                className="type-body-medium text-on-surface-variant"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </>
          )}
        </div>
      </Dialog>

      {suggestion && (
        <Dialog
          title={t('playlists.detectedTitle')}
          onDismiss={() => setSuggestion(null)}
          buttons={
            <>
              <button className="btn text" onClick={() => {
                setSuggestion(null);
                submit({ mode: 'url', name, url: url.trim(), epgUrl: epg.trim() });
              }}>{t('playlists.keepM3u')}</button>
              <button className="btn text" onClick={() => {
                setSuggestion(null);
                submit({ mode: 'xtream', name, server: suggestion.base, username: suggestion.user, password: suggestion.pass });
              }}>{t('playlists.useXtream')}</button>
            </>
          }
        >
          <p className="type-body-medium text-on-surface-variant">
            {t('playlists.detectedBody', { base: suggestion.base })}
          </p>
        </Dialog>
      )}
    </>
  );
}
