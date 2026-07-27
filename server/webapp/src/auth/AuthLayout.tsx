import { ReactNode } from 'react';
import { Icon } from '../components/Icons';
import './auth.css';

/**
 * Shell shared by the authentication screens and by the pre-authentication
 * states rendered from AuthProvider. Living outside the lazily loaded screen
 * chunk keeps the stylesheet available before any screen is fetched.
 */
export function AuthLayout({ eyebrow, title, subtitle, children, footer }: {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <main className="auth-page">
      <div className="flex w-full max-w-[420px] flex-col">
        <div className="auth-brand">
          <span className="mark"><Icon name="liveTv" /></span>
          <span className="word">OpenTV</span>
        </div>
        <header className="auth-head">
          {eyebrow && <div className="eyebrow">{eyebrow}</div>}
          <h1>{title}</h1>
          {subtitle && <p>{subtitle}</p>}
        </header>
        {children}
        {footer && <div className="auth-foot">{footer}</div>}
      </div>
    </main>
  );
}
