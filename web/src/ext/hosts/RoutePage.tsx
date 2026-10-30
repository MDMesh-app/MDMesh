import { createElement } from 'react';
import { Card } from '../../ui/Card';
import { EmptyState } from '../../ui/EmptyState';
import { IconLock } from '../../ui/icons';
import { ExtensionBoundary } from '../ExtensionBoundary';
import type { SlotEntry } from '../registry';
import type { RoutePageContribution } from '../types';
import { useAccess } from '../useContributions';

/**
 * A page the signed-in role cannot use. Shown in place (not a redirect), so a shared deep link explains itself. A page
 * shows it too for a path its route's permission doesn't cover (the Configurations editor's /new without add_config).
 */
export function NoAccess() {
  return (
    <Card>
      <EmptyState icon={<IconLock size={18} />} title="You don’t have access to this page">
        Your role doesn’t include it. Ask an administrator if you need it.
      </EmptyState>
    </Card>
  );
}

/** The element of a `route.page` route: the page in its own boundary, when the signed-in user may see it. */
export function RoutePage({ entry }: { entry: SlotEntry<RoutePageContribution> }) {
  const access = useAccess()(entry);
  if (access === 'pending') return null; // the active plugins are still loading: decide once they are known
  if (access === 'denied') return <NoAccess />;
  return <ExtensionBoundary entry={entry} fallback="page">{createElement(entry.contribution.component)}</ExtensionBoundary>;
}
