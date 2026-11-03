import { createElement, type ReactNode } from 'react';
import { Card, CardHeader } from '../../ui/Card';
import type { SectionNavItem } from '../../ui/SectionNav';
import { ExtensionBoundary } from '../ExtensionBoundary';
import { sectionAnchor, type SlotEntry } from '../registry';
import type { SettingsSectionContribution } from '../types';
import { useContributions } from '../useContributions';

/** A core section's nav meta ("1 ready", "Running"), which the page works out from its own data. */
export type SectionMeta = Pick<SectionNavItem, 'meta' | 'metaTone'>;

/**
 * One section in its boundary. A crash keeps the card, its anchor and its heading, with the callout inside; a core
 * section's card keeps core's card class too, so it looks like its neighbours.
 */
function SettingsSection({ entry }: { entry: SlotEntry<SettingsSectionContribution> }) {
  const anchorId = sectionAnchor(entry.extension, entry.contribution.id);
  const headingId = `${anchorId}-title`;
  return (
    <ExtensionBoundary
      entry={entry}
      frame={(callout) => (
        <Card id={anchorId} aria-labelledby={headingId} gap={12} className={entry.extension.core ? 'v2-settings-card' : undefined}>
          <CardHeader title={entry.contribution.title} titleId={headingId} />
          {callout}
        </Card>
      )}
    >
      {createElement(entry.contribution.component, { anchorId, headingId })}
    </ExtensionBoundary>
  );
}

/**
 * The Settings page's sections the role may see, core's and extensions' in order, and the section nav that goes
 * with them. `coreMeta` is keyed by core section id.
 */
export function useSettingsSections(coreMeta: Readonly<Record<string, SectionMeta>>): {
  nav: SectionNavItem[];
  sections: ReactNode;
} {
  const entries = useContributions('settings.section');
  const nav = entries.map((entry) => ({
    id: sectionAnchor(entry.extension, entry.contribution.id),
    label: entry.contribution.title,
    ...(entry.extension.core ? coreMeta[entry.contribution.id] : undefined),
  }));
  const sections = entries.map((entry) => <SettingsSection key={entry.key} entry={entry} />);
  return { nav, sections };
}
