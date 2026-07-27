// Settings. Playback prefs are per-browser; network + downloads settings live
// on the server. Mirrors SettingsScreen.kt.

import { useEffect, useRef, useState } from 'react';
import { api, Settings } from '../api';
import { Icon, IconName } from '../components/Icons';
import { Segmented, Spinner, TextField, ScreenHeader } from '../components/Primitives';
import { GENERIC, reportError } from '../errors';
import { Language, languageSetting, MessageKey, t } from '../i18n';
import { applyTheme, prefs, Theme } from '../preferences';
import { useAuth } from '../auth/AuthProvider';

const USER_AGENT_PRESETS: [string, string][] = [
  ['', ''],
  ['VLC/3.0.20 LibVLC/3.0.20', 'VLC'],
  ['IPTVSmartersPlayer', 'IPTV Smarters'],
  ['Kodi/20.0 (Linux; Android) Inputstream.adaptive', 'Kodi'],
  ['TiviMate/4.7.0 (Android)', 'TiviMate'],
];

type SectionId = 'appearance' | 'playback' | 'downloads' | 'network' | 'about';

const SECTIONS: { id: SectionId; icon: IconName }[] = [
  { id: 'appearance', icon: 'aspect' },
  { id: 'playback', icon: 'play' },
  { id: 'downloads', icon: 'download' },
  { id: 'network', icon: 'refresh' },
  { id: 'about', icon: 'person' },
];

const sectionLabel = (id: SectionId) => t(`settings.${id}` as MessageKey);

export function SettingsScreen() {
  const { user } = useAuth();
  const admin = user?.role === 'ADMIN';
  const [server, setServer] = useState<Settings | null>(null);
  const [seek, setSeek] = useState(prefs.seekSeconds);
  const [resize, setResize] = useState(prefs.resizeMode);
  const [theme, setTheme] = useState<Theme>(prefs.theme);
  const [language, setLanguage] = useState<Language>(languageSetting.get());
  const [current, setCurrent] = useState<SectionId>('appearance');
  const saveTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const pendingSettings = useRef<Settings | null>(null);
  const accepted = useRef<Settings | null>(null);

  useEffect(() => {
    if (!admin) {
      accepted.current = null;
      setServer(null);
      return;
    }
    api.settings()
      .then((next) => {
        accepted.current = next;
        setServer(next);
      })
      .catch((cause) => reportError(cause, { [GENERIC]: () => t('settings.loadFailed') }));
  }, [admin]);

  useEffect(() => () => {
    clearTimeout(saveTimer.current);
    const pending = pendingSettings.current;
    pendingSettings.current = null;
    // Last write on the way out: nothing is left to render an error into, and the tab is
    // already closing. Losing it silently is the only option, so keep it in the log.
    if (pending) void api.saveSettings(pending, true).catch(console.warn);
  }, []);

  // Debounced persist so typing a custom User-Agent doesn't spam the server.
  const saveServer = (next: Settings, immediate = false) => {
    setServer(next);
    clearTimeout(saveTimer.current);
    pendingSettings.current = next;
    const run = () => {
      pendingSettings.current = null;
      return api.saveSettings(next)
        .then(() => { accepted.current = next; })
        .catch((cause: unknown) => {
          setServer(accepted.current);
          reportError(cause);
        });
    };
    if (immediate) void run(); else saveTimer.current = setTimeout(() => void run(), 500);
  };

  const visibleSections = admin
    ? SECTIONS
    : SECTIONS.filter(({ id }) => id !== 'downloads' && id !== 'network');
  const sectionOptions: [SectionId, string][] = visibleSections.map(({ id }) => [id, sectionLabel(id)]);

  return (
    <>
      <ScreenHeader title={t('settings.title')} />

      <div className="mx-3 mt-1 mb-2 md:hidden">
        <Segmented className="scroll" options={sectionOptions} selected={current} onSelect={setCurrent} />
      </div>

      <div className="mx-auto flex max-w-[880px] items-start gap-8 px-4">
        <nav className="sticky top-[92px] hidden w-[220px] flex-none flex-col gap-1 self-start pt-4 md:flex">
          {visibleSections.map(({ id, icon }) => (
            <button key={id} className={`panel-row${current === id ? ' selected' : ''}`}
                    onClick={() => setCurrent(id)}>
              <Icon name={icon} />
              <div className="body"><div className="name">{sectionLabel(id)}</div></div>
            </button>
          ))}
        </nav>

        <div className="min-w-0 max-w-[560px] flex-1">
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

          {admin && current === 'downloads' && (
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

          {admin && current === 'network' && (
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
              {server !== null && (
                <>
                  <div>
                    <div className="setting-label">{t('settings.pageSize')}</div>
                    <Hint>{t('settings.pageSizeHint', { count: server.pageSize })}</Hint>
                  </div>
                  <div className="divider" />
                </>
              )}
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
