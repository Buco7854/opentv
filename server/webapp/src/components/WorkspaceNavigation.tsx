import { Link } from 'react-router';
import { Icon, IconName } from './Icons';
import './workspace-navigation.css';

export interface WorkspaceDestination {
  id: string;
  label: string;
  to: string;
  icon: IconName;
  count?: number;
}

/** Real section links remain visible, support browser navigation, and never hide labels in a select. */
export function WorkspaceNavigation({ label, active, destinations, className = '' }: {
  label: string;
  active: string;
  destinations: WorkspaceDestination[];
  className?: string;
}) {
  return <nav className={`workspace-navigation ${className}`.trim()} aria-label={label}>
    {destinations.map((destination) => <Link key={destination.id} to={destination.to}
      className="workspace-destination" aria-current={destination.id === active ? 'page' : undefined}>
      <Icon name={destination.icon} />
      <span>{destination.label}</span>
      {(destination.count ?? 0) > 0 && <small>{destination.count}</small>}
    </Link>)}
  </nav>;
}
