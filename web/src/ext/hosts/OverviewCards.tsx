import { createElement, type ReactNode } from 'react';
import { CORE_OVERVIEW_COLUMN } from '../core/overview';
import { ExtensionBoundary } from '../ExtensionBoundary';
import type { SlotEntry } from '../registry';
import type { OverviewCardContribution } from '../types';
import { useContributions } from '../useContributions';

type Entry = SlotEntry<OverviewCardContribution>;

/**
 * A stat tile's crash: the small inline mark, as a callout does not fit the stat grid, in a tile's own dt and dd,
 * the markup the grid's <dl> may hold (ui/Stats.tsx StatCell).
 */
const statFrame = (entry: Entry) => (mark: ReactNode) => (
  <div className="v2-stat">
    <dt className="v2-stat__label">{entry.extension.title}</dt>
    <dd className="v2-stat__value">{mark}</dd>
  </div>
);

/** A card in its boundary. */
function OverviewCard({ entry }: { entry: Entry }) {
  const stat = entry.contribution.placement === 'stat';
  return (
    <ExtensionBoundary entry={entry} fallback={stat ? 'inline' : 'callout'} frame={stat ? statFrame(entry) : undefined}>
      {createElement(entry.contribution.component)}
    </ExtensionBoundary>
  );
}

/** The fleet summary's stat tiles the role may see, core's and extensions' in order. */
export function OverviewStats() {
  return useContributions('overview.card')
    .filter((e) => e.contribution.placement === 'stat')
    .map((entry) => <OverviewCard key={entry.key} entry={entry} />);
}

/**
 * The card rows under the fleet summary. In order, a `main` card starts a row and the `aside` cards after it stack
 * in that row's aside column (an aside before any main gets a row of its own).
 */
export function OverviewRows() {
  const rows: { main?: Entry; asides: Entry[] }[] = [];
  for (const entry of useContributions('overview.card')) {
    const { placement } = entry.contribution;
    if (placement === 'main') rows.push({ main: entry, asides: [] });
    else if (placement === 'aside') {
      if (!rows.length) rows.push({ asides: [] });
      rows[rows.length - 1].asides.push(entry);
    }
  }
  return rows.map(({ main, asides }) => {
    const head = asides[0];
    // By configuration keeps its column's width wherever it sits in the column.
    const core = asides.find((e) => e.extension.core && CORE_OVERVIEW_COLUMN[e.contribution.id]);
    const column = (core && CORE_OVERVIEW_COLUMN[core.contribution.id]) || 'v2-ov-side';
    return (
      <div key={(main ?? head).key} className="v2-ov-row">
        {main ? (
          <div className="v2-ov-main">
            <OverviewCard entry={main} />
          </div>
        ) : null}
        {asides.length ? (
          <div className={column}>
            {asides.map((entry) => (
              <OverviewCard key={entry.key} entry={entry} />
            ))}
          </div>
        ) : null}
      </div>
    );
  });
}
