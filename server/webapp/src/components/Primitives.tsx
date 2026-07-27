// Shared UI primitives. Styling lives in webapp/src/index.css.

import { ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { t } from '../i18n';
import { Icon, IconName, iconMarkup } from './Icons';

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
        <h1 className={`truncate ${home ? 'type-headline-medium' : 'type-title-large'}`}>{title}</h1>
        {subtitle}
      </div>
      {actions}
    </div>
  );
}

export function IconBtn({ name, label, onClick, className = '', disabled, title }: {
  name: IconName;
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

const FOCUSABLE = [
  'a[href]', 'button:not([disabled])', 'input:not([disabled])', 'select:not([disabled])',
  'textarea:not([disabled])', '[tabindex]:not([tabindex="-1"])',
].join(',');

function useModalFocus(onEscape?: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const focusables = () => Array.from(ref.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
    (focusables()[0] ?? ref.current)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && onEscape) {
        onEscape();
        return;
      }
      if (e.key !== 'Tab') return;
      const items = focusables();
      const first = items[0];
      const last = items[items.length - 1];
      if (!first || !last) return;
      const active = document.activeElement;
      if (e.shiftKey && (active === first || !ref.current?.contains(active))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      opener?.focus?.();
    };
  }, [onEscape]);
  return ref;
}

export function Dialog({ title, onDismiss, children, buttons, className = '', dismissible = true }: {
  title: ReactNode;
  onDismiss: () => void;
  children?: ReactNode;
  buttons?: ReactNode;
  className?: string;
  dismissible?: boolean;
}) {
  const titleId = useId();
  const ref = useModalFocus(dismissible ? onDismiss : undefined);
  return createPortal(
    <div
      className="scrim"
      onClick={(e) => { if (dismissible && e.target === e.currentTarget) onDismiss(); }}
    >
      <div
        ref={ref}
        className={`dialog ${className}`.trim()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <h2 id={titleId} className="type-headline-small">{title}</h2>
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
  const sheetRef = useModalFocus(onDismiss);
  const titleId = useId();
  const drag = useRef<{ startY: number; dy: number } | null>(null);

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
      <div
        ref={sheetRef}
        className="sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={header ? titleId : undefined}
        tabIndex={-1}
      >
        <div
          id={titleId}
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

/**
 * What a toast is telling you - a rail colour, an icon and, for failures, an assertive
 * live region. Colour alone would say nothing to a screen reader or a colour-blind eye.
 */
export type ToastTone = 'info' | 'success' | 'error';

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastOptions {
  tone?: ToastTone;
  /** An action button; it keeps the toast up longer so the offer is reachable. */
  action?: ToastAction;
  durationMs?: number;
}

const TONE_ICON: Record<ToastTone, IconName | null> = {
  info: null,
  success: 'check',
  error: 'alert',
};

// Failures need reading time; a confirmation does not.
const TONE_DURATION_MS: Record<ToastTone, number> = {
  info: 3500,
  success: 3500,
  error: 6000,
};

/**
 * Imperative toast. This is the app's one transient-message surface: route failures here
 * through `reportError` in src/errors.ts rather than formatting messages at the call site.
 */
export function toast(message: string, { tone = 'info', action, durationMs }: ToastOptions = {}) {
  let rootEl = document.getElementById('toast-root');
  if (!rootEl) {
    rootEl = document.createElement('div');
    rootEl.id = 'toast-root';
    rootEl.setAttribute('aria-live', 'polite');
    document.body.append(rootEl);
  }
  const el = document.createElement('div');
  el.className = `toast ${tone}`;
  // Errors interrupt: they are the one kind the user may need to act on.
  el.setAttribute('role', tone === 'error' ? 'alert' : 'status');
  const glyph = TONE_ICON[tone];
  if (glyph) {
    const icon = document.createElement('span');
    icon.className = 'toast-icon';
    icon.innerHTML = iconMarkup(glyph);
    el.append(icon);
  }
  const text = document.createElement('span');
  text.className = 'toast-text';
  text.textContent = message;
  el.append(text);
  if (action) {
    const btn = document.createElement('button');
    btn.className = 'toast-action';
    btn.textContent = action.label;
    btn.onclick = () => { action.onClick(); el.remove(); };
    el.append(btn);
  }
  rootEl.append(el);
  setTimeout(() => el.remove(), durationMs ?? (action ? 5000 : TONE_DURATION_MS[tone]));
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
        <input type="search" value={value} placeholder={placeholder} aria-label={placeholder}
               autoFocus={autoFocus} onChange={(e) => onChange(e.target.value)} />
        <Icon name="search" />
        {value && <IconBtn className="muted clear" name="close" label={t('common.clear')} onClick={() => onChange('')} />}
      </div>
    </div>
  );
}

const optionsOf = (container: HTMLElement | null) =>
  Array.from(container?.querySelectorAll<HTMLElement>('button:not([disabled])') ?? []);

function usePopupNavigation(selectedIndex = 0) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const items = optionsOf(ref.current);
    (items[selectedIndex] ?? items[0])?.focus();
  }, [selectedIndex]);
  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const items = optionsOf(ref.current);
    if (items.length === 0) return;
    const current = items.indexOf(document.activeElement as HTMLElement);
    const next = {
      ArrowDown: (current + 1) % items.length,
      ArrowUp: (current - 1 + items.length) % items.length,
      Home: 0,
      End: items.length - 1,
    }[e.key];
    if (next === undefined) return;
    e.preventDefault();
    items[next]?.focus();
  };
  return { ref, onKeyDown };
}

export interface MenuOption {
  icon?: IconName;
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
  const { ref: menuRef, onKeyDown } = usePopupNavigation();

  useLayoutEffect(() => {
    const menu = menuRef.current;
    if (!menu) return;
    const rect = anchor.getBoundingClientRect();
    const left = Math.max(8, Math.min(rect.right - menu.offsetWidth, window.innerWidth - menu.offsetWidth - 8));
    const below = rect.bottom + 4;
    const top = below + menu.offsetHeight > window.innerHeight - 8
      ? Math.max(8, rect.top - menu.offsetHeight - 4)
      : below;
    menu.style.left = `${left}px`;
    menu.style.top = `${top}px`;
    menu.classList.add('placed');
  }, [anchor, menuRef]);

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
      anchor.focus();
    };
  }, [anchor, onDismiss]);

  return createPortal(
    <div ref={menuRef} className="menu-popover" role="menu" onKeyDown={onKeyDown}>
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

/** Close on Escape or a pointer down outside the returned ref - shared by the select components. */
function useDismiss(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => { if (!ref.current?.contains(e.target as Node)) close(); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, close]);
  return ref;
}

/** The list of options shown under either select. */
function SelectMenu<T extends string | number>({ options, selected, onPick, id }: {
  options: [T, string][]; selected: T; onPick: (value: T) => void; id?: string;
}) {
  const { ref, onKeyDown } = usePopupNavigation(options.findIndex(([value]) => value === selected));
  return (
    <div ref={ref} id={id} className="select-menu" role="listbox" onKeyDown={onKeyDown}>
      {options.map(([value, text]) => (
        <button key={String(value)} role="option" aria-selected={value === selected}
                className={`menu-option${value === selected ? ' selected' : ''}`}
                onClick={() => onPick(value)}>
          {text}
        </button>
      ))}
    </div>
  );
}

/** Form select: filled trigger with a floating label, option menu anchored under it. */
export function SelectField<T extends string | number>({ label, options, selected, onSelect }: {
  label: string;
  options: [T, string][];
  selected: T;
  onSelect: (value: T) => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useDismiss(open, () => setOpen(false));
  const labelId = useId();
  const menuId = useId();
  return (
    <div ref={rootRef} className="field select">
      <button type="button" className="select-trigger" aria-haspopup="listbox" aria-expanded={open}
              aria-labelledby={labelId} aria-controls={open ? menuId : undefined}
              onClick={() => setOpen((o) => !o)}>
        <span className="truncate">{options.find(([value]) => value === selected)?.[1] ?? ''}</span>
        <Icon name={open ? 'expandLess' : 'expandMore'} />
      </button>
      <label id={labelId}>{label}</label>
      {open && (
        <SelectMenu id={menuId} options={options} selected={selected}
                    onPick={(v) => { setOpen(false); onSelect(v); }} />
      )}
    </div>
  );
}

/** Compact inline dropdown (no label), for list rows like the watch-together roster. */
export function Select<T extends string | number>({ options, selected, onSelect, ariaLabel }: {
  options: [T, string][];
  selected: T;
  onSelect: (value: T) => void;
  ariaLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useDismiss(open, () => setOpen(false));
  const menuId = useId();
  return (
    <div ref={rootRef} className="select-dropdown">
      <button type="button" className="select-trigger" aria-haspopup="listbox" aria-expanded={open}
              aria-controls={open ? menuId : undefined}
              aria-label={ariaLabel} onClick={() => setOpen((o) => !o)}>
        <span className="truncate">{options.find(([value]) => value === selected)?.[1] ?? ''}</span>
        <Icon name={open ? 'expandLess' : 'expandMore'} />
      </button>
      {open && (
        <SelectMenu id={menuId} options={options} selected={selected}
                    onPick={(v) => { setOpen(false); onSelect(v); }} />
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
                aria-pressed={value === selected}
                onClick={() => onSelect(value)}>
          {label}
        </button>
      ))}
    </div>
  );
}

export const Spinner = () => <div className="spinner" />;
