// "/": forwards into the active playlist, or greets a fresh install.

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import { EmptyState } from '../components/Common';
import { Icon } from '../components/Icons';
import { Spinner } from '../components/Primitives';
import { PlaylistDialog } from '../components/PlaylistDialog';
import { useAsync } from '../hooks';
import { t } from '../i18n';
import { prefs } from '../preferences';

export function HomeScreen() {
  const navigate = useNavigate();
  const { data: playlists, reload } = useAsync(() => api.playlists(), []);
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    if (!playlists?.length) return;
    const active = playlists.find((p) => p.id === prefs.activePlaylist) ?? playlists[0];
    prefs.activePlaylist = active.id;
    navigate(`/browse/${active.id}`, { replace: true });
  }, [playlists, navigate]);

  if (playlists === null) return <Spinner />;
  if (playlists.length > 0) return null; // redirecting

  return (
    <>
      <div className="flex min-h-[75vh] flex-col items-center justify-center">
        <EmptyState title={t('home.welcome')} subtitle={t('home.welcomeSub')}>
          <div className="empty-home-art"><Icon name="playlist" /></div>
        </EmptyState>
        <button className="btn" style={{ width: 'auto' }} onClick={() => setAdding(true)}>
          <Icon name="add" />{t('playlists.add')}
        </button>
      </div>
      {adding && (
        <PlaylistDialog
          editing={null}
          onDismiss={() => setAdding(false)}
          onDone={(saved) => {
            setAdding(false);
            prefs.activePlaylist = saved.id;
            reload();
            navigate(`/browse/${saved.id}`);
          }}
        />
      )}
    </>
  );
}
