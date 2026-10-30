// Core's configuration editor sections. Their bodies share the editor's state (the draft, the assigned apps, the
// library, the kiosk rules), so the editor builds them and hands them down in a page-private context; the
// contributions here only pick theirs out, as core's Settings sections do. Core and extension sections then share one
// host: ordering, `requires`, the boundary and the section nav.
//
// They edit the configuration's own fields, not `extensions`: their storage is CORE_STORAGE, which only core can name,
// and the editor tracks their changes itself (pages/ConfigEditorPage.tsx changedKeys).
import { createContext, useContext, type ReactNode } from 'react';
import { CORE_STORAGE, coreComponent, type ConfigSectionContribution } from '../types';
import { ORDER } from './order';

/** Section ids, also their anchors (`/configurations/3#kiosk`). */
export const CORE_CONFIG = {
  identity: 'identity',
  apps: 'apps',
  kiosk: 'kiosk',
  network: 'network',
  security: 'security',
  enrollment: 'enrollment',
  headwind: 'headwind',
} as const;

export type CoreConfigSection = keyof typeof CORE_CONFIG;

const CoreConfigParts = createContext<Partial<Record<CoreConfigSection, ReactNode>> | null>(null);

/** The editor's cards for core's sections. Not part of the extension API. */
export function CoreConfigProvider({ parts, children }: { parts: Record<CoreConfigSection, ReactNode>; children: ReactNode }) {
  return <CoreConfigParts.Provider value={parts}>{children}</CoreConfigParts.Provider>;
}

function coreSection(key: CoreConfigSection, title: string): ConfigSectionContribution<null> {
  function CoreSectionBody() {
    return useContext(CoreConfigParts)?.[key] ?? null;
  }
  return {
    slot: 'config.section',
    id: CORE_CONFIG[key],
    order: ORDER.configSection[key],
    title,
    storage: CORE_STORAGE,
    component: coreComponent(CoreSectionBody),
  };
}

/**
 * Every role that opens the editor sees them (the page needs `configurations`). `title` is the section nav's label;
 * each body renders its own card, with its own heading.
 */
export const CORE_CONFIG_SECTIONS: readonly ConfigSectionContribution<null>[] = [
  coreSection('identity', 'Identity'),
  coreSection('apps', 'Apps'),
  coreSection('kiosk', 'Kiosk'),
  coreSection('network', 'Network'),
  coreSection('security', 'Security'),
  coreSection('enrollment', 'Enrollment'),
  coreSection('headwind', 'Headwind only'),
];
