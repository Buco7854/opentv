// Add/edit playlist dialog with Xtream detection for get.php M3U links.

import { useState } from 'react';
import {
  api, Playlist, PlaylistEdit, PlaylistEditField, PlaylistUpdateRequest, PlaylistUpsertRequest,
} from '../api';
import { reportError, reportSuccess } from '../errors';
import { t } from '../i18n';
import { Dialog, Segmented, toast, TextField } from './Primitives';

type Mode = 'xtream' | 'url' | 'file';

export function PlaylistDialog({ editing, onDismiss, onDone }: {
  editing: PlaylistEdit | null;
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

  const [busy, setBusy] = useState(false);
  const [suggestion, setSuggestion] = useState<{ base: string; user: string; pass: string } | null>(null);
  const editShowsAny = (...fields: PlaylistEditField[]) =>
    editing?.fields.some((field) => fields.includes(field)) === true;

  async function submit(req: PlaylistUpsertRequest | PlaylistUpdateRequest) {
    setBusy(true);
    try {
      const saved = isEdit
        ? await api.updatePlaylist(editing.id, req as PlaylistUpdateRequest)
        : await api.addPlaylist(req as PlaylistUpsertRequest);
      reportSuccess(isEdit ? t('playlists.updated') : t('playlists.added'));
      onDone(saved);
    } catch (e) {
      setBusy(false);
      reportError(e);
    }
  }

  async function onConfirm() {
    if (editing) {
      const fields = new Set(editing.fields);
      const update: PlaylistUpdateRequest = {};
      if (fields.has(PlaylistEditField.NAME)) update.name = name;
      if (fields.has(PlaylistEditField.SERVER) && server.trim()) update.server = server.trim();
      if (fields.has(PlaylistEditField.USERNAME) && username.trim()) {
        update.username = username.trim();
      }
      if (fields.has(PlaylistEditField.PASSWORD) && password) update.password = password;
      if (fields.has(PlaylistEditField.URL) && url.trim()) update.url = url.trim();
      if (fields.has(PlaylistEditField.EPG_URL) && epg.trim()) update.epgUrl = epg.trim();
      if (fields.has(PlaylistEditField.CONTENT) && file) update.content = await file.text();
      await submit(update);
      return;
    }
    if (mode === 'xtream') {
      if (!server.trim() || !username.trim() || !password) return;
      await submit({
        mode,
        name,
        server: server.trim(),
        username: username.trim(),
        password,
      });
    } else if (mode === 'url') {
      const trimmed = url.trim();
      if (!trimmed) return;
      // A get.php URL carries an Xtream login: offer the richer mode.
      const detected = /get\.php\?/.test(trimmed) && /username=/.test(trimmed)
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
      if (file) await submit({ mode, name, content: await file.text() });
      else toast(t('playlists.pickFileFirst'));
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
          {(!editing || editing.fields.includes(PlaylistEditField.NAME)) && (
            <TextField label={t('playlists.name')} value={name} onChange={setName} />
          )}
          {editing && editing.storedFields.some((field) => field !== PlaylistEditField.CONTENT) && (
            <p className="hint">{t('playlists.credentialsEditHint')}</p>
          )}
          {(!editing ? mode === 'xtream' : editShowsAny(
            PlaylistEditField.SERVER,
            PlaylistEditField.USERNAME,
            PlaylistEditField.PASSWORD,
          )) && (
            <>
              {(!editing || editing.fields.includes(PlaylistEditField.SERVER)) && (
                <TextField label={t('playlists.server')} value={server} onChange={setServer} />
              )}
              {(!editing || editing.fields.includes(PlaylistEditField.USERNAME)) && (
                <TextField label={t('playlists.username')} value={username} onChange={setUsername} autoComplete="username" />
              )}
              {(!editing || editing.fields.includes(PlaylistEditField.PASSWORD)) && (
                <TextField label={t('playlists.password')} type="password" value={password} onChange={setPassword} autoComplete="current-password" />
              )}
              {!isEdit && (
                <p className="hint">{t('playlists.xtreamHint')}</p>
              )}
            </>
          )}
          {(!editing ? mode === 'url' : editShowsAny(
            PlaylistEditField.URL,
            PlaylistEditField.EPG_URL,
          )) && (
            <>
              {(!editing || editing.fields.includes(PlaylistEditField.URL)) && (
                <TextField label={t('playlists.url')} value={url} onChange={setUrl} />
              )}
              {(!editing || editing.fields.includes(PlaylistEditField.EPG_URL)) && (
                <TextField label={t('playlists.epgUrl')} value={epg} onChange={setEpg} />
              )}
            </>
          )}
          {(!editing ? mode === 'file' : editing.fields.includes(PlaylistEditField.CONTENT)) && (
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
