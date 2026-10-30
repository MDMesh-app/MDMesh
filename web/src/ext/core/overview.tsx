// Core's Overview cards and stat tiles. Their bodies read the page's one data hook (useOverviewData), so the page
// builds them and hands them down in a page-private context; the contributions here only pick theirs out. Core and
// extension cards then share one host: ordering, placement, `requires` and the boundary. The fleet summary at the
// top is the page's frame for the stat tiles, not a card of its own.
import { createContext, useContext, type ReactNode } from 'react';
import { coreComponent, type OverviewCardContribution } from '../types';
import { ORDER } from './order';

type CoreCard = keyof typeof ORDER.overviewCard;
type CoreStat = keyof typeof ORDER.overviewStat;
type CoreParts = Record<CoreCard | CoreStat, ReactNode>;

const CoreOverviewParts = createContext<Partial<CoreParts> | null>(null);

/** The page's bodies for core's cards (pages/OverviewPage.tsx); null for one it has nothing to show in. */
export function CoreOverviewProvider({ parts, children }: { parts: CoreParts; children: ReactNode }) {
  return <CoreOverviewParts.Provider value={parts}>{children}</CoreOverviewParts.Provider>;
}

function corePart(key: CoreCard | CoreStat) {
  function CoreOverviewBody() {
    return useContext(CoreOverviewParts)?.[key] ?? null;
  }
  return coreComponent(CoreOverviewBody);
}

const card = (id: string, key: CoreCard, placement: 'main' | 'aside'): OverviewCardContribution => ({
  slot: 'overview.card',
  id,
  order: ORDER.overviewCard[key],
  placement,
  component: corePart(key),
});

const stat = (id: string, key: CoreStat): OverviewCardContribution => ({
  slot: 'overview.card',
  id,
  order: ORDER.overviewStat[key],
  placement: 'stat',
  component: corePart(key),
});

/** Every role sees them: the Overview's reads need no permission (core/access.ts). */
export const CORE_OVERVIEW_CARDS: readonly OverviewCardContribution[] = [
  stat('sync', 'sync'),
  stat('rollout', 'rollout'),
  card('needs-attention', 'needsAttention', 'main'),
  card('by-configuration', 'byConfiguration', 'aside'),
  card('recent-activity', 'recentActivity', 'main'),
  card('server', 'server', 'aside'),
];

/**
 * The aside column's class when it holds this core card, where it is not the default `v2-ov-side`: By
 * configuration's column is the padded card's width in the design, wider than the Server column (OverviewPage.css).
 */
export const CORE_OVERVIEW_COLUMN: Readonly<Record<string, string>> = { 'by-configuration': 'v2-ov-aside' };
