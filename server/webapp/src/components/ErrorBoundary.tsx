import { Component, ReactNode } from 'react';
import { useLocation } from 'react-router';
import { t } from '../i18n';
import { EmptyState } from './Common';
import { Icon } from './Icons';

interface Props { children: ReactNode }
interface State { failed: boolean }

export class AppErrorBoundary extends Component<Props, State> {
  override state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  override render() {
    if (!this.state.failed) return this.props.children;
    return (
      <EmptyState
        title={t('common.crashTitle')}
        subtitle={t('common.crashBody')}
        action={
          <button className="btn" onClick={() => location.reload()}>
            <Icon name="refresh" />{t('common.reload')}
          </button>
        }
      >
        <Icon name="alert" />
      </EmptyState>
    );
  }
}

/**
 * A feature crash belongs to the route that threw it. The dock remains usable outside the
 * boundary; keying by location lets navigation recover instead of pinning every later route to
 * the crash screen until the whole tab is reloaded.
 */
export function RouteErrorBoundary({ children }: Props) {
  const { key } = useLocation();
  return <AppErrorBoundary key={key}>{children}</AppErrorBoundary>;
}
