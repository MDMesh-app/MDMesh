import { createElement, type ReactNode } from 'react';
import { Grid, GridCell } from '../../ui/Grid';
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

const statEntries = (entries: readonly Entry[], extensionsOnly: boolean) =>
  entries.filter((e) => e.contribution.placement === 'stat' && !(extensionsOnly && e.extension.core));

/**
 * The fleet summary's stat tiles the role may see, core's and extensions' in order. `extensionsOnly`: just the
 * extensions' (the first-run Overview has no fleet to sum up, but an extension's stat may be about something else).
 */
export function OverviewStats({ extensionsOnly = false }: { extensionsOnly?: boolean }) {
  return statEntries(useContributions('overview.card'), extensionsOnly).map((entry) => (
    <OverviewCard key={entry.key} entry={entry} />
  ));
}

/** Whether any extension contributes a stat tile the role may see. */
export function useHasExtensionStats(): boolean {
  return statEntries(useContributions('overview.card'), true).length > 0;
}

/**
 * The cards under the fleet summary, in two columns of the Overview's 12-column grid: the `main` cards stacked in the
 * wide one (8), the `aside` cards in the narrow one (4), each in contribution order. Each card is as tall as its
 * content and each column as tall as its cards, so neither column holds a stretched, half-empty card. With no asides
 * the main column takes the full width (and the other way round).
 */
export function OverviewRows() {
  const mains: Entry[] = [];
  const asides: Entry[] = [];
  for (const entry of useContributions('overview.card')) {
    if (entry.contribution.placement === 'main') mains.push(entry);
    else if (entry.contribution.placement === 'aside') asides.push(entry);
  }
  return (
    <Grid>
      {mains.length ? (
        <GridCell span={asides.length ? 8 : 12} className="v2-ov-main">
          {mains.map((entry) => (
            <OverviewCard key={entry.key} entry={entry} />
          ))}
        </GridCell>
      ) : null}
      {asides.length ? (
        <GridCell span={mains.length ? 4 : 12} className="v2-ov-aside">
          {asides.map((entry) => (
            <OverviewCard key={entry.key} entry={entry} />
          ))}
        </GridCell>
      ) : null}
    </Grid>
  );
}
