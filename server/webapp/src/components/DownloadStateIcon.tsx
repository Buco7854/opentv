// Per-state download button: idle downloads, running pauses (right-click
// deletes), paused resumes, done deletes. Mirrors DownloadStateIcon.kt.

import { useState } from 'react';
import { api, Download, DownloadStatus } from '../api';
import { t } from '../i18n';
import { Icon } from './Icons';
import { ConfirmDialog, snackbar } from './Primitives';

function Ring({ fraction }: { fraction: number | null }) {
  const c = 2 * Math.PI * 10;
  return (
    <svg viewBox="0 0 24 24" className={`icon dl-ring${fraction == null ? ' spin' : ''}`}>
      <circle className="track" cx="12" cy="12" r="10" />
      <circle
        className="bar" cx="12" cy="12" r="10"
        strokeDasharray={c} strokeDashoffset={c * (1 - (fraction ?? 0.25))}
      />
    </svg>
  );
}

export function DownloadStateIcon({ state, onDownload, onChanged }: {
  state: Download | undefined;
  onDownload: () => Promise<{ message: string }>;
  onChanged: () => void;
}) {
  const [confirmDelete, setConfirmDelete] = useState(false);

  const act = (fn: () => Promise<unknown>) => async (e: React.MouseEvent) => {
    e.stopPropagation();
    try { await fn(); } catch (err) { snackbar((err as Error).message); }
    onChanged();
  };
  const openDelete = (e: React.MouseEvent) => { e.preventDefault(); e.stopPropagation(); setConfirmDelete(true); };

  let button;
  switch (state?.status) {
    case DownloadStatus.RUNNING:
    case DownloadStatus.QUEUED: {
      const fraction = state.totalBytes > 0 && state.status === DownloadStatus.RUNNING
        ? Math.min(1, state.downloadedBytes / state.totalBytes) : null;
      button = (
        <button className="icon-btn relative" aria-label={t('downloads.pauseAria')}
                title={t('downloads.pauseHint')}
                onClick={act(() => api.pauseDownload(state.id))} onContextMenu={openDelete}>
          <Ring fraction={fraction} />
          <Icon name="pause" className="dl-pause-glyph" />
        </button>
      );
      break;
    }
    case DownloadStatus.PAUSED:
      button = (
        <button className="icon-btn primary" aria-label={t('downloads.resumeAria')}
                title={t('downloads.resumeHint')}
                onClick={act(() => api.resumeDownload(state.id))} onContextMenu={openDelete}>
          <Icon name="play" />
        </button>
      );
      break;
    case DownloadStatus.DONE:
      button = (
        <button className="icon-btn primary" aria-label={t('downloads.downloaded')} title={t('downloads.downloadedHint')}
                onClick={openDelete}>
          <Icon name="downloadDone" />
        </button>
      );
      break;
    default:
      button = (
        <button className="icon-btn primary" aria-label={t('downloads.download')} title={t('downloads.download')}
                onClick={act(async () => snackbar((await onDownload()).message))}>
          <Icon name="download" />
        </button>
      );
  }

  return (
    <>
      {button}
      {confirmDelete && state && (
        <ConfirmDialog
          title={t('downloads.removeTitle')}
          message={state.status === DownloadStatus.DONE
            ? t('downloads.deleteFileMsg', { title: state.title })
            : t('downloads.deletePartialMsg', { title: state.title })}
          confirmLabel={t('common.delete')}
          onConfirm={async () => { await api.deleteDownload(state.id).catch(() => {}); onChanged(); }}
          onDismiss={() => setConfirmDelete(false)}
        />
      )}
    </>
  );
}
