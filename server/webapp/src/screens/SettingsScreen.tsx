// Settings. Playback prefs are per-browser; network + downloads settings live
// on the server. Mirrors SettingsScreen.kt.

import { useEffect, useRef, useState } from 'react';
import { api, Settings } from '../api';
import { Icon } from '../components/Icons';
import { Segmented, snackbar, Spinner, TextField, ScreenHeader } from '../components/Primitives';
import { Language, languageSetting, MessageKey, t } from '../i18n';
import { applyTheme, prefs, Theme } from '../preferences';

const USER_AGENT_PRESETS: [string, string][] = [
  ['', ''],
  ['VLC/3.0.20 LibVLC/3.0.20', 'VLC'],
  ['IPTVSmartersPlayer', 'IPTV Smarters'],
  ['Kodi/20.0 (Linux; Android) Inputstream.adaptive', 'Kodi'],
  ['TiviMate/4.7.0 (Android)', 'TiviMate'],
];

type SectionId = 'appearance' | 'playback' | 'downloads' | 'network' | 'about';

const SECTIONS: { id: SectionId; icon: string }[] = [
  { id: 'appearance', icon: 'aspect' },
  { id: 'playback', icon: 'play' },
  { id: 'downloads', icon: 'download' },
  { id: 'network', icon: 'refresh' },
  { id: 'about', icon: 'person' },
];

const sectionLabel = (id: SectionId) => t(`settings.${id}` as MessageKey);

export function SettingsScreen() {
  const [server, setServer] = useState<Settings | null>(null);
  const [seek, setSeek] = useState(prefs.seekSeconds);
  const [resize, setResize] = useState(prefs.resizeMode);
  const [theme, setTheme] = useState<Theme>(prefs.theme);
  const [language, setLanguage] = useState<Language>(languageSetting.get());
  const [current, setCurrent] = useState<SectionId>('appearance');
  const saveTimer = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => { api.settings().then(setServer).catch(() => snackbar(t('settings.loadFailed'))); }, []);

  // Debounced persist so typing a custom User-Agent doesn't spam the server.
  const saveServer = (next: Settings, immediate = false) => {
    setServer(next);
    clearTimeout(saveTimer.current);
    const run = () => api.saveSettings(next).catch((e: Error) => snackbar(e.message));
    if (immediate) run(); else saveTimer.current = setTimeout(run, 500);
  };

  const sectionOptions: [SectionId, string][] = SECTIONS.map(({ id }) => [id, sectionLabel(id)]);

  return (
    <>
      <ScreenHeader title={t('settings.title')} />

      <div className="settings-tabs">
        <Segmented className="scroll" options={sectionOptions} selected={current} onSelect={setCurrent} />
      </div>

      <div className="settings-shell">
        <nav className="settings-nav">
          {SECTIONS.map(({ id, icon }) => (
            <button key={id} className={`panel-row${current === id ? ' selected' : ''}`}
                    onClick={() => setCurrent(id)}>
              <Icon name={icon} />
              <div className="body"><div className="name">{sectionLabel(id)}</div></div>
            </button>
          ))}
        </nav>

        <div className="settings-main">
          {current === 'appearance' && (
            <Section title={sectionLabel('appearance')}>
              <ChipSetting
                label={t('settings.theme')}
                options={[['system', t('settings.themeAuto')], ['dark', t('settings.themeDark')], ['light', t('settings.themeLight')]]}
                selected={theme}
                onSelect={(v) => { setTheme(v as Theme); prefs.theme = v as Theme; applyTheme(v as Theme); }}
                hint={t('settings.themeHint')}
              />
              <div className="divider" />
              <ChipSetting
                label={t('settings.language')}
                options={[['auto', t('settings.languageAuto')], ['en', 'English'], ['fr', 'Français']]}
                selected={language}
                onSelect={(v) => { setLanguage(v as Language); languageSetting.set(v as Language); }}
              />
            </Section>
          )}

          {current === 'playback' && (
            <Section title={sectionLabel('playback')}>
              <ChipSetting
                label={t('settings.seekStep')}
                options={[[5, '5 s'], [10, '10 s'], [30, '30 s']]}
                selected={seek}
                onSelect={(v) => { setSeek(v); prefs.seekSeconds = v; }}
              />
              <div className="divider" />
              <ChipSetting
                label={t('settings.scaling')}
                options={[['fit', t('settings.scaleFit')], ['zoom', t('settings.scaleZoom')], ['stretch', t('settings.scaleStretch')]]}
                selected={resize}
                onSelect={(v) => { setResize(v); prefs.resizeMode = v; }}
                hint={t('settings.playbackHint')}
              />
            </Section>
          )}

          {current === 'downloads' && (
            server === null ? <Spinner /> : (
              <Section title={sectionLabel('downloads')}>
                <ChipSetting
                  label={t('settings.simultaneous')}
                  options={[[1, '1'], [2, '2'], [3, '3']]}
                  selected={server.downloadLimit}
                  onSelect={(v) => saveServer({ ...server, downloadLimit: v }, true)}
                  hint={t('settings.downloadsHint')}
                />
              </Section>
            )
          )}

          {current === 'network' && (
            server === null ? <Spinner /> : (
              <Section title={sectionLabel('network')}>
                <div>
                  <div className="setting-label">{t('settings.userAgent')}</div>
                  <Segmented
                    options={USER_AGENT_PRESETS.map(([v, label]) =>
                      [v, label || t('settings.uaDefault')] as [string, string])}
                    selected={USER_AGENT_PRESETS.some(([v]) => v === server.userAgent) ? server.userAgent : ' '}
                    onSelect={(v) => saveServer({ ...server, userAgent: v }, true)}
                  />
                  <div className="mt-2.5">
                    <TextField label={t('settings.customUserAgent')} value={server.userAgent}
                               onChange={(v) => saveServer({ ...server, userAgent: v })} />
                  </div>
                  <Hint>{t('settings.networkHint')}</Hint>
                </div>
              </Section>
            )
          )}

          {current === 'about' && (
            <Section title={sectionLabel('about')}>
              <div>
                <div className="setting-label">{t('settings.pageSize')}</div>
                <Hint>{t('settings.pageSizeHint', { count: server?.pageSize ?? 50 })}</Hint>
              </div>
              <div className="divider" />
              <div>
                <div className="setting-label">{t('settings.access')}</div>
                <Hint>{t('settings.accessHint')}</Hint>
              </div>
            </Section>
          )}
        </div>
      </div>
      <div className="h-8" />
    </>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="settings-section">
      <div className="label">{title}</div>
      <div className="settings-card">{children}</div>
    </div>
  );
}

function Hint({ children }: { children: React.ReactNode }) {
  return <p className="setting-hint">{children}</p>;
}

function ChipSetting<T extends string | number>({ label, options, selected, onSelect, hint }: {
  label: string;
  options: [T, string][];
  selected: T;
  onSelect: (value: T) => void;
  hint?: string;
}) {
  return (
    <div>
      <div className="setting-label">{label}</div>
      <Segmented options={options} selected={selected} onSelect={onSelect} />
      {hint && <Hint>{hint}</Hint>}
    </div>
  );
}
