// Shared UI primitives. Styling lives in webapp/src/index.css.

import { ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { t } from '../i18n';
import { Icon } from './Icons';

export function ScreenHeader({ title, home, subtitle, onBack, actions }: {
  title: ReactNode;
  // Home variant uses the large headline style.
  home?: boolean;
  subtitle?: ReactNode;
  onBack?: () => void;
  actions?: ReactNode;
}) {
  return (
    <div className="topbar">
      {onBack && <IconBtn name="back" label={t('common.back')} onClick={onBack} />}
      <div className="titles">
        <h1 className={home ? 'home' : undefined}>{title}</h1>
        {subtitle}
      </div>
      {actions}
    </div>
  );
}

export function IconBtn({ name, label, onClick, className = '', disabled, title }: {
  name: string;
  label: string;
  onClick?: (e: React.MouseEvent) => void;
  className?: string;
  disabled?: boolean;
  title?: string;
}) {
  return (
    <button
      className={`icon-btn ${className}`.trim()}
      aria-label={label}
      title={title ?? label}
      disabled={disabled}
      onClick={(e) => { e.stopPropagation(); onClick?.(e); }}
    >
      <Icon name={name} />
    </button>
  );
}

export function Dialog({ title, onDismiss, children, buttons, className = '' }: {
  title: ReactNode;
  onDismiss: () => void;
  children?: ReactNode;
  buttons?: ReactNode;
  className?: string;
}) {
  return createPortal(
    <div className="scrim" onClick={(e) => { if (e.target === e.currentTarget) onDismiss(); }}>
      <div className={`dialog ${className}`.trim()}>
        <h2>{title}</h2>
        {children}
        {buttons && <div className="buttons">{buttons}</div>}
      </div>
    </div>,
    document.body,
  );
}

export function ConfirmDialog({ title, message, confirmLabel, onConfirm, onDismiss }: {
  title: string;
  message: string;
  confirmLabel: string;
  onConfirm: () => void;
  onDismiss: () => void;
}) {
  return (
    <Dialog
      title={title}
      onDismiss={onDismiss}
      buttons={
        <>
          <button className="btn text" onClick={onDismiss}>{t('common.cancel')}</button>
          <button className="btn danger-text" onClick={() => { onDismiss(); onConfirm(); }}>{confirmLabel}</button>
        </>
      }
    >
      <p className="type-body-medium text-on-surface-variant">{message}</p>
    </Dialog>
  );
}

/**
 * Adaptive overlay: bottom sheet (draggable to dismiss) on phones, centered
 * modal on desktop. `container` overrides the portal target so the fullscreen
 * player can stack sheets above its own frame.
 */
export function Sheet({ onDismiss, header, children, container }: {
  onDismiss: () => void;
  header?: ReactNode;
  children: ReactNode;
  container?: Element | null;
}) {
  const sheetRef = useRef<HTMLDivElement>(null);
  const drag = useRef<{ startY: number; dy: number } | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onDismiss(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onDismiss]);

  const onPointerDown = (e: React.PointerEvent) => {
    if (!window.matchMedia('(max-width: 719px)').matches) return;
    drag.current = { startY: e.clientY, dy: 0 };
    sheetRef.current?.classList.add('dragging');
    (e.target as Element).setPointerCapture?.(e.pointerId);
  };
  const onPointerMove = (e: React.PointerEvent) => {
    const state = drag.current;
    if (!state) return;
    state.dy = Math.max(0, e.clientY - state.startY);
    sheetRef.current!.style.transform = `translateY(${state.dy}px)`;
  };
  const onPointerEnd = () => {
    const state = drag.current;
    if (!state) return;
    drag.current = null;
    const el = sheetRef.current!;
    el.classList.remove('dragging');
    if (state.dy > 110) {
      onDismiss();
    } else {
      el.classList.add('settling');
      el.style.transform = '';
      setTimeout(() => el.classList.remove('settling'), 200);
    }
  };

  return createPortal(
    <>
      <div className="sheet-scrim" onClick={onDismiss} />
      <div ref={sheetRef} className="sheet" role="dialog" aria-modal="true">
        <div
          className={`sheet-head${header ? '' : ' bare'}`}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerEnd}
          onPointerCancel={onPointerEnd}
        >
          {header}
          <IconBtn name="close" label={t('common.close')} className="muted sheet-close" onClick={onDismiss} />
        </div>
        <div className="sheet-body">{children}</div>
      </div>
    </>,
    container ?? document.body,
  );
}

export function Pager({ page, pages, onPage }: {
  page: number;
  pages: number;
  onPage: (page: number) => void;
}) {
  if (pages <= 1) return null;
  return (
    <div className="pager">
      <IconBtn name="back" label={t('pager.prev')} className="muted"
               disabled={page <= 0} onClick={() => onPage(page - 1)} />
      <span className="label">{t('pager.page', { page: page + 1, pages })}</span>
      <IconBtn name="chevron" label={t('pager.next')} className="muted"
               disabled={page >= pages - 1} onClick={() => onPage(page + 1)} />
    </div>
  );
}

/** Imperative snackbar; an optional action button extends the visible time. */
export function snackbar(message: string, action?: { label: string; onClick: () => void; durationMs?: number }) {
  let rootEl = document.getElementById('snackbar-root');
  if (!rootEl) {
    rootEl = document.createElement('div');
    rootEl.id = 'snackbar-root';
    document.body.append(rootEl);
  }
  const el = document.createElement('div');
  el.className = 'snackbar';
  const text = document.createElement('span');
  text.textContent = message;
  el.append(text);
  if (action) {
    const btn = document.createElement('button');
    btn.className = 'snackbar-action';
    btn.textContent = action.label;
    btn.onclick = () => { action.onClick(); el.remove(); };
    el.append(btn);
  }
  rootEl.append(el);
  setTimeout(() => el.remove(), action?.durationMs ?? (action ? 5000 : 3500));
}

export function TextField({ label, value, onChange, type = 'text', autoFocus, autoComplete }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  autoFocus?: boolean;
  autoComplete?: string;
}) {
  const id = useId();
  return (
    <div className="field">
      <input
        id={id}
        type={type}
        value={value}
        placeholder=" "
        autoFocus={autoFocus}
        autoComplete={autoComplete}
        onChange={(e) => onChange(e.target.value)}
      />
      <label htmlFor={id}>{label}</label>
    </div>
  );
}

export function SearchField({ placeholder, value, onChange, autoFocus }: {
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  autoFocus?: boolean;
}) {
  return (
    <div className="search-wrap">
      <div className="field round">
        <input value={value} placeholder={placeholder} autoFocus={autoFocus}
               onChange={(e) => onChange(e.target.value)} />
        <Icon name="search" />
        {value && <IconBtn className="muted clear" name="close" label={t('common.clear')} onClick={() => onChange('')} />}
      </div>
    </div>
  );
}

export interface MenuOption {
  icon?: string;
  label: string;
  danger?: boolean;
  onSelect: () => void;
}

/** Anchored dropdown menu; flips above the anchor when there's no room below. */
export function Menu({ anchor, options, onDismiss }: {
  anchor: HTMLElement;
  options: MenuOption[];
  onDismiss: () => void;
}) {
  const menuRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);

  useLayoutEffect(() => {
    const rect = anchor.getBoundingClientRect();
    const menu = menuRef.current!;
    const left = Math.max(8, Math.min(rect.right - menu.offsetWidth, window.innerWidth - menu.offsetWidth - 8));
    let top = rect.bottom + 4;
    if (top + menu.offsetHeight > window.innerHeight - 8) {
      top = Math.max(8, rect.top - menu.offsetHeight - 4);
    }
    setPos({ left, top });
  }, [anchor]);

  useEffect(() => {
    const onDown = (e: PointerEvent) => {
      const target = e.target as Node;
      // Anchor toggles the menu itself; don't double-dismiss.
      if (!menuRef.current?.contains(target) && !anchor.contains(target)) onDismiss();
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onDismiss(); };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onDismiss, true);
    window.addEventListener('resize', onDismiss);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('scroll', onDismiss, true);
      window.removeEventListener('resize', onDismiss);
    };
  }, [anchor, onDismiss]);

  return createPortal(
    <div ref={menuRef} className="menu-popover" role="menu"
         style={pos ? pos : { visibility: 'hidden' }}>
      {options.map((option) => (
        <button key={option.label} role="menuitem"
                className={`menu-option${option.danger ? ' danger' : ''}`}
                onClick={() => { onDismiss(); option.onSelect(); }}>
          {option.icon && <Icon name={option.icon} className="sm" />}
          {option.label}
        </button>
      ))}
    </div>,
    document.body,
  );
}

/** Select field with floating label and an option menu anchored under it. */
export function SelectField<T extends string | number>({ label, options, selected, onSelect, className }: {
  /** Omit for a compact inline select (no floating label), e.g. inside a list row. */
  label?: string;
  options: [T, string][];
  selected: T;
  onSelect: (value: T) => void;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div ref={rootRef} className={`field select${label ? '' : ' compact'}${className ? ` ${className}` : ''}`}>
      <button type="button" className="select-trigger" aria-haspopup="listbox" aria-expanded={open}
              onClick={() => setOpen((o) => !o)}>
        <span className="truncate">{options.find(([value]) => value === selected)?.[1] ?? ''}</span>
        <Icon name={open ? 'expandLess' : 'expandMore'} />
      </button>
      {label && <label>{label}</label>}
      {open && (
        <div className="select-menu" role="listbox">
          {options.map(([value, text]) => (
            <button key={String(value)} role="option" aria-selected={value === selected}
                    className={`menu-option${value === selected ? ' selected' : ''}`}
                    onClick={() => { setOpen(false); onSelect(value); }}>
              {text}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function Segmented<T extends string | number>({ options, selected, onSelect, className }: {
  options: [T, string][];
  selected: T;
  onSelect: (value: T) => void;
  className?: string;
}) {
  return (
    <div className={`chip-set${className ? ` ${className}` : ''}`}>
      {options.map(([value, label]) => (
        <button key={String(value)} className={`chip${value === selected ? ' selected' : ''}`}
                onClick={() => onSelect(value)}>
          {label}
        </button>
      ))}
    </div>
  );
}

export const Spinner = () => <div className="spinner" />;
