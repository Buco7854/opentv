import { ReactNode } from 'react';
import { Icon } from '../components/Icons';
import { Segmented, Sheet } from '../components/Primitives';
import { t } from '../i18n';

export function MenuSheet({
  title, options, selected, onPick, onDismiss, container, emptyText, headerAction,
}: {
  title: string;
  options: string[];
  selected: number;
  onPick: (index: number) => void;
  onDismiss: () => void;
  container?: Element | null;
  emptyText?: string;
  headerAction?: ReactNode;
}) {
  return (
    <Sheet onDismiss={onDismiss} container={container}
           header={<><h3 className="sheet-title">{title}</h3>{headerAction}</>}>
      {options.length === 0 && (
        <p className="py-3 type-body-medium text-on-surface-variant">
          {emptyText ?? t('player.noTracks')}
        </p>
      )}
      {options.map((label, index) => (
        <button
          key={label + index}
          className={`menu-option${index === selected ? ' selected' : ''}`}
          onClick={() => { onDismiss(); onPick(index); }}
        >
          <span className="min-w-0 flex-1 truncate">{label}</span>
          {index === selected && <Icon name="check" className="ml-2 size-5 shrink-0" />}
        </button>
      ))}
    </Sheet>
  );
}

export type SubtitleStyle = { scale: number; style: string; bold: boolean };

/** Pure subtitle appearance controls; playback orchestration owns only the value. */
export function SubtitleStyleSheet({ value, onChange, onDismiss, container }: {
  value: SubtitleStyle;
  onChange: (next: SubtitleStyle) => void;
  onDismiss: () => void;
  container?: Element | null;
}) {
  return (
    <Sheet onDismiss={onDismiss} container={container}
           header={<h3 className="sheet-title">{t('player.subtitleStyle')}</h3>}>
      <div className="sub-preview">
        <span className={`cue cue-${value.style}${value.bold ? ' bold' : ''}`}
              style={{ fontSize: `${value.scale * 22}px` }}>
          <span className="cue-line">{t('player.subtitlePreview')}</span>
        </span>
      </div>

      <div className="sub-row-label">
        {t('player.subtitleSize', { pct: Math.round(value.scale * 100) })}
      </div>
      <input
        className="seek sub-slider"
        type="range"
        min={50}
        max={200}
        step={10}
        value={Math.round(value.scale * 100)}
        onChange={(event) => onChange({ ...value, scale: Number(event.target.value) / 100 })}
      />

      <div className="sub-row-label">{t('player.subtitleStyle')}</div>
      <Segmented
        options={[['outline', t('player.subtitleOutline')], ['background', t('player.subtitleBackground')]]}
        selected={value.style}
        onSelect={(next) => onChange({ ...value, style: String(next) })}
      />

      <div className="sub-row-label">{t('player.subtitleBold')}</div>
      <Segmented
        options={[['off', t('player.off')], ['on', t('player.on')]]}
        selected={value.bold ? 'on' : 'off'}
        onSelect={(next) => onChange({ ...value, bold: next === 'on' })}
      />
    </Sheet>
  );
}
