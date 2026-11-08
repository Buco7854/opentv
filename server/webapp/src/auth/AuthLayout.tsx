import { ReactNode } from 'react';
import { Icon } from '../components/Icons';
import { authText as tx } from './copy';
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
      <div className="auth-container">
        <div className="auth-brand">
          <span className="mark"><Icon name="liveTv" /></span>
          <span className="word">OpenTV</span>
        </div>
        <div className="auth-server"><Icon name="liveTv" /><span>{tx('serverContext')}</span><strong>{window.location.host}</strong></div>
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
