import { Component, ReactNode } from 'react';
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
