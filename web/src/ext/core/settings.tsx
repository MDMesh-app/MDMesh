// Core's Settings sections. Their bodies share the page's state (the supervisor status, the rollout, the account),
// so the page builds them and hands them down in a page-private context; the contributions here only pick theirs
// out. Core and extension sections then share one host: ordering, `requires`, the boundary and the section nav.
import { createContext, useContext, type ReactNode } from 'react';
import { coreComponent, type SettingsSectionContribution } from '../types';
import { ORDER } from './order';

/** Section ids, also their anchors: other pages link to `/settings#updates` and `/settings#agent-rollout`. */
export const CORE_SETTINGS = {
  account: 'account',
  server: 'server',
  updates: 'updates',
  agentRollout: 'agent-rollout',
  enrollment: 'enrollment',
  appearance: 'appearance',
} as const;

type CoreSection = keyof typeof CORE_SETTINGS;

const CoreSettingsParts = createContext<Partial<Record<CoreSection, ReactNode>> | null>(null);

/** The page's bodies for core's sections (pages/SettingsPage.tsx). Not part of the extension API. */
export function CoreSettingsProvider({ parts, children }: { parts: Record<CoreSection, ReactNode>; children: ReactNode }) {
  return <CoreSettingsParts.Provider value={parts}>{children}</CoreSettingsParts.Provider>;
}

function coreSection(key: CoreSection, title: string): SettingsSectionContribution {
  function CoreSectionBody() {
    return useContext(CoreSettingsParts)?.[key] ?? null;
  }
  return {
    slot: 'settings.section',
    id: CORE_SETTINGS[key],
    order: ORDER.settingsSection[key],
    title,
    component: coreComponent(CoreSectionBody),
  };
}

/**
 * Every role sees them (the reads need no permission); a section's own actions follow the role (rollout, enroll).
 * `title` is the section nav's label; each body renders its card with that heading.
 */
export const CORE_SETTINGS_SECTIONS: readonly SettingsSectionContribution[] = [
  coreSection('account', 'Account'),
  coreSection('server', 'Server'),
  coreSection('updates', 'Updates'),
  coreSection('agentRollout', 'Agent rollout'),
  coreSection('enrollment', 'Enrollment'),
  coreSection('appearance', 'Appearance'),
];
