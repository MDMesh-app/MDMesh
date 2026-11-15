import { createElement, Fragment, type ReactNode } from 'react';
import { GridCell } from '../../ui/Grid';
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
 * The card rows under the fleet summary, as cells of the Overview's 12-column grid. In order, a `main` card starts a
 * row (8 columns) and the `aside` cards after it stack in that row's 4-column aside, the last one stretched so both
 * columns end on one line; an aside before any main starts a row of its own. A main card with no asides, or asides
 * that render nothing, takes the full width.
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
  return rows.map(({ main, asides }) => (
    <Fragment key={(main ?? asides[0]).key}>
      {main ? (
        <GridCell span={asides.length ? 8 : 12} className="v2-ov-main">
          <OverviewCard entry={main} />
        </GridCell>
      ) : null}
      {asides.length ? (
        <GridCell span={main ? 4 : 12} className="v2-ov-aside">
          {asides.map((entry) => (
            <OverviewCard key={entry.key} entry={entry} />
          ))}
        </GridCell>
      ) : null}
    </Fragment>
  ));
}
